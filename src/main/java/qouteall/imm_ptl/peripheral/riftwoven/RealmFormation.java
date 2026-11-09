package qouteall.imm_ptl.peripheral.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Small vanilla-material arch. Rotation is cosmetic, not a hidden recipe input. */
public final class RealmFormation {
    private RealmFormation() {}

    public static String problem(Level level, BlockPos core, Direction facing) {
        Direction side = facing.getClockWise();
        for (int x = -2; x <= 2; x++) {
            BlockPos foot = core.relative(side, x).below();
            if (!level.getBlockState(foot).is(BlockTags.BASE_STONE_OVERWORLD)
                && !level.getBlockState(foot).is(Blocks.STONE_BRICKS)
                && !level.getBlockState(foot).is(Blocks.COBBLESTONE)) {
                return "Build a five-block stone footing beneath the seed.";
            }
        }
        for (int sign : new int[]{-1, 1}) {
            for (int y = 0; y <= 2; y++) {
                if (!level.getBlockState(core.relative(side, sign * 2).above(y)).is(Blocks.CRYING_OBSIDIAN)) {
                    return "Build three-high crying-obsidian uprights two blocks to either side.";
                }
            }
            if (!level.getBlockState(core.relative(side, sign * 2).above(3)).is(Blocks.AMETHYST_BLOCK)) {
                return "Cap both uprights with amethyst blocks.";
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int y = 1; y <= 3; y++) {
                if (!level.getBlockState(core.relative(side, x).above(y)).isAir()) {
                    return "Clear the three-by-three opening above the seed.";
                }
            }
        }
        return null;
    }
}
