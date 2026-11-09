package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;

import java.util.function.BiConsumer;

@Mixin(BlockBehaviour.BlockStateBase.class)
public class MixinBlockState_RealmProtection {
    @Inject(method = "onExplosionHit", at = @At("HEAD"), cancellable = true)
    private void riftwoven$preventArrivalExplosionDrops(ServerLevel level, BlockPos pos,
        Explosion explosion, BiConsumer<ItemStack, BlockPos> drops, CallbackInfo ci) {
        if (RealmProtection.protectedBlock(level, pos)) ci.cancel();
    }
}
