package qouteall.imm_ptl.core.platform_shared;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Configuration-phase transport; the handshake's policy and payloads remain shared. */
public final class ConfigNetworking {
    public record ClientContext(Consumer<CustomPacketPayload> replySender) {
        public void reply(CustomPacketPayload payload) { replySender.accept(payload); }
    }

    public record ServerContext(ServerConfigurationPacketListenerImpl packetListener) {}

    public static <T extends CustomPacketPayload> void registerClientbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec
    ) { PayloadTypeRegistry.clientboundConfiguration().register(type, codec); }

    public static <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec
    ) { PayloadTypeRegistry.serverboundConfiguration().register(type, codec); }

    public static <T extends CustomPacketPayload> void registerServerReceiver(
        CustomPacketPayload.Type<T> type, BiConsumer<T, ServerContext> handler
    ) {
        ServerConfigurationNetworking.registerGlobalReceiver(type,
            (payload, context) -> handler.accept(payload, new ServerContext(context.packetListener())));
    }

    @Environment(EnvType.CLIENT)
    public static <T extends CustomPacketPayload> void registerClientReceiver(
        CustomPacketPayload.Type<T> type, BiConsumer<T, ClientContext> handler
    ) {
        ClientConfigurationNetworking.registerGlobalReceiver(type,
            (payload, context) -> handler.accept(payload, new ClientContext(context.responseSender()::sendPacket)));
    }

    public static boolean canSend(ServerConfigurationPacketListenerImpl listener, CustomPacketPayload.Type<?> type) {
        return ServerConfigurationNetworking.canSend(listener, type);
    }

    public static void registerConfigure(BiConsumer<ServerConfigurationPacketListenerImpl, MinecraftServer> callback) {
        ServerConfigurationConnectionEvents.CONFIGURE.register(callback::accept);
    }

    public static void addTask(ServerConfigurationPacketListenerImpl listener, ConfigurationTask task) {
        listener.addTask(task);
    }

    public static void completeTask(ServerConfigurationPacketListenerImpl listener, ConfigurationTask.Type type) {
        listener.completeTask(type);
    }

    @Environment(EnvType.CLIENT)
    public static void registerLoginInit(Runnable callback) {
        ClientLoginConnectionEvents.INIT.register((listener, client) -> callback.run());
    }

    @Environment(EnvType.CLIENT)
    public static void registerJoin(Runnable callback) {
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> callback.run());
    }

    private ConfigNetworking() {}
}
