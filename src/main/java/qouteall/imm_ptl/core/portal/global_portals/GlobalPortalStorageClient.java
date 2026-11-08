package qouteall.imm_ptl.core.portal.global_portals;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/** Client synchronization and cleanup, initialized only from the client entrypoint. */
@Environment(EnvType.CLIENT)
public final class GlobalPortalStorageClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            if (ClientWorldLoader.getIsInitialized()) {
                for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                    for (Portal portal : GlobalPortalStorage.getGlobalPortals(world)) {
                        portal.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    }
                }
            }
        });
    }

    public static void receiveSync(ResourceKey<Level> dimension, CompoundTag data) {
        ClientLevel world = ClientWorldLoader.getWorld(dimension);
        List<Portal> oldPortals = ((IEClientWorld) world).ip_getGlobalPortals();
        if (oldPortals != null) {
            for (Portal portal : oldPortals) { portal.remove(Entity.RemovalReason.KILLED); }
        }
        List<Portal> portals = GlobalPortalStorage.getPortalsFromTag(data, world);
        for (Portal portal : portals) {
            portal.myUnsetRemoved();
            portal.isGlobalPortal = true;
            Validate.isTrue(portal.isPortalValid());
            ClientWorldLoader.getWorld(portal.getDestDim());
        }
        ((IEClientWorld) world).ip_setGlobalPortals(portals);
        LOGGER.info("Global Portals Updated {}", dimension.identifier());
    }

    private GlobalPortalStorageClient() {}
}
