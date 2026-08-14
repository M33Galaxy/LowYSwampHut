package project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LowYSwampHut 命令行入口。单种子与多种子逻辑与图形界面一致。
 */
public final class CmdLineRunner {

    private static final int DEFAULT_MIN_X = -58594;
    private static final int DEFAULT_MAX_X = 58593;
    private static final int DEFAULT_MIN_Z = -58594;
    private static final int DEFAULT_MAX_Z = 58593;
    private static final long DEFAULT_SQUARE_SIDE = DEFAULT_MAX_X - (long) DEFAULT_MIN_X + 1;

    private static AppLocale i18n;

    private CmdLineRunner() {
    }

    public static void run(String[] args) {
        CliOptions options = CliOptions.parse(args);
        i18n = AppLocale.load(options.lang);
        System.out.println(i18n.get("cli.detectedLanguage", i18n.displayName()));

        if (options.showHelp) {
            printHelp();
            System.exit(0);
            return;
        }
        if (options.seedsFile != null && options.seedSpecified) {
            fail(i18n.get("cli.seedAndListConflict"));
        }
        if (!options.seedSpecified && options.seedsFile == null) {
            fail(i18n.get("cli.needSeedOrList"));
        }

        if (options.seedsFile != null) {
            runListSearch(options);
        } else {
            runSingleSearch(options);
        }
        // SeedChecker / Minecraft Util 会留下非守护线程，不主动退出的话进程会挂住。
        System.exit(0);
    }

    private static void runSingleSearch(CliOptions options) {
        SearchContext ctx = prepareContext(options, false);
        System.out.println(i18n.get(
                "cli.singleStart",
                Long.toString(options.seed), ctx.maxY, options.versionName, ctx.presetLabel, ctx.checkGen, ctx.threads
        ));
        System.out.println(i18n.get("cli.searchRange",
                Integer.toString(ctx.minX), Integer.toString(ctx.maxX),
                Integer.toString(ctx.minZ), Integer.toString(ctx.maxZ)));
        if (!options.noProgress) {
            System.out.println(i18n.get("cli.progressEnabled"));
        }

        resetProgressOutput();
        long startTime = System.currentTimeMillis();
        SearchCoords searcher = new SearchCoords(ctx.gameVersion, ctx.preset);
        List<String> rawResults = Collections.synchronizedList(new ArrayList<>());
        AtomicLong lastProgressPrintMs = new AtomicLong(0);

        searcher.startSearch(
                options.seed,
                ctx.threads,
                ctx.minX, ctx.maxX, ctx.minZ, ctx.maxZ,
                ctx.maxY,
                progress -> {
                    if (options.noProgress) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastProgressPrintMs.get() < 200 && progress.processed() < progress.total()) {
                        return;
                    }
                    lastProgressPrintMs.set(now);
                    printProgressLine(formatSingleProgress(progress));
                },
                result -> {
                    rawResults.add(result);
                    printHitLine(result);
                },
                ctx.checkGen
        );
        searcher.awaitCompletion();

        closeProgressLine();
        System.out.println(i18n.get("cli.searchComplete", formatTime(System.currentTimeMillis() - startTime)));

        if (rawResults.isEmpty()) {
            System.out.println(i18n.get("cli.noHuts"));
            return;
        }

        List<String> sortedResults = sortTpLines(rawResults);
        writeLines(options.outputFile, writer -> {
            writer.println(i18n.get("cli.foundHuts", sortedResults.size()));
            for (String line : sortedResults) {
                writer.println(line);
            }
            writer.println();
            writer.println(i18n.get("cli.lowestHut", sortedResults.get(0)));
        });
        System.out.println(i18n.get("cli.resultsSaved", options.outputFile));
        maybeExportSeeds(options.exportSeedsFile, List.of(options.seed));
    }

    private static void runListSearch(CliOptions options) {
        File seedFile = new File(options.seedsFile);
        if (!seedFile.isFile()) {
            fail(i18n.get("error.seedFileRequired"));
        }

        SearchContext ctx = prepareContext(options, true);
        final long totalSeeds;
        try {
            System.out.println(i18n.get("progress.countingSeeds"));
            totalSeeds = ListSearchSupport.countValidSeeds(seedFile, () -> true);
        } catch (IOException e) {
            fail(i18n.get("error.seedFileReadFailed", e.getMessage()));
            return;
        }
        if (totalSeeds <= 0) {
            fail(i18n.get("error.seedFileEmpty"));
        }

        long area = (long) (ctx.maxX - ctx.minX) * (ctx.maxZ - ctx.minZ);
        int concurrentSeeds = ListSearchSupport.computeConcurrentSeeds(area, ctx.threads);
        int threadsPerSeed = ListSearchSupport.computeThreadsPerSeed(ctx.threads, concurrentSeeds);
        boolean seedParallel = concurrentSeeds > 1;

        System.out.println(i18n.get(
                "cli.listStart",
                seedFile.getName(), Long.toString(totalSeeds), ctx.maxY, options.versionName,
                ctx.presetLabel, ctx.checkGen, ctx.threads, concurrentSeeds, threadsPerSeed
        ));
        System.out.println(i18n.get("cli.searchRange",
                Integer.toString(ctx.minX), Integer.toString(ctx.maxX),
                Integer.toString(ctx.minZ), Integer.toString(ctx.maxZ)));
        if (!options.noProgress) {
            System.out.println(i18n.get("cli.progressEnabled"));
        }

        resetProgressOutput();
        Map<Long, List<String>> seedResults = new ConcurrentHashMap<>();
        List<Long> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processedSeeds = new AtomicInteger(0);
        AtomicLong lastProgressPrintMs = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(concurrentSeeds);
        Semaphore throttle = new Semaphore(Math.max(2, concurrentSeeds * 2));

        try (BufferedReader reader = new BufferedReader(new FileReader(seedFile), 1 << 20)) {
            String line;
            int seedIndex = 0;
            while ((line = reader.readLine()) != null) {
                Long parsed = ListSearchSupport.parseSeedLine(line);
                if (parsed == null) {
                    continue;
                }
                final long seed = parsed;
                seedIndex++;
                final int currentSeedIndex = seedIndex;
                throttle.acquire();
                try {
                    executor.submit(() -> {
                        try {
                            searchOneListSeed(
                                    seed, currentSeedIndex, totalSeeds,
                                    ctx, threadsPerSeed, seedParallel, options.noProgress,
                                    seedResults, completionOrder, processedSeeds,
                                    lastProgressPrintMs, startTime
                            );
                        } finally {
                            throttle.release();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    throttle.release();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            fail(i18n.get("cli.searchInterrupted"));
        } catch (IOException e) {
            executor.shutdownNow();
            fail(i18n.get("error.seedFileReadFailed", e.getMessage()));
        }

        executor.shutdown();
        try {
            while (!executor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                // wait
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            fail(i18n.get("cli.searchInterrupted"));
        }

        closeProgressLine();
        System.out.println(i18n.get("cli.searchComplete", formatTime(System.currentTimeMillis() - startTime)));
        System.out.println(i18n.get("progress.totalComplete", processedSeeds.get(), totalSeeds));

        List<Long> seedsWithHits = new ArrayList<>(completionOrder);
        writeLines(options.outputFile, writer -> {
            for (Long seed : seedsWithHits) {
                List<String> results = seedResults.get(seed);
                if (results == null || results.isEmpty()) {
                    continue;
                }
                writer.println(seed);
                for (String result : results) {
                    writer.println(result);
                }
            }
        });
        if (seedsWithHits.isEmpty()) {
            System.out.println(i18n.get("cli.noHuts"));
        } else {
            System.out.println(i18n.get("cli.resultsSaved", options.outputFile));
        }
        maybeExportSeeds(options.exportSeedsFile, seedsWithHits);
    }

    private static void searchOneListSeed(
            long seed, int currentSeedIndex, long totalSeeds,
            SearchContext ctx, int threadsPerSeed, boolean seedParallel, boolean noProgress,
            Map<Long, List<String>> seedResults, List<Long> completionOrder, AtomicInteger processedSeeds,
            AtomicLong lastProgressPrintMs, long startTime
    ) {
        SearchCoords searcher = new SearchCoords(ctx.gameVersion, ctx.preset);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        searcher.startSearch(
                seed,
                threadsPerSeed,
                ctx.minX, ctx.maxX, ctx.minZ, ctx.maxZ,
                ctx.maxY,
                progress -> {
                    if (noProgress || seedParallel) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastProgressPrintMs.get() < 200) {
                        return;
                    }
                    lastProgressPrintMs.set(now);
                    String stage = progress.stage() == 1 ? i18n.get("cli.stage1") : i18n.get("cli.stage2");
                    printProgressLine(assembleProgress(
                            stage + " " + formatPercent(progress.percentage()),
                            i18n.get("cli.elapsedShort", formatCompactTime(System.currentTimeMillis() - startTime)),
                            i18n.get("cli.remainingShort",
                                    formatRemaining(progress.remainingMs(), progress.processed(), progress.total())),
                            currentSeedIndex + "/" + totalSeeds + " "
                                    + progress.processed() + "/" + progress.total()
                    ));
                },
                result -> {
                    results.add(result);
                    if (!seedParallel) {
                        printHitLine(result);
                    }
                },
                ctx.checkGen
        );
        searcher.awaitCompletion();

        if (!results.isEmpty()) {
            seedResults.put(seed, new ArrayList<>(results));
            completionOrder.add(seed);
        }
        int completed = processedSeeds.incrementAndGet();
        if (!noProgress) {
            long now = System.currentTimeMillis();
            if (now - lastProgressPrintMs.get() >= 100 || completed == totalSeeds) {
                lastProgressPrintMs.set(now);
                double pct = completed * 100.0 / totalSeeds;
                String extra = seedParallel
                        ? i18n.get("currentSeed.concurrent",
                        ListSearchSupport.computeConcurrentSeeds(
                                (long) (ctx.maxX - ctx.minX) * (ctx.maxZ - ctx.minZ), ctx.threads))
                        : "";
                long elapsed = System.currentTimeMillis() - startTime;
                long remaining = completed > 0 && completed < totalSeeds
                        ? elapsed * (totalSeeds - completed) / completed
                        : 0;
                String remainingText = formatRemaining(remaining, completed, totalSeeds);
                printProgressLine(assembleProgress(
                        completed + "/" + totalSeeds + " " + formatPercent(pct),
                        i18n.get("cli.elapsedShort", formatCompactTime(elapsed)),
                        i18n.get("cli.remainingShort", remainingText),
                        extra
                ));
            }
        }
    }

    private static SearchContext prepareContext(CliOptions options, boolean listMode) {
        int[] bounds = resolveBounds(options, listMode);
        if (bounds[0] >= bounds[1]) {
            fail(i18n.get("error.minXGreaterThanMaxX"));
        }
        if (bounds[2] >= bounds[3]) {
            fail(i18n.get("error.minZGreaterThanMaxZ"));
        }

        GameVersion gameVersion = GameVersion.fromDisplayName(options.versionName);
        WorldPresetMode preset = parsePreset(options.presetName);
        if (preset == null) {
            fail(i18n.get("cli.unsupportedPreset", options.presetName));
        }

        boolean checkGen = options.checkGen;
        if (preset == WorldPresetMode.SINGLE_BIOME && checkGen) {
            System.out.println(i18n.get("cli.singleBiomeCheckDisabled"));
            checkGen = false;
        }

        int threads = options.threads;
        int cpuThreads = Runtime.getRuntime().availableProcessors();
        if (threads > cpuThreads) {
            System.out.println(i18n.get("cli.threadCountCapped", cpuThreads, cpuThreads));
            threads = cpuThreads;
        }

        return new SearchContext(
                bounds[0], bounds[1], bounds[2], bounds[3],
                options.maxY, threads, gameVersion, preset,
                presetLabel(preset), checkGen
        );
    }

    private static final Object PROGRESS_LOCK = new Object();
    private static boolean progressLineOpen;
    private static boolean progressOutputClosed;
    private static int lastProgressRows;

    private static void resetProgressOutput() {
        synchronized (PROGRESS_LOCK) {
            progressLineOpen = false;
            progressOutputClosed = false;
            lastProgressRows = 0;
        }
    }

    private static void printProgressLine(String text) {
        String line = text.replace('\r', ' ').replace('\n', ' ');
        synchronized (PROGRESS_LOCK) {
            if (progressOutputClosed) {
                return;
            }
            if (progressLineOpen) {
                if (lastProgressRows > 1) {
                    System.out.print("\u001B[" + (lastProgressRows - 1) + "A");
                }
                System.out.print("\r\u001B[J");
            }
            System.out.print(line);
            System.out.flush();
            lastProgressRows = 1;
            progressLineOpen = true;
        }
    }

    private static void closeProgressLine() {
        synchronized (PROGRESS_LOCK) {
            progressOutputClosed = true;
            if (progressLineOpen) {
                System.out.print('\n');
                System.out.flush();
            }
            progressLineOpen = false;
            lastProgressRows = 0;
        }
    }

    private static void printHitLine(String result) {
        synchronized (PROGRESS_LOCK) {
            if (progressLineOpen) {
                System.out.print("\r\u001B[J");
                progressLineOpen = false;
                lastProgressRows = 0;
            }
            System.out.println(result);
            System.out.flush();
        }
    }

    private static int terminalColumns() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int width = Integer.parseInt(columns.trim());
                if (width >= 40) {
                    return width;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 80;
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            width += isWideGlyph(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isWideGlyph(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return cp >= 0x1100 && (
                cp <= 0x115F
                        || cp == 0x2329 || cp == 0x232A
                        || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                        || (cp >= 0xAC00 && cp <= 0xD7A3)
                        || (cp >= 0xF900 && cp <= 0xFAFF)
                        || (cp >= 0xFE10 && cp <= 0xFE19)
                        || (cp >= 0xFE30 && cp <= 0xFE6F)
                        || (cp >= 0xFF00 && cp <= 0xFF60)
                        || (cp >= 0xFFE0 && cp <= 0xFFE6)
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        );
    }

    private static String fitDisplayWidth(String text, int maxWidth) {
        if (displayWidth(text) <= maxWidth) {
            return text;
        }
        StringBuilder fitted = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int glyphWidth = isWideGlyph(cp) ? 2 : 1;
            if (width + glyphWidth > maxWidth) {
                break;
            }
            fitted.appendCodePoint(cp);
            width += glyphWidth;
            i += Character.charCount(cp);
        }
        return fitted.toString();
    }

    private static String formatRemaining(long remainingMs, long processed, long total) {
        if (total > 0 && processed >= total) {
            return formatCompactTime(0);
        }
        if (remainingMs > 0) {
            return formatCompactTime(remainingMs);
        }
        return i18n.get("cli.remainingCalc");
    }

    private static String formatCompactTime(long milliseconds) {
        long totalSeconds = Math.max(0, milliseconds / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String formatPercent(double percentage) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", percentage);
    }

    private static String assembleProgress(String head, String elapsed, String remaining, String tail) {
        String primary = head + " | " + elapsed + " | " + remaining;
        int maxWidth = Math.max(20, terminalColumns() - 1);
        if (tail != null && !tail.isBlank()) {
            String full = primary + " | " + tail;
            if (displayWidth(full) <= maxWidth) {
                return full;
            }
        }
        if (displayWidth(primary) <= maxWidth) {
            return primary;
        }
        return fitDisplayWidth(head + " | " + remaining, maxWidth);
    }

    private static String formatSingleProgress(SearchCoords.ProgressInfo progress) {
        String stage = progress.stage() == 1 ? i18n.get("cli.stage1") : i18n.get("cli.stage2");
        String remaining = formatRemaining(progress.remainingMs(), progress.processed(), progress.total());
        return assembleProgress(
                stage + " " + formatPercent(progress.percentage()),
                i18n.get("cli.elapsedShort", formatCompactTime(progress.elapsedMs())),
                i18n.get("cli.remainingShort", remaining),
                progress.processed() + "/" + progress.total()
        );
    }

    private static int[] resolveBounds(CliOptions options, boolean listMode) {
        int defaultMinX = listMode ? ListSearchSupport.DEFAULT_MIN_X : DEFAULT_MIN_X;
        int defaultMaxX = listMode ? ListSearchSupport.DEFAULT_MAX_X : DEFAULT_MAX_X;
        int defaultMinZ = listMode ? ListSearchSupport.DEFAULT_MIN_Z : DEFAULT_MIN_Z;
        int defaultMaxZ = listMode ? ListSearchSupport.DEFAULT_MAX_Z : DEFAULT_MAX_Z;

        if (options.squareSide != null) {
            long side = options.squareSide;
            if (side < 1) {
                fail(i18n.get("cli.squareSideInvalid"));
            }
            long min = -side / 2;
            long max = min + side - 1;
            if (min < Integer.MIN_VALUE || max > Integer.MAX_VALUE) {
                fail(i18n.get("cli.squareSideTooLarge"));
            }
            return new int[]{(int) min, (int) max, (int) min, (int) max};
        }

        return new int[]{
                options.minX != null ? options.minX : defaultMinX,
                options.maxX != null ? options.maxX : defaultMaxX,
                options.minZ != null ? options.minZ : defaultMinZ,
                options.maxZ != null ? options.maxZ : defaultMaxZ
        };
    }

    private static String formatTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return i18n.get("time.format", days, hours, minutes, seconds);
    }

    private static String presetLabel(WorldPresetMode preset) {
        return switch (preset) {
            case NORMAL -> i18n.get("worldPreset.normal");
            case LARGE_BIOMES -> i18n.get("worldPreset.largeBiomes");
            case SINGLE_BIOME -> i18n.get("worldPreset.singleBiome");
        };
    }

    private static WorldPresetMode parsePreset(String presetName) {
        if (presetName == null) {
            return WorldPresetMode.NORMAL;
        }
        return switch (presetName.trim().toLowerCase()) {
            case "normal", "default", "普通世界", "普通" -> WorldPresetMode.NORMAL;
            case "large-biomes", "large_biomes", "largebiomes", "巨型生物群系", "巨型" -> WorldPresetMode.LARGE_BIOMES;
            case "single-biome", "single_biome", "singlebiome", "单生物群系", "单生物群系(沼泽)", "沼泽" ->
                    WorldPresetMode.SINGLE_BIOME;
            default -> null;
        };
    }

    private static List<String> sortTpLines(List<String> lines) {
        List<TpLine> valid = new ArrayList<>();
        List<TpLine> invalid = new ArrayList<>();
        List<String> other = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("/tp ")) {
                other.add(trimmed);
                continue;
            }
            String[] parts = trimmed.substring(4).trim().split("\\s+");
            if (parts.length < 3) {
                other.add(trimmed);
                continue;
            }
            try {
                double y = Double.parseDouble(parts[1]);
                boolean cannotGenerate = trimmed.contains("无法生成") || trimmed.contains(" x");
                TpLine tpLine = new TpLine(y, trimmed);
                if (cannotGenerate) {
                    invalid.add(tpLine);
                } else {
                    valid.add(tpLine);
                }
            } catch (NumberFormatException e) {
                other.add(trimmed);
            }
        }

        Comparator<TpLine> byY = Comparator.comparingDouble(tp -> tp.y);
        valid.sort(byY);
        invalid.sort(byY);

        List<String> sorted = new ArrayList<>(valid.size() + invalid.size() + other.size());
        valid.forEach(tp -> sorted.add(tp.line));
        invalid.forEach(tp -> sorted.add(tp.line));
        sorted.addAll(other);
        return sorted;
    }

    private static void maybeExportSeeds(String exportSeedsFile, List<Long> seeds) {
        if (exportSeedsFile == null || exportSeedsFile.isBlank()) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>(seeds);
        if (unique.isEmpty()) {
            System.out.println(i18n.get("error.noSeedsFound"));
            return;
        }
        writeLines(exportSeedsFile, writer -> {
            for (Long seed : unique) {
                writer.println(seed);
            }
        });
        System.out.println(i18n.get("success.exportSeeds", unique.size()) + " -> " + exportSeedsFile);
    }

    private static void writeLines(String file, IoConsumer<PrintWriter> consumer) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            consumer.accept(writer);
        } catch (IOException e) {
            fail(i18n.get("error.exportFailed", e.getMessage()));
        }
    }

    private static void printHelp() {
        System.out.println(i18n.get("cli.helpTitle"));
        System.out.println(i18n.get("cli.usage"));
        System.out.println(i18n.get("cli.usageGui"));
        System.out.println();
        System.out.println(i18n.get("cli.helpRequired"));
        System.out.println("  --seed, -s <seed>         " + i18n.get("cli.helpSeed"));
        System.out.println("  --seeds-file, -f <file>   " + i18n.get("cli.helpSeedsFile"));
        System.out.println();
        System.out.println(i18n.get("cli.helpRange"));
        System.out.println("  --min-x / --max-x / --min-z / --max-z");
        System.out.println("  --square-side <n>         " + i18n.get("cli.helpSquareSide", String.valueOf(DEFAULT_SQUARE_SIDE)));
        System.out.println();
        System.out.println(i18n.get("cli.helpSearch"));
        System.out.println("  --max-y <n>               " + i18n.get("cli.helpMaxY"));
        System.out.println("  --threads <n>             " + i18n.get("cli.helpThreads"));
        System.out.println("  --version <ver>           " + i18n.get("cli.helpVersion"));
        System.out.println("  --preset <preset>         " + i18n.get("cli.helpPreset"));
        System.out.println("  --check-gen [true|false]  " + i18n.get("cli.helpCheckGen"));
        System.out.println("  --lang <zh|en>            " + i18n.get("cli.helpLang"));
        System.out.println();
        System.out.println(i18n.get("cli.helpOutput"));
        System.out.println("  --output, -o <file>       " + i18n.get("cli.helpOutputFile"));
        System.out.println("  --export-seeds <file>     " + i18n.get("cli.helpExportSeeds"));
        System.out.println("  --no-progress             " + i18n.get("cli.helpNoProgress"));
        System.out.println("  --help, -h                " + i18n.get("cli.helpHelp"));
        System.out.println();
        System.out.println(i18n.get("cli.helpExamples"));
        System.out.println("  java -jar LowYSwampHut.jar --seed -1421144132636065691");
        System.out.println("  java -jar LowYSwampHut.jar --seeds-file seeds.txt -o result.txt --export-seeds hits.txt");
        System.out.println("  java -jar LowYSwampHut.jar --seeds-file seeds.txt --square-side 256 --max-y -40");
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            if (i18n == null) {
                i18n = AppLocale.load();
            }
            System.err.println(i18n.get("cli.missingValue", option));
            printHelp();
            System.exit(1);
        }
        return args[index];
    }

    @FunctionalInterface
    private interface IoConsumer<T> {
        void accept(T value) throws IOException;
    }

    private record TpLine(double y, String line) {
    }

    private record SearchContext(
            int minX, int maxX, int minZ, int maxZ,
            int maxY, int threads, GameVersion gameVersion, WorldPresetMode preset,
            String presetLabel, boolean checkGen
    ) {
    }

    private static final class CliOptions {
        private long seed;
        private boolean seedSpecified;
        private String seedsFile;
        private int maxY = -40;
        private Integer minX;
        private Integer maxX;
        private Integer minZ;
        private Integer maxZ;
        private Long squareSide;
        private String outputFile = "result.txt";
        private String exportSeedsFile;
        private String versionName = "26.2";
        private String presetName = "normal";
        private boolean checkGen = true;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private boolean noProgress;
        private boolean showHelp;
        private String lang;

        private static CliOptions parse(String[] args) {
            CliOptions options = new CliOptions();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--seed", "-s" -> {
                        options.seed = Long.parseLong(requireValue(args, ++i, arg));
                        options.seedSpecified = true;
                    }
                    case "--seeds-file", "-f" -> options.seedsFile = requireValue(args, ++i, arg);
                    case "--max-y" -> options.maxY = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--min-x" -> options.minX = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--max-x" -> options.maxX = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--min-z" -> options.minZ = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--max-z" -> options.maxZ = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--square-side" -> options.squareSide = Long.parseLong(requireValue(args, ++i, arg));
                    case "--version" -> options.versionName = requireValue(args, ++i, arg);
                    case "--preset" -> options.presetName = requireValue(args, ++i, arg);
                    case "--output", "-o" -> options.outputFile = requireValue(args, ++i, arg);
                    case "--export-seeds" -> options.exportSeedsFile = requireValue(args, ++i, arg);
                    case "--lang" -> options.lang = requireValue(args, ++i, arg);
                    case "--threads" -> options.threads = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--check-gen" -> {
                        if (i + 1 < args.length) {
                            String next = args[i + 1];
                            if ("true".equalsIgnoreCase(next) || "false".equalsIgnoreCase(next)) {
                                options.checkGen = Boolean.parseBoolean(next);
                                i++;
                            } else {
                                options.checkGen = true;
                            }
                        } else {
                            options.checkGen = true;
                        }
                    }
                    case "--no-progress" -> options.noProgress = true;
                    case "--help", "-h" -> options.showHelp = true;
                    default -> {
                        if (i18n == null) {
                            i18n = AppLocale.load();
                        }
                        System.err.println(i18n.get("cli.unknownArg", arg));
                        printHelp();
                        System.exit(1);
                    }
                }
            }
            if (options.threads < 1) {
                if (i18n == null) {
                    i18n = AppLocale.load();
                }
                fail(i18n.get("error.threadCountRequired"));
            }
            return options;
        }
    }
}
