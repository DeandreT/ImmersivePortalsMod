package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.riftwoven.storage.ReceiptAccess;

@Mixin(ServerPlayer.class)
public class MixinServerPlayer_RealmReceipt implements ReceiptAccess {
    @Unique private String riftwoven$transaction = "";

    @Override public String riftwoven$receipt() { return riftwoven$transaction; }
    @Override public void riftwoven$receipt(String receipt) { riftwoven$transaction = receipt; }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void riftwoven$read(ValueInput input, CallbackInfo ci) {
        riftwoven$transaction = input.getStringOr(RECEIPT_TAG, "");
    }

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void riftwoven$write(ValueOutput output, CallbackInfo ci) {
        output.putString(RECEIPT_TAG, riftwoven$transaction);
    }

    @Inject(method = "restoreFrom", at = @At("RETURN"))
    private void riftwoven$copy(ServerPlayer original, boolean keepEverything, CallbackInfo ci) {
        riftwoven$transaction = ((ReceiptAccess) original).riftwoven$receipt();
    }
}
