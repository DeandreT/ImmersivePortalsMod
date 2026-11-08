package qouteall.imm_ptl.core.platform_shared;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import qouteall.q_misc_util.Helper;

import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public final class ClientTickEvents {
    public static final Event<Consumer<Minecraft>> END_CLIENT_TICK = Helper.createConsumerEvent();

    private ClientTickEvents() {}
}
