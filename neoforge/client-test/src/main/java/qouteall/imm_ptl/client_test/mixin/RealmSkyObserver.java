package qouteall.imm_ptl.client_test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.client_test.Evidence;
import qouteall.imm_ptl.client_test.SkyExtractionEvidence;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmSkyStateAccess;

/** Read-only observation of the actual packaged sky extraction and ordinary celestial draws. */
@Mixin(SkyRenderer.class)
public abstract class RealmSkyObserver {
    @Unique private boolean ip_nativeClient$realmSky;
    @Unique private int ip_nativeClient$sunDraws;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ip_nativeClient$extractHead(ClientLevel level, float partialTick, Camera camera,
        SkyRenderState state, CallbackInfo ci) {
        String dimension = level.dimension().identifier().toString();
        SkyExtractionEvidence.head(dimension, dimension.equals(Evidence.expectedRealm));
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void ip_nativeClient$extracted(ClientLevel level, float partialTick, Camera camera,
        SkyRenderState state, CallbackInfo ci) {
        var layout = ((RealmSkyStateAccess) state).riftwoven$layout();
        String dimension = level.dimension().identifier().toString();
        SkyExtractionEvidence.returned(dimension, dimension.equals(Evidence.expectedRealm), layout.suns(), layout.moons(), layout.seed());
        if (!dimension.equals(Evidence.expectedRealm)) return;
        if (layout.suns() == 2 && layout.moons() == 1) Evidence.REALM_SKY_EXTRACTIONS.incrementAndGet();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void ip_nativeClient$begin(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
        var layout = ((RealmSkyStateAccess) state).riftwoven$layout();
        ip_nativeClient$realmSky = Evidence.expectedRealm != null && layout.suns() == 2 && layout.moons() == 1;
        ip_nativeClient$sunDraws = 0;
    }

    @Inject(method = "renderSun", at = @At("HEAD"))
    private void ip_nativeClient$sun(RenderPass pass, float brightness, PoseStack pose, CallbackInfo ci) {
        if (ip_nativeClient$realmSky) ip_nativeClient$sunDraws++;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ip_nativeClient$end(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
        if (ip_nativeClient$realmSky && ip_nativeClient$sunDraws == 2) Evidence.REALM_TWO_SUN_DRAWS.incrementAndGet();
        ip_nativeClient$realmSky = false;
    }
}
