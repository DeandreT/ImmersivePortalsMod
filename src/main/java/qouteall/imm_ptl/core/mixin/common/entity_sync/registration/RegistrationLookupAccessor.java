package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

@Mixin(EntityLookup.class)
public interface RegistrationLookupAccessor {
    @Accessor("byId") Int2ObjectMap<Entity> ip_registrationById();
    @Accessor("byUuid") Map<UUID, Entity> ip_registrationByUuid();
}
