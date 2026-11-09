package qouteall.imm_ptl.peripheral.mixin.client.riftwoven;

import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientClockInstanceAccess;

@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class RealmClientClockInstanceMixin implements RealmClientClockInstanceAccess {
    @Shadow private long totalTicks;
    @Shadow private float partialTick;
    @Shadow private float rate;

    @Override
    public void riftwoven$set(long ticks, float fraction, float newRate) {
        totalTicks = ticks;
        partialTick = fraction;
        rate = newRate;
    }
}
