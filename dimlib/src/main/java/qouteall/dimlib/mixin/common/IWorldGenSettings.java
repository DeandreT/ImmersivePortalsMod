package qouteall.dimlib.mixin.common;

import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Update the saved dimension snapshot introduced by Minecraft 26.3. */
@Mixin(WorldGenSettings.class)
public interface IWorldGenSettings {
    @Mutable
    @Accessor("dimensions")
    void dimlib_setDimensions(WorldDimensions dimensions);
}
