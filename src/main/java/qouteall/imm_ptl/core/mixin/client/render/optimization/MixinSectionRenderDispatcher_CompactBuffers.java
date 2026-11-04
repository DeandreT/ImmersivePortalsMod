package qouteall.imm_ptl.core.mixin.client.render.optimization;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * Vanilla's terrain buffers grow in slabs of 128 MiB (vertices) and 32 MiB (indices) per chunk layer, and a slab
 * is only freed with the renderer. That suits one renderer showing a full render distance. This mod keeps a
 * renderer per dimension, usually showing little terrain, so most of those slabs would be empty GPU memory.
 * With smaller slabs a renderer holds about as much as it shows, at the cost of slightly more draw batches.
 */
@Mixin(SectionRenderDispatcher.class)
public class MixinSectionRenderDispatcher_CompactBuffers {
    // Each renderer's upload queue. It's rewritten from the start every frame, so the pages it ever touched stay
    // resident, and nothing can trim them. The limit per frame is still far more than a normal frame uploads.
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 102760448))
    private int ip_modifyStagingSize(int original) {
        return IPGlobal.compactTerrainBuffers ? 48 * 1024 * 1024 : original;
    }

    @ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 134217728))
    private int ip_modifyVertexSlabSize(int original) {
        return IPGlobal.compactTerrainBuffers ? 32 * 1024 * 1024 : original;
    }

    @ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 33554432))
    private int ip_modifyIndexSlabSize(int original) {
        return IPGlobal.compactTerrainBuffers ? 8 * 1024 * 1024 : original;
    }
}
