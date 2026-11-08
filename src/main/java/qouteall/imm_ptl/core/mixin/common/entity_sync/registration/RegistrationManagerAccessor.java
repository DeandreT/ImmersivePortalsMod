package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

@Mixin(PersistentEntitySectionManager.class)
public interface RegistrationManagerAccessor {
    @Accessor("knownUuids") Set<UUID> ip_registrationKnownUuids();
    @Accessor("callbacks") LevelCallback<Entity> ip_registrationCallbacks();
    @Accessor("visibleEntityStorage") EntityLookup<Entity> ip_registrationVisible();
    @Accessor("sectionStorage") EntitySectionStorage<Entity> ip_registrationSections();
}
