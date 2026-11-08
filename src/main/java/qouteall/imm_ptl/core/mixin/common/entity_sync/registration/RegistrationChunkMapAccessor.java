package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface RegistrationChunkMapAccessor {
    @Accessor("playerMap") PlayerMap ip_registrationPlayerMap();
    @Invoker("updatePlayerStatus") void ip_registrationPlayerStatus(ServerPlayer player, boolean added);
}
