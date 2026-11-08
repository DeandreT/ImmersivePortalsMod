package qouteall.imm_ptl.core.platform_specific.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisVertexBindings;

import java.util.List;

/** Keeps Iris's extended attributes and NeoForge's complete binding-slot contract. */
@Mixin(value = RenderPipeline.class, priority = 900)
public class MixinIrisRenderPipelineVertexBindings {
    @Shadow @Final
    private List<VertexFormat> vertexFormatPerBuffer;

    // Applied after Iris's higher-priority mixin: wrap the complete method,
    // including its cancellable return injection, rather than validation/draws.
    @WrapMethod(method = "getVertexFormatBindings")
    private List<VertexFormat> ip_retainNativeBindingSlots(Operation<List<VertexFormat>> original) {
        return IrisVertexBindings.restoreDeclaredSlots(vertexFormatPerBuffer, original.call());
    }
}
