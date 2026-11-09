package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.RealmCoreBlock;
import qouteall.imm_ptl.peripheral.riftwoven.Riftwoven;

/** Sneak + diamonds would otherwise skip block interaction before the block's use handler. */
@Mixin(ServerPlayerGameMode.class)
public class MixinServerPlayerGameMode_RealmRitual {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void riftwoven$useCore(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand == InteractionHand.MAIN_HAND && !player.isSpectator()
            && level == player.level() && player.isWithinBlockInteractionRange(hit.getBlockPos(), 0)
            && level.getBlockState(hit.getBlockPos()).getBlock() instanceof RealmCoreBlock) {
            Riftwoven.useCore(player, hit.getBlockPos(), hand);
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
