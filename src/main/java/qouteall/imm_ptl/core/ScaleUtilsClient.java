package qouteall.imm_ptl.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;

/** Camera and local-player scaling are reached only through a physical-client guard. */
@Environment(EnvType.CLIENT)
public final class ScaleUtilsClient {
    public static void onPlayerTeleported(Portal portal) {
        if (portal.hasScaling() && portal.isTeleportChangesScale()) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            Validate.notNull(player, "Player is null");
            ScaleUtils.doScalingForEntity(player, portal);
            IECamera camera = (IECamera) client.gameRenderer.mainCamera();
            camera.ip_setCameraY((float) (camera.ip_getCameraY() * portal.getScaling()),
                (float) (camera.ip_getLastCameraY() * portal.getScaling()));
        }
    }

    private ScaleUtilsClient() {}
}
