package qouteall.imm_ptl.core.platform_specific;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.platform_shared.ClientPlatformHooks;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.Helper;

/** Client-only operations are called by client entrypoints, mixins and client world code. */
@Environment(EnvType.CLIENT)
public final class ClientPlatformOperations {
    public static ClientChunkCache createClientChunkManager(ClientLevel world, int loadDistance) {
        return new ImmPtlClientChunkMap(world, loadDistance);
    }

    public static void postChunkLoad(LevelChunk chunk) { ClientPlatformHooks.postChunkLoad(chunk); }
    public static void postChunkUnload(LevelChunk chunk) { ClientPlatformHooks.postChunkUnload(chunk); }

    public static void onPlayerChangeDimension(ResourceKey<Level> from, ResourceKey<Level> to) {
        if (!RequiemCompat.getIsRequiemPresent()) { return; }
        LocalPlayer player = Minecraft.getInstance().player;
        Mob possessed = RequiemCompat.getPossessedEntity(player);
        if (possessed != null && possessed.level() != player.level()) {
            Helper.LOGGER.info("Move Requiem Possessed Entity at Client");
            ClientTeleportationManager.moveClientEntityAcrossDimension(possessed, (ClientLevel) player.level(), player.position());
        }
    }

    private ClientPlatformOperations() {}
}
