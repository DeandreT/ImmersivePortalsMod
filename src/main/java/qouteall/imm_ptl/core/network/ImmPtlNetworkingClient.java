package qouteall.imm_ptl.core.network;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.platform_shared.ClientPlatformHooks;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.Objects;

/** Physically client-only packet consumers; the packet records and codecs remain common. */
@Environment(EnvType.CLIENT)
public final class ImmPtlNetworkingClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        ClientPlatformHooks.registerClientReceiver(ImmPtlNetworking.GlobalPortalSyncPacket.TYPE,
            (packet, context) -> handleGlobalPortalSync(packet));
        ClientPlatformHooks.registerClientReceiver(ImmPtlNetworking.PortalSyncPacket.TYPE,
            (packet, context) -> handlePortalSync(packet));
    }

    public static void handleGlobalPortalSync(ImmPtlNetworking.GlobalPortalSyncPacket packet) {
        ResourceKey<Level> dimension = PortalAPI.clientIntToDimKey(packet.dimensionId());
        GlobalPortalStorage.receiveGlobalPortalSync(dimension, packet.data());
    }

    public static void handlePortalSync(ImmPtlNetworking.PortalSyncPacket packet) {
        ResourceKey<Level> dimension = PortalAPI.clientIntToDimKey(packet.dimensionId());
        ClientLevel world = ClientWorldLoader.getWorld(dimension);
        Entity existing = world.getEntity(packet.id());
        if (existing instanceof Portal existingPortal) {
            if (!Objects.equals(existingPortal.getUUID(), packet.uuid())) {
                LOGGER.error("UUID mismatch when syncing portal {} {}", existingPortal, packet.uuid());
                return;
            }
            if (existingPortal.getType() != packet.entityType()) {
                LOGGER.error("Entity type mismatch when syncing portal {} {}", existingPortal, packet.entityType());
                return;
            }
            existingPortal.acceptDataSync(new Vec3(packet.x(), packet.y(), packet.z()), packet.extraData());
        }
        else {
            Entity entity = packet.entityType().create(world, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
            Validate.notNull(entity, "Entity type is null");
            if (!(entity instanceof Portal portal)) {
                LOGGER.error("Spawned entity is not a portal. {} {}", entity, packet.entityType());
                return;
            }
            entity.setId(packet.id());
            entity.setUUID(packet.uuid());
            entity.syncPacketPositionCodec(packet.x(), packet.y(), packet.z());
            entity.snapTo(packet.x(), packet.y(), packet.z());
            portal.readPortalDataFromNbt(packet.extraData());
            world.addEntity(entity);
            ClientWorldLoader.getWorld(portal.getDestDim());
            Portal.CLIENT_PORTAL_SPAWN_EVENT.invoker().accept(portal);
            if (IPGlobal.clientPortalLoadDebug) { LOGGER.info("Portal loaded to client {}", portal); }
        }
    }

    private ImmPtlNetworkingClient() {}
}
