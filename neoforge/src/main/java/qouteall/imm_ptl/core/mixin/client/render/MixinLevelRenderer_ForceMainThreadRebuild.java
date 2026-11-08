package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.AdditionalSectionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;

import java.util.List;

/** Native section compilation retains NeoForge's additional geometry in both scheduling modes. */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_ForceMainThreadRebuild {
    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileAsync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Ljava/util/List;)V"))
    private void wrapCompileAsync(
        SectionRenderDispatcher.RenderSection section, RenderSectionRegion region,
        List<AdditionalSectionRenderer> additionalRenderers, Operation<Void> original
    ) {
        // A portal view can include sections outside the current ViewArea grid.
        if (section == null) { return; }
        if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) {
            section.compileSync(region, additionalRenderers);
        }
        else { original.call(section, region, additionalRenderers); }
    }

    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Ljava/util/List;)V"))
    private void wrapCompileSync(
        SectionRenderDispatcher.RenderSection section, RenderSectionRegion region,
        List<AdditionalSectionRenderer> additionalRenderers, Operation<Void> original
    ) {
        if (section != null) { original.call(section, region, additionalRenderers); }
    }
}
