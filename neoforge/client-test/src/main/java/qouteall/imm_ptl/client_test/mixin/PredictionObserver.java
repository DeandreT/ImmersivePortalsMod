package qouteall.imm_ptl.client_test.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.client_test.Evidence;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.imm_ptl.core.teleportation.TeleportationUtil;

@Mixin(value = ClientTeleportationManager.class, remap = false)
public class PredictionObserver {
    @Inject(method = "teleportPlayer", at = @At("RETURN"))
    private static void observed(TeleportationUtil.Teleportation teleportation, float partial, CallbackInfo ci) {
        var client = Minecraft.getInstance();
        if (client.level != null && client.level.dimension() == teleportation.portal().getDestDim()) {
            Evidence.PREDICTIONS.add(client.level.dimension().identifier().toString());
        }
    }
}
