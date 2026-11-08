package qouteall.dimlib.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Fabric has no additional level-load bookkeeping outside the shared implementation. */
public final class DimLibPlatform {
    private DimLibPlatform() {}

    public static void onDimensionLoad(ServerLevel level) {}

    public static void onDimensionUnload(ServerLevel level) {}

    public static boolean canSendDimensionSync(ServerPlayer player) {
        // Preserve the early-login send ordering. Fabric/vanilla peers can ignore
        // unknown custom payloads without NeoForge's channel-negotiation guard.
        return true;
    }
}
