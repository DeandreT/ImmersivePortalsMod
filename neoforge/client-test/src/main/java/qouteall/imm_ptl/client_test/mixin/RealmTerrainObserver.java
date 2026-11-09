package qouteall.imm_ptl.client_test.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.client_test.RealmTerrainDiagnostics;
import qouteall.imm_ptl.client_test.BaselineMarkerDiagnostics;

/** Observes normal opaque terrain submission; never changes visibility, mesh, projection or clipping. */
@Mixin(LevelRenderer.class)
abstract class RealmTerrainObserver {
    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void observeTerrain(CallbackInfo ci) {
        RealmTerrainDiagnostics.observeMainDraw((LevelRenderer) (Object) this);
        BaselineMarkerDiagnostics.observeDraw((LevelRenderer) (Object) this);
    }
}
