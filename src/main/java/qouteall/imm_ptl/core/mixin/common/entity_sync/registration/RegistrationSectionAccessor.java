package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntitySection.class)
public interface RegistrationSectionAccessor {
    @Accessor("storage") ClassInstanceMultiMap<Entity> ip_registrationStorage();
}
