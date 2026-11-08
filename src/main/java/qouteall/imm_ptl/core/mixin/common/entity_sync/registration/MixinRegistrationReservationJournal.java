package qouteall.imm_ptl.core.mixin.common.entity_sync.registration;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;

/** A reservation is journaled before section allocation or load callbacks can throw. */
@Mixin(PersistentEntitySectionManager.class)
public class MixinRegistrationReservationJournal {
    @Inject(method = "addEntityUuid", at = @At("RETURN"))
    private void ip_recordReservation(EntityAccess entity, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ()) {
            EntityRegistration.noteReservation((PersistentEntitySectionManager<?>) (Object) this, entity);
        }
    }
}
