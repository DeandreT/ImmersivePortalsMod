package qouteall.imm_ptl.core.platform_specific;

import net.fabricmc.api.DedicatedServerModInitializer;
import qouteall.imm_ptl.core.compat.IPModInfoChecking;

public class IPModEntryDedicatedServer implements DedicatedServerModInitializer {
    
    @Override
    public void onInitializeServer() {
        IPModInfoChecking.initDedicatedServer();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
            server -> qouteall.imm_ptl.core.miscellaneous.HeapTrimmer.trimNow()
        );
    }
    
    
}
