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

/** Structure position only. */
int swampHutPosInRegion(uint64_t seed, int regX, int regZ, int gameVersion, int *outX, int *outZ);

/**
 * Structure pos + erosion / temperature / weirdness only.
 * Cont, cave ladders, 5-probe density, aquifer stay in Java.
 */
int swampHutClimateRegion(uint64_t seed, int regX, int regZ,
                          int gameVersion, int worldPreset, int *outX, int *outZ);

/** Climate-3 checks for a known hut (no structure lookup). */
int swampHutClimateFilter(uint64_t seed, int hutX, int hutZ,
                          int gameVersion, int worldPreset);

#ifdef __cplusplus
}
#endif

#endif
