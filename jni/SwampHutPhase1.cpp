#include "SwampHutPhase1.h"

#include "cubiomes/generator.h"
#include "cubiomes/biomes.h"

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

static bool isSwampBiomeId(int id) {
    // Witch hut structure set: swamp (+ legacy swamp_hills). Not mangrove_swamp.
    return id == swamp || id == swamp_hills;
}

} // namespace

extern "C" int swampHutIsSwampBiome(uint64_t seed, int hutX, int hutZ,
                                    int gameVersion, int worldPreset) {
    if (worldPreset == WP_SINGLE_BIOME) {
        return 1;
    }
    Generator *g = ensureGenerator(seed, gameVersion, worldPreset);
    // Surface-structure biome check: sample near sea level at hut center.
    // scale=4 uses biome/quart coordinates (block / 4).
    int bx = (hutX + 8) >> 2;
    int bz = (hutZ + 8) >> 2;
    int by = 63 >> 2; // ~Y=63
    int id = getBiomeAt(g, 4, bx, by, bz);
    if (id < 0) {
        return 0;
    }
    return isSwampBiomeId(id) ? 1 : 0;
}
