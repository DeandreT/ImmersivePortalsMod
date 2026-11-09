package qouteall.imm_ptl.core.platform_shared;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentChange;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentSync;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import qouteall.imm_ptl.core.commands.PortalCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Fabric implementation. The native NeoForge module supplies its own implementation. */
public final class PlatformHooks {
    public static void init() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
            server -> ServerTickEvents.END_SERVER_TICK.invoker().accept(server));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
            server -> ServerLifecycleEvents.SERVER_STARTED.invoker().accept(server));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
            (server, resources, success) -> ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker()
                .onReload(server, resources, success));
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(
            (player, world, hand, pos, direction) -> AttackBlockCallback.EVENT.invoker()
                .interact(player, world, hand, pos, direction));
    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> {
            PortalCommand.register(dispatcher, context);
            qouteall.imm_ptl.peripheral.riftwoven.RealmCommands.register(dispatcher);
        });
    }

    public static <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> void registerArgumentType(
        Identifier id, Class<A> type, ArgumentTypeInfo<A, T> info
    ) {
        ArgumentTypeRegistry.registerArgumentType(id, type, info);
    }

    public static <T> void registerDynamicRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        DynamicRegistries.register(key, codec);
    }

    public static <T, V extends T> V register(Registry<T> registry, String id, V value) {
        return register(registry, Identifier.parse(id), value);
    }

    public static <T, V extends T> V register(Registry<T> registry, Identifier id, V value) {
        return Registry.register(registry, id, value);
    }

    public static <T extends CustomPacketPayload> void registerClientbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec
    ) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }

    public static <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec
    ) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
    }

    public static <T extends CustomPacketPayload> void registerServerReceiver(
        CustomPacketPayload.Type<T> type, BiConsumer<T, PlayNetworking.ServerContext> handler
    ) {
        ServerPlayNetworking.registerGlobalReceiver(type,
            (payload, context) -> handler.accept(payload, new PlayNetworking.ServerContext(context.player())));
    }

    public static void syncChunkAttachments(ServerGamePacketListenerImpl listener, LevelChunk chunk) {
        List<AttachmentChange> changes = new ArrayList<>();
        ((AttachmentTargetImpl) chunk).fabric_computeInitialSyncChanges(listener.player, changes::add);
        if (!changes.isEmpty()) {
            AttachmentSync.trySync(changes, listener.player);
        }
    }

    public static net.minecraft.network.protocol.Packet<?> createChunkPacket(
        net.minecraft.server.level.ServerLevel world, LevelChunk chunk
    ) {
        return new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
            chunk, world.getLightEngine(), null, null);
    }

    public static void onChunkWatch(net.minecraft.server.level.ServerPlayer player,
        net.minecraft.server.level.ServerLevel world, LevelChunk chunk) {}

    public static void onChunkUnwatch(net.minecraft.server.level.ServerPlayer player,
        net.minecraft.server.level.ServerLevel world, net.minecraft.world.level.ChunkPos pos) {}

    public static void onPlayerChangedDimension(net.minecraft.server.level.ServerPlayer player,
        net.minecraft.server.level.ServerLevel from, net.minecraft.server.level.ServerLevel to) {}

    public static void postPortalSpawn(qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo info) {}

    public static boolean allowDimensionTravel(net.minecraft.world.entity.Entity entity,
        qouteall.imm_ptl.core.portal.Portal portal) { return true; }

    public static boolean canTravelToDimension(net.minecraft.world.entity.Entity entity,
        net.minecraft.server.level.ServerLevel destination) { return true; }

    public static boolean needsEntityTracker(net.minecraft.world.entity.Entity entity) {
        return !(entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart)
            && entity.getType().clientTrackingRange() != 0;
    }

    public static List<? extends net.minecraft.world.entity.Entity> registrationParts(net.minecraft.world.entity.Entity entity) {
        return entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon
            ? java.util.Arrays.asList(dragon.getSubEntities()) : List.of();
    }

    /** Fabric's load metadata is changed before ALLOW_LOAD, including a cancelled add. */
    public static boolean registrationMetadataFlag(net.minecraft.world.entity.Entity entity) {
        return ((net.fabricmc.fabric.api.event.lifecycle.v1.EntityLoadData) entity).isLoadedFromDisk();
    }
    public static void restoreRegistrationMetadataFlag(net.minecraft.world.entity.Entity entity, boolean loadedFromDisk) {
        ((net.fabricmc.fabric.impl.event.lifecycle.EntityLoadDataSetter) entity).fabric_setLoadedFromDisk(loadedFromDisk);
    }
    public static void finishRegistrationRestoration(net.minecraft.world.entity.Entity entity, boolean loadedFromDisk) {
        restoreRegistrationMetadataFlag(entity, loadedFromDisk);
    }
    public static void finishRegistrationRemoval(net.minecraft.world.entity.Entity entity) {}

    public static <T extends net.minecraft.world.entity.Entity> net.minecraft.world.entity.EntityType.Builder<T> withVelocityUpdates(
        net.minecraft.world.entity.EntityType.Builder<T> builder
    ) { return builder.alwaysUpdateVelocity(true); }

    private PlatformHooks() {}
}
