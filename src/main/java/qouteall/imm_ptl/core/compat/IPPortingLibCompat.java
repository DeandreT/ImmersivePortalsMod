package qouteall.imm_ptl.core.compat;

import qouteall.imm_ptl.core.platform_shared.ModPlatform;
import qouteall.q_misc_util.Helper;

public class IPPortingLibCompat {

    public static boolean isPortingLibPresent = false;

    // In 1.21.1 this also toggled Porting Lib's stencil buffer on RenderTargets.
    // 26.3 portal rendering doesn't use stencil (see docs/rendering-26.3.md), so that part is gone.
    public static void init() {
        if (ModPlatform.isModLoaded("porting_lib")) {
            Helper.log("Porting Lib is present");
            isPortingLibPresent = true;
        }
    }
}
