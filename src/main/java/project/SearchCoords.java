package project;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.SwampHut;
import net.minecraft.block.Blocks;
import nl.jellejurre.seedchecker.SeedChecker;
import nl.jellejurre.seedchecker.SeedCheckerDimension;
import nl.jellejurre.seedchecker.TargetState;
import nl.kallestruik.noisesampler.minecraft.NoiseColumnSampler;
import nl.kallestruik.noisesampler.minecraft.NoiseParameterKey;
import nl.kallestruik.noisesampler.minecraft.Xoroshiro128PlusPlusRandom;
import nl.kallestruik.noisesampler.minecraft.noise.LazyDoublePerlinNoiseSampler;
import nl.kallestruik.noisesampler.minecraft.util.MathHelper;
import nl.kallestruik.noisesampler.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SearchCoords {

    /*
     * SeedChecker 生成 chunk 时会启动额外的 CompletableFuture 工作。
     * 限制外层线程池可以避免多个搜索线程同时保留完整的 chunk generation 邻域。
     */
    private static final int MAX_SEARCH_THREADS = boundedConcurrencyProperty(
            "lowyswamphut.maxSearchThreads", 4);
    private static final int MAX_CONCURRENT_REAL_GENERATIONS = boundedConcurrencyProperty(
            "lowyswamphut.maxConcurrentRealGenerations", 2);
    private static final int MAX_CHUNK_CACHE_SIZE = boundedChunkCacheSize(
            "lowyswamphut.maxChunkCacheSize", 1024);
    private static final int MAX_SEARCH_AXIS_SPAN = positiveIntProperty(
            "lowyswamphut.maxSearchAxisSpan", 250_000);
    private static final long MAX_SEARCH_ITERATIONS = positiveLongProperty(
            "lowyswamphut.maxSearchIterations", 20_000_000_000L);
    private static final Semaphore REAL_GENERATION_PERMITS =
            new Semaphore(MAX_CONCURRENT_REAL_GENERATIONS, true);

    private final SwampHut swampHut;
    private final GameVersion gameVersion;
    private final MCVersion mcVersion;
    private final WorldPresetMode worldPresetMode;
    private final SearchMetricsHook metricsHook;
    private ExecutorService executor;
    private Thread progressThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private final AtomicLong searchGeneration = new AtomicLong();
    private final List<String> results = new ArrayList<>();

    // 保存当前搜索状态，用于动态调整线程数
    private long currentSeed;
    private int currentMinX, currentMaxX, currentMinZ, currentMaxZ;
    private double currentMaxHeight;
    private AtomicLong currentProcessedCount;
    private Consumer<String> currentResultCallback;
    private int currentThreadCount;
    private boolean currentCheckGeneration;

    // ================= 每线程每种子缓存（噪声采样器 + SeedChecker） =================
    private static final ThreadLocal<ThreadSeedResources> THREAD_RESOURCES = new ThreadLocal<>();

    public record ProgressInfo(long processed, long total, double percentage, long elapsedMs, long remainingMs) {
    }

    public SearchCoords(GameVersion gameVersion, WorldPresetMode worldPresetMode) {
        this(gameVersion, worldPresetMode, SearchMetricsHook.NO_OP);
    }

    public SearchCoords(GameVersion gameVersion, WorldPresetMode worldPresetMode,
                        SearchMetricsHook metricsHook) {
        this.gameVersion = gameVersion;
        this.mcVersion = gameVersion.getMcVersion();
        this.worldPresetMode = worldPresetMode;
        this.swampHut = new SwampHut(mcVersion);
        this.metricsHook = metricsHook == null ? SearchMetricsHook.NO_OP : metricsHook;
    }
    public void startSearch(long seed, int threadCount, int minX, int maxX, int minZ, int maxZ, double maxHeight,
                            Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback, boolean checkGeneration) {
        validateSearchBounds(minX, maxX, minZ, maxZ);
        threadCount = boundedThreadCount(threadCount);
        // 如果正在运行且处于暂停状态，且线程数变化，则调整线程数
        if (isRunning && isPaused && threadCount != currentThreadCount) {
            adjustThreadCount(threadCount, resultCallback, checkGeneration);
            return;
        }

        if (isRunning) {
            return;
        }
        // worker 提交后会立即调用 shutdown()，因此不能只依赖 isRunning
        // 判断前一个线程池是否已经彻底退出。
        if (executor != null && !executor.isTerminated()) {
            return;
        }
        isRunning = true;
        long searchGenerationId = searchGeneration.incrementAndGet();
        results.clear();

        long totalTasks = (long) (maxX - minX) * (maxZ - minZ);

        // 保存当前搜索状态
        currentSeed = seed;
        currentMinX = minX;
        currentMaxX = maxX;
        currentMinZ = minZ;
        currentMaxZ = maxZ;
        currentMaxHeight = maxHeight;
        currentThreadCount = threadCount;
        currentResultCallback = resultCallback;
        currentCheckGeneration = checkGeneration;

        executor = Executors.newFixedThreadPool(threadCount);
        ExecutorService searchExecutor = executor;
        int totalX = maxX - minX;
        int chunkSize = Math.max(1, totalX / threadCount);
        AtomicLong processedCount = new AtomicLong(0);
        currentProcessedCount = processedCount;

        // 启动进度监控线程
        long startTime = System.currentTimeMillis();
        AtomicLong pausedTime = new AtomicLong(0); // 累计暂停时间
        AtomicReference<Long> pauseStartTime = new AtomicReference<>(0L); // 暂停开始时间
        progressThread = new Thread(() -> {
            while (isRunning && !executor.isTerminated()) {
                try {
                    Thread.sleep(100); // 每100ms更新一次
                    long processed = processedCount.get();
                    double percentage = (double) processed / totalTasks * 100.0;

                    // 如果暂停，更新暂停时间
                    if (isPaused) {
                        pauseStartTime.updateAndGet(start -> start == 0 ? System.currentTimeMillis() : start);
                    } else {
                        // 如果从暂停恢复，累计暂停时间
                        Long pauseStart = pauseStartTime.getAndSet(0L);
                        if (pauseStart > 0) {
                            pausedTime.addAndGet(System.currentTimeMillis() - pauseStart);
                        }
                    }

                    // 计算实际已用时间（排除暂停时间）
                    long elapsed = System.currentTimeMillis() - startTime - pausedTime.get();
                    long remaining = processed > 0 ? (elapsed * (totalTasks - processed) / processed) : 0;

                    if (progressCallback != null) {
                        progressCallback.accept(new ProgressInfo(processed, totalTasks, percentage, elapsed, remaining));
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            // 最终进度
            long processed = processedCount.get();
            double percentage = (double) processed / totalTasks * 100.0;
            long elapsed = System.currentTimeMillis() - startTime - pausedTime.get();
            if (progressCallback != null) {
                progressCallback.accept(new ProgressInfo(processed, totalTasks, percentage, elapsed, 0));
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        for (int i = 0; i < threadCount; i++) {
            int startX = minX + i * chunkSize;
            int endX = (i == threadCount - 1) ? maxX : startX + chunkSize;
            searchExecutor.execute(new RegionChecker(seed, startX, endX, minZ, maxZ, maxHeight, processedCount, resultCallback, checkGeneration));
        }
        searchExecutor.shutdown();

        // 等待完成
        new Thread(() -> {
            try {
                searchExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (searchGeneration.get() == searchGenerationId) {
                    isRunning = false;
                }
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        isPaused = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (progressThread != null) {
            progressThread.interrupt();
        }
    }

    public void pause() {
        isPaused = true;
    }

    public void resume() {
        isPaused = false;
    }

    // 动态调整线程数，保持进度继续
    private void adjustThreadCount(int newThreadCount, Consumer<String> resultCallback, boolean checkGeneration) {
        if (newThreadCount < 1) {
            return;
        }
        newThreadCount = boundedThreadCount(newThreadCount);
        long searchGenerationId = searchGeneration.incrementAndGet();

        // 停止当前的 executor，并等待旧工作线程退出，避免与新建线程池并发争抢
        if (executor != null && !executor.isTerminated()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    isRunning = false;
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                isRunning = false;
                return;
            }
        }

        // 更新线程数
        currentThreadCount = newThreadCount;
        currentResultCallback = resultCallback;
        currentCheckGeneration = checkGeneration;
        isRunning = true;

        // 创建新的executor
        executor = Executors.newFixedThreadPool(newThreadCount);
        ExecutorService searchExecutor = executor;
        int totalX = currentMaxX - currentMinX;
        int chunkSize = Math.max(1, totalX / newThreadCount);

        // 重新分配任务（使用相同的进度计数器，保持进度）
        for (int i = 0; i < newThreadCount; i++) {
            int startX = currentMinX + i * chunkSize;
            int endX = (i == newThreadCount - 1) ? currentMaxX : startX + chunkSize;
            searchExecutor.execute(new RegionChecker(currentSeed, startX, endX, currentMinZ, currentMaxZ, currentMaxHeight,
                    currentProcessedCount, currentResultCallback, currentCheckGeneration));
        }
        searchExecutor.shutdown();

        new Thread(() -> {
            try {
                searchExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (searchGeneration.get() == searchGenerationId) {
                    isRunning = false;
                }
            }
        }).start();

        // 恢复执行（不再暂停）
        isPaused = false;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public List<String> getResults() {
        return new ArrayList<>(results);
    }

    public GameVersion getGameVersion() {
        return gameVersion;
    }

    public MCVersion getMCVersion() {
        return mcVersion;
    }

    public WorldPresetMode getWorldPresetMode() {
        return worldPresetMode;
    }

    class RegionChecker implements Runnable {
        private final long seed;
        private final int startX;
        private final int endX;
        private final int minZ;
        private final int maxZ;
        private final double maxHeight;
        private final ChunkRand rand;
        private final AtomicLong processedCount;
        private final Consumer<String> resultCallback;
        private final boolean checkGeneration;

        public RegionChecker(long seed, int startX, int endX, int minZ, int maxZ, double maxHeight, AtomicLong processedCount, Consumer<String> resultCallback, boolean checkGeneration) {
            this.seed = seed;
            this.startX = startX;
            this.endX = endX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.maxHeight = maxHeight;
            this.rand = new ChunkRand();
            this.processedCount = processedCount;
            this.resultCallback = resultCallback;
            this.checkGeneration = checkGeneration;
        }

        @Override
        public void run() {
            metricsHook.regionWorkerStarted();
            // 将 maxHeight 转为 int，供 check(...) 使用
            int maxHeightInt = (int) maxHeight;

            try {
                for (int x = startX; x < endX && isRunning; x++) {
                    for (int z = minZ; z < maxZ && isRunning; z++) {
                        // 暂停时等待
                        while (isPaused && isRunning) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (!isRunning) {
                            break;
                        }
                        try {
                            CPos pos = swampHut.getInRegion(seed, x, z, rand);
                            // 阶段1：检查噪声和群系条件
                            if (!SearchCoords.this.check(seed, 16 * pos.getX(), 16 * pos.getZ(), maxHeightInt)) {
                                continue;
                            }
                            // 阶段2：精确检查未生成结构时每一点的地表高度
                            int hutX = 16 * pos.getX();
                            int hutZ = 16 * pos.getZ();
                            Result estimated = checkHeight(seed, hutX, hutZ, mcVersion, worldPresetMode,
                                    metricsHook);
                            if (!(estimated.height <= maxHeight)) {
                                continue;
                            }
                            // 阶段3：真实生成后直接判断小屋是否生成以及真实生成高度并输出结果
                            if (worldPresetMode == WorldPresetMode.SINGLE_BIOME || !checkGeneration) {
                                emitResultLine(estimated.toString(), resultCallback);
                            } else {
                                tryCheckHeightByRealGen(pos, estimated, resultCallback);
                            }
                        } finally {
                            processedCount.incrementAndGet();
                        }
                    }
                }
            } finally {
                ThreadSeedResources resources = THREAD_RESOURCES.get();
                if (resources != null && resources.seed == seed && resources.worldPresetMode == worldPresetMode) {
                    resources.clear();
                    THREAD_RESOURCES.remove();
                }
                metricsHook.regionWorkerStopped();
            }
        }

        private void emitResultLine(String resultStr, Consumer<String> resultCallback) {
            synchronized (results) {
                results.add(resultStr);
            }
            if (resultCallback != null) {
                resultCallback.accept(resultStr);
            }
        }

        private void tryCheckHeightByRealGen(CPos pos, Result estimatedHeight, Consumer<String> resultCallback) {
            try {
                checkHeightByRealGen(pos, estimatedHeight, resultCallback);
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                // 如果 SeedChecker 初始化失败（通常是 log4j 问题），跳过这个坐标，这不应该阻止程序继续运行
                if (e.getCause() != null && e.getCause().getMessage() != null && e.getCause().getMessage().contains("No class provided")) {
                    // 这是 log4j 的调用者查找问题，跳过这个坐标
                    return;
                }
                throw e;
            }
        }

        // 仅在精确检查生成且非单群系时执行最后的精确生成检查：判断是否生成并微调小屋最终高度
        private void checkHeightByRealGen(CPos pos, Result estimatedHeight, Consumer<String> resultCallback) {
            boolean permitAcquired = false;
            try {
                REAL_GENERATION_PERMITS.acquire();
                permitAcquired = true;
                checkHeightByRealGenWithPermit(pos, estimatedHeight, resultCallback);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (permitAcquired) {
                    REAL_GENERATION_PERMITS.release();
                }
            }
        }

        private void checkHeightByRealGenWithPermit(CPos pos, Result estimatedHeight, Consumer<String> resultCallback) {
            metricsHook.structureGenerationStarted();
            int hutX = 16 * pos.getX();
            int hutZ = 16 * pos.getZ();
            Integer generatedFloorY = findGeneratedHutFloorY(seed, hutX, hutZ, worldPresetMode,
                    metricsHook);
            String resultStr;
            if (generatedFloorY == null) {
                resultStr = estimatedHeight.toString() + " x";
            } else {
                double actualHeight = generatedFloorY - 1;
                if (Double.compare(estimatedHeight.height(), actualHeight) != 0) {
                    resultStr = new Result(hutX, hutZ, actualHeight).toString();
                } else {
                    resultStr = estimatedHeight.toString();
                }
            }
            emitResultLine(resultStr, resultCallback);
        }
    }

    // Result类，用于返回坐标和高度
    public record Result(int x, int z, double height) {

        @NotNull
        @Override
        public String toString() {
            return String.format("/tp %d %.0f %d", x, height, z);
        }
    }

    public static Integer findGeneratedHutFloorY(long seed, int hutX, int hutZ, WorldPresetMode worldPresetMode) {
        return findGeneratedHutFloorY(seed, hutX, hutZ, worldPresetMode,
                SearchMetricsHook.NO_OP);
    }

    private static Integer findGeneratedHutFloorY(long seed, int hutX, int hutZ,
                                                   WorldPresetMode worldPresetMode,
                                                   SearchMetricsHook metricsHook) {
        ThreadSeedResources resources = getThreadResources(seed, worldPresetMode, metricsHook);
        SeedChecker checker = resources.getStructureChecker();
        try {
            for (int y = -55; y <= 128; y++) {
                boolean isFloor = checker.getBlock(hutX + 2, y, hutZ + 2) == Blocks.SPRUCE_PLANKS;
                SeedCheckerCache.clearIfOversized(checker, metricsHook);
                if (isFloor) {
                    return y;
                }
            }
            return null;
        } finally {
            resources.releaseStructureChecker();
        }
    }

    // 精确检查女巫小屋所在区域的地形高度(未生成结构时)
    public static Result checkHeight(long seed, int x, int z, MCVersion mcVersion, WorldPresetMode worldPresetMode) {
        return checkHeight(seed, x, z, mcVersion, worldPresetMode,
                SearchMetricsHook.NO_OP);
    }

    private static Result checkHeight(long seed, int x, int z, MCVersion mcVersion,
                                      WorldPresetMode worldPresetMode,
                                      SearchMetricsHook metricsHook) {
        long structureSeed = seed & 281474976710655L;
        ChunkRand rand = new ChunkRand();
        rand.setCarverSeed(structureSeed, x / 16, z / 16, mcVersion);
        float a = rand.nextFloat();
        ThreadSeedResources resources = getThreadResources(seed, worldPresetMode, metricsHook);
        SeedChecker checker = resources.getTerrainChecker();
        try {
            int totalHeight = 0;
            if (a < 0.25F || (a >= 0.5F && a < 0.75F)) {
                for (int i = x; i < x + 7; i++) {
                    for (int j = z; j < z + 9; j++) {
                        boolean checked = false;
                        for (int k = 200; k >= -55 && !checked; k--) {
                            boolean solid = !checker.getBlockState(i, k, j).isAir();
                            SeedCheckerCache.clearIfOversized(checker, metricsHook);
                            if (solid) {
                                checked = true;
                                totalHeight += k;
                            }
                        }
                    }
                }
            } else {
                for (int i = x; i < x + 9; i++) {
                    for (int j = z; j < z + 7; j++) {
                        boolean checked = false;
                        for (int k = 200; k >= -55 && !checked; k--) {
                            boolean solid = !checker.getBlockState(i, k, j).isAir();
                            SeedCheckerCache.clearIfOversized(checker, metricsHook);
                            if (solid) {
                                checked = true;
                                totalHeight += k;
                            }
                        }
                    }
                }
            }
            int height = (int) Math.ceil(((double) totalHeight / 63) + 1);
            return new Result(x, z, height);
        } finally {
            SeedCheckerCache.clear(checker, metricsHook);
        }
    }

    public boolean check(long seed, int x, int z, int maxHeight) {
        WorldNoiseCache cache = getThreadResources(seed, worldPresetMode, metricsHook).noise;
        int climateX = x + 8;
        int climateZ = z + 8;
        int heightX = x + 3;
        int heightZ = z + 3;

        boolean isSingleBiome = worldPresetMode == WorldPresetMode.SINGLE_BIOME;
        if (!isSingleBiome) { // 检查群系
            double erosionSample = cache.erosion.sample((double) climateX / 4, 0, (double) climateZ / 4);
            if (erosionSample < 0.55) {
                return false;
            }
            double temperature = cache.temperature.sample((double) climateX / 4, 0, (double) climateZ / 4);
            // 1.18.2版本只检查温度不能小于-0.45，其他版本检查温度不能小于-0.45且不能大于0.2
            if (mcVersion == MCVersion.v1_18_2) {
                if (temperature < -0.45) {
                    return false;
                }
            } else {
                if (temperature > 0.2 || temperature < -0.45) {
                    return false;
                }
            }
            double ridge = cache.ridge.sample((double) climateX / 4, 0, (double) climateZ / 4);
            if ((ridge > 0.42 && ridge < 0.91) || (ridge < -0.42 && ridge > -0.91)) {
                return false;
            }
            if (gameVersion == GameVersion.V26_2 && ridge <= -0.91) {
                return false;
            }
        }
        if (Entrance(seed, heightX, 50, heightZ, worldPresetMode) >= 0) {
            return false;
        }
        if (Entrance(seed, heightX, 60, heightZ, worldPresetMode) >= 0) {
            return false;
        }
        // 检查maxHeight本身
        if (Entrance2(seed, heightX, maxHeight, heightZ, worldPresetMode) >= 0 && Cheese(seed, heightX, maxHeight, heightZ, worldPresetMode) >= 0) {
            return false;
        }
        // 0以下使用Entrance2
        for (int y = 0; y >= -40; y -= 10) {
            if (maxHeight < y) {
                if (Entrance2(seed, heightX, y, heightZ, worldPresetMode) >= 0 && Cheese(seed, heightX, y, heightZ, worldPresetMode) >= 0) {
                    return false;
                }
            }
        }
        // 10-40使用Entrance（较复杂）
        for (int y = 10; y <= 40; y += 10) {
            if (Entrance(seed, heightX, y, heightZ, worldPresetMode) >= 0 && Cheese(seed, heightX, y, heightZ, worldPresetMode) >= 0) {
                return false;
            }
        }
        if (!isSingleBiome && cache.continentalness.sample((double) climateX / 4, 0, (double) climateZ / 4) < -0.11) { // 检查大陆性
            return false;
        }
        for (int y = maxHeight; y <= 60; y += 10) {
            if (cache.aquiferFloodedness.sample(heightX, y * 0.67, heightZ) > 0.41) {
                return false;
            }
        }
        return true;
    }

    private static ThreadSeedResources getThreadResources(long seed, WorldPresetMode worldPresetMode) {
        return getThreadResources(seed, worldPresetMode, SearchMetricsHook.NO_OP);
    }

    private static ThreadSeedResources getThreadResources(long seed, WorldPresetMode worldPresetMode,
                                                           SearchMetricsHook metricsHook) {
        ThreadSeedResources resources = THREAD_RESOURCES.get();
        if (resources == null || resources.seed != seed || resources.worldPresetMode != worldPresetMode) {
            if (resources != null) {
                resources.clear();
            }
            resources = new ThreadSeedResources(seed, worldPresetMode, metricsHook);
            THREAD_RESOURCES.set(resources);
        }
        return resources;
    }

    private static final class ThreadSeedResources {
        final long seed;
        final WorldPresetMode worldPresetMode;
        final WorldNoiseCache noise;
        final SearchMetricsHook metricsHook;
        private SeedChecker terrainChecker;
        private SeedChecker structureChecker;

        ThreadSeedResources(long seed, WorldPresetMode worldPresetMode, SearchMetricsHook metricsHook) {
            this.seed = seed;
            this.worldPresetMode = worldPresetMode;
            this.noise = new WorldNoiseCache(seed, worldPresetMode);
            this.metricsHook = metricsHook;
        }

        SeedChecker getTerrainChecker() {
            if (terrainChecker == null) {
                terrainChecker = SeedCheckerFactory.create(
                        seed, TargetState.NO_STRUCTURES, SeedCheckerDimension.OVERWORLD, worldPresetMode);
                metricsHook.seedCheckerCreated(terrainChecker);
            }
            return terrainChecker;
        }

        SeedChecker getStructureChecker() {
            if (structureChecker == null) {
                structureChecker = SeedCheckerFactory.create(
                        seed, TargetState.STRUCTURES, SeedCheckerDimension.OVERWORLD, worldPresetMode);
                metricsHook.seedCheckerCreated(structureChecker);
            }
            return structureChecker;
        }

        void clear() {
            if (terrainChecker != null) {
                SeedCheckerCache.clear(terrainChecker, metricsHook);
                metricsHook.seedCheckerReleased(terrainChecker);
                terrainChecker = null;
            }
            if (structureChecker != null) {
                SeedCheckerCache.clear(structureChecker, metricsHook);
                metricsHook.seedCheckerReleased(structureChecker);
                structureChecker = null;
            }
        }

        void releaseStructureChecker() {
            if (structureChecker != null) {
                SeedCheckerCache.clear(structureChecker, metricsHook);
                metricsHook.seedCheckerReleased(structureChecker);
                structureChecker = null;
            }
        }
    }

    private static int boundedThreadCount(int requested) {
        return Math.max(1, Math.min(requested, MAX_SEARCH_THREADS));
    }

    private static void validateSearchBounds(int minX, int maxX, int minZ, int maxZ) {
        long spanX = (long) maxX - minX;
        long spanZ = (long) maxZ - minZ;
        if (spanX <= 0 || spanZ <= 0) {
            throw new IllegalArgumentException("Search bounds must have min < max");
        }
        if (spanX > MAX_SEARCH_AXIS_SPAN || spanZ > MAX_SEARCH_AXIS_SPAN
                || spanX > MAX_SEARCH_ITERATIONS / spanZ) {
            throw new IllegalArgumentException(
                    "Search range is too large; split it into smaller searches "
                            + "(max axis span=" + MAX_SEARCH_AXIS_SPAN
                            + ", max iterations=" + MAX_SEARCH_ITERATIONS + ")");
        }
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        return Math.max(1, Integer.getInteger(name, defaultValue));
    }

    private static int boundedConcurrencyProperty(String name, int defaultValue) {
        return Math.max(1, Math.min(4, Integer.getInteger(name, defaultValue)));
    }

    private static int boundedChunkCacheSize(String name, int defaultValue) {
        return Math.max(512, Math.min(2048, Integer.getInteger(name, defaultValue)));
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        try {
            return Math.max(1L, Long.parseLong(System.getProperty(name, Long.toString(defaultValue))));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * seed-checker 1.2.0 的 clearMemory() 只会清空 chunkMap，随后调用 Runtime.gc()。
     * 直接清空固定依赖版本中的 Map，可以避免每个候选点都触发 stop-the-world Full GC。
     * 反射失败时回退到依赖库方法，以保持其他版本的正确性。
     */
    private static final class SeedCheckerCache {
        private static final Field GENERATOR_FIELD;
        private static final Field CHUNK_MAP_FIELD;

        static {
            Field generator = null;
            Field chunkMap = null;
            try {
                generator = SeedChecker.class.getDeclaredField("seedChunkGenerator");
                generator.setAccessible(true);
                Class<?> generatorClass = Class.forName("nl.jellejurre.seedchecker.SeedChunkGenerator");
                chunkMap = generatorClass.getDeclaredField("chunkMap");
                chunkMap.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 由下方 clear() 的回退逻辑处理。
            }
            GENERATOR_FIELD = generator;
            CHUNK_MAP_FIELD = chunkMap;
        }

        static void clearIfOversized(SeedChecker checker, SearchMetricsHook metricsHook) {
            Map<?, ?> chunks = chunks(checker);
            if (chunks != null) {
                metricsHook.chunkCacheObserved(checker, chunks.size());
                if (chunks.size() > MAX_CHUNK_CACHE_SIZE) {
                    chunks.clear();
                    metricsHook.chunkCacheObserved(checker, 0);
                }
            }
        }

        static void clear(SeedChecker checker, SearchMetricsHook metricsHook) {
            Map<?, ?> chunks = chunks(checker);
            if (chunks != null) {
                metricsHook.chunkCacheObserved(checker, chunks.size());
                chunks.clear();
                metricsHook.chunkCacheObserved(checker, 0);
            } else {
                checker.clearMemory();
            }
        }

        private static Map<?, ?> chunks(SeedChecker checker) {
            if (GENERATOR_FIELD == null || CHUNK_MAP_FIELD == null) {
                return null;
            }
            try {
                return (Map<?, ?>) CHUNK_MAP_FIELD.get(GENERATOR_FIELD.get(checker));
            } catch (IllegalAccessException | RuntimeException ignored) {
                return null;
            }
        }
    }

    private static class WorldNoiseCache {
        final LazyDoublePerlinNoiseSampler caveEntrance;
        final LazyDoublePerlinNoiseSampler spaghettiRarity;
        final LazyDoublePerlinNoiseSampler spaghettiThickness;
        final LazyDoublePerlinNoiseSampler spaghetti3D1;
        final LazyDoublePerlinNoiseSampler spaghetti3D2;
        final LazyDoublePerlinNoiseSampler spaghettiRoughnessModulator;
        final LazyDoublePerlinNoiseSampler spaghettiRoughness;
        final LazyDoublePerlinNoiseSampler erosion;
        final LazyDoublePerlinNoiseSampler temperature;
        final LazyDoublePerlinNoiseSampler continentalness;
        final LazyDoublePerlinNoiseSampler ridge;
        final LazyDoublePerlinNoiseSampler caveLayer;
        final LazyDoublePerlinNoiseSampler caveCheese;
        final LazyDoublePerlinNoiseSampler aquiferFloodedness;

        WorldNoiseCache(long worldSeed, WorldPresetMode worldPresetMode) {
            Xoroshiro128PlusPlusRandom random = new Xoroshiro128PlusPlusRandom(worldSeed);
            var deriver = random.createRandomDeriver();
            caveEntrance = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.CAVE_ENTRANCE);
            spaghettiRarity = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_RARITY);
            spaghettiThickness = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_THICKNESS);
            spaghetti3D1 = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_1);
            spaghetti3D2 = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_2);
            spaghettiRoughnessModulator = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_ROUGHNESS_MODULATOR);
            spaghettiRoughness = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_ROUGHNESS);
            NoiseParameterKey erosionKey = worldPresetMode == WorldPresetMode.LARGE_BIOMES ? NoiseParameterKey.EROSION_LARGE : NoiseParameterKey.EROSION;
            NoiseParameterKey temperatureKey = worldPresetMode == WorldPresetMode.LARGE_BIOMES ? NoiseParameterKey.TEMPERATURE_LARGE : NoiseParameterKey.TEMPERATURE;
            NoiseParameterKey continentalnessKey = worldPresetMode == WorldPresetMode.LARGE_BIOMES ? NoiseParameterKey.CONTINENTALNESS_LARGE : NoiseParameterKey.CONTINENTALNESS;
            erosion = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, erosionKey);
            temperature = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, temperatureKey);
            continentalness = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, continentalnessKey);
            ridge = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.RIDGE);

            Xoroshiro128PlusPlusRandom cheeseRandom = new Xoroshiro128PlusPlusRandom(worldSeed);
            var cheeseDeriver = cheeseRandom.createRandomDeriver();
            caveLayer = LazyDoublePerlinNoiseSampler.createNoiseSampler(cheeseDeriver, NoiseParameterKey.CAVE_LAYER);
            caveCheese = LazyDoublePerlinNoiseSampler.createNoiseSampler(cheeseDeriver, NoiseParameterKey.CAVE_CHEESE);

            aquiferFloodedness = LazyDoublePerlinNoiseSampler.createNoiseSampler(
                    new Xoroshiro128PlusPlusRandom(worldSeed).createRandomDeriver(),
                    NoiseParameterKey.AQUIFER_FLUID_LEVEL_FLOODEDNESS);
        }
    }

    public static double Entrance(long worldSeed, int x, int y, int z, WorldPresetMode worldPresetMode) {
        WorldNoiseCache cache = getThreadResources(worldSeed, worldPresetMode).noise;
        double c = cache.caveEntrance.sample(x * 0.75, y * 0.5, z * 0.75) + 0.37 +
                MathHelper.clampedLerp(0.3, 0.0, (10 + (double) y) / 40.0);
        double d = cache.spaghettiRarity.sample(x * 2, y, z * 2);
        double e = NoiseColumnSampler.CaveScaler.scaleTunnels(d);
        double h = Util.lerpFromProgress(cache.spaghettiThickness, x, y, z, 0.065, 0.088);
        double l = NoiseColumnSampler.sample(cache.spaghetti3D1, x, y, z, e);
        double m = Math.abs(e * l) - h;
        double n = NoiseColumnSampler.sample(cache.spaghetti3D2, x, y, z, e);
        double o = Math.abs(e * n) - h;
        double p = MathHelper.clamp(Math.max(m, o), -1.0, 1.0);
        double q = (-0.05 + (-0.05 * cache.spaghettiRoughnessModulator.sample(x, y, z))) *
                (-0.4 + Math.abs(cache.spaghettiRoughness.sample(x, y, z)));
        return Math.min(c, p + q);
    }

    public static double Cheese(long worldSeed, int x, int y, int z, WorldPresetMode worldPresetMode) {
        WorldNoiseCache cache = getThreadResources(worldSeed, worldPresetMode).noise;
        double a = 4 * cache.caveLayer.sample(x, y * 8, z) * cache.caveLayer.sample(x, y * 8, z);
        double b = MathHelper.clamp((0.27 + cache.caveCheese.sample(x, y * 0.6666666666666666, z)), -1, 1);
        return a + b; // 仍缺少 sloped_cheese，但其计算过程过于复杂。
    }

    public static double Entrance2(long worldSeed, int x, int y, int z, WorldPresetMode worldPresetMode) {
        WorldNoiseCache cache = getThreadResources(worldSeed, worldPresetMode).noise;
        double d = cache.spaghettiRarity.sample(x * 2, y, z * 2);
        double e = NoiseColumnSampler.CaveScaler.scaleTunnels(d);
        double h = Util.lerpFromProgress(cache.spaghettiThickness, x, y, z, 0.065, 0.088);
        double l = NoiseColumnSampler.sample(cache.spaghetti3D1, x, y, z, e);
        double m = Math.abs(e * l) - h;
        double n = NoiseColumnSampler.sample(cache.spaghetti3D2, x, y, z, e);
        double o = Math.abs(e * n) - h;
        double p = MathHelper.clamp(Math.max(m, o), -1.0, 1.0);
        double q = (-0.05 + (-0.05 * cache.spaghettiRoughnessModulator.sample(x, y, z))) *
                (-0.4 + Math.abs(cache.spaghettiRoughness.sample(x, y, z)));
        return p + q;
    }
}
