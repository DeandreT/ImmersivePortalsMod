package qouteall.dimlib.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import qouteall.dimlib.DimLibNetworking;

/** Publish the lifecycle events NeoForge normally emits around vanilla levels. */
public final class DimLibPlatform {
    private DimLibPlatform() {}

    public static void onDimensionLoad(ServerLevel level) {
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    public static void onDimensionUnload(ServerLevel level) {
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
    }

    public static boolean canSendDimensionSync(ServerPlayer player) {
        return player.connection.hasChannel(DimLibNetworking.DimSyncPacket.TYPE);
    }
}
