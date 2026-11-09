package qouteall.imm_ptl.peripheral.mixin.client.riftwoven;

import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialLayout;
import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialViewState;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmSkyStateAccess;

@Mixin(SkyRenderState.class)
public abstract class RealmSkyRenderStateMixin implements RealmSkyStateAccess {
    @Unique private final CelestialViewState riftwoven$view = new CelestialViewState();
    @Override public CelestialLayout riftwoven$layout() { return riftwoven$view.layout(); }
    @Override public void riftwoven$layout(CelestialLayout layout) { riftwoven$view.extract(layout); }

    @Inject(method = "reset", at = @At("RETURN"))
    private void riftwoven$clearSnapshot(CallbackInfo ci) {
        riftwoven$view.reset();
    }
}
