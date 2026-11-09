package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

@Mixin(ServerLevel.class)
public class MixinServerLevel_RealmProtection {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void riftwoven$preventArrivalEnemySpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Enemy
            && RealmProtection.protectedArrival((ServerLevel) (Object) this, entity.blockPosition())) {
            cir.setReturnValue(false);
        }
    }
}
