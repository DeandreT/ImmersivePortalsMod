package qouteall.imm_ptl.client_test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.client_test.Evidence;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

@Mixin(value = ImmPtlNetworkConfig.C2SConfigCompletePacket.class, remap = false)
public class ConfigCompleteObserver {
    @Inject(method = "handle", at = @At("RETURN"))
    private void observed(CallbackInfo ci) { Evidence.CONFIG_COMPLETE.incrementAndGet(); }
}
