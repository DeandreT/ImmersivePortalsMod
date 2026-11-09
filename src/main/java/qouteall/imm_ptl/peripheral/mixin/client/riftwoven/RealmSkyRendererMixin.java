package qouteall.imm_ptl.peripheral.mixin.client.riftwoven;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialLayout;
import qouteall.imm_ptl.peripheral.riftwoven.environment.ViewStateScope;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientEnvironmentAccess;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmSkyCompatibility;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmSkyStateAccess;

@Mixin(SkyRenderer.class)
public abstract class RealmSkyRendererMixin {
    @Unique private final ViewStateScope<CelestialLayout> riftwoven$views = new ViewStateScope<>();

    @Shadow private void renderSun(RenderPass pass, float brightness, PoseStack pose) {}
    @Shadow private void renderMoon(RenderPass pass, MoonPhase phase, float brightness, PoseStack pose) {}

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void riftwoven$extract(ClientLevel level, float partialTick, Camera camera, SkyRenderState state, CallbackInfo ci) {
        // Always overwrite, including vanilla/end/no-sky views and state-pool reuse. Extraction
        // receives the actual view's level; never consult Minecraft.player.level().
        var layout = ((RealmClientEnvironmentAccess) level).riftwoven$clock().layout();
        ((RealmSkyStateAccess) state).riftwoven$layout(layout);
        RealmSkyCompatibility.reportBoundary(layout);
    }

    @WrapMethod(method = "render")
    private void riftwoven$viewScope(GpuBufferSlice fog, SkyRenderState state, Operation<Void> original) {
        try (var ignored = riftwoven$views.enter(((RealmSkyStateAccess) state).riftwoven$layout())) {
            original.call(fog, state);
        }
    }

    @WrapOperation(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSun(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void riftwoven$extraSuns(SkyRenderer renderer, RenderPass pass, float brightness,
        PoseStack pose, Operation<Void> original, @Local(argsOnly = true, ordinal = 0) float angle) {
        original.call(renderer, pass, brightness, pose);
        var layout = riftwoven$views.current();
        if (layout == null) { return; }
        for (int index = 1; index < layout.suns(); index++) {
            var orbit = layout.orbit(index, false, angle);
            pose.pushPose();
            try {
                pose.rotate(Axis.XP, orbit.separationRadians());
                pose.rotate(Axis.ZP, orbit.inclinationRadians());
                // Reuse the real celestial draw, atlas transparency, active render target,
                // validated pipeline, and Iris's per-body phase/cancellation hooks.
                renderSun(pass, brightness, pose);
            } finally {
                pose.popPose();
            }
        }
    }

    @WrapOperation(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void riftwoven$extraMoons(SkyRenderer renderer, RenderPass pass, MoonPhase phase,
        float brightness, PoseStack pose, Operation<Void> original, @Local(argsOnly = true, ordinal = 1) float angle) {
        original.call(renderer, pass, phase, brightness, pose);
        var layout = riftwoven$views.current();
        if (layout == null) { return; }
        for (int index = 1; index < layout.moons(); index++) {
            var orbit = layout.orbit(index, true, angle);
            pose.pushPose();
            try {
                pose.rotate(Axis.XP, orbit.separationRadians());
                pose.rotate(Axis.ZP, orbit.inclinationRadians());
                renderMoon(pass, phase, brightness, pose);
            } finally {
                pose.popPose();
            }
        }
    }
}
