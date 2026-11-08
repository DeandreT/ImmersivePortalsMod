package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityTickList.class)
public interface RegistrationTickListAccessor {
    @Accessor("active") Int2ObjectMap<Entity> ip_registrationActive();
    @Invoker("ensureActiveIsNotIterated") void ip_registrationPrepareMutation();
}
