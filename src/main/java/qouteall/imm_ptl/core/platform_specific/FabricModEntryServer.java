package qouteall.imm_ptl.core.platform_specific;

import net.fabricmc.api.DedicatedServerModInitializer;

public final class FabricModEntryServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        new IPModEntryDedicatedServer().onInitializeServer();
    }
}
