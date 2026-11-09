package qouteall.imm_ptl.peripheral.mixin.client.riftwoven;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeManagerAccess;

/** Register only in the client mixin list; server biome seeding remains untouched. */
@Mixin(BiomeManager.class)
public abstract class RealmBiomeManagerMixin implements RealmBiomeManagerAccess {
    @Shadow @Final @Mutable public long biomeZoomSeed;

    @Override
    public boolean riftwoven$setBiomeZoomSeed(long obfuscatedSeed) {
        if (biomeZoomSeed == obfuscatedSeed) {
            return false;
        }
        biomeZoomSeed = obfuscatedSeed;
        return true;
    }
}
