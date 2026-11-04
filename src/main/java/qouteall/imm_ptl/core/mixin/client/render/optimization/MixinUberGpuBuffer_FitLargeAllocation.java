package qouteall.imm_ptl.core.mixin.client.render.optimization;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Vanilla can't place an allocation larger than a slab. With smaller slabs
 * ({@link MixinSectionRenderDispatcher_CompactBuffers}) a very dense section could reach that,
 * so the slabs created from then on are made large enough for it.
 */
@Mixin(UberGpuBuffer.class)
public class MixinUberGpuBuffer_FitLargeAllocation {
    @Shadow
    @Final
    @Mutable
    private int heapSize;

    @Inject(method = "addAllocation", at = @At("HEAD"))
    private void ip_onAddAllocation(
        Object key, UberGpuBuffer.UploadCallback<?> callback, ByteBuffer data, CallbackInfoReturnable<Boolean> cir
    ) {
        int size = data.remaining();
        if (size > heapSize) {
            int mebibyte = 1024 * 1024;
            heapSize = (size / mebibyte + 1) * mebibyte;
        }
    }
}
