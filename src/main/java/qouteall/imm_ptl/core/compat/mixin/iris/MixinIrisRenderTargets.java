package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.targets.RenderTargets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Iris' world depth attachments in sync when sequential portal views change targets. */
@Mixin(value = RenderTargets.class, remap = false)
public class MixinIrisRenderTargets {
    @Shadow private GpuTexture currentDepthTexture;
    @Shadow @Final private GlFramebuffer noTranslucentsDestFb;
    @Shadow @Final private GlFramebuffer noHandDestFb;

    @ModifyExpressionValue(
        method = "resizeIfNeeded",
        at = @At(
            value = "FIELD",
            target = "Lnet/irisshaders/iris/targets/RenderTargets;cachedDepthBufferVersion:I",
            opcode = Opcodes.GETFIELD
        )
    )
    private int ip_includeDepthTextureIdentity(
        int cachedVersion,
        @Local(argsOnly = true, ordinal = 0) int newVersion,
        @Local(argsOnly = true) GpuTexture newDepthTexture
    ) {
        // Versions belong to individual Minecraft targets, not the pipeline: two equally sized views
        // commonly have the same version. Compare Java texture identity, not a reusable OpenGL name.
        // Alter only the comparison; Iris still stores the real version and does not resize color targets.
        return currentDepthTexture != newDepthTexture ? newVersion ^ 1 : cachedVersion;
    }

    @WrapOperation(
        method = "resizeIfNeeded",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;addDepthAttachment(Lcom/mojang/renderpearl/api/textures/GpuTexture;)V"
        )
    )
    private void ip_preserveCopiedDepthAttachments(
        GlFramebuffer framebuffer, GpuTexture depthTexture, Operation<Void> original,
        @Local(argsOnly = true) GpuTexture newDepthTexture
    ) {
        // Iris 1.11.7 also includes these copy destinations in its world-depth reattachment loop.
        // Leave their own attachments alone, including their legitimate replacements on a resize.
        if ((framebuffer == noTranslucentsDestFb || framebuffer == noHandDestFb)
            && depthTexture == newDepthTexture) {
            return;
        }
        original.call(framebuffer, depthTexture);
    }
}
