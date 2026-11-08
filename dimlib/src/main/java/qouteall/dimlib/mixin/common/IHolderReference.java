package qouteall.dimlib.mixin.common;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Bind a dynamically registered LevelStem without refreezing unrelated registry tags. */
@Mixin(Holder.Reference.class)
public interface IHolderReference<T> {
    @Invoker("bindValue")
    void dimlib_bindValue(T value);
}
