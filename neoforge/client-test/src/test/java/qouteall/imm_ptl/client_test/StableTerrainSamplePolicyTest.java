package qouteall.imm_ptl.client_test;

/** Dependency-free regression for scheduled leaf updates and bounded geological sampling. */
public final class StableTerrainSamplePolicyTest {
    public static void main(String[] args) {
        String leafBefore = "minecraft:birch_leaves[distance=3,persistent=false,waterlogged=false]";
        String leafAfter = "minecraft:birch_leaves[distance=2,persistent=false,waterlogged=false]";
        require(!leafBefore.equals(leafAfter), "A normal scheduled leaf update changes the full state");
        require(!candidate(leafBefore), "Leaves at distance 3 must not be a frozen sample, even without random ticking");
        require(!candidate(leafAfter), "The normal distance-2 leaf update must remain excluded");
        for (String unstable : new String[]{"minecraft:air", "minecraft:water", "minecraft:grass_block",
            "minecraft:snow_block", "minecraft:bedrock", "minecraft:gravel", "minecraft:oak_log"}) {
            require(!candidate(unstable), "Only nontrivial stable geology is a candidate: " + unstable);
        }
        for (String stable : new String[]{"minecraft:stone", "minecraft:deepslate", "minecraft:tuff", "minecraft:calcite",
            "minecraft:granite", "minecraft:diorite", "minecraft:andesite"}) {
            require(candidate(stable), "Natural mixed geology must remain available: " + stable);
        }
        require(!StableTerrainSamplePolicy.eligible("minecraft:stone", true, false, false, true, true, false), "Reject air state");
        require(!StableTerrainSamplePolicy.eligible("minecraft:stone", false, true, false, true, true, false), "Reject fluid state");
        require(!StableTerrainSamplePolicy.eligible("minecraft:stone", false, false, true, true, true, false), "Reject ticking state");
        require(!StableTerrainSamplePolicy.eligible("minecraft:stone", false, false, false, false, true, false), "Require full collision");
        require(!StableTerrainSamplePolicy.eligible("minecraft:stone", false, false, false, true, false, false), "Require solid render shape");
        require(!StableTerrainSamplePolicy.eligible("minecraft:stone", false, false, false, true, true, true), "Reject block-entity state");
        int selectedBefore = StableTerrainSamplePolicy.selectY(66, -64, 96,
            y -> candidate(y == 66 ? leafBefore : y == 59 ? "minecraft:calcite" : "minecraft:air")).orElseThrow();
        int selectedAfterLeafUpdate = StableTerrainSamplePolicy.selectY(66, -64, 96,
            y -> candidate(y == 66 ? leafAfter : y == 59 ? "minecraft:calcite" : "minecraft:air")).orElseThrow();
        require(selectedBefore == 59 && selectedAfterLeafUpdate == 59, "Leaf-distance changes must not invalidate geological sample selection");
        require(StableTerrainSamplePolicy.selectY(50, -64, 3, y -> y == 47).isEmpty(), "Never scan beyond the depth bound");
        require(StableTerrainSamplePolicy.selectY(50, 49, 96, y -> y == 48).isEmpty(), "Never scan below the level's minimum");
        require(StableTerrainSamplePolicy.selectY(50, -64, 3, y -> y == 48).orElseThrow() == 48, "Include the last in-bound candidate");
    }

    private static boolean candidate(String id) {
        int properties = id.indexOf('[');
        if (properties >= 0) id = id.substring(0, properties);
        return StableTerrainSamplePolicy.eligible(id, false, false, false, true, true, false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private StableTerrainSamplePolicyTest() {}
}
