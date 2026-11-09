package qouteall.imm_ptl.client_test;

import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

/** Test fixture policy: choose actual stable geology, never vegetation or a painted marker. */
final class StableTerrainSamplePolicy {
    static final int MAX_SCAN_DEPTH = 96;
    private static final Set<String> GEOLOGY = Set.of("minecraft:stone", "minecraft:deepslate",
        "minecraft:tuff", "minecraft:calcite", "minecraft:granite", "minecraft:diorite", "minecraft:andesite");

    static boolean eligible(String blockId, boolean air, boolean fluid, boolean randomlyTicking,
        boolean fullCollision, boolean solidRender, boolean blockEntity) {
        // Leaves can change distance through scheduled ticks even when not randomly ticking.
        // A narrow geology vocabulary also excludes weather-sensitive grass and falling blocks.
        return GEOLOGY.contains(blockId) && !air && !fluid && !randomlyTicking
            && fullCollision && solidRender && !blockEntity;
    }

    static OptionalInt selectY(int surfaceY, int minimumY, int maximumDepth, IntPredicate eligible) {
        if (maximumDepth < 1) throw new IllegalArgumentException("Scan depth must be positive");
        for (int depth = 0, y = surfaceY; depth < maximumDepth && y >= minimumY; depth++, y--) {
            if (eligible.test(y)) return OptionalInt.of(y);
        }
        return OptionalInt.empty();
    }

    private StableTerrainSamplePolicy() {}
}
