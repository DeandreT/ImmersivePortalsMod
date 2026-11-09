package qouteall.imm_ptl.peripheral.riftwoven;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.UUID;
import java.util.function.Consumer;

public final class RealmSeedItem extends BlockItem {
    public RealmSeedItem(Block block, Properties properties) { super(block, properties); }

    public static UUID reference(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        try { return UUID.fromString(data.copyTag().getStringOr("riftwoven_realm", "")); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public static void bind(ItemStack stack, UUID realm) {
        CompoundTag tag = new CompoundTag();
        tag.putString("riftwoven_realm", realm.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player && !Riftwoven.canPlaceReference(player, context.getItemInHand())) {
            return InteractionResult.FAIL;
        }
        return super.useOn(context);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) Riftwoven.recoverReference(serverPlayer, hand);
            return InteractionResult.SUCCESS;
        }
        return super.use(level, player, hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
        Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable(reference(stack) == null
            ? "riftwoven.seed.tooltip" : "riftwoven.seed.reference"));
    }
}
