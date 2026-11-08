package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.server.MinecraftServer;
import qouteall.q_misc_util.Helper;

import java.util.function.Consumer;

/** Invoked by each loader after a complete server tick, including integrated servers. */
public final class ServerTickEvents {
    public static final Event<Consumer<MinecraftServer>> END_SERVER_TICK = Helper.createConsumerEvent();

    private ServerTickEvents() {}
}
