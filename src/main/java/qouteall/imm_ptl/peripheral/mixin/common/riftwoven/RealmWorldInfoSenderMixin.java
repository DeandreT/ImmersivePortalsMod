package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.chunk_loading.WorldInfoSender;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment;

@Mixin(WorldInfoSender.class)
public abstract class RealmWorldInfoSenderMixin {
    @Inject(method = "sendWorldInfo", at = @At("HEAD"), cancellable = true)
    private static void riftwoven$sendRealmEnvironment(ServerPlayer player, ServerLevel level, CallbackInfo ci) {
        if (RealmEnvironment.isRealm(level)) {
            // Vanilla's packet listener owns one global clock manager. Sending a realm's
            // default-clock value there would also change every normal portal world's time.
            RealmEnvironment.send(player, level);
            ci.cancel();
        }
    }
}
