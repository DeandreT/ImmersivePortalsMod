package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

@Mixin(Player.class)
public class MixinPlayer_RealmProtection {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void riftwoven$protectArrivalPlayer(ServerLevel level, DamageSource source, float amount,
        CallbackInfoReturnable<Boolean> cir) {
        Player player = (Player) (Object) this;
        if (RealmProtection.protectedArrival(level, player.blockPosition())) {
            cir.setReturnValue(false);
        }
    }
}
