package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

/** Vanilla packet construction shared by both loaders. */
public final class PlayNetworking {
    public record ServerContext(ServerPlayer player) {}
    public record ClientContext() {}

    public static Packet<ClientCommonPacketListener> createClientboundPacket(CustomPacketPayload payload) {
        return new ClientboundCustomPayloadPacket(payload);
    }

    public static Packet<ServerCommonPacketListener> createServerboundPacket(CustomPacketPayload payload) {
        return new ServerboundCustomPayloadPacket(payload);
    }

    private PlayNetworking() {}
}
