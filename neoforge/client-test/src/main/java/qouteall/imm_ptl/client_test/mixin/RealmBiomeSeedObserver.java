package qouteall.imm_ptl.client_test.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the actual client biome metadata without changing it. */
@Mixin(BiomeManager.class)
public interface RealmBiomeSeedObserver {
    @Accessor("biomeZoomSeed")
    long ip_nativeClient$biomeZoomSeed();
}
