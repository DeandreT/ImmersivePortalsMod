package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface RegistrationEntityAccessor {
    @Accessor("levelCallback") EntityInLevelCallback ip_registrationCallback();
    @Accessor("position") void ip_registrationExactPosition(Vec3 position);
    @Accessor("xRot") void ip_registrationExactXRot(float rotation);
    @Accessor("yRot") void ip_registrationExactYRot(float rotation);
    @Accessor("passengers") void ip_registrationPassengers(ImmutableList<Entity> passengers);
    @Accessor("vehicle") void ip_registrationVehicle(@Nullable Entity vehicle);
    @Accessor("boardingCooldown") int ip_registrationBoardingCooldown();
    @Accessor("boardingCooldown") void ip_registrationBoardingCooldown(int cooldown);
    @Accessor("removalReason") void ip_registrationRemovalReason(@Nullable Entity.RemovalReason reason);
    @Accessor("portalCooldown") int ip_registrationPortalCooldown();
    @Accessor("portalCooldown") void ip_registrationPortalCooldown(int cooldown);
}
