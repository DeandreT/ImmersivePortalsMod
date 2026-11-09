package qouteall.imm_ptl.peripheral.mixin.client.riftwoven;

import net.minecraft.client.ClientClockManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientClock;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientEnvironmentAccess;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeSeeds;

@Mixin(ClientLevel.class)
public abstract class RealmClientLevelMixin implements RealmClientEnvironmentAccess {
    @Shadow @Final private ClientPacketListener connection;
    @Unique private RealmClientClock riftwoven$clock;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void riftwoven$restoreBiomeMetadata(CallbackInfo ci) {
        RealmBiomeSeeds.onLevelConstructed((ClientLevel) (Object) this, connection);
    }

    @Override
    public RealmClientClock riftwoven$clock() {
        if (riftwoven$clock == null) {
            var level = (ClientLevel) (Object) this;
            riftwoven$clock = new RealmClientClock(() -> connection.clockManager(), level.dimensionType().defaultClock());
        }
        return riftwoven$clock;
    }

    @Inject(method = "clockManager()Lnet/minecraft/client/ClientClockManager;", at = @At("HEAD"), cancellable = true)
    private void riftwoven$perLevelClock(CallbackInfoReturnable<ClientClockManager> ci) {
        ci.setReturnValue(riftwoven$clock());
    }
}
