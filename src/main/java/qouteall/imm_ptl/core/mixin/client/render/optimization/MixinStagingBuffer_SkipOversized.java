package qouteall.imm_ptl.core.mixin.client.render.optimization;

import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Vanilla throws when one mesh is larger than the staging buffer, which is smaller here
 * ({@link MixinSectionRenderDispatcher_CompactBuffers}). Not drawing a section beats crashing the game.
 */
@Mixin(StagingBuffer.class)
public abstract class MixinStagingBuffer_SkipOversized {
    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow
    protected abstract ByteBuffer getWriteBuffer();

    @Inject(method = "tryAppend", at = @At("HEAD"), cancellable = true)
    private void ip_onTryAppend(ByteBuffer data, CallbackInfoReturnable<StagingBuffer.BufferHandle> cir) {
        if (data.remaining() > getWriteBuffer().capacity()) {
            LOGGER.warn("Skipped a chunk section mesh of {} bytes, too large for the staging buffer", data.remaining());
            cir.setReturnValue(null);
        }
    }
}
