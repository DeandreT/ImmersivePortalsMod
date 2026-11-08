package qouteall.dimlib;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DimLibNetworking {
    public static final Logger LOGGER = LoggerFactory.getLogger(DimLibNetworking.class);
    
    public static record DimSyncPacket(
        CompoundTag dimIdToTypeIdTag
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DimSyncPacket> TYPE =
            new Type<>(Identifier.parse("dimlib:dim_sync"));
        
        public static final StreamCodec<FriendlyByteBuf, DimSyncPacket> CODEC =
            StreamCodec.of((b, p) -> p.write(b), DimSyncPacket::read);
        
        public static DimSyncPacket read(FriendlyByteBuf buf) {
            CompoundTag compoundTag = buf.readNbt();
            return new DimSyncPacket(compoundTag);
        }
        
        public void write(FriendlyByteBuf buf) {
            buf.writeNbt(dimIdToTypeIdTag);
        }
        
        public static DimSyncPacket createPacket(MinecraftServer server) {
            RegistryAccess registryManager = server.registryAccess();
            Registry<DimensionType> dimensionTypes = registryManager.lookupOrThrow(Registries.DIMENSION_TYPE);
            
            CompoundTag dimIdToDimTypeId = new CompoundTag();
            for (ServerLevel world : server.getAllLevels()) {
                ResourceKey<Level> dimId = world.dimension();
                
                DimensionType dimType = world.dimensionType();
                Identifier dimTypeId = dimensionTypes.getKey(dimType);
                
                if (dimTypeId == null) {
                    LOGGER.error("Cannot find dimension type for {}", dimId.identifier());
                    LOGGER.error(
                        "Registered dimension types {}", dimensionTypes.keySet()
                    );
                    dimTypeId = BuiltinDimensionTypes.OVERWORLD.identifier();
                }
                
                dimIdToDimTypeId.putString(
                    dimId.identifier().toString(),
                    dimTypeId.toString()
                );
            }
            
            return new DimSyncPacket(dimIdToDimTypeId);
        }
        
        public ImmutableMap<ResourceKey<Level>, ResourceKey<DimensionType>> toMap() {
            CompoundTag tag = dimIdToTypeIdTag();
            
            ImmutableMap.Builder<ResourceKey<Level>, ResourceKey<DimensionType>> builder =
                new ImmutableMap.Builder<>();
            
            for (String key : tag.keySet()) {
                ResourceKey<Level> dimId = ResourceKey.create(
                    Registries.DIMENSION,
                    Identifier.parse(key)
                );
                String dimTypeId = tag.getStringOr(key, "");
                ResourceKey<DimensionType> dimType = ResourceKey.create(
                    Registries.DIMENSION_TYPE,
                    Identifier.parse(dimTypeId)
                );
                builder.put(dimId, dimType);
            }
            
            return builder.build();
        }
        
        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    
    public static ClientboundCustomPayloadPacket createClientboundPacket(MinecraftServer server) {
        return new ClientboundCustomPayloadPacket(DimSyncPacket.createPacket(server));
    }
}
