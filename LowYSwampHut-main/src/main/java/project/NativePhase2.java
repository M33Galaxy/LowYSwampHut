/*
 * project.NativePhase2 —— Java 侧对 C 内核「阶段 1 + 阶段 2 完整流水线」的声明。
 *
 * 加载：`lysh.dll`（Windows）/ `liblysh.so` 由 CMake 的 `lysh_jni` 目标产出，
 *       输出名固定为 `lysh`，与 `project.NativePhase1` 用的是同一个库。
 *       查找顺序**复用** {@link NativePhase1#loadLibrary()}，不另抄一份。
 *       本文件是这份 native 声明的**唯一一份**（原 `lysh-c/tools/NativePhase2.java`
 *       与验收程序 `NativePhase2Smoke` 已随 tools/ 一起删除并归档到
 *       `_archive/verify_tools_*.zip`）。
 *
 * 语义（全部由 C 核心给出，见 lysh-c/src/eval.h、src/jni_bridge.c 头部）：
 *   · avg_y   = footprint 63 列高度之和 / 63（整数除法，向零截断；26.1.2 oracle 的口径，
 *               实测 11 个已知小屋 **11/11 相等**。**不要**为了对齐旧 Java 的写法去 +1：
 *               Java 每列累加的是"方块 Y"（= getBaseHeight − 1），
 *               所以旧式 ceil(sum_block/63 + 1) 与 sum(getBaseHeight)/63 **描述同一个量**，
 *               负 Y 段 ceil 与截断同值，两边都没有 off-by-one
 *               —— 详见 lysh-c/README.md §2.5.4 与 eval.h 顶部（那条"0/11、相差 1"的
 *               早期说法已作废，不要在新注释里复述它）。
 *   · flooded = 整片 footprint 在 y=62 被水灌满（这类候选一律拒绝）
 *   · ok      = biome_ok && avg_y <= maxY && !flooded
 *   · Y 的展示口径 = avg_y（Java 产品 `Result.toString()` 是 `/tp %d %.0f %d`）
 *
 * ── 两个入口（2026-xx 多种子性能修复）────────────────────────────────
 *   · {@link #gradeScanNative}  —— 一个区域一趟跑完（自包含，一个带）。
 *   · {@link #scanOpen} / {@link #scanBand} / {@link #scanClose}
 *                              —— 一个种子扫**很多**个带时的入口：
 *                                 open 一次建好 worker ctx（每个一次噪声栈初始化），
 *                                 之后每个带复用，连明细回放也不再新建 ctx。
 *
 *   为什么要多这一组：C 的 `lysh_grade_scan` 每调一次都要重建 worker ctx，还要为
 *   明细回放再建一次；实测 65,536 格切成 256 带时是 95.4 ms/种子，而纯计算只要
 *   ~7.5 ms —— 成本只跟**带数**走（每带 2 次初始化 ≈ 0.19 ms）。会话把这层代价变成
 *   "每个种子一次"。**不要**在带循环里反复调 gradeScanNative。
 *
 * ⚠️ 数组布局与 C 侧的 `LYSH_HUT_GRADE_INTS` 绑定：改动必须两边同时改，
 *    并以 `gradeIntsPerHit()` 为准（不要在任何一侧写死 12）。
 *
 * ⚠️ 版本范围：C 的阶段 2 地形/含水层实现只在**已验证过的版本**上成立
 *    （`lysh-c/README.md` §0 / §2.5：1.18.2 与 26.1.2 实测口径）。
 *    这里不做版本门禁 —— 与阶段 1 的既有约定一致，由调用方决定。
 *    没有 Java 回退路径：缺库就是"不能搜索"（见 {@link #describe()}）。
 */
package project;

/**
 * C 内核「阶段 1 + 阶段 2」的 JNI 桥 + 产品侧薄封装。
 *
 * <p>产品侧入口有两个，都只是薄封装、**不含任何判定逻辑**：
 * {@link #gradeScanNative}（一个区域一趟跑完）与
 * {@link #scanOpen}/{@link #scanBand}/{@link #scanClose}
 * （一个种子扫很多带：worker 噪声栈只初始化一次）。另有 {@link #describe()}
 * 之类的纯数据访问。
 */
public final class NativePhase2 {

    private NativePhase2() {
    }

    /** 单候选明细的 int 个数（与 C 侧 LYSH_HUT_GRADE_INTS 一致）。 */
    public static native int gradeIntsPerHit();

    /** 库版本串，方便确认加载的是哪一份。 */
    public static native String coreVersion();

    /**
     * 整个区域：阶段 1 过滤 + 阶段 2 逐个评估（与 CLI `lysh scan` 同一条 C 路径）。
     *
     * @param outHits 明细缓冲；每条候选占 {@link #gradeIntsPerHit()} 个 int，
     *                布局与 {@link #F_HUT_X} 等字段下标一致。
     *                只装**通过**的候选；传 null 表示只要统计。
     * @return long[20]：
     * <pre>
     *   [0] scanned         区域格数
     *   [1] phase1Accepted  阶段 1 幸存数
     *   [2] evaluated       (= 阶段 1 幸存数)
     *   [3] accepted        阶段 2 通过数
     *   [4] rejectedY       因 avg_y > maxY 被拒
     *   [5] rejectedFlood   因整片灌满被拒
     *   [6] hitsWritten     写进 outHits 的条数
     *   [7] capacityNeeded  = accepted * 12；outHits 太小时为 -1 且 hitsWritten = 0
     *   [8..11]  rx0 rx1 rz0 rz1
     *   [12..15] maxY maxHeight salt threads
     *   [16] ms             这一遍（阶段 1 + 阶段 2 回放）的墙钟（毫秒）
     *   [17] msPhase2       阶段 2 的 CPU 累计毫秒（各线程之和）
     *   [18] intsPerHit     (= 12)
     *   [19] floodedHits    明细里 flooded == 1 的条数（正常搜索恒为 0）
     * </pre>
     * salt 传 0 = 用内核默认 swamp-hut salt；threads 传 0 = 用 CPU 核数。
     *
     * <p>⚠️ 这是"一个带"的入口，每次调用都会重建 worker ctx（+ 回放再建一次）。
     * 一个种子要扫很多带时请改用 {@link #scanOpen}/{@link #scanBand}。
     */
    public static native long[] gradeScanNative(long seed,
                                                int mc1182, int singleBiome, int v262, int largeBiomes,
                                                int maxHeight, int salt, int threads,
                                                int rx0, int rx1, int rz0, int rz1,
                                                int maxY,
                                                int[] outHits);

    /**
     * 开一个扫描会话：**整个种子只在这里建一次 worker ctx（= 一次噪声栈初始化）**，
     * 之后每个 {@link #scanBand} 都复用它，连明细回放也不再新建 ctx。
     *
     * <p>为什么需要它：把一个种子切成 256 个 Z 带时，每带一次
     * {@link #gradeScanNative} 会重建两次噪声栈（worker 一次 + 回放一次）。实测
     * 65,536 格 256 带 = 95.4 ms/种子，而纯计算只要 ~7.5 ms —— 成本只跟带数走。
     * 用会话之后 threads=1 时**整个种子恰好一次** {@code lysh_search_ctx_init}。
     *
     * @return 会话句柄；0 = 失败（内存不足 / 参数非法），此时不要调用
     *         {@link #scanBand}。用完必须 {@link #scanClose}（放 finally 里）。
     * @param salt    传 0 = 内核默认 swamp-hut salt
     * @param threads 传 0 = CPU 核数；1 = 在调用线程内联跑（不建线程）
     */
    public static native long scanOpen(long seed,
                                       int mc1182, int singleBiome, int v262, int largeBiomes,
                                       int maxHeight, int salt, int threads);

    /**
     * 扫会话里的一个带 {@code [rx0,rx1) x [rz0,rz1)}，返回与
     * {@link #gradeScanNative} **完全相同**的 long[20] 头布局（同一个 C 填充函数）。
     *
     * <p>区别只在 {@code [12..15]} 的口径：[12] maxY 是本带的值；
     * {@code [13..15]} maxHeight/salt/threads 是 {@link #scanOpen} 时给会话级参数的回显。
     * 统计与命中明细都是**本带**的，逐带累加即可（见 {@link project.SearchCoords}）。
     *
     * @param handle  {@link #scanOpen} 返回的非 0 句柄
     * @param outHits 同 {@link #gradeScanNative}：容量不够时 {@code [7] == -1}，
     *                放大缓冲后**重扫这一个带**即可（返回 null = 句柄无效）
     */
    public static native long[] scanBand(long handle,
                                         int rx0, int rx1, int rz0, int rz1,
                                         int maxY,
                                         int[] outHits);

    /**
     * 关会话并释放全部 ctx。安全传 0（空操作）；同一个非 0 句柄只许关一次
     * —— 调用方关完把句柄置 0（这就是"可重复调用"的实现方式，见
     * {@link project.SearchCoords}）。
     */
    public static native void scanClose(long handle);

    // ------------------------------------------------------------------
    // 产品侧薄封装（无判定逻辑，只做加载状态与数组取值）
    // ------------------------------------------------------------------

    /** 明细布局里的字段下标（与 C 侧 grade_fill_hit 的 12 个 int 一一对应）。 */
    public static final int F_HUT_X = 0;
    public static final int F_HUT_Z = 1;
    public static final int F_REGION_X = 2;
    public static final int F_REGION_Z = 3;
    public static final int F_DIR = 4;
    public static final int F_AVG_Y = 5;
    public static final int F_FLOODED = 6;
    public static final int F_OK = 7;
    public static final int F_WET_COLUMNS = 8;
    public static final int F_SIZE_X = 9;
    public static final int F_SIZE_Z = 10;
    public static final int F_REJECT = 11;

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("lowyswamphut.nativePhase2", "true"));

    private static final boolean AVAILABLE;
    private static final String STATUS;
    private static final String VERSION;

    static {
        boolean ok = false;
        String status;
        String version = null;
        if (!ENABLED) {
            status = "disabled by -Dlowyswamphut.nativePhase2=false";
        } else {
            // 两个类共用同一个 lysh.dll（native 方法与类名绑定，与"谁加载了库"无关）。
            // 加载顺序：
            //   ① 阶段 1 已经加载过 → 本类一定也能解析 native（不再重复 System.load，
            //      同一 ClassLoader 重复 load 同一路径会抛）；
            //   ② 否则自己按同一份候选列表加载（同进程此前没加载过，必然成功或真的缺库）。
            boolean loadedByPhase1 = probeCoreVersion() != null;
            String loaded = loadedByPhase1 ? null : NativePhase1.loadLibrary();
            if (loadedByPhase1 || loaded != null) {
                version = loadedByPhase1 ? probeCoreVersion() : coreVersion();
                ok = true;
                status = loadedByPhase1
                        ? "reusing lysh.dll already loaded by NativePhase1"
                        : "loaded from " + loaded;
            } else {
                status = "not found; tried: " + String.join(" | ", NativePhase1.loadAttempts());
            }
        }
        AVAILABLE = ok;
        STATUS = status;
        VERSION = version;
    }

    /** 真去调一次 native：能返回串就说明 lysh.dll 已经在本进程加载过。 */
    private static String probeCoreVersion() {
        try {
            return coreVersion();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本进程能否走 C 内核。 */
    public static boolean isAvailable() { return AVAILABLE; }

    /**
     * 实际生效的路径：true = 阶段 1 + 阶段 2 由 C 内核算。
     *
     * <p>{@code -Dlowyswamphut.nativePhase2=false} 会把这里变成 false ——
     * 但那**不再**意味着"回退到 Java"：Java 路径已删除，产品在缺库/被关掉时
     * 直接报"不能搜索"（{@link project.SearchCoords} 与 GUI 的 main 都会明确报错）。
     */
    public static boolean isActive() { return AVAILABLE && ENABLED; }

    /** 供 CLI / GUI 打印，确认到底走的是哪条路径。 */
    public static String describe() {
        if (!AVAILABLE) return "native phase 2: OFF (" + STATUS + ")";
        if (!ENABLED) return "native phase 2: OFF (disabled by -Dlowyswamphut.nativePhase2=false)";
        return "native phase 2: ON  [" + VERSION + "]  " + STATUS;
    }
}
