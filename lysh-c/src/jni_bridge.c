/* lysh jni_bridge.c —— 把 C 内核暴露给 Java GUI 壳。
 *
 * 设计原则：JNI 层只做「拆参数 / 拷结果」，**不含任何判定逻辑** ——
 * 这样它永远不可能与被对拍过的 C 核心产生分歧。所有数学都在
 * src/phase1.c / src/phase2.c / src/eval.c。
 *
 * ── 两个 Java 类 ────────────────────────────────────────────────────
 *
 * ① `project.NativePhase1`
 *      String coreVersion()      // 只用来确认 lysh.dll 已加载、以及加载的是哪一版
 *
 * ② `project.NativePhase2`（完整流水线：阶段 1 + 阶段 2）
 *      long[] gradeScanNative(long seed, int mc_1_18_2, int single_biome, int v26_2,
 *                             int large_biomes, int maxHeight, int salt, int threads,
 *                             int rx0, int rx1, int rz0, int rz1, int maxY,
 *                             int[] outHits)
 *      int    gradeIntsPerHit()  -> LYSH_HUT_GRADE_INTS（= 12）
 *      String coreVersion()
 *
 *      `gradeScanNative` 返回 long[20] = 头 + 明细（明细放在 outHits int[]）：
 *        [0] scanned（区域格数）          [1] phase1Accepted（阶段 1 幸存）
 *        [2] evaluated（= 阶段 1 幸存数）  [3] accepted（阶段 2 通过数）
 *        [4] rejectedY   [5] rejectedFlood
 *        [6] hitsWritten（写进 outHits 的条数）
 *        [7] capacityNeeded = accepted * 12（outHits 太小时为 -1 且 hitsWritten = 0）
 *        [8] rx0 [9] rx1 [10] rz0 [11] rz1
 *        [12] maxY  [13] maxHeight  [14] salt  [15] threads
 *        [16] ms（这一遍的墙钟，毫秒）   [17] ms_phase2（阶段 2 的 CPU 累计毫秒）
 *        [18] hitsIntsPerHit（= 12，冗余，防止 Java 侧写死）
 *        [19] floodedHitCount（明细里 flooded == 1 的条数；正常搜索应为 0）
 *
 *      outHits 布局：每候选 12 个 int（见 eval.h 的 grade_fill_hit）：
 *        [0] hutX  [1] hutZ  [2] rx  [3] rz
 *        [4] dir（0=N 1=E 2=S 3=W）  [5] avg_y（footprint 平均高度）
 *        [6] flooded  [7] ok  [8] wet_columns  [9] size_x  [10] size_z  [11] reject
 *
 *      ⚠️ outHits 只装**通过阶段 2 的**候选（ok == 1）。被拒的候选不计入明细，
 *         只计入 [4]/[5] 的计数。
 *
 * ── Java 侧怎么用（GUI 全流程）────────────────────────────────────────
 *   int[] buf = new int[4096];
 *   long[] st = NativePhase2.gradeScanNative(seed, 1, 0, 0, 0, -50, 8, SALT_INTERNAL,
 *                                            rx0, rx1, rz0, rz1, -40, buf);
 *   int written = (int) st[6];
 *   for (int i = 0; i < written; i++) {
 *       int b = i * 12;
 *       emit(String.format("/tp %d %.0f %d", buf[b], (double) buf[b+5], buf[b+1]));
 *   }
 *
 *   salt 传 0 表示用内核默认的 swamp-hut salt（不要传 Java 侧的默认值）。
 *   threads 传 0 表示用 CPU 核数。
 *
 * ── 线程安全 ─────────────────────────────────────────────────────────
 *   `gradeScanNative` 是**自包含**的（内部建/销毁自己的 lysh_search_ctx），
 *   所以可以并发调用（每次调用会付一次 ctx 初始化的代价 ≈ 0.3 ms）。
 *
 * ── 已删除的入口（不要加回来）────────────────────────────────────────
 *   · `NativePhase1.scanNative`（阶段 1-only 命中列表）：产品只走 gradeScanNative，
 *     Java 侧 `NativePhase1.scan` 也没有任何调用方，两边一起删了。
 *   · `NativePhase2.gradeHutNative` / `hitsCapacity`（单候选评估、容量换算）：
 *     同样没有任何调用方。
 *   · `LYSH_JNI_TRACE=1` 的 stderr trace：版本位现在由 `describe()` / `[engine]`
 *     行报告，不需要在热路径入口里留一条 printf。
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "eval.h"
#include "search.h"
#include "structure.h"

/* ------------------------------------------------------------------ */
/* project.NativePhase1                                                */
/* ------------------------------------------------------------------ */
JNIEXPORT jstring JNICALL
Java_project_NativePhase1_coreVersion(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, "lysh 2.0.0 (phase1: climate + cave ladder + continentalness + floodedness; phase2: exact footprint height + aquifer)");
}

/* ------------------------------------------------------------------ */
/* project.NativePhase2 —— 完整流水线（阶段 1 + 阶段 2）                */
/* ------------------------------------------------------------------ */
JNIEXPORT jlongArray JNICALL
Java_project_NativePhase2_gradeScanNative(JNIEnv *env, jclass cls,
                                          jlong seed,
                                          jint mc_1_18_2, jint single_biome, jint v26_2, jint large_biomes,
                                          jint max_height, jint salt, jint threads,
                                          jint rx0, jint rx1, jint rz0, jint rz1,
                                          jint max_y,
                                          jintArray out_hits) {
    (void)cls;
    enum { HDR = 20 };
    jlong out[HDR];
    memset(out, 0, sizeof(out));
    out[8] = rx0; out[9] = rx1; out[10] = rz0; out[11] = rz1;
    out[12] = max_y; out[13] = max_height;
    out[14] = salt; out[15] = threads;
    out[18] = LYSH_HUT_GRADE_INTS;

    lysh_phase1_opts opts;
    memset(&opts, 0, sizeof(opts));
    opts.mc_1_18_2 = mc_1_18_2 ? 1 : 0;
    opts.single_biome = single_biome ? 1 : 0;
    /* ⚠️ 反向映射：Java 的 v262 = 1 表示 26.2 ⇒ C 的 pre_26_2 = 0。
     * 全零初始化即 26.2（见 phase1.h），所以"没传"也拿到 26.2 语义。 */
    opts.pre_26_2 = v26_2 ? 0 : 1;
    opts.large_biomes = large_biomes ? 1 : 0;

    int use_salt = salt ? salt : LYSH_SWAMP_HUT_SALT;

    /* ---------------------------------------------------------------- *
     * ⚡ 单遍扫描（2026-xx 性能修复）
     *
     * 这里原来跑**两遍阶段 1**：
     *   ① lysh_scan_rect()          —— 只为拿 scanned / 幸存数 / 阶段 1 墙钟
     *   ② lysh_grade_scan(...)      —— 同样的阶段 1，外加阶段 2
     * 全图 1.373e10 格、阶段 1 约 29 ns/格 ⇒ 多扫一遍就是多花 ~400 s。
     * 这正是 Java 产品 948 s vs C CLI ~400 s 的主因。
     *
     * 现在只跑 ②，统计全部由 lysh_hut_grade 返回：
     *   · scanned / evaluated / accepted / rejectedY / rejectedFlood / rejectedBiome
     * 明细直接写进调用方的 out_hits：容量够就一次到位；不够时返回
     * capacityNeeded = -1 并让调用方放大重试（只有那一带会重扫）。
     * ---------------------------------------------------------------- */
    jsize cap = 0;
    if (out_hits != NULL) cap = (*env)->GetArrayLength(env, out_hits) / LYSH_HUT_GRADE_INTS;

    lysh_hut_grade stats;
    memset(&stats, 0, sizeof(stats));
    int *buf = NULL;
    if (out_hits != NULL && cap > 0) {
        buf = (int *)malloc((size_t)cap * LYSH_HUT_GRADE_INTS * sizeof(int));
        if (buf) { stats.hits = buf; stats.hits_cap = (int)cap; }
    }

    lysh_grade_scan((uint64_t)seed, &opts, max_height, rx0, rx1, rz0, rz1,
                    use_salt, threads, max_y, &stats);

    out[0] = stats.scanned;
    out[1] = stats.evaluated;       /* 阶段 1 幸存数 */
    out[2] = stats.evaluated;
    out[3] = stats.accepted;
    out[4] = stats.rejected_y;
    out[5] = stats.rejected_flood;
    out[7] = stats.accepted * LYSH_HUT_GRADE_INTS;
    out[16] = (jlong)stats.ms;      /* 这一遍（阶段 1 + 阶段 2 回放）的墙钟 */
    out[17] = (jlong)stats.p2_ms;   /* 其中阶段 2 的 CPU 累计毫秒 */

    if (out_hits != NULL) {
        if (stats.accepted > 0 && cap < stats.accepted) {
            out[7] = -1;            /* 数组太小：告诉 Java 侧要多大，明细不写 */
        } else if (buf && stats.hits_written > 0) {
            (*env)->SetIntArrayRegion(env, out_hits, 0,
                                      (jsize)(stats.hits_written * LYSH_HUT_GRADE_INTS),
                                      (const jint *)buf);
            out[6] = stats.hits_written;

            /* 明细里 flooded 的条数（正常搜索结果应为 0 —— 灌满的一律被拒） */
            long long nf = 0;
            for (int i = 0; i < stats.hits_written; i++) {
                if (buf[i * LYSH_HUT_GRADE_INTS + 6]) nf++;
            }
            out[19] = nf;
        }
    }
    free(buf);

    jlongArray a = (*env)->NewLongArray(env, HDR);
    if (a) (*env)->SetLongArrayRegion(env, a, 0, HDR, out);
    return a;
}

JNIEXPORT jint JNICALL
Java_project_NativePhase2_gradeIntsPerHit(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)LYSH_HUT_GRADE_INTS;
}

JNIEXPORT jstring JNICALL
Java_project_NativePhase2_coreVersion(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, "lysh 2.0.0 (phase1+phase2 end-to-end: exact footprint avg height + aquifer flood judgment)");
}
