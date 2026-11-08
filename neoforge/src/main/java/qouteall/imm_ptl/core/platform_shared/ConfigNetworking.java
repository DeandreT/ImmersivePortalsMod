package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IServerConfigurationPacketListenerExtension;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import qouteall.imm_ptl.core.mixin.common.chunk_sync.IEServerCommonPacketListenerImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native configuration transport, retaining the shared version/config policy. */
public final class ConfigNetworking {
    public record ClientContext(Consumer<CustomPacketPayload> replySender) {
        public void reply(CustomPacketPayload payload) { replySender.accept(payload); }
    }
    public record ServerContext(ServerConfigurationPacketListenerImpl packetListener) {}

    private static final List<Consumer<PayloadRegistrar>> PAYLOADS = new ArrayList<>();
    static final List<CustomPacketPayload.Type<?>> CLIENTBOUND_TYPES = new ArrayList<>();
    private static final Map<CustomPacketPayload.Type<?>, BiConsumer<?, ServerContext>> SERVER_HANDLERS = new HashMap<>();
    static final Map<CustomPacketPayload.Type<?>, BiConsumer<?, ClientContext>> CLIENT_HANDLERS = new HashMap<>();
    private static final List<BiConsumer<ServerConfigurationPacketListenerImpl, MinecraftServer>> CONFIGURE = new ArrayList<>();
    private static final ThreadLocal<RegisterConfigurationTasksEvent> ACTIVE_CONFIGURATION = new ThreadLocal<>();
    static final List<Runnable> LOGIN_INIT = new ArrayList<>();
    static final List<Runnable> JOIN = new ArrayList<>();

    static void install(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            PayloadRegistrar registrar = event.registrar("1").optional();
            PAYLOADS.forEach(registration -> registration.accept(registrar));
        });
        modBus.addListener((RegisterConfigurationTasksEvent event) -> {
            ServerConfigurationPacketListenerImpl listener = (ServerConfigurationPacketListenerImpl) event.getListener();
            MinecraftServer server = ((IEServerCommonPacketListenerImpl) listener).ip_getServer();
            ACTIVE_CONFIGURATION.set(event);
            try {
                CONFIGURE.forEach(callback -> callback.accept(listener, server));
            }
            finally {
                ACTIVE_CONFIGURATION.remove();
            }
        });
    }

    public static <T extends CustomPacketPayload> void registerClientbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec
    ) {
        CLIENTBOUND_TYPES.add(type);
        PAYLOADS.add(registrar -> registrar.configurationToClient(type, codec));
    }

    public static <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec
    ) {
        PAYLOADS.add(registrar -> registrar.configurationToServer(type, codec, (payload, context) ->
            serverReceiver(type).accept(payload, new ServerContext((ServerConfigurationPacketListenerImpl) context.listener()))));
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> BiConsumer<T, ServerContext> serverReceiver(CustomPacketPayload.Type<T> type) {
        return (BiConsumer<T, ServerContext>) java.util.Objects.requireNonNull(SERVER_HANDLERS.get(type));
    }

    public static <T extends CustomPacketPayload> void registerServerReceiver(CustomPacketPayload.Type<T> type, BiConsumer<T, ServerContext> handler) {
        SERVER_HANDLERS.put(type, handler);
    }

    public static <T extends CustomPacketPayload> void registerClientReceiver(CustomPacketPayload.Type<T> type, BiConsumer<T, ClientContext> handler) {
        CLIENT_HANDLERS.put(type, handler);
    }

    public static boolean canSend(ServerConfigurationPacketListenerImpl listener, CustomPacketPayload.Type<?> type) {
        return listener.hasChannel(type.id());
    }

    public static void registerConfigure(BiConsumer<ServerConfigurationPacketListenerImpl, MinecraftServer> callback) { CONFIGURE.add(callback); }
    public static void addTask(ServerConfigurationPacketListenerImpl listener, ConfigurationTask task) {
        RegisterConfigurationTasksEvent event = ACTIVE_CONFIGURATION.get();
        if (event == null || event.getListener() != listener) {
            throw new IllegalStateException("Configuration tasks must be registered during the configuration callback");
        }
        event.register(task);
    }
    public static void completeTask(ServerConfigurationPacketListenerImpl listener, ConfigurationTask.Type type) {
        ((IServerConfigurationPacketListenerExtension) listener).finishCurrentTask(type);
    }
    public static void registerLoginInit(Runnable callback) { LOGIN_INIT.add(callback); }
    public static void registerJoin(Runnable callback) { JOIN.add(callback); }

    private ConfigNetworking() {}
}
