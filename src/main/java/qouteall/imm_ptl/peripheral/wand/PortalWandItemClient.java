package qouteall.imm_ptl.peripheral.wand;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.platform_shared.ClientTickEvents;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Client entrypoints and physically guarded common calls are the only callers of this helper. */
@Environment(EnvType.CLIENT)
public final class PortalWandItemClient {
    private static boolean instructionInformed;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                ItemStack stack = client.player.getMainHandItem();
                if (stack.getItem() == PortalWandItem.instance) { updateDisplay(stack); }
                else { ClientPortalWandPortalCreation.clearCursorPointing(); }
            }
            ClientPortalWandPortalDrag.tick();
        });
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(ClientPortalWandPortalCreation::reset);
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(ClientPortalWandPortalDrag::reset);
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(ClientPortalWandPortalCopy::reset);
    }

    public static void onLeftClick(LocalPlayer player, ItemStack stack) {
        if (player.isShiftKeyDown()) { showSettings(player); }
        else {
            switch (stack.getOrDefault(PortalWandItem.COMPONENT_TYPE, Mode.FALLBACK)) {
                case CREATE_PORTAL -> ClientPortalWandPortalCreation.onLeftClick();
                case DRAG_PORTAL -> ClientPortalWandPortalDrag.onLeftClick();
                case COPY_PORTAL -> ClientPortalWandPortalCopy.onLeftClick();
            }
        }
    }

    public static void onUse(Mode mode) {
        switch (mode) {
            case CREATE_PORTAL -> ClientPortalWandPortalCreation.onRightClick();
            case DRAG_PORTAL -> ClientPortalWandPortalDrag.onRightClick();
            case COPY_PORTAL -> ClientPortalWandPortalCopy.onRightClick();
        }
    }

    public static void appendHoverText(Consumer<Component> tooltip) {
        tooltip.accept(Component.translatable("imm_ptl.wand.item_desc_1",
            Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage(),
            Minecraft.getInstance().options.keyUse.getTranslatedKeyMessage()));
        tooltip.accept(Component.translatable("imm_ptl.wand.item_desc_2",
            Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage(),
            Minecraft.getInstance().options.keyAttack.getTranslatedKeyMessage()));
    }

    public static void showSettings(Player player) {
        player.sendSystemMessage(Component.translatable("imm_ptl.wand.settings_1"));
        player.sendSystemMessage(Component.translatable("imm_ptl.wand.settings_alignment"));
        int[] alignments = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 32, 64};
        List<MutableComponent> texts = new ArrayList<>();
        for (int alignment : alignments) {
            texts.add(IPMcHelper.getTextWithCommand(Component.literal("1/" + alignment),
                "/imm_ptl_client_debug wand set_cursor_alignment " + alignment));
        }
        texts.add(IPMcHelper.getTextWithCommand(Component.translatable("imm_ptl.wand.no_alignment"),
            "/imm_ptl_client_debug wand set_cursor_alignment 0"));
        player.sendSystemMessage(texts.stream().reduce(Component.literal(""), (a, b) -> a.append(" ").append(b)));
        player.sendSystemMessage(Component.translatable("imm_ptl.wand.settings_2",
            Minecraft.getInstance().options.keyChat.getTranslatedKeyMessage()));
    }

    private static void updateDisplay(ItemStack stack) {
        switch (stack.getOrDefault(PortalWandItem.COMPONENT_TYPE, Mode.FALLBACK)) {
            case CREATE_PORTAL -> ClientPortalWandPortalCreation.updateDisplay();
            case DRAG_PORTAL -> ClientPortalWandPortalDrag.updateDisplay();
            case COPY_PORTAL -> ClientPortalWandPortalCopy.updateDisplay();
        }
    }

    public static void render(LocalPlayer player, ItemStack stack, PoseStack poseStack, double camX, double camY, double camZ) {
        if (!instructionInformed) { instructionInformed = true; }
        switch (stack.getOrDefault(PortalWandItem.COMPONENT_TYPE, Mode.FALLBACK)) {
            case CREATE_PORTAL -> ClientPortalWandPortalCreation.render(poseStack, camX, camY, camZ);
            case DRAG_PORTAL -> ClientPortalWandPortalDrag.render(poseStack, camX, camY, camZ);
            case COPY_PORTAL -> ClientPortalWandPortalCopy.render(poseStack, camX, camY, camZ);
        }
    }

    private PortalWandItemClient() {}
}
