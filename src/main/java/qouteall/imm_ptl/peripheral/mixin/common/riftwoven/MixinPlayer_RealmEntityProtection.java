package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

/** Entity-targeted ignition must not bypass the ordinary item-use BUILD permission check. */
@Mixin(Player.class)
public class MixinPlayer_RealmEntityProtection {
    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void riftwoven$ignition(Entity target, InteractionHand hand, net.minecraft.world.phys.Vec3 hit,
        CallbackInfoReturnable<InteractionResult> cir) {
        if ((Object) this instanceof ServerPlayer player && !RealmProtection.canBuild(player, target.level())) {
            var held = player.getItemInHand(hand);
            if (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
