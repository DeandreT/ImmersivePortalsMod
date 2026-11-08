package qouteall.imm_ptl.core.platform_specific.mixin.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Accurately describes Iris's unmanaged custom quad passes to RenderPearl's frontend. */
@Mixin(value = CompositeRenderer.class, remap = false)
public class MixinIrisCompositeRenderer {
    @Shadow @Final @Mutable
    public static RenderPipeline COMPOSITE_PIPELINE;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void ip_describeCustomPassResources(CallbackInfo ci) {
        // Iris binds its real GL program and samplers itself, then marks these
        // passes CustomPass. Its backend preserves those bindings. The managed
        // placeholder must not claim a vanilla blit InSampler that Iris never
        // binds through RenderPass. Keep frontend validation and all real Iris
        // depth/composite draws; only correct the placeholder's resource contract.
        // These managed passes are color-only: even ALWAYS_PASS with no depth
        // writes would declare a required depth attachment in RenderPearl.
        COMPOSITE_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("iris", "composite"))
            .withVertexShader(Identifier.fromNamespaceAndPath("iris", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/iris_custom_pass_placeholder"))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();
    }
}
