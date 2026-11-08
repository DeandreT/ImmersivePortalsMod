package qouteall.imm_ptl.core.platform_specific.mixin.common;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** NeoForge's lifecycle flag is captured state, not proof of manager registration. */
@Mixin(Entity.class)
public interface RegistrationNativeEntityAccessor {
    @Accessor("isAddedToLevel") void ip_registrationAddedFlag(boolean added);
}
