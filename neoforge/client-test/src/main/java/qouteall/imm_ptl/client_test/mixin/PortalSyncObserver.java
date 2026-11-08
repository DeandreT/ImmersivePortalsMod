package qouteall.imm_ptl.client_test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.client_test.Evidence;
import qouteall.imm_ptl.core.network.ImmPtlNetworkingClient;

@Mixin(value = ImmPtlNetworkingClient.class, remap = false)
public class PortalSyncObserver {
    @Inject(method = "handlePortalSync", at = @At("RETURN"))
    private static void observed(CallbackInfo ci) { Evidence.PORTAL_SYNC.incrementAndGet(); }
}
