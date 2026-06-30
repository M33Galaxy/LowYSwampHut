package project.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MetricsCsvExporter implements AutoCloseable {
    private final BufferedWriter writer;

    public MetricsCsvExporter(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write("timestamp,elapsed_s,iterations,ms_per_100k,iterations_per_sec,"
                + "chunks_total,chunks_per_sec,structures_total,structures_per_sec,"
                + "heap_used_mb,heap_committed_mb,heap_max_mb,cache_size,cache_peak,cache_single_peak,"
                + "seedcheckers,seedchecker_estimated_mb,region_workers,active_threads,blocked_threads,"
                + "forkjoin_active,forkjoin_queued,gc_pause_delta_ms,gc_pause_total_ms,"
                + "process_cpu_percent,worker_cpu_percent,alarms\n");
        writer.flush();
    }

    public synchronized void write(PerformanceMetrics.Snapshot s) throws IOException {
        writer.write(String.join(",",
                Long.toString(s.timestamp()),
                format(s.elapsedSeconds()),
                Long.toString(s.iterations()),
                format(s.millisPer100k()),
                format(s.iterationsPerSecond()),
                Long.toString(s.chunksGenerated()),
                format(s.chunksPerSecond()),
                Long.toString(s.structuresGenerated()),
                format(s.structuresPerSecond()),
                format(s.heapUsedMb()),
                format(s.heapCommittedMb()),
                format(s.heapMaxMb()),
                Long.toString(s.cacheOccupancy()),
                Long.toString(s.cachePeak()),
                Long.toString(s.cacheSinglePeak()),
                Integer.toString(s.seedCheckerInstances()),
                format(s.seedCheckerEstimatedMb()),
                Integer.toString(s.regionWorkers()),
                Integer.toString(s.activeThreads()),
                Integer.toString(s.blockedThreads()),
                Integer.toString(s.forkJoinActive()),
                Long.toString(s.forkJoinQueued()),
                Long.toString(s.gcPauseDeltaMs()),
                Long.toString(s.gcPauseTotalMs()),
                format(s.processCpuPercent()),
                format(s.workerCpuPercent()),
                quote(s.alarms())));
        writer.newLine();
        writer.flush();
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.3f", value) : "";
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
