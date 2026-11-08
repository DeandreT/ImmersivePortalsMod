package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import qouteall.imm_ptl.core.commands.ClientDebugCommand;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

@OnlyIn(Dist.CLIENT)
public final class ClientPlatformHooks {
    private static final Map<CustomPacketPayload.Type<?>, BiConsumer<?, PlayNetworking.ClientContext>> HANDLERS = new HashMap<>();

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) ->
            ClientTickEvents.END_CLIENT_TICK.invoker().accept(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ConfigNetworking.LOGIN_INIT.forEach(Runnable::run));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> ConfigNetworking.JOIN.forEach(Runnable::run));
        registerClientReceiver(PacketRedirection.Payload.TYPE,
            (payload, context) -> payload.handle(Minecraft.getInstance().getConnection()));
    }

    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        PlatformHooks.CLIENTBOUND_TYPES.forEach(type -> registerPlayHandler(event, type));
        ConfigNetworking.CLIENTBOUND_TYPES.forEach(type -> registerConfigHandler(event, type));
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerPlayHandler(RegisterClientPayloadHandlersEvent event, CustomPacketPayload.Type<T> type) {
        event.register(type, (payload, context) -> {
            BiConsumer<T, PlayNetworking.ClientContext> receiver = (BiConsumer<T, PlayNetworking.ClientContext>)
                java.util.Objects.requireNonNull(HANDLERS.get(type), "Missing client payload receiver " + type.id());
            receiver.accept(payload, new PlayNetworking.ClientContext());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerConfigHandler(RegisterClientPayloadHandlersEvent event, CustomPacketPayload.Type<T> type) {
        event.register(type, (payload, context) -> {
            BiConsumer<T, ConfigNetworking.ClientContext> receiver = (BiConsumer<T, ConfigNetworking.ClientContext>)
                java.util.Objects.requireNonNull(ConfigNetworking.CLIENT_HANDLERS.get(type));
            receiver.accept(payload, new ConfigNetworking.ClientContext(context::reply));
        });
    }

    public static void registerCommands() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> ClientDebugCommand.register(event.getDispatcher()));
    }

    public static <T extends Entity> void registerRenderer(EntityType<? extends T> type, EntityRendererProvider<T> provider) {
        EntityRenderers.register(type, provider);
    }

    public static <T extends CustomPacketPayload> void registerClientReceiver(CustomPacketPayload.Type<T> type, BiConsumer<T, PlayNetworking.ClientContext> handler) {
        HANDLERS.put(type, handler);
    }

    public static void postChunkLoad(LevelChunk chunk) { NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false)); }
    public static void postChunkUnload(LevelChunk chunk) { NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk)); }

    private ClientPlatformHooks() {}
}
