package qouteall.imm_ptl.core.collision;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.q_misc_util.my_util.LimitedLogger;

/** Client world iteration and HUD state must not be verified with common collision code. */
@Environment(EnvType.CLIENT)
public final class CollisionHelperClient {
    private static final LimitedLogger LIMITED_LOGGER = new LimitedLogger(20);
    private static boolean thisTickStagnate;
    private static boolean lastTickStagnate;

    public static void init() { IPGlobal.POST_CLIENT_TICK_EVENT.register(CollisionHelperClient::tick); }

    private static void tick() {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                CollisionHelper.updateCollidingPortalForWorld(world, 0);
            }
        }
        if (thisTickStagnate && lastTickStagnate) {
            Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("imm_ptl.stagnate_movement"), false);
        }
        else if (!thisTickStagnate && lastTickStagnate) {
            Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(""), false);
        }
        lastTickStagnate = thisTickStagnate;
        thisTickStagnate = false;
    }

    public static void informStagnant() {
        thisTickStagnate = true;
        LIMITED_LOGGER.log("client movement stagnated");
    }

    private CollisionHelperClient() {}
}
