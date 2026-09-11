#include "SwampHutPhase1.h"

#include "cubiomes/finders.h"
#include "cubiomes/generator.h"
#include "cubiomes/noise.h"
#include "cubiomes/terrainnoise.h"
#include "cubiomes/biomenoise.h"

#include <cmath>
#include <cstring>

namespace {

constexpr double kContTarget = -0.11;
constexpr double kContMargin = 0.088;
constexpr double kDoublePerlinF = 337.0 / 331.0;

constexpr int kDensityMargin = 8;
constexpr int kPhase1MinHeight = -50;
constexpr int kLadderDown[] = {40, 30, 20, 10, 0, -10, -20, -30, -40, -50};
constexpr int kLadderDownCount = (int)(sizeof(kLadderDown) / sizeof(kLadderDown[0]));
constexpr int kProbes[][2] = {{3, 4}, {3, 1}, {3, 7}, {1, 4}, {5, 4}};
constexpr int kProbeCount = 5;

struct ThreadCtx {
    uint64_t seed = 0;
    int mc = -1;
    int flags = -1;
    int climateReady = 0;
    int terrainReady = 0;
    Generator climateGen{};
    TerrainNoise tn{};
};

thread_local ThreadCtx g_ctx;

struct ColumnTerrain {
    double depth;
    double factor;
    double jagged;
};

static int mapMcVersion(int gameVersion) {
    switch (gameVersion) {
    case GV_1_18_2: return MC_1_18_2;
    case GV_1_19_2: return MC_1_19_2;
    case GV_1_20_1: return MC_1_20;
    case GV_1_21_TO_26_1: return MC_1_21;
    case GV_26_2: return MC_26_2;
    default: return MC_1_21;
    }
}

static int generatorFlags(int worldPreset) {
    return worldPreset == WP_LARGE_BIOMES ? LARGE_BIOMES : 0;
}

/** Lightweight climate generator — no cave TerrainNoise init (stage 1 hot path). */
static Generator *ensureClimateGen(uint64_t seed, int gameVersion, int worldPreset) {
    int mc = mapMcVersion(gameVersion);
    int flags = generatorFlags(worldPreset);
    // Reuse terrain generator if already warmed for this seed/version
    if (g_ctx.terrainReady && g_ctx.mc == mc && g_ctx.flags == flags && g_ctx.seed == seed) {
        return &g_ctx.tn.g;
    }
    if (g_ctx.mc != mc || g_ctx.flags != flags || !g_ctx.climateReady) {
        std::memset(&g_ctx.climateGen, 0, sizeof(g_ctx.climateGen));
        setupGenerator(&g_ctx.climateGen, mc, flags);
        g_ctx.mc = mc;
        g_ctx.flags = flags;
        g_ctx.climateReady = 1;
        g_ctx.terrainReady = 0;
        g_ctx.seed = 0;
    }
    if (g_ctx.seed != seed) {
        applySeed(&g_ctx.climateGen, DIM_OVERWORLD, seed);
        g_ctx.seed = seed;
        g_ctx.terrainReady = 0;
    }
    return &g_ctx.climateGen;
}

/** Full terrain noise — only for stage 2 density/ladders on climate survivors. */
static TerrainNoise *ensureTerrain(uint64_t seed, int gameVersion, int worldPreset) {
    int mc = mapMcVersion(gameVersion);
    int flags = generatorFlags(worldPreset);
    if (!g_ctx.terrainReady || g_ctx.mc != mc || g_ctx.flags != flags) {
        std::memset(&g_ctx.tn, 0, sizeof(g_ctx.tn));
        setupTerrainNoise(&g_ctx.tn, mc, flags);
        initTerrainNoise(&g_ctx.tn, seed, DIM_OVERWORLD);
        g_ctx.mc = mc;
        g_ctx.flags = flags;
        g_ctx.seed = seed;
        g_ctx.terrainReady = 1;
        g_ctx.climateReady = 1;
    } else if (g_ctx.seed != seed) {
        initTerrainNoise(&g_ctx.tn, seed, DIM_OVERWORLD);
        g_ctx.seed = seed;
    }
    return &g_ctx.tn;
}

static double sampleContOctA(const DoublePerlinNoise *dpn, int idx, double x, double y, double z) {
    if (idx < 0 || idx >= dpn->octA.octcnt) return 0.0;
    const PerlinNoise *p = dpn->octA.octaves + idx;
    double lf = p->lacunarity;
    return p->amplitude * samplePerlin(p, maintainPrecision(x * lf), maintainPrecision(y * lf), maintainPrecision(z * lf), 0, 0);
}

static double sampleContOctB(const DoublePerlinNoise *dpn, int idx, double x, double y, double z) {
    if (idx < 0 || idx >= dpn->octB.octcnt) return 0.0;
    const PerlinNoise *p = dpn->octB.octaves + idx;
    double lf = p->lacunarity;
    return p->amplitude * samplePerlin(p,
        maintainPrecision(x * lf * kDoublePerlinF),
        maintainPrecision(y * lf * kDoublePerlinF),
        maintainPrecision(z * lf * kDoublePerlinF), 0, 0);
}

static bool passContinentalness(const BiomeNoise *bn, double bx, double bz) {
    const DoublePerlinNoise *dpn = &bn->climate[NP_CONTINENTALNESS];
    const double amp = dpn->amplitude;
    const double x = bx, y = 0.0, z = bz;
    double sum = 0.0;

    for (int i = 0; i < 5; ++i) {
        sum += amp * (sampleContOctA(dpn, i, x, y, z) + sampleContOctB(dpn, i, x, y, z));
    }
    if (sum < kContTarget - kContMargin) return false;
    if (sum > kContTarget + kContMargin) return true;

    for (int i = 5; i < 9; ++i) {
        sum += amp * (sampleContOctA(dpn, i, x, y, z) + sampleContOctB(dpn, i, x, y, z));
    }
    return sum >= kContTarget;
}

static bool weirdnessInGap(double w) {
    return (w > -0.9333 && w < -0.4) || (w > 0.4 && w < 0.9333);
}

static bool passClimate(const BiomeNoise *bn, int hutX, int hutZ, int gameVersion) {
    double bx = (hutX + 8) / 4.0;
    double bz = (hutZ + 8) / 4.0;

    double erosion = sampleDoublePerlin(&bn->climate[NP_EROSION], bx, 0, bz);
    if (erosion < 0.55) return false;

    double temperature = sampleDoublePerlin(&bn->climate[NP_TEMPERATURE], bx, 0, bz);
    if (gameVersion == GV_1_18_2) {
        if (temperature < -0.45) return false;
    } else {
        if (temperature < -0.45 || temperature > 0.2) return false;
    }

    double weirdness = sampleDoublePerlin(&bn->climate[NP_WEIRDNESS], bx, 0, bz);
    if (weirdnessInGap(weirdness)) return false;
    if (gameVersion == GV_26_2 && weirdness <= -0.91) return false;

    if (!passContinentalness(bn, bx, bz)) return false;
    return true;
}

static double entrance(TerrainNoise *tn, int x, int y, int z) {
    double rough = sampleSpaghettiRoughness(tn, x, y, z);
    return sampleEntrances(tn, x, y, z, rough);
}

static double entrance2(TerrainNoise *tn, int x, int y, int z) {
    return sampleSpaghetti3d(tn, x, y, z) + sampleSpaghettiRoughness(tn, x, y, z);
}

static double cheesePlain(TerrainNoise *tn, int x, int y, int z) {
    double layer = sampleCaveLayer(tn, x, y, z);
    double cheese = sampleDoublePerlin(&tn->noises[OTP_CAVE_CHEESE], x, y * (2.0 / 3.0), z);
    double b = cheese + 0.27;
    if (b < -1.0) b = -1.0;
    if (b > 1.0) b = 1.0;
    return layer + b;
}

static ColumnTerrain sampleColumnTerrain(TerrainNoise *tn, int x, int z) {
    ColumnTerrain col{};
    int cellX = x >> 2;
    int cellZ = z >> 2;
    float np_param[4];
    sampleNoiseParameters(&tn->g.bn, cellX, cellZ, np_param);
    col.depth = getSpline(tn->g.bn.sp, np_param) - 0.50375f;
    col.factor = getSpline(tn->factorSpline, np_param);
    double j = sampleDoublePerlin(&tn->noises[OTP_JAGGED], (cellX << 2) * 1500.0, 0, (cellZ << 2) * 1500.0);
    j = j >= 0.0 ? j : j / 2.0;
    col.jagged = j * getSpline(tn->jaggednessSpline, np_param);
    return col;
}

static double cheeseWithSlopedCached(TerrainNoise *tn, int x, int y, int z, const ColumnTerrain &col) {
    double sloped = sampleSlopedCheese(tn, x, y, z, col.depth, col.factor, col.jagged);
    return sampleCaveLayer(tn, x, y, z) + sampleCaveCheese(tn, x, y, z, sloped);
}

static bool passCaveLadders(TerrainNoise *tn, int heightX, int heightZ, int maxHeight) {
    if (entrance(tn, heightX, 50, heightZ) >= 0.0) return false;
    if (entrance(tn, heightX, 60, heightZ) >= 0.0) return false;

    if (entrance2(tn, heightX, maxHeight, heightZ) >= 0.0
        && cheesePlain(tn, heightX, maxHeight, heightZ) >= 0.0) {
        return false;
    }

    int ladderFloor = maxHeight < -40 ? maxHeight : -40;
    if (ladderFloor < kPhase1MinHeight) ladderFloor = kPhase1MinHeight;

    for (int y = 0; y >= ladderFloor; y -= 10) {
        if (maxHeight < y) {
            if (entrance2(tn, heightX, y, heightZ) >= 0.0
                && cheesePlain(tn, heightX, y, heightZ) >= 0.0) {
                return false;
            }
        }
    }
    for (int y = 10; y <= 40; y += 10) {
        if (entrance(tn, heightX, y, heightZ) >= 0.0
            && cheesePlain(tn, heightX, y, heightZ) >= 0.0) {
            return false;
        }
    }
    return true;
}

static bool isDensityLadderAir(TerrainNoise *tn, int x, int y, int z, const ColumnTerrain &col) {
    double cheese = cheeseWithSlopedCached(tn, x, y, z, col);
    if (y > 50) {
        return entrance(tn, x, y, z) < 0.0;
    }
    if (y >= 10) {
        return entrance(tn, x, y, z) < 0.0 || cheese < 0.0;
    }
    return entrance2(tn, x, y, z) < 0.0 || cheese < 0.0;
}

static int estimateCheeseSurfaceLadder(TerrainNoise *tn, int x, int z) {
    ColumnTerrain col = sampleColumnTerrain(tn, x, z);
    if (!isDensityLadderAir(tn, x, 60, z, col)) {
        if (!isDensityLadderAir(tn, x, 70, z, col)) return 70;
        return 63;
    }
    if (!isDensityLadderAir(tn, x, 50, z, col)) return 60;
    for (int i = 0; i < kLadderDownCount; ++i) {
        if (!isDensityLadderAir(tn, x, kLadderDown[i], z, col)) {
            if (i == 0) return 50;
            return kLadderDown[i - 1];
        }
    }
    return -54;
}

static bool isHutRotated90(uint64_t seed, int hutX, int hutZ) {
    uint64_t structureSeed = seed & 0xFFFFFFFFFFFFULL;
    uint64_t rng = chunkGenerateRnd(structureSeed, hutX >> 4, hutZ >> 4);
    float a = nextFloat(&rng);
    return !(a < 0.25f || (a >= 0.5f && a < 0.75f));
}

static bool passDensityPrefilter(TerrainNoise *tn, uint64_t seed, int hutX, int hutZ, int maxHeight) {
    bool rotated90 = isHutRotated90(seed, hutX, hutZ);
    int sum = 0;
    for (int i = 0; i < kProbeCount; ++i) {
        int lx = kProbes[i][0], lz = kProbes[i][1];
        int wx = rotated90 ? hutX + lz : hutX + lx;
        int wz = rotated90 ? hutZ + lx : hutZ + lz;
        sum += estimateCheeseSurfaceLadder(tn, wx, wz);
    }
    double mean = sum / (double)kProbeCount;
    return !(mean > maxHeight + kDensityMargin);
}

} // namespace

extern "C" int swampHutPosInRegion(uint64_t seed, int regX, int regZ, int gameVersion, int *outX, int *outZ) {
    (void)gameVersion;
    StructureConfig sc = {14357620, 32, 24, Swamp_Hut, 0, 0};
    Pos pos = getFeaturePos(sc, seed, regX, regZ);
    if (outX) *outX = pos.x;
    if (outZ) *outZ = pos.z;
    return 1;
}

extern "C" int swampHutClimateRegion(uint64_t seed, int regX, int regZ,
                                     int gameVersion, int worldPreset, int *outX, int *outZ) {
    int hutX = 0, hutZ = 0;
    swampHutPosInRegion(seed, regX, regZ, gameVersion, &hutX, &hutZ);
    if (worldPreset != WP_SINGLE_BIOME) {
        Generator *g = ensureClimateGen(seed, gameVersion, worldPreset);
        if (!passClimate(&g->bn, hutX, hutZ, gameVersion)) {
            return 0;
        }
    }
    if (outX) *outX = hutX;
    if (outZ) *outZ = hutZ;
    return 1;
}

extern "C" int swampHutDensityFilter(uint64_t seed, int hutX, int hutZ, int maxHeight,
                                     int gameVersion, int worldPreset) {
    if (maxHeight < kPhase1MinHeight) {
        maxHeight = kPhase1MinHeight;
    }
    TerrainNoise *tn = ensureTerrain(seed, gameVersion, worldPreset);
    if (!tn) return 0;
    int heightX = hutX + 3;
    int heightZ = hutZ + 3;
    if (!passCaveLadders(tn, heightX, heightZ, maxHeight)) {
        return 0;
    }
    if (!passDensityPrefilter(tn, seed, hutX, hutZ, maxHeight)) {
        return 0;
    }
    return 1;
}

extern "C" int swampHutPhase1Filter(uint64_t seed, int hutX, int hutZ, int maxHeight,
                                    int gameVersion, int worldPreset) {
    if (worldPreset != WP_SINGLE_BIOME) {
        Generator *g = ensureClimateGen(seed, gameVersion, worldPreset);
        if (!passClimate(&g->bn, hutX, hutZ, gameVersion)) {
            return 0;
        }
    }
    return swampHutDensityFilter(seed, hutX, hutZ, maxHeight, gameVersion, worldPreset);
}

extern "C" int swampHutPhase1Region(uint64_t seed, int regX, int regZ, int maxHeight,
                                    int gameVersion, int worldPreset, int *outX, int *outZ) {
    int hutX = 0, hutZ = 0;
    if (!swampHutClimateRegion(seed, regX, regZ, gameVersion, worldPreset, &hutX, &hutZ)) {
        return 0;
    }
    if (!swampHutDensityFilter(seed, hutX, hutZ, maxHeight, gameVersion, worldPreset)) {
        return 0;
    }
    if (outX) *outX = hutX;
    if (outZ) *outZ = hutZ;
    return 1;
}
