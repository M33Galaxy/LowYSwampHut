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

/**
 * Precise swamp-biome check at hut center (block scale sample via cubiomes).
 * Returns 1 if swamp (or single-biome preset), else 0.
 */
int swampHutIsSwampBiome(uint64_t seed, int hutX, int hutZ,
                         int gameVersion, int worldPreset);

#ifdef __cplusplus
}
#endif

#endif
