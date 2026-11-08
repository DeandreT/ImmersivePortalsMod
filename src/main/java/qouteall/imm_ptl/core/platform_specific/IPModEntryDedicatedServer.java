package qouteall.imm_ptl.core.platform_specific;

import qouteall.imm_ptl.core.compat.IPModInfoChecking;

public class IPModEntryDedicatedServer {
    
    public void onInitializeServer() {
        IPModInfoChecking.initDedicatedServer();
        qouteall.imm_ptl.core.platform_shared.ServerLifecycleEvents.SERVER_STARTED.register(
            server -> qouteall.imm_ptl.core.miscellaneous.HeapTrimmer.trimNow()
        );
    }
    
    
}
