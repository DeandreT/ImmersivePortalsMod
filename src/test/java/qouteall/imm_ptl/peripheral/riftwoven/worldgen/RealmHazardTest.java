package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import static org.junit.jupiter.api.Assertions.*;

class RealmHazardTest {
    private static RealmTerrain terrain(long seed, int danger) {
        return new RealmTerrain(RealmTerrainTest.profile(128, 4, danger, RealmProfile.Topology.CONTINENT), seed);
    }

    @Test
    void surfaceOpeningsActuallyReachTheSurfaceAndIncreaseWithDanger() {
        int[] openings = new int[5];
        int[] deepest = new int[5];
        for (int danger = 0; danger <= 4; danger++) {
            for (long seed = 0; seed < 8; seed++) {
                RealmTerrain terrain = terrain(seed, danger);
                for (int x = -120; x < 120; x += 2) {
                    for (int z = -120; z < 120; z += 2) {
                        RealmTerrain.Column c = terrain.column(x, z);
                        if (c.ravine().depth() == 0) { continue; }
                        openings[danger]++;
                        deepest[danger] = Math.max(deepest[danger], c.ravine().depth());
                        assertTrue(open(terrain.sample(c, c.surface())),
                            "An advertised ravine must breach the old six-block cap");
                        assertTrue(open(terrain.sample(c, c.surface() - Math.min(4, c.ravine().depth() - 1))));
                        assertTrue(c.ravine().warningRim(), "Every opening must have an exposed stone lip");
                    }
                }
            }
        }
        assertEquals(0, openings[0]);
        assertTrue(openings[1] > 0, "Low-danger realms still need discoverable cave access");
        for (int danger = 2; danger <= 4; danger++) {
            assertTrue(openings[danger] > openings[danger - 1], "Danger must widen and add real surface exposures");
            assertTrue(deepest[danger] > deepest[danger - 1], "Danger must deepen vertical exposure");
        }
        assertTrue(deepest[1] <= 17, "Low-danger ravines must remain comparatively shallow");
        assertTrue(deepest[4] >= 70, "Extreme danger must permit genuinely severe exposed drops");
    }

    private static boolean open(RealmTerrain.Material material) {
        return material == RealmTerrain.Material.AIR || material == RealmTerrain.Material.WATER || material == RealmTerrain.Material.LAVA;
    }

    @Test
    void increasingDangerCannotCloseAnExistingRavine() {
        RealmTerrain low = terrain(91237, 1), high = terrain(91237, 4);
        for (int x = -120; x < 120; x += 2) {
            for (int z = -120; z < 120; z += 2) {
                RealmTerrain.Column a = low.column(x, z), b = high.column(x, z);
                assertEquals(a.surface(), b.surface(), "Danger should not silently replace the landform recipe");
                if (a.ravine().depth() > 0) {
                    assertTrue(b.ravine().depth() >= a.ravine().depth());
                }
            }
        }
    }

    @Test
    void highDangerExposesReadableLavaHotspotsAndFloodedDepths() {
        int lavaColumns = 0, floodedColumns = 0;
        for (long seed = 0; seed < 12; seed++) {
            RealmTerrain terrain = terrain(seed, 4);
            for (int x = -120; x < 120; x += 3) {
                for (int z = -120; z < 120; z += 3) {
                    RealmTerrain.Column c = terrain.column(x, z);
                    int depth = c.ravine().depth();
                    if (depth < 12) { continue; }
                    RealmTerrain.Material bottom = terrain.material(c, c.surface() - depth + 1);
                    if (bottom == RealmTerrain.Material.LAVA) {
                        lavaColumns++;
                        assertTrue(c.surface() - c.ravine().lavaLevel() >= 12, "Lava must be exposed deep in a visible chasm, not hidden on its lip");
                        for (int y = c.ravine().lavaLevel() + 1; y <= c.surface(); y++) {
                            assertEquals(RealmTerrain.Material.AIR, terrain.sample(c, y), "A hotspot must be visible down the open ravine");
                        }
                    }
                    if (bottom == RealmTerrain.Material.WATER) {
                        floodedColumns++;
                        assertEquals("wet", c.family());
                        assertTrue(c.ravine().flooded());
                    }
                }
            }
        }
        assertTrue(lavaColumns > 0, "High danger must create actual exposed geothermal pools");
        assertTrue(floodedColumns > 0, "Wet high-danger regions must expose actual flooded depths");
    }

    @Test
    void extremeDangerRetainsQuietCountryHabitatCentersAndArrivalApron() {
        RealmTerrain terrain = terrain(73198, 4);
        int quiet = 0, exposed = 0;
        for (int x = -128; x < 128; x += 2) {
            for (int z = -128; z < 128; z += 2) {
                RealmTerrain.Column c = terrain.column(x, z);
                if (c.ravine().depth() == 0) { quiet++; } else { exposed++; }
                if (Math.max(Math.abs(x), Math.abs(z)) <= 42) {
                    assertEquals(0, c.ravine().depth(), "No generated exposure may intrude into the arrival apron");
                }
            }
        }
        assertTrue(exposed > 0);
        assertTrue(quiet > exposed * 3, "Even extreme danger is local country to prepare for, not a uniformly hostile floor");
        for (int x : new int[]{-96, -32, 32, 96}) {
            for (int z : new int[]{-96, -32, 32, 96}) {
                RealmTerrain.Column c = terrain.column(x, z);
                assertEquals(0, c.ravine().depth(), "Every committed palette sector keeps suitable quiet surface habitat");
                assertNotEquals(RealmTerrain.Material.AIR, terrain.sample(c, c.surface()));
            }
        }
    }

    @Test
    void hazardFieldsAreSeededFiniteAndQueryOrderIndependent() {
        RealmTerrain a = terrain(13, 4), same = terrain(13, 4), different = terrain(14, 4);
        int differences = 0;
        for (int x = -140; x < 140; x += 3) {
            for (int z = -140; z < 140; z += 3) {
                RealmTerrain.Column c = a.column(x, z);
                same.column(z, x);
                assertEquals(c, same.column(x, z));
                if (!c.ravine().equals(different.column(x, z).ravine())) { differences++; }
                if (!c.inside()) { assertEquals(0, c.ravine().depth()); }
                assertTrue(c.surface() - c.ravine().depth() >= RealmTerrain.MIN_Y + 8 || !c.inside(),
                    "Open ravines cannot pierce the finite world's bedrock base");
            }
        }
        assertTrue(differences > 100, "Hazard locations must come from the independent seed, not just the profile");
    }
}
