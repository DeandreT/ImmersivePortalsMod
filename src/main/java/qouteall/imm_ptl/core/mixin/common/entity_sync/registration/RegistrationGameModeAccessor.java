package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerGameMode.class)
public interface RegistrationGameModeAccessor {
    @Accessor("level") ServerLevel ip_registrationLevel();
}
