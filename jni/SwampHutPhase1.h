#ifndef SWAMP_HUT_PHASE1_H_
#define SWAMP_HUT_PHASE1_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** GameVersion ordinal matching project.GameVersion */
enum {
    GV_26_2 = 0,
    GV_1_21_TO_26_1 = 1,
    GV_1_20_1 = 2,
    GV_1_19_2 = 3,
    GV_1_18_2 = 4
};

/** WorldPresetMode ordinal matching project.WorldPresetMode */
enum {
    WP_NORMAL = 0,
    WP_LARGE_BIOMES = 1,
    WP_SINGLE_BIOME = 2
};

int swampHutPosInRegion(uint64_t seed, int regX, int regZ, int gameVersion, int *outX, int *outZ);

/**
 * Stage 1: structure pos + climate 4-params (+ Cont early-out). No cave/density.
 * On success writes hut block coords and returns 1.
 */
int swampHutClimateRegion(uint64_t seed, int regX, int regZ,
                          int gameVersion, int worldPreset, int *outX, int *outZ);

/**
 * Stage 2 (native half): cave ladders + 5-point density for a known hut.
 * Returns 1 if passed. Caller runs Java aquifer after this.
 */
int swampHutDensityFilter(uint64_t seed, int hutX, int hutZ, int maxHeight,
                          int gameVersion, int worldPreset);

/** Legacy: climate + ladders + density (no aquifer). Prefer split APIs above. */
int swampHutPhase1Filter(uint64_t seed, int hutX, int hutZ, int maxHeight,
                         int gameVersion, int worldPreset);

/** Legacy combined region call. Prefer climateRegion + densityFilter. */
int swampHutPhase1Region(uint64_t seed, int regX, int regZ, int maxHeight,
                         int gameVersion, int worldPreset, int *outX, int *outZ);

#ifdef __cplusplus
}
#endif

#endif
