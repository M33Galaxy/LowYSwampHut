package project;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.SwampHut;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import nl.jellejurre.seedchecker.SeedChecker;
import nl.jellejurre.seedchecker.SeedCheckerDimension;
import nl.jellejurre.seedchecker.TargetState;
import nl.kallestruik.noisesampler.minecraft.Dimension;
import nl.kallestruik.noisesampler.minecraft.GenerationShapeConfig;
import nl.kallestruik.noisesampler.minecraft.NoiseColumnSampler;
import nl.kallestruik.noisesampler.minecraft.NoiseParameterKey;
import nl.kallestruik.noisesampler.minecraft.VanillaTerrainParameters;
import nl.kallestruik.noisesampler.minecraft.Xoroshiro128PlusPlusRandom;
import nl.kallestruik.noisesampler.minecraft.noise.LazyDoublePerlinNoiseSampler;
import nl.kallestruik.noisesampler.minecraft.util.MathHelper;
import nl.kallestruik.noisesampler.minecraft.util.NoiseSamplingConfig;
import nl.kallestruik.noisesampler.minecraft.util.SlideConfig;
import nl.kallestruik.noisesampler.minecraft.util.TerrainNoisePoint;
import nl.kallestruik.noisesampler.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SearchCoords {

    // ========== 预筛 / 高度扫描可调常量 ==========
    /** 多点梯子估高均值超过 maxHeight 此裕量才拒绝进入阶段2（越大越宽松、越不易漏检） */
    private static final int DENSITY_PREFILTER_HEIGHT_MARGIN = 8;
    /**
     * 相对小屋局部坐标系的 5 探针（未旋转 7×9）：中心 + 十字。
     * 运用前会按 carver RNG 朝向映射到世界坐标。
     */
    private static final int[][] DENSITY_PREFILTER_LOCAL_PROBES = {
            {3, 4}, // 中心 (HUT_LOCAL_WIDTH-1)/2, (HUT_LOCAL_DEPTH-1)/2
            {3, 1},
            {3, 7},
            {1, 4},
            {5, 4}
    };
    /** cheese 梯子向下探测的 Y 档位 */
    private static final int[] DENSITY_PREFILTER_LADDER_DOWN = {40, 30, 20, 10, 0, -10, -20, -30, -40, -50};
    /**
     * 阶段1粗筛 / 密度预筛的高度下限：选 -50 或 -54 时按 -50 处理，-54 不额外降低。
     * 阶段2仍使用用户选定的真实 maxHeight。
     */
    private static final int PHASE1_MIN_CHECK_HEIGHT = -50;
    /** SeedChecker 列扫描起始 Y（必须足够高，否则会把高地表误判成低洞穴顶） */
    private static final int COLUMN_SCAN_START_Y = 200;
    /** SeedChecker 列扫描最低 Y */
    private static final int COLUMN_SCAN_MIN_Y = -55;
    /** 小屋 footprint 列数（7×9） */
    private static final int HUT_FOOTPRINT_COLUMNS = 63;

    /*
     * SeedChecker 生成 chunk 时会启动额外的 CompletableFuture 工作。
     * 默认并行度与 CPU 核数对齐；仍可用系统属性覆盖。
     * 注意：切勿在 getBlock 热路径里频繁反射清缓存，那会比限制线程更伤性能。
     */
    private static final int MAX_SEARCH_THREADS = positiveIntProperty(
            "lowyswamphut.maxSearchThreads",
            Math.max(1, Runtime.getRuntime().availableProcessors()));
    private static final int MAX_CONCURRENT_REAL_GENERATIONS = positiveIntProperty(
            "lowyswamphut.maxConcurrentRealGenerations",
            MAX_SEARCH_THREADS);
    private static final int MAX_CHUNK_CACHE_SIZE = boundedChunkCacheSize(
            "lowyswamphut.maxChunkCacheSize", 1_024);
    private static final int MAX_SEARCH_AXIS_SPAN = positiveIntProperty(
            "lowyswamphut.maxSearchAxisSpan", 250_000);
    private static final long MAX_SEARCH_ITERATIONS = positiveLongProperty(
            "lowyswamphut.maxSearchIterations", 20_000_000_000L);
    private static final Semaphore REAL_GENERATION_PERMITS =
            new Semaphore(MAX_CONCURRENT_REAL_GENERATIONS, false);

    private final SwampHut swampHut;
    private final GameVersion gameVersion;
    private final MCVersion mcVersion;
    private final WorldPresetMode worldPresetMode;
    private final SearchMetricsHook metricsHook;
    private ExecutorService executor;
    private Thread progressThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private volatile boolean coordinatorFinished = false;
    private volatile CountDownLatch searchDone = new CountDownLatch(0);
    private final List<String> results = new ArrayList<>();

    // 保存当前搜索状态，用于动态调整线程数
    private long currentSeed;
    private int currentMinX, currentMaxX, currentMinZ, currentMaxZ;
    private double currentMaxHeight;
    private AtomicLong currentProcessedCount;
    private volatile long currentTotalTasks;
    private volatile int currentStage = 1;
    /** 当前阶段开始时刻（用于本阶段剩余时间：stageElapsed * remaining / processed） */
    private volatile long stageStartTimeMs;
    /** 当前阶段累计暂停时长 */
    private final AtomicLong stagePausedTimeMs = new AtomicLong(0);
    private final AtomicReference<Long> stagePauseStartMs = new AtomicReference<>(0L);
    private Consumer<String> currentResultCallback;
    private int currentThreadCount;
    private boolean currentCheckGeneration;
    private Set<Long> phase1Candidates;
    private List<CPos> phase2Candidates;
    private AtomicInteger phase2Cursor;
    private volatile boolean phase1AdjustPending = false;
    private volatile boolean phase2AdjustPending = false;

    // ================= 每线程每种子缓存（噪声采样器 + SeedChecker） =================
    private static final ThreadLocal<ThreadSeedResources> THREAD_RESOURCES = new ThreadLocal<>();

    public record ProgressInfo(long processed, long total, double percentage, long elapsedMs, long remainingMs, int stage) {
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
        final int searchThreadCount = boundedThreadCount(threadCount);
        // 如果正在运行且处于暂停状态，且线程数变化，则调整线程数
        if (isRunning && isPaused && searchThreadCount != currentThreadCount) {
            adjustThreadCount(searchThreadCount, resultCallback, checkGeneration);
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
        coordinatorFinished = false;
        searchDone = new CountDownLatch(1);
        results.clear();

        long stage1Total = (long) (maxX - minX) * (maxZ - minZ);

        // 保存当前搜索状态
        currentSeed = seed;
        currentMinX = minX;
        currentMaxX = maxX;
        currentMinZ = minZ;
        currentMaxZ = maxZ;
        currentMaxHeight = maxHeight;
        currentThreadCount = searchThreadCount;
        currentResultCallback = resultCallback;
        currentCheckGeneration = checkGeneration;
        currentStage = 1;
        currentTotalTasks = stage1Total;
        stageStartTimeMs = System.currentTimeMillis();
        stagePausedTimeMs.set(0);
        stagePauseStartMs.set(0L);
        phase1Candidates = ConcurrentHashMap.newKeySet();
        phase2Candidates = null;
        phase2Cursor = null;

        AtomicLong processedCount = new AtomicLong(0);
        currentProcessedCount = processedCount;

        final CountDownLatch doneLatch = searchDone;

        // 进度监控线程：仅在有回调时启动（批量种子并行可传 null 以省开销）
        long startTime = System.currentTimeMillis();
        AtomicLong pausedTime = new AtomicLong(0);
        AtomicReference<Long> pauseStartTime = new AtomicReference<>(0L);
        if (progressCallback != null) {
            progressThread = new Thread(() -> {
                while (isRunning && !coordinatorFinished) {
                    try {
                        Thread.sleep(100);
                        reportProgress(progressCallback, startTime, pausedTime, pauseStartTime, false);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                reportProgress(progressCallback, startTime, pausedTime, pauseStartTime, true);
            });
            progressThread.setDaemon(true);
            progressThread.start();
        } else {
            progressThread = null;
        }

        // 批量「1 线程/种子」：在调用线程同步跑完，避免每种子再开协调线程
        if (progressCallback == null && searchThreadCount <= 1) {
            runCoordinator(seed, searchThreadCount, minX, maxX, minZ, maxZ, maxHeight,
                    processedCount, resultCallback, checkGeneration, doneLatch);
            return;
        }

        new Thread(() -> runCoordinator(seed, searchThreadCount, minX, maxX, minZ, maxZ, maxHeight,
                processedCount, resultCallback, checkGeneration, doneLatch),
                "SearchCoords-Coordinator").start();
    }

    private void runCoordinator(long seed, int searchThreadCount, int minX, int maxX, int minZ, int maxZ,
                                double maxHeight, AtomicLong processedCount, Consumer<String> resultCallback,
                                boolean checkGeneration, CountDownLatch doneLatch) {
        try {
            runPhase1(seed, searchThreadCount, minX, maxX, minZ, maxZ, maxHeight, processedCount);
            if (!isRunning) {
                return;
            }

            List<CPos> sorted = new ArrayList<>(phase1Candidates.size());
            for (Long key : phase1Candidates) {
                sorted.add(unpackChunkPos(key));
            }
            sorted.sort(Comparator.comparingLong(pos -> {
                long hx = 16L * pos.getX();
                long hz = 16L * pos.getZ();
                return hx * hx + hz * hz;
            }));
            phase2Candidates = sorted;
            phase2Cursor = new AtomicInteger(0);

            AtomicLong stage2Processed = new AtomicLong(0);
            currentProcessedCount = stage2Processed;
            beginStage(2, sorted.size());

            if (!sorted.isEmpty() && isRunning) {
                runPhase2(seed, searchThreadCount, maxHeight, stage2Processed, resultCallback, checkGeneration);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            shutdownExecutor();
            isRunning = false;
            coordinatorFinished = true;
            doneLatch.countDown();
        }
    }

    /**
     * 阻塞直到本次搜索完成（协调线程结束）。适合批量种子搜索，替代 sleep 轮询。
     */
    public void awaitCompletion() {
        try {
            searchDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 阶段1粗筛 / 密度预筛用高度：-54 按 -50 处理，不额外降低。 */
    static int phase1CheckHeight(int maxHeight) {
        return Math.max(maxHeight, PHASE1_MIN_CHECK_HEIGHT);
    }

    private void beginStage(int stage, long totalTasks) {
        currentStage = stage;
        currentTotalTasks = totalTasks;
        stageStartTimeMs = System.currentTimeMillis();
        stagePausedTimeMs.set(0);
        stagePauseStartMs.set(0L);
    }

    private void reportProgress(Consumer<ProgressInfo> progressCallback, long startTime,
                                AtomicLong pausedTime, AtomicReference<Long> pauseStartTime, boolean finalReport) {
        if (progressCallback == null || currentProcessedCount == null) {
            return;
        }
        long processed = currentProcessedCount.get();
        long total = currentTotalTasks;
        int stage = currentStage;
        double percentage = total > 0 ? (double) processed / total * 100.0 : (stage == 2 ? 100.0 : 0.0);

        if (isPaused) {
            pauseStartTime.updateAndGet(start -> start == 0 ? System.currentTimeMillis() : start);
            stagePauseStartMs.updateAndGet(start -> start == 0 ? System.currentTimeMillis() : start);
        } else {
            Long pauseStart = pauseStartTime.getAndSet(0L);
            if (pauseStart > 0) {
                pausedTime.addAndGet(System.currentTimeMillis() - pauseStart);
            }
            Long stagePauseStart = stagePauseStartMs.getAndSet(0L);
            if (stagePauseStart > 0) {
                stagePausedTimeMs.addAndGet(System.currentTimeMillis() - stagePauseStart);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime - pausedTime.get();
        // 本阶段剩余时间 = 本阶段已耗时 / 已完成数 * 剩余数
        long stageElapsed = System.currentTimeMillis() - stageStartTimeMs - stagePausedTimeMs.get();
        long remaining = 0;
        if (!finalReport && processed > 0 && total > processed) {
            remaining = stageElapsed * (total - processed) / processed;
        }
        progressCallback.accept(new ProgressInfo(processed, total, percentage, elapsed, remaining, stage));
    }

    private void runPhase1(long seed, int threadCount, int minX, int maxX, int minZ, int maxZ,
                           double maxHeight, AtomicLong processedCount) throws InterruptedException {
        do {
            phase1AdjustPending = false;
            if (phase1Candidates != null) {
                phase1Candidates.clear();
            }
            processedCount.set(0);
            beginStage(1, currentTotalTasks);
            int poolSize = currentThreadCount > 0 ? currentThreadCount : threadCount;
            int totalX = maxX - minX;
            int chunkSize = Math.max(1, totalX / poolSize);
            List<Runnable> tasks = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                int startX = minX + i * chunkSize;
                int endX = (i == poolSize - 1) ? maxX : startX + chunkSize;
                tasks.add(new Phase1RegionChecker(seed, startX, endX, minZ, maxZ, maxHeight, processedCount));
            }
            runTasksAndWait(tasks, poolSize);
        } while (isRunning && phase1AdjustPending);
    }

    private void runPhase2(long seed, int threadCount, double maxHeight, AtomicLong processedCount,
                           Consumer<String> resultCallback, boolean checkGeneration) throws InterruptedException {
        do {
            phase2AdjustPending = false;
            int poolSize = currentThreadCount > 0 ? currentThreadCount : threadCount;
            Consumer<String> callback = currentResultCallback != null ? currentResultCallback : resultCallback;
            List<Runnable> tasks = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                tasks.add(new Phase2CandidateChecker(seed, maxHeight, processedCount, callback, currentCheckGeneration));
            }
            runTasksAndWait(tasks, poolSize);
        } while (isRunning && phase2AdjustPending);
    }

    private void runTasksAndWait(List<Runnable> tasks, int poolSize) throws InterruptedException {
        // 单线程：在当前线程直接跑，避免每种子 newFixedThreadPool(1) 的创建/销毁开销
        if (poolSize <= 1 && tasks.size() == 1) {
            tasks.get(0).run();
            return;
        }
        ensureExecutor(poolSize);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(executor.submit(task));
        }
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.CancellationException | java.util.concurrent.ExecutionException ignored) {
                // 调线程数 shutdownNow 或任务内部异常时结束等待
            }
        }
    }

    private int executorPoolSize = 0;

    private void ensureExecutor(int poolSize) {
        if (executor != null && !executor.isShutdown() && !executor.isTerminated() && executorPoolSize == poolSize) {
            return;
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor = Executors.newFixedThreadPool(poolSize);
        executorPoolSize = poolSize;
    }

    private void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        executorPoolSize = 0;
    }

    public void stop() {
        isRunning = false;
        isPaused = false;
        if (executor != null) {
            executor.shutdownNow();
            executorPoolSize = 0;
        }
        if (progressThread != null) {
            progressThread.interrupt();
        }
        // 确保 awaitCompletion 不会因协调线程延迟而永久阻塞
        CountDownLatch done = searchDone;
        if (done != null) {
            done.countDown();
        }
    }

    public void pause() {
        isPaused = true;
    }

    public void resume() {
        isPaused = false;
    }

    // 动态调整线程数：打断当前批次，由协调线程按新线程数重跑当前阶段
    private void adjustThreadCount(int newThreadCount, Consumer<String> resultCallback, boolean checkGeneration) {
        if (newThreadCount < 1) {
            return;
        }
        newThreadCount = boundedThreadCount(newThreadCount);
        currentThreadCount = newThreadCount;
        currentResultCallback = resultCallback;
        currentCheckGeneration = checkGeneration;
        isRunning = true;

        if (currentStage == 1) {
            phase1AdjustPending = true;
        } else {
            phase2AdjustPending = true;
        }

        // 打断正在跑的任务；协调线程的 latch.await 会结束后按新线程数重开
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executorPoolSize = 0;
        }
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

    private static long packChunkPos(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static CPos unpackChunkPos(long key) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        return new CPos(chunkX, chunkZ);
    }

    private void waitIfPaused() {
        while (isPaused && isRunning) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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

    private void clearThreadResources(long seed) {
        ThreadSeedResources resources = THREAD_RESOURCES.get();
        if (resources != null && resources.seed == seed && resources.worldPresetMode == worldPresetMode) {
            resources.clear();
            THREAD_RESOURCES.remove();
        }
    }

    /** 阶段1：噪声/群系粗筛 + density 预筛，仅暂存候选坐标 */
    class Phase1RegionChecker implements Runnable {
        private final long seed;
        private final int startX;
        private final int endX;
        private final int minZ;
        private final int maxZ;
        private final double maxHeight;
        private final ChunkRand rand;
        private final AtomicLong processedCount;

        Phase1RegionChecker(long seed, int startX, int endX, int minZ, int maxZ, double maxHeight, AtomicLong processedCount) {
            this.seed = seed;
            this.startX = startX;
            this.endX = endX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.maxHeight = maxHeight;
            this.rand = new ChunkRand();
            this.processedCount = processedCount;
        }

        @Override
        public void run() {
            metricsHook.regionWorkerStarted();
            try {
                // -54 在阶段1 / 密度预筛按 -50；阶段2仍用真实 maxHeight
                int phase1Height = phase1CheckHeight((int) maxHeight);
                // 阶段1不清理 ThreadLocal，供同线程池进入阶段2时复用噪声缓存
                for (int x = startX; x < endX && isRunning; x++) {
                    for (int z = minZ; z < maxZ && isRunning; z++) {
                        waitIfPaused();
                        if (!isRunning) {
                            break;
                        }
                        try {
                            CPos pos = swampHut.getInRegion(seed, x, z, rand);
                            int hutX = 16 * pos.getX();
                            int hutZ = 16 * pos.getZ();
                            if (!SearchCoords.this.check(seed, hutX, hutZ, phase1Height)) {
                                continue;
                            }
                            // 用 slopedCheese / 多点梯子预估地表，砍掉明显过高的候选，减少阶段2 SeedChecker 调用
                            if (!passesDensityPrefilter(seed, hutX, hutZ, phase1Height, mcVersion, worldPresetMode)) {
                                continue;
                            }
                            phase1Candidates.add(packChunkPos(pos.getX(), pos.getZ()));
                        } finally {
                            processedCount.incrementAndGet();
                        }
                    }
                }
            } finally {
                metricsHook.regionWorkerStopped();
            }
        }
    }

    /** 阶段2：高度检查 + 可选真实生成校验，通过即输出（多线程并行认领） */
    class Phase2CandidateChecker implements Runnable {
        private final long seed;
        private final double maxHeight;
        private final AtomicLong processedCount;
        private final Consumer<String> resultCallback;
        private final boolean checkGeneration;

        Phase2CandidateChecker(long seed, double maxHeight, AtomicLong processedCount,
                               Consumer<String> resultCallback, boolean checkGeneration) {
            this.seed = seed;
            this.maxHeight = maxHeight;
            this.processedCount = processedCount;
            this.resultCallback = resultCallback;
            this.checkGeneration = checkGeneration;
        }

        @Override
        public void run() {
            metricsHook.regionWorkerStarted();
            try {
                // 预热 SeedChecker，避免首个候选承担全部初始化成本
                getThreadResources(seed, worldPresetMode, metricsHook).getTerrainChecker();
                if (worldPresetMode != WorldPresetMode.SINGLE_BIOME && checkGeneration) {
                    getThreadResources(seed, worldPresetMode, metricsHook).getStructureChecker();
                }
                while (isRunning) {
                    waitIfPaused();
                    if (!isRunning) {
                        break;
                    }
                    int index = phase2Cursor.getAndIncrement();
                    if (index >= phase2Candidates.size()) {
                        break;
                    }
                    try {
                        CPos pos = phase2Candidates.get(index);
                        int hutX = 16 * pos.getX();
                        int hutZ = 16 * pos.getZ();
                        Result estimated = checkHeight(seed, hutX, hutZ, maxHeight, mcVersion,
                                worldPresetMode, metricsHook);
                        if (!(estimated.height <= maxHeight)) {
                            continue;
                        }
                        if (worldPresetMode == WorldPresetMode.SINGLE_BIOME || !checkGeneration) {
                            emitResultLine(estimated.toString(), resultCallback);
                        } else {
                            tryCheckHeightByRealGen(pos, estimated, resultCallback);
                        }
                    } finally {
                        processedCount.incrementAndGet();
                    }
                }
            } finally {
                clearThreadResources(seed);
                metricsHook.regionWorkerStopped();
            }
        }

        private void tryCheckHeightByRealGen(CPos pos, Result estimatedHeight, Consumer<String> resultCallback) {
            try {
                checkHeightByRealGen(pos, estimatedHeight, resultCallback);
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                if (e.getCause() != null && e.getCause().getMessage() != null && e.getCause().getMessage().contains("No class provided")) {
                    return;
                }
                throw e;
            }
        }

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
                SeedCheckerCache.clearIfOversized(checker, hutX + 2, hutZ + 2, metricsHook);
                if (isFloor) {
                    return y;
                }
            }
            return null;
        } finally {
            // 只清空 chunk 缓存，不要销毁 SeedChecker（重建成本极高）
            SeedCheckerCache.clear(checker, metricsHook);
        }
    }

    // 精确检查女巫小屋所在区域的地形高度(未生成结构时)
    // maxHeight 用于提前放弃：若剩余列即使全是最低点仍超标，则不再扫完 footprint
    public static Result checkHeight(long seed, int x, int z, double maxHeight, MCVersion mcVersion, WorldPresetMode worldPresetMode) {
        return checkHeight(seed, x, z, maxHeight, mcVersion, worldPresetMode,
                SearchMetricsHook.NO_OP);
    }

    private static Result checkHeight(long seed, int x, int z, double maxHeight, MCVersion mcVersion,
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
            int columnsDone = 0;
            int startY = COLUMN_SCAN_START_Y;
            if (a < 0.25F || (a >= 0.5F && a < 0.75F)) {
                for (int i = x; i < x + 7; i++) {
                    for (int j = z; j < z + 9; j++) {
                        int columnTop = scanColumnTop(checker, i, j, startY, metricsHook);
                        totalHeight += columnTop;
                        columnsDone++;
                        if (cannotMeetMaxHeight(totalHeight, columnsDone, maxHeight)) {
                            return new Result(x, z, estimateMinHeight(totalHeight, columnsDone));
                        }
                    }
                }
            } else {
                for (int i = x; i < x + 9; i++) {
                    for (int j = z; j < z + 7; j++) {
                        int columnTop = scanColumnTop(checker, i, j, startY, metricsHook);
                        totalHeight += columnTop;
                        columnsDone++;
                        if (cannotMeetMaxHeight(totalHeight, columnsDone, maxHeight)) {
                            return new Result(x, z, estimateMinHeight(totalHeight, columnsDone));
                        }
                    }
                }
            }
            int height = (int) Math.ceil(((double) totalHeight / HUT_FOOTPRINT_COLUMNS) + 1);
            return new Result(x, z, height);
        } finally {
            SeedCheckerCache.clear(checker, metricsHook);
        }
    }

    /** 兼容旧调用：不提前剪枝 */
    public static Result checkHeight(long seed, int x, int z, MCVersion mcVersion, WorldPresetMode worldPresetMode) {
        return checkHeight(seed, x, z, Double.POSITIVE_INFINITY, mcVersion, worldPresetMode,
                SearchMetricsHook.NO_OP);
    }

    private static int scanColumnTop(SeedChecker checker, int i, int j, int startY,
                                     SearchMetricsHook metricsHook) {
        for (int k = startY; k >= COLUMN_SCAN_MIN_Y; k--) {
            boolean solid = !checker.getBlockState(i, k, j).isAir();
            SeedCheckerCache.clearIfOversized(checker, i, j, metricsHook);
            if (solid) {
                return k;
            }
        }
        return COLUMN_SCAN_MIN_Y;
    }

    private static boolean cannotMeetMaxHeight(int totalHeight, int columnsDone, double maxHeight) {
        if (!(maxHeight < Double.POSITIVE_INFINITY) || columnsDone >= HUT_FOOTPRINT_COLUMNS) {
            return false;
        }
        int remaining = HUT_FOOTPRINT_COLUMNS - columnsDone;
        double minHeight = Math.ceil((totalHeight + remaining * (double) COLUMN_SCAN_MIN_Y) / HUT_FOOTPRINT_COLUMNS + 1.0);
        return minHeight > maxHeight;
    }

    private static double estimateMinHeight(int totalHeight, int columnsDone) {
        int remaining = HUT_FOOTPRINT_COLUMNS - columnsDone;
        return Math.ceil((totalHeight + remaining * (double) COLUMN_SCAN_MIN_Y) / HUT_FOOTPRINT_COLUMNS + 1.0);
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
        // 检查maxHeight本身（调用方应对 -54 传入 phase1CheckHeight=-50）
        if (Entrance2(seed, heightX, maxHeight, heightZ, worldPresetMode) >= 0 && Cheese(seed, heightX, maxHeight, heightZ, worldPresetMode) >= 0) {
            return false;
        }
        // 0以下使用Entrance2；选 -50/-54 时梯子下限为 -50，其余仍为 -40
        int ladderFloor = Math.max(PHASE1_MIN_CHECK_HEIGHT, Math.min(-40, maxHeight));
        for (int y = 0; y >= ladderFloor; y -= 10) {
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
        private NoiseColumnSampler columnSampler;
        final SearchMetricsHook metricsHook;
        private SeedChecker terrainChecker;
        private SeedChecker structureChecker;

        ThreadSeedResources(long seed, WorldPresetMode worldPresetMode, SearchMetricsHook metricsHook) {
            this.seed = seed;
            this.worldPresetMode = worldPresetMode;
            this.noise = new WorldNoiseCache(seed, worldPresetMode);
            this.metricsHook = metricsHook;
        }

        NoiseColumnSampler getColumnSampler() {
            if (columnSampler == null) {
                boolean largeBiomes = worldPresetMode == WorldPresetMode.LARGE_BIOMES;
                GenerationShapeConfig config = new GenerationShapeConfig(
                        -64, 384,
                        new NoiseSamplingConfig(1.0, 1.0, 80.0, 160.0),
                        new SlideConfig(-0.078125, 2, 8),
                        new SlideConfig(0.1171875, 3, 0),
                        1, 2, false, false, largeBiomes,
                        VanillaTerrainParameters.createSurfaceParameters()
                );
                columnSampler = new NoiseColumnSampler(config, seed, Dimension.OVERWORLD);
            }
            return columnSampler;
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

    private static int boundedChunkCacheSize(String name, int defaultValue) {
        return Math.max(512, Math.min(2_048, Integer.getInteger(name, defaultValue)));
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

        static void clearIfOversized(SeedChecker checker, int blockX, int blockZ,
                                     SearchMetricsHook metricsHook) {
            Map<?, ?> chunks = chunks(checker);
            if (chunks != null) {
                metricsHook.chunkCacheObserved(checker, chunks.size());
                if (chunks.size() > MAX_CHUNK_CACHE_SIZE) {
                    ChunkPos protectedChunk = new ChunkPos(
                            Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
                    for (Object key : chunks.keySet()) {
                        if (chunks.size() <= MAX_CHUNK_CACHE_SIZE) {
                            break;
                        }
                        if (!protectedChunk.equals(key)) {
                            chunks.remove(key);
                        }
                    }
                    metricsHook.chunkCacheObserved(checker, chunks.size());
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

    /**
     * 与 {@link #checkHeight} 相同的朝向判定：carver seed 后第一个 float
     * 落在 [0,0.25)∪[0.5,0.75) → footprint 为 7×9（未转 90°）；
     * 否则为 9×7（转 90°）。
     */
    static boolean isHutRotated90(long seed, int hutX, int hutZ, MCVersion mcVersion) {
        long structureSeed = seed & 281474976710655L;
        ChunkRand rand = new ChunkRand();
        rand.setCarverSeed(structureSeed, hutX / 16, hutZ / 16, mcVersion);
        float a = rand.nextFloat();
        return !(a < 0.25F || (a >= 0.5F && a < 0.75F));
    }

    /**
     * 将未旋转 7×9 局部坐标映射到世界块坐标（与 checkHeight 的轴对齐 footprint 一致）。
     * 未转：世界 = 原点 + (lx, lz)，范围 7×9；
     * 转 90°：世界 = 原点 + (lz, lx)，范围 9×7。
     */
    static void mapHutLocalToWorld(int hutX, int hutZ, int localX, int localZ, boolean rotated90, int[] outXZ) {
        if (!rotated90) {
            outXZ[0] = hutX + localX;
            outXZ[1] = hutZ + localZ;
        } else {
            outXZ[0] = hutX + localZ;
            outXZ[1] = hutZ + localX;
        }
    }

    /**
     * 阶段1→2 之间的 density 预筛：先定小屋朝向，再在 footprint 内用 5 点梯子估高。
     * 只剔除“确定过高”的候选，避免漏掉真低 y（允许少量假阳性进入阶段2）。
     */
    static boolean passesDensityPrefilter(long seed, int hutX, int hutZ, int maxHeight,
                                          MCVersion mcVersion, WorldPresetMode worldPresetMode) {
        boolean rotated90 = isHutRotated90(seed, hutX, hutZ, mcVersion);
        int[] worldXZ = new int[2];
        int sum = 0;
        for (int[] local : DENSITY_PREFILTER_LOCAL_PROBES) {
            mapHutLocalToWorld(hutX, hutZ, local[0], local[1], rotated90, worldXZ);
            sum += estimateCheeseSurfaceLadder(seed, worldXZ[0], worldXZ[1], worldPresetMode);
        }
        double mean = sum / (double) DENSITY_PREFILTER_LOCAL_PROBES.length;
        return !(mean > maxHeight + DENSITY_PREFILTER_HEIGHT_MARGIN);
    }

    /** 对齐 cubiomes ly_cheese_surface_ladder，但 cheese 使用含 slopedCheese 的版本 */
    static int estimateCheeseSurfaceLadder(long seed, int x, int z, WorldPresetMode worldPresetMode) {
        if (!isDensityLadderAir(seed, x, 60, z, worldPresetMode)) {
            if (!isDensityLadderAir(seed, x, 70, z, worldPresetMode)) {
                return 70;
            }
            return 63;
        }
        if (!isDensityLadderAir(seed, x, 50, z, worldPresetMode)) {
            return 60;
        }
        for (int i = 0; i < DENSITY_PREFILTER_LADDER_DOWN.length; i++) {
            if (!isDensityLadderAir(seed, x, DENSITY_PREFILTER_LADDER_DOWN[i], z, worldPresetMode)) {
                if (i == 0) {
                    return 50;
                }
                return DENSITY_PREFILTER_LADDER_DOWN[i - 1];
            }
        }
        return -54;
    }

    private static boolean isDensityLadderAir(long seed, int x, int y, int z, WorldPresetMode worldPresetMode) {
        double cheese = cheeseWithSloped(seed, x, y, z, worldPresetMode);
        if (y > 50) {
            return Entrance(seed, x, y, z, worldPresetMode) < 0.0;
        }
        if (y >= 10) {
            return Entrance(seed, x, y, z, worldPresetMode) < 0.0 || cheese < 0.0;
        }
        return Entrance2(seed, x, y, z, worldPresetMode) < 0.0 || cheese < 0.0;
    }

    /** Minecraft sampleCaveCheese：在原 Cheese 上加上 slopedCheese 修正项 */
    static double cheeseWithSloped(long worldSeed, int x, int y, int z, WorldPresetMode worldPresetMode) {
        WorldNoiseCache cache = getThreadResources(worldSeed, worldPresetMode).noise;
        double layer = 4 * cache.caveLayer.sample(x, y * 8, z) * cache.caveLayer.sample(x, y * 8, z);
        double caveCheese = MathHelper.clamp(0.27 + cache.caveCheese.sample(x, y * 0.6666666666666666, z), -1, 1);
        double sloped = sampleSlopedCheese(worldSeed, x, y, z, worldPresetMode);
        double slopedTerm = MathHelper.clamp(1.5 + (-0.64 * sloped), 0.0, 0.5);
        return layer + caveCheese + slopedTerm;
    }

    private static double noiseGradientDensity(double factor, double depth) {
        double f = depth * factor;
        return f > 0.0 ? f * 4.0 : f;
    }

    private static double clampedMap(double value, double oldMin, double oldMax, double newMin, double newMax) {
        return MathHelper.clampedLerp(newMin, newMax, (value - oldMin) / (oldMax - oldMin));
    }

    /**
     * 近似 Minecraft sloped_cheese / 地形初始 density（含 jagged + base 3d terrain noise）。
     * 这是 final_density 的主要固体贡献项，用于廉价预估地表高度。
     */
    static double sampleSlopedCheese(long worldSeed, int x, int y, int z, WorldPresetMode worldPresetMode) {
        NoiseColumnSampler ncs = getThreadResources(worldSeed, worldPresetMode).getColumnSampler();
        int cellX = x >> 2;
        int cellZ = z >> 2;
        NoiseColumnSampler.NoiseInfo info = ncs.createNoiseInfo(cellX, cellZ);
        TerrainNoisePoint terrain = info.terrainInfo();
        double jagged = ncs.sampleJaggedNoise(terrain.peaks(), info.shiftedX(), info.shiftedZ());
        double depth = terrain.offset() + clampedMap(y, -64, 320, 1.5, -1.5);
        double density = noiseGradientDensity(terrain.factor(), depth + jagged);
        // 与 NoiseSampler.queryNoiseFromBlockPos → TERRAIN 路径一致：xz 为 cell，y 为方块坐标
        density += ncs.terrainNoise.calculateNoise(cellX, y, cellZ);
        return density;
    }
}
