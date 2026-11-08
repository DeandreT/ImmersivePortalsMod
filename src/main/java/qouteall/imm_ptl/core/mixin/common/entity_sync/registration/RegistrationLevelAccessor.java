package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(ServerLevel.class)
public interface RegistrationLevelAccessor {
    @Accessor("entityTickList") EntityTickList ip_registrationTickList();
    @Accessor("navigatingMobs") Set<Mob> ip_registrationNavigatingMobs();
    @Accessor("dragonParts") Int2ObjectMap<Entity> ip_registrationParts();
}
