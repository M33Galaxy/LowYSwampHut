package project.benchmark;

import project.GameVersion;
import project.SearchCoords;
import project.SeedCheckerInitializer;
import project.WorldPresetMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 运行真实搜索路径，不替换或近似处理 seed logic。 */
public final class BenchmarkRunner {
    private BenchmarkRunner() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        StressProfile profile = profile(options.getOrDefault("profile", "A"));
        validate(profile);

        // 必须在 SearchCoords 或 Minecraft Util 初始化前设置。
        System.setProperty("lowyswamphut.maxSearchThreads", Integer.toString(profile.threads()));
        System.setProperty("lowyswamphut.maxConcurrentRealGenerations",
                Integer.toString(profile.worldgenConcurrency()));
        System.setProperty("lowyswamphut.maxWorldgenThreads",
                Integer.toString(profile.worldgenConcurrency()));
        System.setProperty("max.bg.threads", Integer.toString(profile.worldgenConcurrency()));
        System.setProperty("lowyswamphut.maxChunkCacheSize",
                Integer.toString(profile.maxChunkCacheSize()));
        System.setProperty("lowyswamphut.metrics.cpuCapacityThreads",
                Integer.toString(profile.threads()));

        long sampleSeconds = Long.parseLong(options.getOrDefault("sample-seconds", "5"));
        Path csv = Path.of(options.getOrDefault("output",
                "benchmark-results/" + profile.name() + "-" + Instant.now().toEpochMilli() + ".csv"));
        Path report = siblingReport(csv);
        AtomicLong iterations = new AtomicLong();

        System.out.printf(Locale.ROOT,
                "Starting %s: threads=%d iterations=%,d cache=%d worldgen=%d output=%s%n",
                profile.name(), profile.threads(), profile.iterations(), profile.maxChunkCacheSize(),
                profile.worldgenConcurrency(), csv.toAbsolutePath());

        SeedCheckerInitializer.initialize(WorldPresetMode.NORMAL);
        PerformanceMetrics metrics = new PerformanceMetrics(
                iterations::get, csv, sampleSeconds * 1_000L);
        SearchCoords search = new SearchCoords(
                GameVersion.V1_18_2, WorldPresetMode.NORMAL, metrics);
        PerformanceMetrics.Analysis analysis;
        try (metrics) {
            metrics.start();
            search.startSearch(profile.seed(), profile.threads(), 0, profile.width(), 0, profile.height(),
                    profile.maxHeight(), info -> iterations.set(info.processed()), null, true);
            while (search.isRunning()) {
                Thread.sleep(1_000L);
            }
            analysis = metrics.analysis();
        } finally {
            search.stop();
        }

        writeReport(report, profile, iterations.get(), analysis);
        System.out.printf("Completed: iterations=%,d conclusive=%s plateau=%s hiddenLeak=%s throughputStable=%s bottleneck=%s%n",
                iterations.get(), yesNo(analysis.conclusive()), yesNo(analysis.plateau()), yesNo(analysis.hiddenMemoryLeak()),
                yesNo(analysis.throughputStable()), analysis.bottleneck());
        System.out.println("Analysis: " + analysis.reason());
        System.out.println("Report: " + report.toAbsolutePath());
    }

    private static StressProfile profile(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "A" -> new StressProfileA();
            case "B" -> new StressProfileB();
            case "C" -> new StressProfileC();
            default -> throw new IllegalArgumentException("Unknown profile: " + name + " (expected A, B or C)");
        };
    }

    private static void validate(StressProfile profile) {
        long rectangle = (long) profile.width() * profile.height();
        if (rectangle != profile.iterations()) {
            throw new IllegalArgumentException("Profile dimensions do not equal requested iterations");
        }
        if (profile.threads() < 1 || profile.threads() > 4) {
            throw new IllegalArgumentException("Profile threads must be within 1..4");
        }
        if (profile.maxChunkCacheSize() < 512 || profile.maxChunkCacheSize() > 2_048) {
            throw new IllegalArgumentException("Profile cache must be within 512..2048");
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --name=value, got: " + arg);
            }
            int split = arg.indexOf('=');
            result.put(arg.substring(2, split), arg.substring(split + 1));
        }
        return result;
    }

    private static Path siblingReport(Path csv) {
        String name = csv.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        Path parent = csv.toAbsolutePath().getParent();
        return parent.resolve(base + "-summary.json");
    }

    private static void writeReport(Path path, StressProfile profile, long iterations,
                                    PerformanceMetrics.Analysis analysis) throws IOException {
        Files.createDirectories(path.getParent());
        String json = """
                {
                  "profile": "%s",
                  "iterations": %d,
                  "conclusive": %s,
                  "plateau": %s,
                  "hidden_memory_leak": %s,
                  "throughput_stable": %s,
                  "bottleneck": "%s",
                  "reason": "%s"
                }
                """.formatted(profile.name(), iterations, analysis.conclusive(), analysis.plateau(),
                analysis.hiddenMemoryLeak(), analysis.throughputStable(),
                escape(analysis.bottleneck()), escape(analysis.reason()));
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
