package project;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 搜索驱动：把一次搜索整体交给 C 内核（{@code lysh.dll}），Java 侧只负责分带、进度与结果搬运。
 *
 * <p>本文件**没有任何地形 / 结构 / 气候判定**，全部由原生核心完成：
 * <ul>
 *   <li>阶段 1（女巫小屋候选粗筛）+ 阶段 2（footprint 平均高度 + 含水层灌满判定）由
 *       {@link NativePhase2#scanOpen}/{@link NativePhase2#scanBand} 一趟跑完 —— 与 CLI
 *       {@code lysh scan} 同一条 C 路径；</li>
 *   <li>Java 只做区域 Z 分带（决定进度刷新与"停止"的响应粒度）、把命中的候选格式化成
 *       {@code /tp x y z}、上报 {@link ProgressInfo}。</li>
 * </ul>
 *
 * <p><b>一个种子一个会话。</b>{@link NativePhase2#scanOpen} 把该种子的 worker 上下文
 * （= 整条噪声栈）**只初始化一次**，{@link NativePhase2#scanBand} 每个 Z 带复用它，
 * 最后在 {@code finally} 里 {@link NativePhase2#scanClose}。之前的写法是每带调一次
 * {@code gradeScanNative}，于是每带都重建一次上下文（还要为明细回放再建一次）——
 * 实测 256 带 / 65,536 格要 95.4 ms/种子，而纯计算只有 ~7.5 ms，成本完全被初始化吃掉。
 * <b>MAX_SCAN_BANDS 仍然是 256</b>：带数现在不再有代价，而更细的带意味着更细的进度。</p>
 *
 * <p><b>进度模型：单一、单调、无阶段。</b>因为阶段 1 与阶段 2 是同一趟跑完的（单遍修复，
 * 见 {@code lysh-c/README.md} §7.4），两个阶段在时间上**不可分**，所以进度按"已扫完的
 * region 数 / 总 region 数"上报，{@link ProgressInfo} 里**没有 stage 字段**。
 * 完成信号就是 {@code processed >= total}（total 恒为总 region 数）。</p>
 *
 * <p>为什么要删掉 Java 老路径：C 内核的阶段 1 / 阶段 2 都已与 Java 实现逐位对拍过
 * （见 {@code lysh-c/README.md} §2），而 Java 路径依赖 SeedChecker（内含 Mojang 代码）、
 * mc_core / mc_feature、noise-sampler 等第三方 jar。删掉它们之后，本产品**运行期只需要
 * JDK + lysh.dll**，可以直接 {@code java -jar} 或双击启动。
 *
 * <p>被删除的老路径（历史上仅作为 {@code -Dlowyswamphut.nativePhase2=false} 的退路）：
 * {@code checkHeightByDensity} / {@code DensityHeightmap} / {@code findGeneratedHutFloorY} /
 * SeedChecker 逐列扫描 / 真实生成交叉校验 / {@code SeedCheckerCache} / density 预筛 /
 * mc_feature 的 {@code SwampHut.getInRegion} / noise-sampler 的气候与洞穴判定。
 * 现在缺库就是明确的"不能搜索"，而不是静默退回 Java。
 */
public class SearchCoords {

    /** 阶段 1 的高度下限：选 -50 或 -54 时按 -50 处理（与 C 内核阶段 1 的口径一致）。 */
    private static final int PHASE1_MIN_CHECK_HEIGHT = -50;

    /** 单条明细的 int 数；C 侧 {@code LYSH_HUT_GRADE_INTS} 的兜底值（12）。 */
    private static final int FALLBACK_INTS_PER_HIT = 12;

    /** 明细缓冲初始容量（条数）：阶段 1 幸存候选通常是个位数。 */
    private static final int INITIAL_HITS_SLOTS = 64;

    /**
     * 一次扫描最多分多少个 Z 带（带越小，进度越细、"停止"响应越快）。
     *
     * <p>带数**不再有原生调用开销**：每个种子只 scanOpen 一次，所有带复用同一批 ctx
     * （见 {@link NativePhase2#scanOpen}）。所以这里保持 256，取更细的进度。
     */
    private static final int MAX_SCAN_BANDS = 256;

    private static final int MAX_SEARCH_AXIS_SPAN =
            positiveIntProperty("lowyswamphut.maxSearchAxisSpan", 250_000);
    private static final long MAX_SEARCH_ITERATIONS =
            positiveLongProperty("lowyswamphut.maxSearchIterations", 20_000_000_000L);

    private static final int MAX_SEARCH_THREADS = positiveIntProperty(
            "lowyswamphut.maxSearchThreads",
            Math.max(1, Runtime.getRuntime().availableProcessors()));

    /** 走 C 阶段 2 评估过的候选数（CLI / GUI 诊断行用）。 */
    private static final AtomicLong PHASE2_NATIVE_HUTS = new AtomicLong(0);

    /** 最近一次搜索的阶段 1 幸存数（= 阶段 2 候选数，诊断用）。 */
    private static final AtomicInteger PHASE1_LAST_CANDIDATES = new AtomicInteger(0);

    private final GameVersion gameVersion;
    private final WorldPresetMode worldPresetMode;
    private Thread progressThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private volatile boolean coordinatorFinished = false;
    private volatile CountDownLatch searchDone = new CountDownLatch(0);
    private final List<String> results = new ArrayList<>();

    // 当前搜索状态（动态调整线程数 / 进度上报用）
    private int currentThreadCount;
    private AtomicLong currentProcessedCount;
    private volatile long currentTotalTasks;
    private volatile long searchStartTimeMs;
    private final AtomicLong pausedTimeMs = new AtomicLong(0);
    private final AtomicReference<Long> pauseStartMs = new AtomicReference<>(0L);

    /**
     * 一次进度上报。
     *
     * <p><b>没有 stage 字段</b>：阶段 1 / 阶段 2 在同一趟里跑完，按阶段分不出真实进度。
     * {@code processed} / {@code total} 都是 <b>region 数</b>（不是格数），{@code total} 恒为
     * 本次搜索的 region 总数，因此 {@code processed >= total} 就是"扫完了"这一唯一完成条件。
     * 命中结果在扫描过程中随带输出（可能在 {@code processed < total} 时就出现），这是正常的。
     */
    public record ProgressInfo(long processed, long total, double percentage, long elapsedMs, long remainingMs) {
    }

    /**
     * 一个命中候选的最终结果。
     *
     * <p>{@code height} 就是 C 的 {@code avg_y}：footprint 63 列高度的平均值
     * （{@code sum/63} 向零截断）。这是 26.1.2 oracle 的口径，也是 11 个已知小屋的真值 Y
     * （见 {@code lysh-c/README.md} §2.5.6.1）。
     */
    public record Result(int x, int z, double height) {
        @Override
        public String toString() {
            return String.format("/tp %d %.0f %d", x, height, z);
        }
    }

    public SearchCoords(GameVersion gameVersion, WorldPresetMode worldPresetMode) {
        this.gameVersion = gameVersion;
        this.worldPresetMode = worldPresetMode;
    }

    /**
     * 启动一次搜索。判定全部在 C 内核里做，本方法只分带调用并把结果回调出去。
     *
     * @param minX/maxX/minZ/maxZ <b>区域（region）下标</b>，不是方块坐标；每个 region 是 32x32 区块
     * @param maxHeight          最终 Y 门槛（阶段 2 的 {@code avg_y <= maxHeight}）
     */
    public void startSearch(long seed, int threadCount, int minX, int maxX, int minZ, int maxZ, double maxHeight,
                            Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback) {
        validateSearchBounds(minX, maxX, minZ, maxZ);
        int searchThreadCount = boundedThreadCount(threadCount);

        if (isRunning) {
            // 已经跑着：只接受"暂停中改线程数"这一种调整。新的线程数在下一带生效，
            // 因此不需要像 Java 老路径那样打断并重跑整个阶段。
            if (isPaused && searchThreadCount != currentThreadCount) {
                currentThreadCount = searchThreadCount;
                isPaused = false;
            }
            return;
        }

        isRunning = true;
        coordinatorFinished = false;
        searchDone = new CountDownLatch(1);
        synchronized (results) {
            results.clear();
        }

        long now = System.currentTimeMillis();
        currentThreadCount = searchThreadCount;
        currentTotalTasks = (long) (maxX - minX) * (maxZ - minZ);
        searchStartTimeMs = now;
        pausedTimeMs.set(0);
        pauseStartMs.set(0L);

        AtomicLong processedCount = new AtomicLong(0);
        currentProcessedCount = processedCount;

        final CountDownLatch doneLatch = searchDone;
        final Consumer<ProgressInfo> progress = progressCallback;

        if (progressCallback != null) {
            progressThread = new Thread(() -> {
                while (isRunning && !coordinatorFinished) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!isRunning || coordinatorFinished) {
                        return;
                    }
                    reportProgress(progress);
                }
            }, "SearchCoords-Progress");
            progressThread.setDaemon(true);
            progressThread.start();
        } else {
            progressThread = null;
        }

        Runnable body = () -> runNativeScan(seed, minX, maxX, minZ, maxZ, maxHeight, searchThreadCount,
                processedCount, resultCallback, progress, doneLatch);

        // 批量种子搜索（无进度回调 + 单线程）时在调用线程里跑完，省掉"每次搜索一个线程"的开销。
        if (progressCallback == null && searchThreadCount <= 1) {
            body.run();
            return;
        }
        Thread coordinator = new Thread(body, "SearchCoords-Coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    /** 阻塞直到本次搜索结束（协调线程退出）。 */
    public void awaitCompletion() {
        try {
            searchDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // C 内核调用
    // ------------------------------------------------------------------

    private void runNativeScan(long seed, int minX, int maxX, int minZ, int maxZ, double maxHeight,
                               int threadCount, AtomicLong processedCount,
                               Consumer<String> resultCallback, Consumer<ProgressInfo> progressCallback,
                               CountDownLatch doneLatch) {
        long acceptedTotal = 0;
        long evaluatedTotal = 0;
        // 扫过的 region 数（= 进度分子）。循环里逐带累加，正常跑完必然等于 currentTotalTasks。
        long scannedTotal = 0;
        // 本次搜索的 C 会话句柄（0 = 还没开）。关闭放在 finally，保证"停止"提前 return
        // 与异常路径都不会泄漏 worker ctx。
        long scanSession = 0;
        // true = 这次搜索"有结论"（成功或明确失败）。只有用户主动停止才为 false ——
        // 用户停掉时不再补发"完成"信号，否则会把刚停掉的界面又"解锁"成完成态。
        boolean terminal = false;
        try {
            if (!NativePhase2.isActive()) {
                String message = "[engine] native core unavailable -- " + NativePhase2.describe()
                        + " ; this build has no Java fallback, so no search can run. "
                        + "Put lysh.dll next to the jar (or pass -Dlowyswamphut.nativeLib=<path>).";
                failSearch(message, resultCallback);
                terminal = true;
                return;
            }

            int maxY = maxHeightToInt(maxHeight);
            int phase1Height = phase1CheckHeight(maxY);
            int intsPerHit = intsPerHit();
            int[] outHits = new int[INITIAL_HITS_SLOTS * intsPerHit];

            int totalZ = maxZ - minZ;
            int bands = (int) Math.max(1, Math.min(MAX_SCAN_BANDS, totalZ));
            int bandZ = Math.max(1, (totalZ + bands - 1) / bands);
            long scanned = 0;

            // ⚡ 一个种子**一个**会话：worker ctx（整条噪声栈）只在这里初始化一次，
            // 下面的循环无论切多少带都复用它（明细回放也不再新建 ctx）。
            scanSession = openScan(seed, phase1Height, threadCount);
            if (scanSession == 0) {
                throw new IllegalStateException(
                        "NativePhase2.scanOpen returned 0 (out of memory or invalid arguments)");
            }
            try {
                for (int z = minZ; z < maxZ; z += bandZ) {
                    waitIfPaused();
                    if (!isRunning) {
                        return;
                    }
                    int zEnd = Math.min(maxZ, z + bandZ);
                    long[] stats = NativePhase2.scanBand(scanSession, minX, maxX, z, zEnd, maxY, outHits);
                    if (stats == null) {
                        throw new IllegalStateException("NativePhase2.scanBand returned null (bad handle)");
                    }
                    if (stats[7] < 0) {
                        // 明细缓冲不够（C 侧约定 capacityNeeded == -1 且 hitsWritten == 0）：按需求容量重来这一带。
                        long needed = Math.max(intsPerHit, stats[3] * (long) intsPerHit);
                        outHits = new int[(int) Math.min(Integer.MAX_VALUE, needed)];
                        stats = NativePhase2.scanBand(scanSession, minX, maxX, z, zEnd, maxY, outHits);
                        if (stats == null) {
                            throw new IllegalStateException("NativePhase2.scanBand returned null (bad handle)");
                        }
                    }

                    int written = (int) stats[6];
                    for (int i = 0; i < written; i++) {
                        int base = i * intsPerHit;
                        if (outHits[base + NativePhase2.F_OK] == 0) {
                            continue;
                        }
                        emitResultLine(new Result(
                                outHits[base + NativePhase2.F_HUT_X],
                                outHits[base + NativePhase2.F_HUT_Z],
                                outHits[base + NativePhase2.F_AVG_Y]).toString(), resultCallback);
                    }

                    scanned += stats[0];
                    scannedTotal = scanned;
                    acceptedTotal += stats[1];
                    evaluatedTotal += stats[2];
                    processedCount.set(scanned);
                }
            } finally {
                // 提前 return（用户按了停止）与异常路径都会走到这里。
                NativePhase2.scanClose(scanSession);
                scanSession = 0;
            }

            PHASE1_LAST_CANDIDATES.set((int) Math.min(Integer.MAX_VALUE, acceptedTotal));
            PHASE2_NATIVE_HUTS.set(evaluatedTotal);
            terminal = true;
        } catch (Throwable t) {
            // 缺库 / 版本不匹配 / 内存不足都必须说出来，不能让 GUI 静默假死。
            failSearch("[engine] native scan failed: " + t, resultCallback);
            t.printStackTrace();
            terminal = true;
        } finally {
            if (scanSession != 0) {
                // 兜底：正常路径已在上面关掉（并清零），这里只覆盖"开完会话后、
                // 进带循环前"抛异常这类极端情况。
                NativePhase2.scanClose(scanSession);
                scanSession = 0;
            }
            coordinatorFinished = true;
            if (progressThread != null) {
                progressThread.interrupt();
            }
            if (terminal && progressCallback != null) {
                // 单一进度的完成信号：processed == total == 本次搜索的 region 总数。
                // GUI 单种子搜索就以此（processed >= total）作为"跑完、解锁 UI"的依据。
                //
                // 一定要用 region 总数、而不是命中数：完成信号要求 processed >= total，
                // 若把 total 换成命中数，total=0（一个都没命中）时就是 0 >= 0 —— 碰巧成立；
                // 但只要命中数 < region 总数，进度条的分子分母就不再是同一把尺子，
                // CLI 列表模式还会把它当成"总进度 <100%"。region 总数既单调又和扫描一致。
                long total = currentTotalTasks;
                currentProcessedCount.set(total);
                progressCallback.accept(new ProgressInfo(total, total, 100.0,
                        System.currentTimeMillis() - searchStartTimeMs - pausedTimeMs.get(), 0L));
            }
            isRunning = false;
            doneLatch.countDown();
        }
    }

    /**
     * 开一个扫描会话（{@link NativePhase2#scanOpen}）：整个种子的 worker ctx 只建这一次。
     *
     * <p>由 {@link #runNativeScan} 在带循环之前调用，返回的句柄在每个带上复用
     * （{@link NativePhase2#scanBand}），并在 {@code finally} 里关掉。
     * salt 固定传 0 → 内核默认的 1.13+ 女巫小屋 salt。
     */
    private long openScan(long seed, int phase1Height, int threads) {
        return NativePhase2.scanOpen(
                seed,
                gameVersion == GameVersion.V1_18_2 ? 1 : 0,
                worldPresetMode == WorldPresetMode.SINGLE_BIOME ? 1 : 0,
                gameVersion == GameVersion.V26_2 ? 1 : 0,
                worldPresetMode == WorldPresetMode.LARGE_BIOMES ? 1 : 0,
                phase1Height,
                0, // salt = 0 → 内核默认的 1.13+ 女巫小屋 salt
                threads);
    }

    private void failSearch(String message, Consumer<String> resultCallback) {
        System.err.println(message);
        if (resultCallback != null) {
            resultCallback.accept(message);
        }
    }

    // ------------------------------------------------------------------
    // 路径诊断（CLI / GUI 打印"这一轮到底走的哪条路径"）
    // ------------------------------------------------------------------

    /** 阶段 2 实际走的是 C 内核吗（= lysh.dll 加载成功，且未被系统属性关掉）。 */
    public static boolean isNativePhase2Active() {
        return NativePhase2.isActive();
    }

    /** 本次进程里走 C 阶段 2 评估过的候选数。 */
    public static long nativePhase2HutCount() {
        return PHASE2_NATIVE_HUTS.get();
    }

    /** 最近一次搜索的阶段 1 幸存数（= 阶段 2 候选数）。 */
    public static int lastPhase1CandidateCount() {
        return PHASE1_LAST_CANDIDATES.get();
    }

    /** 一行式路径摘要，给 CLI / GUI 的状态栏用。 */
    public static String nativePathSummary() {
        return NativePhase1.describe() + " | " + NativePhase2.describe();
    }

    /** 阶段 1 用高度：-54 按 -50 处理，不额外降低。 */
    static int phase1CheckHeight(int maxHeight) {
        return Math.max(maxHeight, PHASE1_MIN_CHECK_HEIGHT);
    }

    // ------------------------------------------------------------------
    // 进度 / 暂停 / 停止
    // ------------------------------------------------------------------

    /**
     * 上报一次进度：单一、单调，无阶段。
     *
     * <p>{@code processed} 是已扫完的 region 数，{@code total} 是本次搜索的 region 总数，
     * 因此百分比在整个任务里只增不减。剩余时间是按"本任务平均速度"外推的。
     */
    private void reportProgress(Consumer<ProgressInfo> progressCallback) {
        if (progressCallback == null || currentProcessedCount == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (isPaused) {
            pauseStartMs.updateAndGet(start -> start == 0 ? now : start);
        } else {
            Long pauseStart = pauseStartMs.getAndSet(0L);
            if (pauseStart > 0) {
                pausedTimeMs.addAndGet(now - pauseStart);
            }
        }

        long processed = currentProcessedCount.get();
        long total = currentTotalTasks;
        double percentage = total > 0 ? (double) processed / total * 100.0 : 0.0;
        long elapsed = now - searchStartTimeMs - pausedTimeMs.get();
        long remaining = processed > 0 && total > processed
                ? elapsed * (total - processed) / processed
                : 0;
        progressCallback.accept(new ProgressInfo(processed, total, percentage, elapsed, remaining));
    }

    public void stop() {
        isRunning = false;
        isPaused = false;
        if (progressThread != null) {
            progressThread.interrupt();
        }
        // 确保 awaitCompletion 不会因进度线程延迟而永久阻塞
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

    public boolean isPaused() {
        return isPaused;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public GameVersion getGameVersion() {
        return gameVersion;
    }

    public WorldPresetMode getWorldPresetMode() {
        return worldPresetMode;
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

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

    /**
     * 把 Y 门槛折算成 int。调用点只会传 -50/-54/-40 这类值；
     * 万一传了 {@code +∞}（"不设门槛"），按不设门槛处理，避免 (int) 溢出。
     */
    private static int maxHeightToInt(double maxHeight) {
        if (!(maxHeight <= Integer.MAX_VALUE)) {
            return Integer.MAX_VALUE;
        }
        return (int) maxHeight;
    }

    /** 单条明细的 int 数：优先问 native（C 侧 LYSH_HUT_GRADE_INTS），缺库时用 12 兜底。 */
    private static int intsPerHit() {
        try {
            return NativePhase2.gradeIntsPerHit();
        } catch (Throwable t) {
            return FALLBACK_INTS_PER_HIT;
        }
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        return Math.max(1, Integer.getInteger(name, defaultValue));
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        try {
            return Math.max(1L, Long.parseLong(System.getProperty(name, Long.toString(defaultValue))));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
