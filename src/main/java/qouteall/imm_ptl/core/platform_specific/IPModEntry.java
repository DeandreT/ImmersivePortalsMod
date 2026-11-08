package qouteall.imm_ptl.core.platform_specific;

import qouteall.imm_ptl.core.platform_shared.ModPlatform;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import qouteall.imm_ptl.core.IPModMain;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.q_misc_util.Helper;

public class IPModEntry {
    
    public void onInitialize() {
        IPModMain.init();
        RequiemCompat.init();
        
        IPModMain.registerEntityTypes(
            (id, entityType) -> PlatformHooks.register(BuiltInRegistries.ENTITY_TYPE, id, entityType)
        );
        
        IPModMain.registerBlocks((id, obj) -> PlatformHooks.register(BuiltInRegistries.BLOCK, id, obj));
        
        if (ModPlatform.isModLoaded("dimthread")) {
            O_O.isDimensionalThreadingPresent = true;
            Helper.log("Dimensional Threading is present");
        }
        else {
            Helper.log("Dimensional Threading is not present");
        }
        
        if (ModPlatform.isModLoaded("gravity_changer_q")) {
            // TODO(26.3): re-enable once compat is ported
            // GravityChangerInterface.invoker = new OnGravityChangerPresent();
            Helper.log("Gravity API is present");
        }
        else {
            Helper.log("Gravity API is not present");
        }
        
    }
    
}
