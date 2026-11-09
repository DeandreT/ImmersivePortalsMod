package qouteall.imm_ptl.peripheral.mixin.client.riftwoven;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeSeeds;

@Mixin(ClientPacketListener.class)
public abstract class RealmBiomeSeedConnectionMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void riftwoven$clearConnectionMetadata(CallbackInfo ci) {
        RealmBiomeSeeds.connectionClosed(this);
    }
}
