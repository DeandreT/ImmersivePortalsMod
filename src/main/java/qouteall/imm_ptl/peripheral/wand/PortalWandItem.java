package qouteall.imm_ptl.peripheral.wand;

import java.util.function.Consumer;
import net.minecraft.world.item.component.TooltipDisplay;
import com.mojang.serialization.Codec;
import qouteall.imm_ptl.core.platform_shared.AttackBlockCallback;
import qouteall.imm_ptl.core.platform_shared.ModPlatform;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import qouteall.imm_ptl.core.McHelper;

public class PortalWandItem extends Item {
    public static final Identifier ID = McHelper.newResourceLocation("immersive_portals:portal_wand");
    
    // in 26.3 the item id must be set in the properties before constructing
    public static final PortalWandItem instance = new PortalWandItem(
        new Properties().setId(ResourceKey.create(Registries.ITEM, ID))
    );
    
    public static void init() {
        PlatformHooks.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            "iportal:portal_wand_data",
            COMPONENT_TYPE
        );
        
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player.getMainHandItem().getItem() == instance) {
                // cannot break block using the wand
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        
        BlockManipulationServer.canDoCrossPortalInteractionEvent.register(p -> {
            return p.getMainHandItem().getItem() != instance;
        });
    }
    
    public static void addIntoCreativeTag(CreativeModeTab.Output entries) {
        ItemStack w1 = new ItemStack(instance);
        w1.set(COMPONENT_TYPE, Mode.CREATE_PORTAL);
        entries.accept(w1);
        
        ItemStack w2 = new ItemStack(instance);
        w2.set(COMPONENT_TYPE, Mode.DRAG_PORTAL);
        entries.accept(w2);
        
        ItemStack w3 = new ItemStack(instance);
        w3.set(COMPONENT_TYPE, Mode.COPY_PORTAL);
        entries.accept(w3);
    }
    
    public static enum Mode {
        CREATE_PORTAL,
        DRAG_PORTAL,
        COPY_PORTAL;
        
        public static final Mode FALLBACK = CREATE_PORTAL;
        
        public static Mode fromTag(CompoundTag tag) {
            String mode = tag.getStringOr("mode", "");
            
            return fromStr(mode);
        }
        
        public static Mode fromStr(String mode) {
            return switch (mode) {
                case "create_portal" -> CREATE_PORTAL;
                case "drag_portal" -> DRAG_PORTAL;
                case "copy_portal" -> COPY_PORTAL;
                default -> FALLBACK;
            };
        }
        
        public Mode next() {
            return switch (this) {
                case CREATE_PORTAL -> DRAG_PORTAL;
                case DRAG_PORTAL -> COPY_PORTAL;
                case COPY_PORTAL -> CREATE_PORTAL;
            };
        }
        
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            String modeString = toStr();
            tag.putString("mode", modeString);
            return tag;
        }
        
        public String toStr() {
            return switch (this) {
                case CREATE_PORTAL -> "create_portal";
                case DRAG_PORTAL -> "drag_portal";
                case COPY_PORTAL -> "copy_portal";
            };
        }
        
        public MutableComponent getText() {
            return switch (this) {
                case CREATE_PORTAL -> Component.translatable("imm_ptl.wand.mode.create_portal");
                case DRAG_PORTAL -> Component.translatable("imm_ptl.wand.mode.drag_portal");
                case COPY_PORTAL -> Component.translatable("imm_ptl.wand.mode.copy_portal");
            };
        }
        
    }
    
    public static final Codec<Mode> MODE_CODEC = Codec.STRING.xmap(Mode::fromStr, Mode::toStr);
    
    public static final DataComponentType<Mode> COMPONENT_TYPE =
        DataComponentType.<Mode>builder()
            .persistent(MODE_CODEC)
            .build();
    
    public PortalWandItem(Properties properties) {
        super(properties);
    }
    
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        Mode mode = itemStack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);
        
        if (player.isShiftKeyDown()) {
            if (!world.isClientSide()) {
                if (!PortalWandInteraction.isDragging(((ServerPlayer) player))) {
                    Mode nextMode = mode.next();
                    itemStack.set(COMPONENT_TYPE, nextMode);
                    return InteractionResult.SUCCESS.heldItemTransformedTo(itemStack);
                }
            }
        }
        
        if (!player.isShiftKeyDown()) {
            if (world.isClientSide() && !ModPlatform.isDedicatedServer()) {
                PortalWandItemClient.onUse(mode);
            }
        }
        
        return super.use(world, player, hand);
    }
    
    @Override
    public void appendHoverText(
        ItemStack stack, Item.TooltipContext tooltipContext,
        TooltipDisplay tooltipDisplay, Consumer<Component> tooltip, TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, tooltipContext, tooltipDisplay, tooltip, tooltipFlag);
        
        if (!ModPlatform.isDedicatedServer()) { PortalWandItemClient.appendHoverText(tooltip); }
    }
    
    @Override
    public Component getName(ItemStack stack) {
        Mode mode = stack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);
        
        MutableComponent baseText = Component.translatable("item.immersive_portals.portal_wand");
        
        return baseText
            .append(Component.literal(" : "))
            .append(mode.getText().withStyle(ChatFormatting.GOLD));
    }
    
}
