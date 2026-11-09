package qouteall.imm_ptl.client_test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only full-generation sample selection at the original horizontal coordinate. */
final class StableTerrainSample {
    static BlockPos choose(ServerLevel level, int x, int z) {
        // getChunk is the ordinary FULL LevelChunk path, including normal feature generation.
        level.getChunk(x >> 4, z >> 4);
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        var selected = StableTerrainSamplePolicy.selectY(surfaceY, level.getMinY(),
            StableTerrainSamplePolicy.MAX_SCAN_DEPTH, y -> {
                BlockPos position = new BlockPos(x, y, z);
                var state = level.getBlockState(position);
                return StableTerrainSamplePolicy.eligible(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    state.isAir(), !state.getFluidState().isEmpty(), state.isRandomlyTicking(),
                    state.isCollisionShapeFullBlock(level, position), state.isSolidRender(), state.hasBlockEntity());
            });
        ArtifactProof.require(selected.isPresent(), "No stable natural geology in bounded terrain sample column");
        return new BlockPos(x, selected.orElseThrow(), z);
    }

    private StableTerrainSample() {}
}
