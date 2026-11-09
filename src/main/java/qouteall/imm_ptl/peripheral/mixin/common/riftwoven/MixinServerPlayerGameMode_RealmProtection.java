package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;
import qouteall.imm_ptl.peripheral.riftwoven.RealmCoreBlock;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

@Mixin(ServerPlayerGameMode.class)
public class MixinServerPlayerGameMode_RealmProtection {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void riftwoven$checkBreakPermission(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockManipulationServer.Context redirect = BlockManipulationServer.REDIRECT_CONTEXT.get();
        ServerLevel target = redirect == null ? level : redirect.world();
        if (!RealmProtection.canModify(player, target, pos)) cir.setReturnValue(false);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void riftwoven$checkUsePermission(ServerPlayer actor, Level target, ItemStack stack,
        InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (!RealmProtection.isRealm(target)) return;
        // Exempt only interactions consumed by RealmRitual, including a revoked guest's return.
        // Sneaking offhand use can otherwise skip the block and run the held item's useOn.
        if (hand == InteractionHand.MAIN_HAND && !actor.isSpectator()
            && target == actor.level() && actor.isWithinBlockInteractionRange(hit.getBlockPos(), 0)
            && target.getBlockState(hit.getBlockPos()).getBlock() instanceof RealmCoreBlock) return;
        if (!RealmProtection.canModify(actor, target, hit.getBlockPos())
            || !RealmProtection.canModify(actor, target, hit.getBlockPos().relative(hit.getDirection()))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void riftwoven$checkDirectItemPermission(ServerPlayer actor, Level target, ItemStack stack,
        InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        // Buckets, spawn items and other non-block-targeted uses must not bypass membership.
        if (!RealmProtection.canBuild(actor, target) && !stack.has(DataComponents.CONSUMABLE)
            && !(stack.getItem() instanceof net.minecraft.world.item.ShieldItem)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
