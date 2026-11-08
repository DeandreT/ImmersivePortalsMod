package qouteall.imm_ptl.core.platform_shared;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.serialization.Codec;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.NewDatapackRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.platform_specific.mixin.common.RegistrationNativeEntityAccessor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGeneration;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;
import qouteall.imm_ptl.neoforge.PortalSpawnEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native event bus, registry, attachment and payload implementation. */
public final class PlatformHooks {
    private static final List<Consumer<RegisterEvent>> REGISTRATIONS = new ArrayList<>();
    private static final List<Consumer<NewDatapackRegistryEvent>> DATA_REGISTRIES = new ArrayList<>();
    private static final Map<ResourceKey<?>, Codec<?>> DATA_CODECS = new HashMap<>();
    private static final List<Consumer<PayloadRegistrar>> PAYLOADS = new ArrayList<>();
    static final List<CustomPacketPayload.Type<?>> CLIENTBOUND_TYPES = new ArrayList<>();
    private static final Map<CustomPacketPayload.Type<?>, BiConsumer<?, PlayNetworking.ServerContext>> SERVER_HANDLERS = new HashMap<>();
    private static boolean sharedInitialized;
    private static boolean datapackDeclarationsClosed;

    public static void install(IEventBus modBus, Runnable initializeShared) {
        modBus.addListener((RegisterEvent event) -> {
            // NeoForge opens all built-in registries immediately before these events,
            // not during mod construction. Prepare the shared eager objects once,
            // before processing even the first registry's queued registrations.
            if (!sharedInitialized) {
                if (!datapackDeclarationsClosed) { throw new IllegalStateException("Datapack registries were not declared before built-in registration"); }
                sharedInitialized = true;
                initializeShared.run();
            }
            REGISTRATIONS.forEach(registration -> registration.accept(event));
        });
        modBus.addListener((NewDatapackRegistryEvent event) -> {
            // This earlier phase must not initialize PeripheralModMain's blocks/items.
            // These codecs create only private codec registries, not intrusive objects.
            registerDynamicRegistry(CustomPortalGeneration.REGISTRY_KEY, CustomPortalGeneration.CODEC);
            registerDynamicRegistry(CustomPortalGeneration.LEGACY_REGISTRY_KEY, CustomPortalGeneration.CODEC);
            DATA_REGISTRIES.forEach(registration -> registration.accept(event));
            datapackDeclarationsClosed = true;
        });
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            // The mod's shared configuration handshake enforces its user-configurable
            // peer/version policy. NeoForge's channels are therefore optional.
            PayloadRegistrar registrar = event.registrar("1").optional();
            PAYLOADS.forEach(registration -> registration.accept(registrar));
        });
        ConfigNetworking.install(modBus);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
            ServerTickEvents.END_SERVER_TICK.invoker().accept(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
            ServerLifecycleEvents.SERVER_STARTED.invoker().accept(event.getServer()));
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                var server = event.getPlayerList().getServer();
                ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker().onReload(server, server.getResourceManager(), true);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> {
            var player = event.getEntity();
            InteractionResult result = AttackBlockCallback.EVENT.invoker().interact(
                player, player.level(), event.getHand(), event.getPos(), event.getFace());
            if (result != InteractionResult.PASS) {
                event.setCanceled(true);
            }
        });
    }

    public static void init() {
        // Event bridges must be installed in the native mod constructor, before
        // shared registration starts. No second registration is needed here.
    }

    public static void registerCommands() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
            PortalCommand.register(event.getDispatcher(), event.getBuildContext()));
    }

    public static <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> void registerArgumentType(
        Identifier id, Class<A> type, ArgumentTypeInfo<A, T> info
    ) {
        ArgumentTypeInfos.registerByClass(type, info);
        register(BuiltInRegistries.COMMAND_ARGUMENT_TYPE, id, info);
    }

    public static <T> void registerDynamicRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        Codec<?> previous = DATA_CODECS.get(key);
        if (previous != null) {
            if (previous != codec) { throw new IllegalStateException("Conflicting datapack registry codec: " + key); }
            return;
        }
        if (datapackDeclarationsClosed) { throw new IllegalStateException("Datapack registry declared after its native registration event: " + key); }
        DATA_CODECS.put(key, codec);
        DATA_REGISTRIES.add(event -> event.worldRegistry(key, codec));
    }

    public static <T, V extends T> V register(Registry<T> registry, String id, V value) {
        return register(registry, Identifier.parse(id), value);
    }

    public static <T, V extends T> V register(Registry<T> registry, Identifier id, V value) {
        REGISTRATIONS.add(event -> event.register(registry.key(), id, () -> value));
        return value;
    }

    public static <T extends CustomPacketPayload> void registerClientbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec
    ) {
        CLIENTBOUND_TYPES.add(type);
        PAYLOADS.add(registrar -> registrar.playToClient(type, codec));
    }

    public static <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec
    ) {
        PAYLOADS.add(registrar -> registrar.playToServer(type, codec, (payload, context) ->
            serverReceiver(type).accept(payload, new PlayNetworking.ServerContext((ServerPlayer) context.player()))));
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> BiConsumer<T, PlayNetworking.ServerContext> serverReceiver(CustomPacketPayload.Type<T> type) {
        return (BiConsumer<T, PlayNetworking.ServerContext>) java.util.Objects.requireNonNull(
            SERVER_HANDLERS.get(type), "Missing server payload receiver " + type.id());
    }

    public static <T extends CustomPacketPayload> void registerServerReceiver(
        CustomPacketPayload.Type<T> type, BiConsumer<T, PlayNetworking.ServerContext> handler
    ) { SERVER_HANDLERS.put(type, handler); }

    public static void syncChunkAttachments(ServerGamePacketListenerImpl listener, LevelChunk chunk) {
        // The portal chunk sender replaces vanilla's sender, including its watch
        // notification. Publishing Sent preserves attachments and other mods' hooks.
        ServerLevel world = (ServerLevel) chunk.getLevel();
        world.debugSynchronizers().startTrackingChunk(listener.player, chunk.getPos());
        EventHooks.fireChunkSent(listener.player, chunk, world);
    }

    public static net.minecraft.network.protocol.Packet<?> createChunkPacket(ServerLevel world, LevelChunk chunk) {
        return chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(
            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                chunk, world.getLightEngine(), null, null));
    }

    public static void onChunkWatch(ServerPlayer player, ServerLevel world, LevelChunk chunk) {
        EventHooks.fireChunkWatch(player, chunk, world);
    }

    public static void onChunkUnwatch(ServerPlayer player, ServerLevel world, net.minecraft.world.level.ChunkPos pos) {
        PacketRedirection.withForceRedirect(world, () -> EventHooks.fireChunkUnWatch(player, pos, world));
    }

    public static void onPlayerChangedDimension(ServerPlayer player, ServerLevel from, ServerLevel to) {
        if (from != to) {
            EventHooks.firePlayerChangedDimensionEvent(player, from.dimension(), to.dimension());
        }
    }

    public static void postPortalSpawn(PortalGenInfo info) {
        NeoForge.EVENT_BUS.post(new PortalSpawnEvent(info));
    }

    public static boolean allowDimensionTravel(Entity entity, Portal portal) {
        // Portal eligibility is queried during collision/visibility checks too.
        // Publish the cancellable travel event only at the transaction preflight.
        return true;
    }

    public static boolean canTravelToDimension(Entity entity, ServerLevel destination) {
        return CommonHooks.onTravelToDimension(entity, destination.dimension());
    }

    public static boolean needsEntityTracker(Entity entity) {
        return !(entity instanceof net.neoforged.neoforge.entity.PartEntity<?> )
            && entity.getType().clientTrackingRange() != 0;
    }

    public static List<? extends Entity> registrationParts(Entity entity) {
        var parts = entity.getParts();
        return entity.isMultipartEntity() && parts != null ? java.util.Arrays.asList(parts) : List.of();
    }

    /** NeoForge's independent added-to-level flag, not Fabric's loaded-from-disk metadata. */
    public static boolean registrationMetadataFlag(Entity entity) { return entity.isAddedToLevel(); }
    public static void restoreRegistrationMetadataFlag(Entity entity, boolean added) {
        ((RegistrationNativeEntityAccessor) entity).ip_registrationAddedFlag(added);
    }
    public static void finishRegistrationRestoration(Entity entity, boolean added) {
        try { entity.onAddedToLevel(); }
        finally { restoreRegistrationMetadataFlag(entity, added); }
    }
    public static void finishRegistrationRemoval(Entity entity) {
        // The real tracking callback already invokes onRemovedFromLevel when visible.
        // Do not repeat a possibly throwing foreign callback; repair only the owned
        // base flag, including hidden-section and interrupted-removal cases.
        restoreRegistrationMetadataFlag(entity, false);
    }

    public static <T extends Entity> net.minecraft.world.entity.EntityType.Builder<T> withVelocityUpdates(
        net.minecraft.world.entity.EntityType.Builder<T> builder
    ) { return builder.setShouldReceiveVelocityUpdates(true); }

    private PlatformHooks() {}
}
