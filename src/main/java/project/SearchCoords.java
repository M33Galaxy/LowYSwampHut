package project;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.SwampHut;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Box;
import nl.jellejurre.seedchecker.SeedChecker;
import nl.jellejurre.seedchecker.SeedCheckerDimension;
import nl.jellejurre.seedchecker.TargetState;
import nl.kallestruik.noisesampler.minecraft.NoiseColumnSampler;
import nl.kallestruik.noisesampler.minecraft.NoiseParameterKey;
import nl.kallestruik.noisesampler.minecraft.Xoroshiro128PlusPlusRandom;
import nl.kallestruik.noisesampler.minecraft.noise.LazyDoublePerlinNoiseSampler;
import nl.kallestruik.noisesampler.minecraft.util.MathHelper;
import nl.kallestruik.noisesampler.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SearchCoords {
    
    private final SwampHut swampHut;
    private final MCVersion mcVersion;
    private ExecutorService executor;
    private Thread progressThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private final List<String> results = new ArrayList<>();
    
    // 保存当前搜索状态，用于动态调整线程数
    private long currentSeed;
    private int currentMinX, currentMaxX, currentMinZ, currentMaxZ;
    private double currentMaxHeight;
    private AtomicLong currentProcessedCount;
    private Consumer<String> currentResultCallback;
    private int currentThreadCount;
    private boolean currentCheckGeneration;

    public static class ProgressInfo {
        public final long processed;
        public final long total;
        public final double percentage;
        public final long elapsedMs;
        public final long remainingMs;

        public ProgressInfo(long processed, long total, double percentage, long elapsedMs, long remainingMs) {
            this.processed = processed;
            this.total = total;
            this.percentage = percentage;
            this.elapsedMs = elapsedMs;
            this.remainingMs = remainingMs;
        }
    }

    public SearchCoords(MCVersion mcVersion) {
        this.mcVersion = mcVersion;
        this.swampHut = new SwampHut(mcVersion);
    }

    public void startSearch(long seed, int threadCount, int minX, int maxX, int minZ, int maxZ, double maxHeight,
                          Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback, boolean checkGeneration) {
        // 如果正在运行且处于暂停状态，且线程数变化，则调整线程数
        if (isRunning && isPaused && threadCount != currentThreadCount) {
            adjustThreadCount(threadCount, resultCallback, checkGeneration);
            return;
        }
        
        if (isRunning) {
            return;
        }
        isRunning = true;
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
            executor.execute(new RegionChecker(seed, startX, endX, minZ, maxZ, maxHeight, processedCount, resultCallback, checkGeneration));
        }
        executor.shutdown();

        // 等待完成
        new Thread(() -> {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                isRunning = false;
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
        
        // 停止当前的executor
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        
        // 更新线程数
        currentThreadCount = newThreadCount;
        currentResultCallback = resultCallback;
        currentCheckGeneration = checkGeneration;
        
        // 创建新的executor
        executor = Executors.newFixedThreadPool(newThreadCount);
        int totalX = currentMaxX - currentMinX;
        int chunkSize = Math.max(1, totalX / newThreadCount);
        
        // 重新分配任务（使用相同的进度计数器，保持进度）
        for (int i = 0; i < newThreadCount; i++) {
            int startX = currentMinX + i * chunkSize;
            int endX = (i == newThreadCount - 1) ? currentMaxX : startX + chunkSize;
            executor.execute(new RegionChecker(currentSeed, startX, endX, currentMinZ, currentMaxZ, currentMaxHeight, 
                    currentProcessedCount, currentResultCallback, currentCheckGeneration));
        }
        executor.shutdown();
        
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
    
    public MCVersion getMCVersion() {
        return mcVersion;
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
            // 将maxHeight转换为int，用于Box和check方法
            int maxHeightInt = (int) maxHeight;
            int expectedAirCount = 128 - (int)maxHeight;
            
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
                    CPos pos = swampHut.getInRegion(seed, x, z, rand);
                    if (SearchCoords.this.check(seed, 16 * pos.getX() + 3, 16 * pos.getZ() + 3, maxHeightInt)) {
                        try {
                            SeedChecker checker = new SeedChecker(seed, TargetState.NO_STRUCTURES, SeedCheckerDimension.OVERWORLD);
                            Box box = new Box(16 * pos.getX() + 3, maxHeightInt, 16 * pos.getZ() + 3,
                                    16 * pos.getX() + 4, 128, 16 * pos.getZ() + 4);
                            if (checker.getBlockCountInBox(Blocks.AIR, box) == expectedAirCount) {
                                // 计算女巫小屋的实际高度
                                int hutX = 16 * pos.getX();
                                int hutZ = 16 * pos.getZ();
                                Result result = checkHeight(seed, hutX, hutZ, mcVersion);
                                
                                // 只有当高度 <= maxHeight 时才输出
                                if (result.height <= maxHeight) {
                                    String resultStr = result.toString();
                                    // 如果开启了精确检查，检查女巫小屋是否可以生成
                                    if (checkGeneration && !checkHutGeneration(seed, hutX, hutZ)) {
                                        resultStr += " cannot spawn";
                                    }
                                    synchronized (results) {
                                        results.add(resultStr);
                                    }
                                    if (resultCallback != null) {
                                        resultCallback.accept(resultStr);
                                    }
                                }
                            }
                            checker.clearMemory();
                        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                            // 如果 SeedChecker 初始化失败（通常是 log4j 问题），跳过这个坐标
                            // 这不应该阻止程序继续运行
                            if (e.getCause() != null && e.getCause().getMessage() != null &&
                                e.getCause().getMessage().contains("No class provided")) {
                                // 这是 log4j 的调用者查找问题，跳过这个坐标
                                continue;
                            }
                            throw e;
                        }
                    }
                    // 更新进度计数器
                    processedCount.incrementAndGet();
                }
            }
        }
    }

    // Result类，用于返回坐标和高度
    public static class Result {
        public final int x;
        public final int z;
        public final double height;

        public Result(int x, int z, double height) {
            this.x = x;
            this.z = z;
            this.height = height;
        }

        @Override
        public String toString() {
            return String.format("/tp %d %.2f %d", x, height, z);
        }
    }

    // 检查女巫小屋是否可以生成（检查云杉木板）
    public static boolean checkHutGeneration(long seed, int hutX, int hutZ) {
        SeedChecker checker = new SeedChecker(seed, TargetState.STRUCTURES, SeedCheckerDimension.OVERWORLD);
        try {
            for (int y = 60; y >= -54; y--) {
                if (checker.getBlock(hutX + 2, y, hutZ + 2) == Blocks.SPRUCE_PLANKS) {
                    return true;
                }
            }
            return false;
        } finally {
            checker.clearMemory();
        }
    }

    // 计算女巫小屋的高度
    public static Result checkHeight(long seed, int x, int z, MCVersion mcVersion) {
        long structureSeed = seed & 281474976710655L;
        ChunkRand rand = new ChunkRand();
        rand.setCarverSeed(structureSeed, x / 16, z / 16, mcVersion);
        float a = rand.nextFloat();
        SeedChecker checker = new SeedChecker(seed, TargetState.NO_STRUCTURES, SeedCheckerDimension.OVERWORLD);
        int totalheight = 0;
        if (a < 0.25F || (a >= 0.5F && a < 0.75F)) {
            for (int i = x; i < x + 7; i++) {
                for (int j = z; j < z + 9; j++) {
                    boolean checked = false;
                    for (int k = 200; k >= -55 && !checked; k--) {
                        if (!checker.getBlockState(i, k, j).isAir()) {
                            checked = true;
                            totalheight += k;
                        }
                    }
                }
            }
        } else {
            for (int i = x; i < x + 9; i++) {
                for (int j = z; j < z + 7; j++) {
                    boolean checked = false;
                    for (int k = 200; k >= -55 && !checked; k--) {
                        if (!checker.getBlockState(i, k, j).isAir()) {
                            checked = true;
                            totalheight += k;
                        }
                    }
                }
            }
        }
        double height = ((double) totalheight / 63) + 2;
        checker.clearMemory();
        return new Result(x, z, height);
    }

    public boolean check(long seed, int x, int z, int maxHeight) {
        NoiseCache cache = new NoiseCache(seed);
        double erosionSample = cache.erosion.sample((double) x / 4, 0, (double) z / 4);
        if (erosionSample < 0.55) {
            return false;
        }
        double temperature = cache.temperature.sample((double) x / 4, 0, (double) z / 4);
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
        double ridge = cache.ridge.sample((double) x / 4, 0, (double) z / 4);
        if ((ridge > 0.46 && ridge < 0.88) || (ridge < -0.46 && ridge > -0.88)) {
            return false;
        }
        if (Entrance(seed, x, 50, z) >= 0) {
            return false;
        }
        if (Entrance(seed, x, 60, z) >= 0) {
            return false;
        }
        // 检查maxHeight本身
        if (Entrance2(seed, x, maxHeight, z) >= 0 && Cheese(seed, x, maxHeight, z) >= 0) {
            return false;
        }
        // 0以下使用Entrance2
        for (int y = 0; y >= -40; y -= 10) {
            if (maxHeight < y) {
                if (Entrance2(seed, x, y, z) >= 0 && Cheese(seed, x, y, z) >= 0) {
                    return false;
                }
            }
        }
        // 10-40使用Entrance（较复杂）
        for (int y = 10; y <= 40; y += 10) {
            if (Entrance(seed, x, y, z) >= 0 && Cheese(seed, x, y, z) >= 0) {
                return false;
            }
        }
        LazyDoublePerlinNoiseSampler continentalnessNoise = LazyDoublePerlinNoiseSampler.createNoiseSampler(
                new Xoroshiro128PlusPlusRandom(seed).createRandomDeriver(),
                NoiseParameterKey.CONTINENTALNESS
        );
        if (continentalnessNoise.sample((double) x / 4, 0, (double) z / 4) < -0.11) {
            return false;
        }
        LazyDoublePerlinNoiseSampler aquiferNoise = LazyDoublePerlinNoiseSampler.createNoiseSampler(
                new Xoroshiro128PlusPlusRandom(seed).createRandomDeriver(),
                NoiseParameterKey.AQUIFER_FLUID_LEVEL_FLOODEDNESS
        );
        for (int y = maxHeight; y <= 60; y += 10) {
            if (aquiferNoise.sample(x, y * 0.67, z) > 0.41) {
                return false;
            }
        }
        return true;
    }

    private static class NoiseCache {
        final LazyDoublePerlinNoiseSampler caveEntrance;
        final LazyDoublePerlinNoiseSampler spaghettiRarity;
        final LazyDoublePerlinNoiseSampler spaghettiThickness;
        final LazyDoublePerlinNoiseSampler spaghetti3D1;
        final LazyDoublePerlinNoiseSampler spaghetti3D2;
        final LazyDoublePerlinNoiseSampler spaghettiRoughnessModulator;
        final LazyDoublePerlinNoiseSampler spaghettiRoughness;
        final LazyDoublePerlinNoiseSampler erosion;
        final LazyDoublePerlinNoiseSampler temperature;
        final LazyDoublePerlinNoiseSampler ridge;

        NoiseCache(long worldseed) {
            Xoroshiro128PlusPlusRandom random = new Xoroshiro128PlusPlusRandom(worldseed);
            var deriver = random.createRandomDeriver();
            caveEntrance = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.CAVE_ENTRANCE);
            spaghettiRarity = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_RARITY);
            spaghettiThickness = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_THICKNESS);
            spaghetti3D1 = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_1);
            spaghetti3D2 = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_3D_2);
            spaghettiRoughnessModulator = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_ROUGHNESS_MODULATOR);
            spaghettiRoughness = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.SPAGHETTI_ROUGHNESS);
            erosion = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.EROSION);
            temperature = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.TEMPERATURE);
            ridge = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.RIDGE);
        }
    }

    private static class CheeseNoiseCache {
        final LazyDoublePerlinNoiseSampler caveLayer;
        final LazyDoublePerlinNoiseSampler caveCheese;

        CheeseNoiseCache(long worldseed) {
            Xoroshiro128PlusPlusRandom random = new Xoroshiro128PlusPlusRandom(worldseed);
            var deriver = random.createRandomDeriver();
            caveLayer = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.CAVE_LAYER);
            caveCheese = LazyDoublePerlinNoiseSampler.createNoiseSampler(deriver, NoiseParameterKey.CAVE_CHEESE);
        }
    }

    public static double Entrance(long worldseed, int x, int y, int z) {
        NoiseCache cache = new NoiseCache(worldseed);
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

    public static double Cheese(long worldseed, int x, int y, int z) {
        CheeseNoiseCache cache = new CheeseNoiseCache(worldseed);
        double a = 4 * cache.caveLayer.sample(x, y * 8, z) * cache.caveLayer.sample(x, y * 8, z);
        double b = MathHelper.clamp((0.27 + cache.caveCheese.sample(x, y * 0.6666666666666666, z)), -1, 1);
        return a + b;//Actually there still need to add a function about sloped_cheese, but sloped_cheese is too complex and IDK how to calculate it.
    }

    public static double Entrance2(long worldseed, int x, int y, int z) {
        NoiseCache cache = new NoiseCache(worldseed);
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

