package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.PortalSurfaceRendering;

// Each portal view goes through the pack's whole pipeline, so these run once per rendered view.
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline_PortalSurfaces {
    @Inject(method = "beginTranslucents", at = @At("HEAD"))
    private void ip_onBeginTranslucents(CallbackInfo ci) {
        PortalSurfaceRendering.captureWorldTransformation();
    }

    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"))
    private void ip_onFinalizeLevelRendering(CallbackInfo ci) {
        PortalSurfaceRendering.drawDeferredSurfaces();
    }
}
