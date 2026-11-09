package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

/** Reject the movement plan before piston code can copy a protected block to its destination. */
@Mixin(PistonBaseBlock.class)
public class MixinPistonBaseBlock_RealmProtection {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void riftwoven$protectShelter(BlockState state, Level level, BlockPos pos,
        Direction direction, boolean destroy, Direction pistonDirection, CallbackInfoReturnable<Boolean> cir) {
        if (RealmProtection.protectedBlock(level, pos)
            || RealmProtection.protectedBlock(level, pos.relative(direction))) cir.setReturnValue(false);
    }
}
