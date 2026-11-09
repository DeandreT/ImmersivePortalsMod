package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.platform_shared.ServerTickEvents;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmChunkGenerator;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

/** Loader-neutral realm simulation and dimension-addressed synchronization. */
public final class RealmEnvironment {
    private RealmEnvironment() {}

    public static boolean isRealm(ServerLevel level) {
        return level.getChunkSource() != null
            && level.getChunkSource().getGenerator() instanceof RealmChunkGenerator;
    }

    public static RealmChunkGenerator generator(ServerLevel level) {
        return (RealmChunkGenerator) level.getChunkSource().getGenerator();
    }

    public static RealmEnvironmentState state(ServerLevel level) {
        return ((RealmEnvironmentAccess) level).riftwoven$environment();
    }

    /** Invoke once from the common initialization path after shared platform events are ready. */
    public static void init() {
        RealmBiomeSeedSync.init();
        Map<ServerPlayer, Set<ResourceKey<Level>>> lastVisible = new IdentityHashMap<>();
        qouteall.imm_ptl.core.IPGlobal.SERVER_CLEANUP_EVENT.register(server ->
            lastVisible.keySet().removeIf(player -> player.level().getServer() == server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            lastVisible.keySet().retainAll(server.getPlayerList().getPlayers());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Set<ResourceKey<Level>> visible = new HashSet<>(ImmPtlChunkTracking.getVisibleDimensions(player));
                visible.add(player.level().dimension());
                Set<ResourceKey<Level>> previous = lastVisible.getOrDefault(player, Set.of());
                for (var dimension : visible) {
                    ServerLevel level = server.getLevel(dimension);
                    if (level != null && isRealm(level)
                        && (server.getTickCount() % 20 == 0 || !previous.contains(dimension))) {
                        send(player, level);
                    }
                }
                lastVisible.put(player, visible);
            }
        });
    }

    public static ServerClockManager createClocks(ServerLevel level) {
        // Do not put this manager in global saved data or call its mutating methods: those
        // broadcast to the entire server. Only our physical clock counters are persisted.
        ServerClockManager clocks = ServerClockManager.TYPE.constructor().get();
        clocks.init(level.getServer());
        updateClocks(level, clocks);
        return clocks;
    }

    public static void updateClocks(ServerLevel level, ServerClockManager clocks) {
        var profile = generator(level).profile();
        var state = state(level);
        // Non-default timeline clocks retain vanilla semantics without sharing mutable instances.
        level.getServer().clockManager().packState().clocks().forEach(
            (definition, value) -> clocks.getInstance(definition).loadFrom(value)
        );
        level.dimensionType().defaultClock().ifPresent(definition -> {
            double time = RealmEnvironmentMath.clockTime(state.timeTicks(), profile.cycleTicks(), profile.daylightTicks());
            long whole = (long) Math.floor(time);
            boolean paused = !level.getGameRules().get(GameRules.ADVANCE_TIME);
            float rate = RealmEnvironmentMath.clockRate(state.timeTicks(), profile.cycleTicks(), profile.daylightTicks());
            clocks.getInstance(definition).loadFrom(new ClockState(whole, (float) (time - whole), rate, paused));
        });
    }

    public static void advance(ServerLevel level) {
        var state = state(level);
        boolean runs = level.tickRateManager().runsNormally();
        state.advance(level.getGameTime(), runs && level.getGameRules().get(GameRules.ADVANCE_TIME),
            runs && level.getGameRules().get(GameRules.ADVANCE_WEATHER));
        updateClocks(level, level.clockManager());
    }

    public static void prepareWeather(ServerLevel level) {
        var generator = generator(level);
        var state = state(level);
        var event = RealmEnvironmentMath.weather(generator.realmSeed(), state.weatherTicks(), generator.profile().weather().name());
        var weather = level.getWeatherData();
        weather.setRaining(!state.weatherSuppressed() && event.raining());
        weather.setThundering(!state.weatherSuppressed() && event.thundering());
        // Keep vanilla's smoothing and dimension-scoped broadcasts; prevent its random timers
        // from choosing a second, inconsistent weather event.
        weather.setClearWeatherTime(0);
        weather.setRainTime(2);
        weather.setThunderTime(2);
    }

    public static void send(ServerPlayer player, ServerLevel level) {
        sendBiomeSeeds(player);
        var generator = generator(level);
        var profile = generator.profile();
        var state = state(level);
        // A string names the cold client endpoint; common bytecode has no client verifier types.
        McRemoteProcedureCall.tellClientToInvoke(player,
            "qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmEnvironmentRemoteCallable.receive",
            level.dimension(), level.getGameTime(), state.timeTicks(),
            profile.cycleTicks(), profile.daylightTicks(), profile.suns(), profile.moons(),
            level.getBiomeManager().biomeZoomSeed, level.getGameRules().get(GameRules.ADVANCE_TIME));
        PacketRedirection.sendRedirectedMessage(player, level.dimension(), new ClientboundGameEventPacket(
            level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        PacketRedirection.sendRedirectedMessage(player, level.dimension(), new ClientboundGameEventPacket(
            ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1)));
        PacketRedirection.sendRedirectedMessage(player, level.dimension(), new ClientboundGameEventPacket(
            ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1)));
    }

    /** Send initial per-dimension metadata after login, before portal/chunk packets. */
    public static void sendBiomeSeeds(ServerPlayer player) {
        RealmBiomeSeedSync.send(player);
    }
}
