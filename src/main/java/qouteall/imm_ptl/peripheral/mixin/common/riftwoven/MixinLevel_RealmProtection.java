package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

@Mixin(Level.class)
public class MixinLevel_RealmProtection {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void riftwoven$protectArrival(BlockPos pos, BlockState state, int flags, int recursion,
        CallbackInfoReturnable<Boolean> cir) {
        if (!RealmProtection.arrivalEditsAllowed()
            && RealmProtection.protectedBlock((Level) (Object) this, pos)) {
            cir.setReturnValue(false);
        }
    }

    // Destruction may produce drops before setBlock is called, so reject it before those side effects.
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
        at = @At("HEAD"), cancellable = true)
    private void riftwoven$preventArrivalHarvest(BlockPos pos, boolean drops, Entity entity, int recursion,
        CallbackInfoReturnable<Boolean> cir) {
        if (!RealmProtection.arrivalEditsAllowed()
            && RealmProtection.protectedBlock((Level) (Object) this, pos)) {
            cir.setReturnValue(false);
        }
    }
}
