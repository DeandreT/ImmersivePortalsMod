package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import qouteall.q_misc_util.Helper;

import java.util.function.Consumer;

public final class ServerLifecycleEvents {
    public static final Event<Consumer<MinecraftServer>> SERVER_STARTED = Helper.createConsumerEvent();
    public static final Event<DataPackReloaded> END_DATA_PACK_RELOAD = Event.createArrayBacked(
        DataPackReloaded.class,
        callbacks -> (server, resources, success) -> {
            for (DataPackReloaded callback : callbacks) {
                callback.onReload(server, resources, success);
            }
        }
    );

    @FunctionalInterface
    public interface DataPackReloaded {
        void onReload(MinecraftServer server, ResourceManager resources, boolean success);
    }

    private ServerLifecycleEvents() {}
}
