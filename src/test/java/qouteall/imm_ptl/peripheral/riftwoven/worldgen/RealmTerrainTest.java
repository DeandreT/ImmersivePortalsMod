package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RealmTerrainTest {
    static RealmProfile profile(int radius, double richness, int danger, RealmProfile.Topology topology) {
        return new RealmProfile(1, radius,
            Map.of("arid", 1, "cold", 2, "lush", 3, "temperate", 6, "wet", 4),
            RealmProfile.Weather.CHANGEABLE, 24000, 12000, 1, 1, richness, danger, 64, 48, topology);
    }

    @Test
    void independentInstancesProduceIdenticalTerrainAndModulesInAnyQueryOrder() {
        RealmProfile profile = profile(256, 2.5, 3, RealmProfile.Topology.PLATEAUS);
        RealmTerrain a = new RealmTerrain(profile, 91273198273L);
        RealmTerrain b = new RealmTerrain(profile, 91273198273L);
        for (int x = -240; x < 250; x += 23) {
            for (int z = 240; z > -250; z -= 31) {
                RealmTerrain.Column ca = a.column(x, z);
                RealmTerrain.Column cb = b.column(x, z);
                assertEquals(ca, cb);
                for (int y = RealmTerrain.MIN_Y; y < RealmTerrain.MIN_Y + RealmTerrain.HEIGHT; y += 13) {
                    // Reverse and interleave unrelated requests: there is no mutable random stream.
                    b.sample(b.column(z, x), y + 7);
                    assertEquals(a.sample(ca, y), b.sample(cb, y), "coordinate " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void sameProfileAndDifferentSeedChangesBothGeographyAndUnderground() {
        RealmProfile profile = profile(256, 3, 3, RealmProfile.Topology.CONTINENT);
        RealmTerrain a = new RealmTerrain(profile, 1);
        RealmTerrain b = new RealmTerrain(profile, 2);
        int heights = 0, underground = 0, regions = 0;
        for (int x = -250; x < 250; x += 17) {
            for (int z = -250; z < 250; z += 19) {
                RealmTerrain.Column ca = a.column(x, z), cb = b.column(x, z);
                if (ca.surface() != cb.surface()) { heights++; }
                if (!ca.family().equals(cb.family())) { regions++; }
                if (a.sample(ca, -20) != b.sample(cb, -20)) { underground++; }
            }
        }
        assertTrue(heights > 100, "Independent seeds must substantially rearrange terrain");
        assertTrue(regions > 100, "Independent seeds must rearrange allocated habitats");
        assertTrue(underground > 100, "Independent seeds must rearrange geology and caves");
        assertEquals(a.profile(), b.profile(), "Geography must not change recipe commitments");
    }

    @Test
    void sectorCentersFulfillExactProfileAllocationsAcrossSeedsAndExtents() {
        for (int radius : new int[]{128, 129, 256, 1024}) {
            RealmProfile profile = profile(radius, 1, 0, RealmProfile.Topology.ISLANDS);
            for (long seed = 0; seed < 40; seed++) {
                RealmTerrain terrain = new RealmTerrain(profile, seed);
                Map<String, Integer> counts = new HashMap<>();
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        int x = (int) (-radius + (col + .5) * radius / 2);
                        int z = (int) (-radius + (row + .5) * radius / 2);
                        RealmTerrain.Column column = terrain.column(x, z);
                        counts.merge(column.family(), 1, Integer::sum);
                        assertTrue(column.surface() > profile.waterLevel(), "Every allocated habitat needs suitable dry terrain");
                        assertTrue(terrain.region(x, z).distanceToBoundary() >= 20, "Climate transitions must not consume a sector");
                    }
                }
                assertEquals(profile.biomeWeights(), counts, "Every promised sector must survive seed rotation at radius " + radius);
            }
        }
    }

    @Test
    void finiteHalfOpenSquareIsEmptyAtEveryHeightOutsideIncludingTheMargin() {
        RealmTerrain terrain = new RealmTerrain(profile(128, 4, 4, RealmProfile.Topology.CAVERNS), 5);
        for (int[] coordinate : new int[][]{{128, 0}, {-129, 0}, {0, 128}, {0, -129}, {150, 150}, {4096, -4096}}) {
            RealmTerrain.Column column = terrain.column(coordinate[0], coordinate[1]);
            assertFalse(column.inside());
            for (int y = -80; y < 220; y++) {
                assertEquals(RealmTerrain.Material.AIR, terrain.sample(column, y));
            }
        }
        assertTrue(terrain.column(-128, -128).inside());
        assertTrue(terrain.column(127, 127).inside());
        assertFalse(terrain.inside(Integer.MIN_VALUE, 0));
        assertFalse(terrain.inside(Integer.MAX_VALUE, 0));
    }

    @Test
    void generationMarginIsAtMostTwoChunksAndNeverDecorated() {
        RealmTerrain terrain = new RealmTerrain(profile(128, 1, 0, RealmProfile.Topology.CONTINENT), 0);
        assertTrue(terrain.generatesChunk(9, 0));
        assertTrue(terrain.generatesChunk(-10, 0));
        assertFalse(terrain.generatesChunk(10, 0));
        assertFalse(terrain.generatesChunk(-11, 0));
        assertFalse(terrain.generatesChunk(Integer.MAX_VALUE, 0), "Bounds must not overflow chunk-to-block conversion");
        assertFalse(terrain.generatesChunk(Integer.MIN_VALUE, 0));
        assertTrue(terrain.decoratesChunk(7, 0));
        assertTrue(terrain.decoratesChunk(-8, 0));
        assertFalse(terrain.decoratesChunk(8, 0));
        assertFalse(terrain.decoratesChunk(-9, 0));
        assertFalse(terrain.decoratesChunk(Integer.MAX_VALUE, 0));
    }

    @Test
    void arrivalFloorAndStandingRoomAreInvariantAndContainNoHarvest() {
        for (RealmProfile.Topology topology : RealmProfile.Topology.values()) {
            for (long seed = 0; seed < 12; seed++) {
                RealmTerrain terrain = new RealmTerrain(profile(128, 4, 4, topology), seed);
                for (int x = -10; x <= 10; x++) {
                    for (int z = -10; z <= 10; z++) {
                        RealmTerrain.Column c = terrain.column(x, z);
                        assertEquals(RealmTerrain.ARRIVAL_Y - 1, c.surface());
                        assertEquals(RealmTerrain.Material.STONE, terrain.sample(c, RealmTerrain.ARRIVAL_Y - 1));
                        for (int y = RealmTerrain.ARRIVAL_Y; y < RealmTerrain.ARRIVAL_Y + 5; y++) {
                            assertEquals(RealmTerrain.Material.AIR, terrain.sample(c, y));
                        }
                        for (int y = -60; y < RealmTerrain.ARRIVAL_Y; y += 17) {
                            assertEquals(RealmTerrain.Material.STONE, terrain.sample(c, y));
                        }
                    }
                }
            }
        }
    }

    @Test
    void differentTopologiesComposeDistinctHeightAndCaveFields() {
        long[] fingerprints = new long[RealmProfile.Topology.values().length];
        for (RealmProfile.Topology topology : RealmProfile.Topology.values()) {
            RealmTerrain terrain = new RealmTerrain(profile(128, 2, 3, topology), 37);
            long hash = 0;
            for (int x = -120; x < 128; x += 11) {
                for (int z = -120; z < 128; z += 13) {
                    RealmTerrain.Column c = terrain.column(x, z);
                    hash = hash * 31 + c.surface();
                    hash = hash * 31 + terrain.sample(c, 20).ordinal();
                }
            }
            fingerprints[topology.ordinal()] = hash;
        }
        for (int i = 0; i < fingerprints.length; i++) {
            for (int j = i + 1; j < fingerprints.length; j++) {
                assertNotEquals(fingerprints[i], fingerprints[j]);
            }
        }
    }

    @Test
    void greaterRichnessAddsMixedMineralOpportunitiesWithoutAnOreQuota() {
        RealmTerrain low = new RealmTerrain(profile(128, 1, 2, RealmProfile.Topology.CONTINENT), 928137);
        RealmTerrain high = new RealmTerrain(profile(128, 4, 2, RealmProfile.Topology.CONTINENT), 928137);
        EnumSet<RealmTerrain.Material> ores = EnumSet.of(RealmTerrain.Material.IRON, RealmTerrain.Material.COPPER,
            RealmTerrain.Material.COAL, RealmTerrain.Material.GOLD, RealmTerrain.Material.REDSTONE,
            RealmTerrain.Material.LAPIS, RealmTerrain.Material.DIAMOND, RealmTerrain.Material.AMETHYST);
        EnumSet<RealmTerrain.Material> encountered = EnumSet.noneOf(RealmTerrain.Material.class);
        int ordinary = 0, rich = 0;
        for (int x = -120; x < 128; x += 3) {
            for (int z = -120; z < 128; z += 5) {
                RealmTerrain.Column c = low.column(x, z);
                for (int y = -54; y < 30; y += 3) {
                    RealmTerrain.Material a = low.material(c, y), b = high.material(c, y);
                    if (ores.contains(a)) { ordinary++; }
                    if (ores.contains(b)) { rich++; encountered.add(b); }
                }
            }
        }
        assertTrue(rich > ordinary, "Richness must change actual geology, not just preview text");
        assertTrue(encountered.size() >= 5, "Rich districts must contain mixed minerals, not one matching ore");
    }

    @Test
    void ruinGrammarCreatesTerrainAdaptiveModulesAwayFromArrival() {
        RealmTerrain terrain = new RealmTerrain(profile(256, 4, 3, RealmProfile.Topology.CONTINENT), 719231);
        int modules = 0;
        for (int x = -240; x < 240; x += 2) {
            for (int z = -240; z < 240; z += 2) {
                RealmTerrain.Column c = terrain.column(x, z);
                for (int y = c.surface() + 1; y < c.surface() + 12; y++) {
                    RealmTerrain.Material material = terrain.ruin(x, y, z);
                    if (material != RealmTerrain.Material.AIR) {
                        modules++;
                        assertTrue(terrain.inside(x, z));
                        assertTrue(Math.max(Math.abs(x), Math.abs(z)) >= 34);
                        assertEquals(material, terrain.sample(c, y));
                    }
                }
            }
        }
        assertTrue(modules > 30, "The authored grammar must actually place ruins");
    }
}
