package qouteall.dimlib;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DimSyncPacketTest {
    @Test
    void roundTripsVanillaAndDynamicDimensionTypes() {
        CompoundTag tag = new CompoundTag();
        tag.putString("minecraft:overworld", "minecraft:overworld");
        tag.putString("dimlib_test:dynamic", "minecraft:the_nether");
        var packet = new DimLibNetworking.DimSyncPacket(tag);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DimLibNetworking.DimSyncPacket.CODEC.encode(buffer, packet);
            var decoded = DimLibNetworking.DimSyncPacket.CODEC.decode(buffer);
            assertEquals(packet.type(), decoded.type());
            assertEquals(Map.of(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld")),
                ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("minecraft:overworld")),
                ResourceKey.create(Registries.DIMENSION, Identifier.parse("dimlib_test:dynamic")),
                ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("minecraft:the_nether"))
            ), decoded.toMap());
            assertEquals(0, buffer.readableBytes());
        }
        finally {
            buffer.release();
        }
    }

    @Test
    void synchronizedClientInfoIsClearedWhenDisconnecting() {
        ClientDimensionInfo.cleanup();
        try {
            assertThrows(IllegalStateException.class, ClientDimensionInfo::getDimensionIds);
            CompoundTag tag = new CompoundTag();
            tag.putString("dimlib_test:dynamic", "minecraft:overworld");
            var dimensions = new DimLibNetworking.DimSyncPacket(tag).toMap();
            ClientDimensionInfo.accept(dimensions);
            assertEquals(dimensions, ClientDimensionInfo.getDimensionIdToType());
            assertEquals(dimensions.keySet(), ClientDimensionInfo.getDimensionIds());
        }
        finally {
            ClientDimensionInfo.cleanup();
        }
        assertThrows(IllegalStateException.class, ClientDimensionInfo::getDimensionIdToType);
    }
}
