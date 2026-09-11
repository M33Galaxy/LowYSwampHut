package project;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mcfeature.structure.SwampHut;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity checks between cubiomes JNI and Java seedfinding / SearchCoords heuristics.
 */
public class CubiomesParityTest {

    @BeforeAll
    static void requireNative() {
        Assumptions.assumeTrue(CubiomesBridge.isAvailable(),
                () -> "JNI unavailable: " + CubiomesBridge.getLoadError());
    }

    @Test
    void hutPositionMatchesSeedfinding() {
        long[] seeds = {1L, 12345L, -99L, 0x123456789ABCDEFL, Long.MIN_VALUE / 3};
        int[][] regions = {{0, 0}, {1, -1}, {-5, 7}, {100, 100}};
        SwampHut hut = new SwampHut(GameVersion.V1_21_TO_26_1.getMcVersion());
        ChunkRand rand = new ChunkRand();
        for (long seed : seeds) {
            for (int[] reg : regions) {
                CPos javaPos = hut.getInRegion(seed, reg[0], reg[1], rand);
                int[] nativePos = CubiomesBridge.getHutInRegion(seed, reg[0], reg[1], GameVersion.V1_21_TO_26_1);
                assertNotNull(nativePos);
                assertEquals(javaPos.getX() * 16, nativePos[0],
                        "seed=" + seed + " reg=" + reg[0] + "," + reg[1]);
                assertEquals(javaPos.getZ() * 16, nativePos[1],
                        "seed=" + seed + " reg=" + reg[0] + "," + reg[1]);
            }
        }
    }

    @Test
    void climateRegionAgreesWithJavaClimate() {
        SearchCoords searcher = new SearchCoords(GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
        long seed = 1L;
        SwampHut hut = new SwampHut(GameVersion.V1_21_TO_26_1.getMcVersion());
        ChunkRand rand = new ChunkRand();
        int checked = 0;
        int agreements = 0;
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                CPos pos = hut.getInRegion(seed, rx, rz, rand);
                int hutX = pos.getX() * 16;
                int hutZ = pos.getZ() * 16;
                boolean javaPass = searcher.checkClimateOnly(seed, hutX, hutZ);
                int[] climate = CubiomesBridge.climateRegion(seed, rx, rz,
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
                boolean jniPass = climate != null;
                if (jniPass == javaPass) {
                    agreements++;
                }
                if (jniPass) {
                    assertEquals(hutX, climate[0]);
                    assertEquals(hutZ, climate[1]);
                }
                checked++;
            }
        }
        assertTrue(checked > 0);
        double rate = agreements / (double) checked;
        assertTrue(rate >= 0.8, "climate agreement rate=" + rate + " (" + agreements + "/" + checked + ")");
    }

    @Test
    void densityFilterAgreesWithJavaLaddersAndDensity() {
        SearchCoords searcher = new SearchCoords(GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
        long seed = 1L;
        int checked = 0;
        int agreements = 0;
        for (int rx = -8; rx <= 8 && checked < 40; rx++) {
            for (int rz = -8; rz <= 8 && checked < 40; rz++) {
                int[] climate = CubiomesBridge.climateRegion(seed, rx, rz,
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
                if (climate == null) {
                    continue;
                }
                int hutX = climate[0];
                int hutZ = climate[1];
                int phase1Height = -50;
                boolean javaPass = searcher.checkCaveLadders(seed, hutX, hutZ, phase1Height)
                        && SearchCoords.passesDensityPrefilter(
                        seed, hutX, hutZ, phase1Height,
                        GameVersion.V1_21_TO_26_1.getMcVersion(), WorldPresetMode.NORMAL);
                boolean jniPass = CubiomesBridge.densityFilter(seed, hutX, hutZ, phase1Height,
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
                if (jniPass == javaPass) {
                    agreements++;
                }
                checked++;
            }
        }
        assertTrue(checked > 0, "need climate survivors for density parity");
        double rate = agreements / (double) checked;
        assertTrue(rate >= 0.8, "density agreement rate=" + rate + " (" + agreements + "/" + checked + ")");
    }

    @Test
    void phase1RegionMatchesClimateThenDensity() {
        long seed = 12345L;
        for (int rx = -1; rx <= 1; rx++) {
            for (int rz = -1; rz <= 1; rz++) {
                int[] combined = CubiomesBridge.phase1Region(seed, rx, rz, -50,
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
                int[] climate = CubiomesBridge.climateRegion(seed, rx, rz,
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
                if (climate == null) {
                    org.junit.jupiter.api.Assertions.assertNull(combined);
                    continue;
                }
                boolean density = CubiomesBridge.densityFilter(seed, climate[0], climate[1], -50,
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
                if (density) {
                    assertNotNull(combined);
                    assertEquals(climate[0], combined[0]);
                    assertEquals(climate[1], combined[1]);
                } else {
                    org.junit.jupiter.api.Assertions.assertNull(combined);
                }
            }
        }
    }

    @Test
    void allSupportedGameVersionsMap() {
        for (GameVersion gv : GameVersion.values()) {
            int[] pos = CubiomesBridge.getHutInRegion(42L, 0, 0, gv);
            assertNotNull(pos);
            assertEquals(2, pos.length);
        }
    }

    @Test
    void climateRegionThroughputCompetitiveWithJava() {
        SearchCoords searcher = new SearchCoords(GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
        SwampHut hut = new SwampHut(GameVersion.V1_21_TO_26_1.getMcVersion());
        ChunkRand rand = new ChunkRand();
        long seed = 99991L;
        int[][] regions = new int[64][2];
        int[][] huts = new int[64][2];
        int n = 0;
        for (int rx = -4; rx <= 3 && n < regions.length; rx++) {
            for (int rz = -4; rz <= 3 && n < regions.length; rz++) {
                regions[n][0] = rx;
                regions[n][1] = rz;
                CPos pos = hut.getInRegion(seed, rx, rz, rand);
                huts[n][0] = pos.getX() * 16;
                huts[n][1] = pos.getZ() * 16;
                n++;
            }
        }
        for (int i = 0; i < n; i++) {
            CubiomesBridge.climateRegion(seed, regions[i][0], regions[i][1],
                    GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
            searcher.checkClimateOnly(seed, huts[i][0], huts[i][1]);
        }
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 40; rep++) {
            for (int i = 0; i < n; i++) {
                CubiomesBridge.climateRegion(seed, regions[i][0], regions[i][1],
                        GameVersion.V1_21_TO_26_1, WorldPresetMode.NORMAL);
            }
        }
        long jniNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int rep = 0; rep < 40; rep++) {
            for (int i = 0; i < n; i++) {
                searcher.checkClimateOnly(seed, huts[i][0], huts[i][1]);
            }
        }
        long javaNs = System.nanoTime() - t0;
        assertTrue(jniNs < javaNs * 3,
                "climate jniNs=" + jniNs + " javaNs=" + javaNs);
    }
}
