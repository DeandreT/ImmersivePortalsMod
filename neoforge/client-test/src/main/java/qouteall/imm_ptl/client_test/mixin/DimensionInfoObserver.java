package qouteall.imm_ptl.client_test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.dimlib.ClientDimensionInfo;
import qouteall.imm_ptl.client_test.Evidence;

@Mixin(value = ClientDimensionInfo.class, remap = false)
public class DimensionInfoObserver {
    @Inject(method = "accept", at = @At("RETURN"))
    private static void observed(CallbackInfo ci) { Evidence.DIMENSION_SYNC.incrementAndGet(); }
}
