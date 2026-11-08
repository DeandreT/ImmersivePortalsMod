package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(ClassInstanceMultiMap.class)
public interface RegistrationMultiMapAccessor {
    @Accessor("byClass") Map<Class<?>, List<Entity>> ip_registrationLists();
}
