package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public interface RegistrationCallbackAccessor {
    @Accessor("this$0") PersistentEntitySectionManager<?> ip_registrationOwner();
    @Accessor("entity") EntityAccess ip_registrationEntity();
    @Accessor("currentSectionKey") long ip_registrationSectionKey();
    @Accessor("currentSection") EntitySection<?> ip_registrationSection();
}
