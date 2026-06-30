package project.benchmark;

import net.minecraft.util.Util;
import nl.jellejurre.seedchecker.SeedChecker;
import project.SearchMetricsHook;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 仅用于测试的 JVM、worldgen 与搜索遥测采集器。 */
public final class PerformanceMetrics implements AutoCloseable, SearchMetricsHook {
    private static final AtomicBoolean ENABLED = new AtomicBoolean();
    private static final AtomicLong CHUNKS_GENERATED = new AtomicLong();
    private static final AtomicLong STRUCTURES_GENERATED = new AtomicLong();
    private static final AtomicLong CACHE_OCCUPANCY = new AtomicLong();
    private static final AtomicLong CACHE_PEAK = new AtomicLong();
    private static final AtomicLong CACHE_SINGLE_PEAK = new AtomicLong();
    private static final AtomicInteger SEED_CHECKER_INSTANCES = new AtomicInteger();
    private static final AtomicInteger REGION_WORKERS = new AtomicInteger();
    private static final Map<SeedChecker, Integer> CHECKER_CACHE_SIZES = new WeakHashMap<>();

    private static final double ESTIMATED_CHECKER_BASE_MB =
            doubleProperty("lowyswamphut.metrics.seedCheckerBaseMb", 32.0);
    private static final double ESTIMATED_PROTO_CHUNK_MB =
            doubleProperty("lowyswamphut.metrics.protoChunkMb", 1.5);
    private static final double CPU_CAPACITY_THREADS =
            doubleProperty("lowyswamphut.metrics.cpuCapacityThreads", 4.0);
    private static final int CONFIGURED_CHUNK_CACHE_LIMIT =
            Integer.getInteger("lowyswamphut.maxChunkCacheSize", 1_024);

    private final LongSupplier iterations;
    private final MetricsCsvExporter exporter;
    private final ScheduledExecutorService sampler;
    private final AnomalyDetector detector = new AnomalyDetector();
    private final long startedAt = System.currentTimeMillis();
    private final long samplePeriodMs;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    private volatile Snapshot latest;
    private long previousTimestamp;
    private long previousIterations;
    private long previousChunks;
    private long previousStructures;
    private long previousGcTime;

    public PerformanceMetrics(LongSupplier iterations, Path csvPath, long samplePeriodMs) throws IOException {
        this.iterations = iterations;
        this.samplePeriodMs = Math.max(1_000L, samplePeriodMs);
        this.exporter = new MetricsCsvExporter(csvPath);
        resetInstrumentation();
        ENABLED.set(true);
        previousTimestamp = startedAt;
        previousGcTime = totalGcTime();
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "performance-metrics");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        sampler.scheduleAtFixedRate(this::sampleSafely, 0L, samplePeriodMs, TimeUnit.MILLISECONDS);
    }

    public Snapshot latest() {
        return latest;
    }

    public Analysis analysis() {
        return detector.analysis();
    }

    @Override
    public void seedCheckerCreated(Object checkerObject) {
        SeedChecker checker = (SeedChecker) checkerObject;
        if (!ENABLED.get()) return;
        synchronized (CHECKER_CACHE_SIZES) {
            if (!CHECKER_CACHE_SIZES.containsKey(checker)) {
                CHECKER_CACHE_SIZES.put(checker, 0);
                SEED_CHECKER_INSTANCES.incrementAndGet();
            }
        }
    }

    @Override
    public void seedCheckerReleased(Object checkerObject) {
        SeedChecker checker = (SeedChecker) checkerObject;
        if (!ENABLED.get()) return;
        synchronized (CHECKER_CACHE_SIZES) {
            Integer size = CHECKER_CACHE_SIZES.remove(checker);
            if (size != null) {
                CACHE_OCCUPANCY.addAndGet(-size);
                SEED_CHECKER_INSTANCES.decrementAndGet();
            }
        }
    }

    @Override
    public void chunkCacheObserved(Object checkerObject, int currentSize) {
        SeedChecker checker = (SeedChecker) checkerObject;
        if (!ENABLED.get()) return;
        synchronized (CHECKER_CACHE_SIZES) {
            Integer previous = CHECKER_CACHE_SIZES.put(checker, currentSize);
            if (previous == null) {
                previous = 0;
                SEED_CHECKER_INSTANCES.incrementAndGet();
            }
            int delta = currentSize - previous;
            CACHE_SINGLE_PEAK.accumulateAndGet(currentSize, Math::max);
            CACHE_OCCUPANCY.addAndGet(delta);
            if (delta > 0) {
                CHUNKS_GENERATED.addAndGet(delta);
            }
            CACHE_PEAK.accumulateAndGet(CACHE_OCCUPANCY.get(), Math::max);
        }
    }

    @Override
    public void structureGenerationStarted() {
        if (ENABLED.get()) STRUCTURES_GENERATED.incrementAndGet();
    }

    @Override
    public void regionWorkerStarted() {
        if (ENABLED.get()) REGION_WORKERS.incrementAndGet();
    }

    @Override
    public void regionWorkerStopped() {
        if (ENABLED.get()) REGION_WORKERS.decrementAndGet();
    }

    private static void resetInstrumentation() {
        CHUNKS_GENERATED.set(0L);
        STRUCTURES_GENERATED.set(0L);
        CACHE_OCCUPANCY.set(0L);
        CACHE_PEAK.set(0L);
        CACHE_SINGLE_PEAK.set(0L);
        SEED_CHECKER_INSTANCES.set(0);
        REGION_WORKERS.set(0);
        synchronized (CHECKER_CACHE_SIZES) {
            CHECKER_CACHE_SIZES.clear();
        }
    }

    private void sampleSafely() {
        try {
            Snapshot snapshot = createSnapshot();
            latest = snapshot;
            detector.accept(snapshot);
            Snapshot withAlarms = snapshot.withAlarms(String.join(" | ", detector.currentAlarms()));
            latest = withAlarms;
            exporter.write(withAlarms);
            print(withAlarms);
        } catch (Throwable error) {
            System.err.println("[metrics] sampling failed: " + error);
        }
    }

    private Snapshot createSnapshot() {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1L, now - previousTimestamp);
        long iterationCount = iterations.getAsLong();
        long chunkCount = CHUNKS_GENERATED.get();
        long structureCount = STRUCTURES_GENERATED.get();
        long gcTime = totalGcTime();

        long iterationDelta = Math.max(0L, iterationCount - previousIterations);
        long chunkDelta = Math.max(0L, chunkCount - previousChunks);
        long structureDelta = Math.max(0L, structureCount - previousStructures);
        long gcDelta = Math.max(0L, gcTime - previousGcTime);
        double seconds = intervalMs / 1_000.0;

        MemoryUsage heap = memory.getHeapMemoryUsage();
        ThreadCounts threadCounts = threadCounts();
        ForkJoinCounts forkJoin = forkJoinCounts();
        int instances = SEED_CHECKER_INSTANCES.get();
        long occupancy = Math.max(0L, CACHE_OCCUPANCY.get());
        double processCpu = processCpuPercent();
        double workerCpu = Double.isFinite(processCpu)
                ? Math.min(100.0, processCpu * Runtime.getRuntime().availableProcessors()
                        / Math.max(1.0, CPU_CAPACITY_THREADS))
                : Double.NaN;

        Snapshot result = new Snapshot(
                now, (now - startedAt) / 1_000.0, iterationCount,
                iterationDelta == 0 ? Double.NaN : intervalMs * 100_000.0 / iterationDelta,
                iterationDelta / seconds,
                chunkCount, chunkDelta / seconds,
                structureCount, structureDelta / seconds,
                mb(heap.getUsed()), mb(heap.getCommitted()), mb(heap.getMax()),
                occupancy, CACHE_PEAK.get(), CACHE_SINGLE_PEAK.get(), instances,
                instances * ESTIMATED_CHECKER_BASE_MB + occupancy * ESTIMATED_PROTO_CHUNK_MB,
                REGION_WORKERS.get(), threadCounts.active(), threadCounts.blocked(),
                forkJoin.active(), forkJoin.queued(), gcDelta, gcTime,
                processCpu, workerCpu, "");

        previousTimestamp = now;
        previousIterations = iterationCount;
        previousChunks = chunkCount;
        previousStructures = structureCount;
        previousGcTime = gcTime;
        return result;
    }

    private ThreadCounts threadCounts() {
        int active = 0;
        int blocked = 0;
        ThreadInfo[] infos = threads.dumpAllThreads(false, false);
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            if (info.getThreadState() == Thread.State.RUNNABLE) active++;
            if (info.getThreadState() == Thread.State.BLOCKED) blocked++;
        }
        return new ThreadCounts(active, blocked);
    }

    private static ForkJoinCounts forkJoinCounts() {
        ExecutorService executor = Util.getMainWorkerExecutor();
        if (executor instanceof ForkJoinPool pool) {
            return new ForkJoinCounts(pool.getActiveThreadCount(),
                    pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount());
        }
        return new ForkJoinCounts(0, 0L);
    }

    private long totalGcTime() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : gcBeans) {
            if (bean.getCollectionTime() > 0L) total += bean.getCollectionTime();
        }
        return total;
    }

    private static double processCpuPercent() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            return Math.max(0.0, sun.getProcessCpuLoad() * 100.0);
        }
        return Double.NaN;
    }

    private static void print(Snapshot s) {
        String alarm = s.alarms().isEmpty() ? "" : " ALERT=" + s.alarms();
        System.out.printf(java.util.Locale.ROOT,
                "[perf] iter=%,d ms/100k=%.0f chunks/s=%.1f structures/s=%.2f "
                        + "heap=%.0fMB cache=%d peak/checker=%d workers=%d threads=%d blocked=%d fjq=%d cpu=%.1f%%%s%n",
                s.iterations(), s.millisPer100k(), s.chunksPerSecond(), s.structuresPerSecond(),
                s.heapUsedMb(), s.cacheOccupancy(), s.cacheSinglePeak(), s.regionWorkers(),
                s.activeThreads(), s.blockedThreads(),
                s.forkJoinQueued(), s.workerCpuPercent(), alarm);
    }

    private static double mb(long bytes) {
        return bytes < 0L ? Double.NaN : bytes / 1_048_576.0;
    }

    private static double doubleProperty(String name, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public void close() throws IOException {
        sampler.shutdown();
        try {
            sampler.awaitTermination(10L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sampleSafely();
        ENABLED.set(false);
        exporter.close();
    }

    public record Snapshot(long timestamp, double elapsedSeconds, long iterations,
                           double millisPer100k, double iterationsPerSecond,
                           long chunksGenerated, double chunksPerSecond,
                           long structuresGenerated, double structuresPerSecond,
                           double heapUsedMb, double heapCommittedMb, double heapMaxMb,
                           long cacheOccupancy, long cachePeak, long cacheSinglePeak, int seedCheckerInstances,
                           double seedCheckerEstimatedMb, int regionWorkers, int activeThreads, int blockedThreads,
                           int forkJoinActive, long forkJoinQueued, long gcPauseDeltaMs,
                           long gcPauseTotalMs, double processCpuPercent,
                           double workerCpuPercent, String alarms) {
        Snapshot withAlarms(String value) {
            return new Snapshot(timestamp, elapsedSeconds, iterations, millisPer100k,
                    iterationsPerSecond, chunksGenerated, chunksPerSecond,
                    structuresGenerated, structuresPerSecond, heapUsedMb,
                    heapCommittedMb, heapMaxMb, cacheOccupancy, cachePeak, cacheSinglePeak,
                    seedCheckerInstances, seedCheckerEstimatedMb, regionWorkers, activeThreads,
                    blockedThreads, forkJoinActive, forkJoinQueued, gcPauseDeltaMs,
                    gcPauseTotalMs, processCpuPercent, workerCpuPercent, value);
        }
    }

    public record Analysis(boolean conclusive, boolean plateau, boolean hiddenMemoryLeak,
                           boolean throughputStable, String bottleneck, String reason) {}

    private record ThreadCounts(int active, int blocked) {}
    private record ForkJoinCounts(int active, long queued) {}

    private static final class AnomalyDetector {
        private static final long TEN_MINUTES_MS = TimeUnit.MINUTES.toMillis(10);
        private final Deque<Snapshot> history = new ArrayDeque<>();
        private final List<String> currentAlarms = new ArrayList<>();

        void accept(Snapshot snapshot) {
            history.addLast(snapshot);
            while (!history.isEmpty()
                    && snapshot.timestamp() - history.getFirst().timestamp() > TEN_MINUTES_MS) {
                history.removeFirst();
            }
            currentAlarms.clear();
            List<Snapshot> samples = List.copyOf(history);
            if (snapshot.regionWorkers() > 4) {
                currentAlarms.add("RegionChecker workers exceeded 4");
            }
            if (samples.size() >= 12) {
                if (throughputDecline(samples) > 0.20) {
                    currentAlarms.add("chunks/sec declined >20%");
                }
                if (backlogGrowing(samples)) {
                    currentAlarms.add("ForkJoin queue backlog growing");
                }
                List<Snapshot> recent = samples.subList(samples.size() - 12, samples.size());
                Snapshot recentFirst = recent.get(0);
                Snapshot recentLast = recent.get(recent.size() - 1);
                if (recentLast.iterations() == recentFirst.iterations()
                        && recentLast.chunksGenerated() > recentFirst.chunksGenerated()) {
                    currentAlarms.add("iteration stalled while chunks continue (cache thrash)");
                }
                long highGcSamples = samples.stream().filter(s -> s.gcPauseDeltaMs() > 500L).count();
                if (highGcSamples >= 3) {
                    currentAlarms.add("frequent GC pauses >500ms");
                }
                double averageCpu = samples.stream().mapToDouble(Snapshot::workerCpuPercent)
                        .filter(Double::isFinite).average().orElse(Double.NaN);
                if (Double.isFinite(averageCpu) && (averageCpu < 60.0 || averageCpu > 90.0)) {
                    currentAlarms.add(String.format(java.util.Locale.ROOT,
                            "worker CPU outside 60-90%% (%.1f%%)", averageCpu));
                }
            }
            if (windowCoversTenMinutes(samples) && memorySlopeMbPerMinute(samples) > 8.0) {
                currentAlarms.add("heap has not plateaued for 10 minutes");
            }
        }

        List<String> currentAlarms() {
            return List.copyOf(currentAlarms);
        }

        Analysis analysis() {
            List<Snapshot> samples = List.copyOf(history);
            boolean enough = windowCoversTenMinutes(samples);
            double memorySlope = memorySlopeMbPerMinute(samples);
            boolean plateau = enough && memorySlope <= 8.0;
            boolean leak = enough && memorySlope > 8.0;
            boolean throughputStable = samples.size() >= 12 && throughputDecline(samples) <= 0.20;
            String bottleneck = inferBottleneck(samples);
            String reason = enough
                    ? String.format(java.util.Locale.ROOT, "10-minute heap slope %.2f MB/min", memorySlope)
                    : "insufficient duration: plateau requires 10 minutes of samples";
            return new Analysis(enough, plateau, leak, throughputStable, bottleneck, reason);
        }

        private static boolean windowCoversTenMinutes(List<Snapshot> samples) {
            return samples.size() >= 2
                    && samples.get(samples.size() - 1).timestamp() - samples.get(0).timestamp()
                    >= TEN_MINUTES_MS - 10_000L;
        }

        private static double memorySlopeMbPerMinute(List<Snapshot> samples) {
            if (samples.size() < 2) return Double.NaN;
            long origin = samples.get(0).timestamp();
            double sumX = 0.0;
            double sumY = 0.0;
            double sumXX = 0.0;
            double sumXY = 0.0;
            for (Snapshot sample : samples) {
                double x = (sample.timestamp() - origin) / 60_000.0;
                double y = sample.heapUsedMb();
                sumX += x;
                sumY += y;
                sumXX += x * x;
                sumXY += x * y;
            }
            double n = samples.size();
            double denominator = n * sumXX - sumX * sumX;
            return denominator == 0.0 ? Double.NaN : (n * sumXY - sumX * sumY) / denominator;
        }

        private static double throughputDecline(List<Snapshot> samples) {
            int third = Math.max(1, samples.size() / 3);
            double first = chunkRate(samples.subList(0, third));
            double last = chunkRate(samples.subList(samples.size() - third, samples.size()));
            return first <= 0.0 ? 0.0 : Math.max(0.0, (first - last) / first);
        }

        private static double chunkRate(List<Snapshot> samples) {
            if (samples.size() < 2) return 0.0;
            Snapshot first = samples.get(0);
            Snapshot last = samples.get(samples.size() - 1);
            double seconds = (last.timestamp() - first.timestamp()) / 1_000.0;
            return seconds <= 0.0 ? 0.0 : (last.chunksGenerated() - first.chunksGenerated()) / seconds;
        }

        private static boolean backlogGrowing(List<Snapshot> samples) {
            Snapshot first = samples.get(0);
            Snapshot last = samples.get(samples.size() - 1);
            return last.forkJoinQueued() - first.forkJoinQueued() >= 16L;
        }

        private static String inferBottleneck(List<Snapshot> samples) {
            if (samples.isEmpty()) return "unknown";
            Snapshot last = samples.get(samples.size() - 1);
            if (last.gcPauseDeltaMs() > 500L) return "GC";
            if (last.cacheSinglePeak() > CONFIGURED_CHUNK_CACHE_LIMIT) return "cache";
            if (last.forkJoinQueued() > 8L || last.forkJoinActive() > 0) return "worldgen";
            if (last.cacheOccupancy() >= 1_024L) return "cache";
            if (last.workerCpuPercent() >= 80.0) return "CPU";
            return "worldgen/waiting";
        }
    }
}
