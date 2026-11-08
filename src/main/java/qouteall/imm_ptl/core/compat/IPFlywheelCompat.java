package qouteall.imm_ptl.core.compat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.imm_ptl.core.platform_shared.ModPlatform;
import qouteall.q_misc_util.Helper;

@Environment(EnvType.CLIENT)
public class IPFlywheelCompat {
    
    public static boolean isFlywheelPresent = false;
    
    public static void init(){
        if (ModPlatform.isModLoaded("flywheel")) {
            Helper.log("Flywheel is present");
        }
        
    }
    
}
