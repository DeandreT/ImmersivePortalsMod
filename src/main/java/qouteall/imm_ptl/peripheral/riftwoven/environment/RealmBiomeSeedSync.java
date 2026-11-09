package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_shared.ServerTickEvents;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

/** Publishes vanilla-equivalent obfuscated biome metadata, never raw generation seeds. */
public final class RealmBiomeSeedSync {
    private static final Map<ServerPlayer, Map<ResourceKey<Level>, SentSeed>> SENT = new IdentityHashMap<>();

    private RealmBiomeSeedSync() {}

    public static void init() {
        IPGlobal.SERVER_CLEANUP_EVENT.register(server ->
            SENT.keySet().removeIf(player -> player.level().getServer() == server));
        DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.register((server, dimensions) -> sendAll(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SENT.keySet().removeIf(player -> player.level().getServer() == server
                && server.getPlayerList().getPlayers().stream().noneMatch(online -> online == player));
            sendAll(server);
        });
    }

    private static void sendAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }

    /** Also call after the login packet and before initial portal/chunk synchronization. */
    public static void send(ServerPlayer player) {
        var server = player.level().getServer();
        Map<ResourceKey<Level>, SentSeed> sent = SENT.computeIfAbsent(player, ignored -> new HashMap<>());
        sent.keySet().retainAll(server.levelKeys());
        for (ServerLevel level : server.getAllLevels()) {
            // Use the actual server sampling seed, including independent realm overrides.
            // It is already hashed; hashing a second time would change biome zoom boundaries.
            long obfuscatedSeed = level.getBiomeManager().biomeZoomSeed;
            var previous = sent.get(level.dimension());
            if (previous == null || previous.level() != level || previous.obfuscatedSeed() != obfuscatedSeed) {
                McRemoteProcedureCall.tellClientToInvoke(player,
                    "qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmEnvironmentRemoteCallable.receiveBiomeSeed",
                    level.dimension(), obfuscatedSeed);
                sent.put(level.dimension(), new SentSeed(level, obfuscatedSeed));
            }
        }
    }

    private record SentSeed(ServerLevel level, long obfuscatedSeed) {}
}
