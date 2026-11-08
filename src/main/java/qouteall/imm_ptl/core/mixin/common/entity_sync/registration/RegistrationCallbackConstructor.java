package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructs the actual pinned vanilla callback, not an imitation callback. */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public interface RegistrationCallbackConstructor {
    @Invoker("<init>")
    static PersistentEntitySectionManager.Callback ip_registrationCreate(
        PersistentEntitySectionManager<?> owner, EntityAccess entity, long sectionKey, EntitySection<?> section
    ) {
        throw new AssertionError("Mixin constructor invoker was not applied");
    }
}
