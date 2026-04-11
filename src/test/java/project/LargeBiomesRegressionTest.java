package project;

import net.minecraft.block.Block;
import nl.jellejurre.seedchecker.SeedChecker;
import nl.jellejurre.seedchecker.SeedCheckerDimension;
import nl.jellejurre.seedchecker.TargetState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeBiomesRegressionTest {

    @Test
    @Timeout(30)
    void largeBiomesPresetChangesTerrainForFixedSeeds() {
        long[] seeds = {5023147298867078368L};
        boolean foundDifference = false;

        for (long seed : seeds) {
            SeedChecker normal = SeedCheckerFactory.create(seed, TargetState.NO_STRUCTURES, SeedCheckerDimension.OVERWORLD, WorldPresetMode.NORMAL);
            SeedChecker large = SeedCheckerFactory.create(seed, TargetState.NO_STRUCTURES, SeedCheckerDimension.OVERWORLD, WorldPresetMode.LARGE_BIOMES);
            try {
                if (terrainDiffers(normal, large)) {
                    foundDifference = true;
                    break;
                }
            } finally {
                normal.clearMemory();
                large.clearMemory();
            }
        }

        assertTrue(foundDifference, "Expected Large Biomes terrain to differ from Normal terrain in the fixed regression search space");
    }

    private static boolean terrainDiffers(SeedChecker normal, SeedChecker large) {
        for (int x = -512; x <= 512; x += 32) {
            for (int z = -512; z <= 512; z += 32) {
                int normalY = findTopSolidY(normal, x, z);
                int largeY = findTopSolidY(large, x, z);
                if (normalY != largeY) {
                    return true;
                }
                Block normalBlock = normal.getBlock(x, normalY, z);
                Block largeBlock = large.getBlock(x, largeY, z);
                if (normalBlock != largeBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int findTopSolidY(SeedChecker checker, int x, int z) {
        for (int y = 128; y >= -55; y--) {
            if (!checker.getBlockState(x, y, z).isAir()) {
                return y;
            }
        }
        return -55;
    }
}
