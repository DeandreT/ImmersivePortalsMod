package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

@FunctionalInterface
public interface AttackBlockCallback {
    Event<AttackBlockCallback> EVENT = Event.createArrayBacked(AttackBlockCallback.class,
        callbacks -> (player, world, hand, pos, direction) -> {
            for (AttackBlockCallback callback : callbacks) {
                InteractionResult result = callback.interact(player, world, hand, pos, direction);
                if (result != InteractionResult.PASS) {
                    return result;
                }
            }
            return InteractionResult.PASS;
        });

    InteractionResult interact(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction);
}
