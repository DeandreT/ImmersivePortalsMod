package qouteall.imm_ptl.core.platform_shared;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qouteall.imm_ptl.core.commands.ClientDebugCommand;

import java.util.function.BiConsumer;

@Environment(EnvType.CLIENT)
public final class ClientPlatformHooks {
    public static void init() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
            client -> ClientTickEvents.END_CLIENT_TICK.invoker().accept(client));
    }

    public static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> ClientDebugCommand.register(dispatcher));
    }

    public static <T extends net.minecraft.world.entity.Entity> void registerRenderer(
        net.minecraft.world.entity.EntityType<? extends T> type,
        net.minecraft.client.renderer.entity.EntityRendererProvider<T> provider
    ) {
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(type, provider);
    }

    public static void postChunkLoad(net.minecraft.world.level.chunk.LevelChunk chunk) {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.invoker()
            .onChunkLoad((net.minecraft.client.multiplayer.ClientLevel) chunk.getLevel(), chunk);
    }

    public static void postChunkUnload(net.minecraft.world.level.chunk.LevelChunk chunk) {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_UNLOAD.invoker()
            .onChunkUnload((net.minecraft.client.multiplayer.ClientLevel) chunk.getLevel(), chunk);
    }

    public static <T extends CustomPacketPayload> void registerClientReceiver(
        CustomPacketPayload.Type<T> type, BiConsumer<T, PlayNetworking.ClientContext> handler
    ) {
        ClientPlayNetworking.registerGlobalReceiver(type,
            (payload, context) -> handler.accept(payload, new PlayNetworking.ClientContext()));
    }

    private ClientPlatformHooks() {}
}
