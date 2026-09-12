#include "SwampHutPhase1.h"

#include "cubiomes/finders.h"
#include "cubiomes/generator.h"
#include "cubiomes/noise.h"
#include "cubiomes/biomenoise.h"

#include <cstring>

namespace {

struct ThreadCtx {
    uint64_t seed = 0;
    int mc = -1;
    int flags = -1;
    int ready = 0;
    Generator gen{};
};

thread_local ThreadCtx g_ctx;

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

static Generator *ensureGenerator(uint64_t seed, int gameVersion, int worldPreset) {
    int mc = mapMcVersion(gameVersion);
    int flags = generatorFlags(worldPreset);
    if (g_ctx.mc != mc || g_ctx.flags != flags || !g_ctx.ready) {
        std::memset(&g_ctx.gen, 0, sizeof(g_ctx.gen));
        setupGenerator(&g_ctx.gen, mc, flags);
        g_ctx.mc = mc;
        g_ctx.flags = flags;
        g_ctx.ready = 1;
        g_ctx.seed = 0;
    }
    if (g_ctx.seed != seed) {
        applySeed(&g_ctx.gen, DIM_OVERWORLD, seed);
        g_ctx.seed = seed;
    }
    return &g_ctx.gen;
}

static bool weirdnessInGap(double w) {
    return (w > -0.9333 && w < -0.4) || (w > 0.4 && w < 0.9333);
}

/** Erosion / temperature / weirdness only. */
static bool passClimate3(const BiomeNoise *bn, int hutX, int hutZ, int gameVersion) {
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

    return true;
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

extern "C" int swampHutClimateFilter(uint64_t seed, int hutX, int hutZ,
                                     int gameVersion, int worldPreset) {
    if (worldPreset == WP_SINGLE_BIOME) {
        return 1;
    }
    Generator *g = ensureGenerator(seed, gameVersion, worldPreset);
    return passClimate3(&g->bn, hutX, hutZ, gameVersion) ? 1 : 0;
}

extern "C" int swampHutClimateRegion(uint64_t seed, int regX, int regZ,
                                     int gameVersion, int worldPreset, int *outX, int *outZ) {
    int hutX = 0, hutZ = 0;
    swampHutPosInRegion(seed, regX, regZ, gameVersion, &hutX, &hutZ);
    if (!swampHutClimateFilter(seed, hutX, hutZ, gameVersion, worldPreset)) {
        return 0;
    }
    if (outX) *outX = hutX;
    if (outZ) *outZ = hutZ;
    return 1;
}
