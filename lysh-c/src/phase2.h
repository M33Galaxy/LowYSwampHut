/* ⛔ 版本归属（见 README §0）：本文件实现的是 **1.18 的地形语义**，默认无效。
 * 只有显式选择版本 `1.18.2` 时才作为该分支的实现保留。
 * 1.21+ 的权威是客户端 jar 的 worldgen JSON（含显式的 `interpolated` / `squeeze` /
 * `quarter_negative` 等计算图节点），**不是**本文件所依据的 1.18.1 字节码。
 *
 * 另：本实现**未做 MC 的三线性插值**，实测 200 点错 86 个（差值 1~2 格）。
 * 1.21 的 JSON 里 `interpolated` 是显式节点，重写时按节点实现即可。
 */

/* lysh phase2.h —— M3-L6：从 (x, z) 得到高度图 Y（`sampleHeightmap`）。
 *
 * 这是**产品真正用的那一步**：低 Y 小屋的 Y 就是这里出来的。
 *
 * 取证：`_archive/dev_scratch_20260921.zip -> net.minecraft.world.gen.NoiseChunkGenerator.txt:238-508`
 *
 * ```java
 * public int getHeight(int x, int z, Heightmap.Type type, HeightLimitView world) {
 *     int minY = max(cfg.minimumY(), world.getBottomY());
 *     int maxY = min(cfg.minimumY() + cfg.height(), world.getTopY());
 *     int minCellY = floorDiv(minY, vbs);
 *     int cellCount = floorDiv(maxY - minY, vbs);
 *     if (cellCount <= 0) return world.getBottomY();
 *     return sampleHeightmap(x, z, null, type.getBlockPredicate(), minCellY, cellCount)
 *              .orElse(world.getBottomY());
 * }
 *
 * private OptionalInt sampleHeightmap(x, z, out, pred, minCellY, cellCount) {
 *     int hbs = cfg.horizontalBlockSize();   // 4
 *     int vbs = cfg.verticalBlockSize();     // 8
 *     int cellX = floorDiv(x, hbs), cellZ = floorDiv(z, hbs);
 *     double fracX = floorMod(x, hbs) / (double) hbs;
 *     double fracZ = floorMod(z, hbs) / (double) hbs;
 *     ChunkNoiseSampler s = ChunkNoiseSampler.create(cellX*hbs, cellZ*hbs,
 *                                minCellY, cellCount, ncs, settings, fluidLevelSampler);
 *     ...
 *     for (int i = cellCount - 1; i >= 0; i--) {
 *         for (int j = vbs - 1; j >= 0; j--) {
 *             int y = (minCellY + i) * vbs + j;
 *             BlockState st = blockStateSampler.apply(s, x, y, z);
 *             BlockState v = (st == null) ? defaultBlock : st;   // ★ null → 石头
 *             if (pred.test(v)) return OptionalInt.of(y + 1);    // ★ 返回值是 y+1
 *         }
 *     }
 *     return empty();
 * }
 * ```
 *
 * ⚠️ 关键语义：`Heightmap.Type.WORLD_SURFACE_WG` 的谓词是 `NOT_AIR`，
 * 而 **水不是空气 → 水算"表面"**。
 * 实测（seed -143551518615525778，300 点）：命中处 **water=165 / stone=135**，
 * 也就是说 **55% 的高度由含水层（水）决定**，不是密度。
 * 这正是"95% 充水洞穴 → 小屋浮在水面（高 Y）"的机制来源。
 *
 * ⚠️ 本层需要含水层（L5）。`method_39900`（列顶 Y）由外部注入回调，
 *    因为它是 L5/L6 交界处唯一还没单独对拍通过的一环。
 */
#ifndef LYSH_PHASE2_H
#define LYSH_PHASE2_H

#include <stdint.h>

#include "aquifer.h"
#include "density.h"
#include "interp_noise.h"
#include "terrain.h"

typedef struct {
    int horizontal_block_size;   /* 4 */
    int vertical_block_size;     /* 8 */
    int minimum_block_y;         /* -8（cell） */
    int vertical_block_count;    /* 48 */
    int minimum_y;               /* -64（方块） */
    int height;                  /* 384 */
    int sea_level;               /* 63 */

    lysh_terrain terrain;
    lysh_density density;
    lysh_interp_noise interp;    /* L4：base_3d_noise */
    lysh_aquifer aquifer;

    int aq_chunk_x, aq_chunk_z;  /* 当前含水层网格对应的 chunk（惰性重建） */

    /* ---- `interpolated` 网格缓存（26.1.2 的 4(x) × 8(y) × 4(z) cell）----
     * 每个 (cellX, cellZ) 需要 4 个角点列，每列 49 个 cell-Y 取值
     * （y = minimum_y + i * vertical_block_size，i = 0..48）。
     *   角点下标 = dx * 2 + dz   （0:(x0,z0) 1:(x0,z1) 2:(x1,z0) 3:(x1,z1)）
     * `noodle_*` 是 noodle.json 里那 3 个 `interpolated` 子节点的角点值
     * （0 = thickness，1 = ridge_a，2 = ridge_b）。 */
    int cell_cache_valid;
    int cell_cache_x, cell_cache_z;
    double cell_main[4][49];
    double cell_noodle[3][4][49];

    int inited;
} lysh_phase2;

/* 初始化（由 worldSeed 推出全部噪声）。 */
void lysh_phase2_init(lysh_phase2 *p2, uint64_t world_seed, int large_biomes);

void lysh_phase2_free(lysh_phase2 *p2);

/* MC 的 `getHeight(x, z, WORLD_SURFACE_WG, world).orElse(bottomY)`。
 * 返回 [bottom_y, maximum_y] 内的高度；无命中返回 bottom_y。 */
int lysh_phase2_height(lysh_phase2 *p2, int x, int z);

/* 26.1.2 的 `final_density`（**含水层拿到的那个**）：
 *   min( squeeze(0.64 * interpolated(sloped_cheese+caves+slides)), noodle )
 * 也就是 `NoiseChunk.getInterpolatedDensity()`。
 * y 落在 [-64, 319] 之外时退回逐点求值（网格覆盖不到）。 */
double lysh_phase2_density_at(lysh_phase2 *p2, int x, int y, int z);

/* 非插值的点值（`interpolated` 节点的**内容**，= 旧的 1.18 `sampleNoiseColumn`）。
 * 只为对拍/诊断保留，不参与产品路径。 */
double lysh_phase2_density_raw_at(lysh_phase2 *p2, int x, int y, int z);

/* 单个 (x,y,z) 的方块状态（含水层判定后）。
 * 返回 LYSH_BLOCK_NULL 表示实心（调用方回退 defaultBlock = 石头）。 */
lysh_block_state lysh_phase2_block_at(lysh_phase2 *p2, int x, int y, int z);

/* 供测试：直接注入列顶 Y 回调 */
void lysh_phase2_set_column_top(lysh_phase2 *p2, lysh_aquifer_column_top_fn fn, void *user);

/* 保证含水层网格对应 (x,z) 所在的 chunk（惰性重建；block_at 内部会自己调）。 */
void lysh_phase2_ensure_chunk(lysh_phase2 *p2, int x, int z);

#endif /* LYSH_PHASE2_H */
