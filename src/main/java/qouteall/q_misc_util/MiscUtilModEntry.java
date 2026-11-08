package qouteall.q_misc_util;

import qouteall.q_misc_util.dimension.DimensionIntId;

public class MiscUtilModEntry {
    public void onInitialize() {
        
        ImplRemoteProcedureCall.init();
        
        MiscNetworking.init();
        
        DimensionIntId.init();
    }
}
