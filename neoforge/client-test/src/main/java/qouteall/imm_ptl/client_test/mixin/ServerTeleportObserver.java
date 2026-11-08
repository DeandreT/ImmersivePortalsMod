package qouteall.imm_ptl.client_test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.client_test.Evidence;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;

@Mixin(value = ImmPtlNetworking.TeleportPacket.class, remap = false)
public class ServerTeleportObserver {
    @Inject(method = "handle", at = @At("RETURN"))
    private void observed(CallbackInfo ci) { Evidence.SERVER_TELEPORT_REQUESTS.incrementAndGet(); }
}
