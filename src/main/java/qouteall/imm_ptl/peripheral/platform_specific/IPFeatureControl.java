package qouteall.imm_ptl.peripheral.platform_specific;

import com.mojang.logging.LogUtils;
import qouteall.imm_ptl.core.platform_shared.ModPlatform;
import org.slf4j.Logger;

public class IPFeatureControl {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static boolean isProvidedByJarInJar() {
        return ModPlatform.isProvidedByJarInJar();
    }
    
    public static boolean enableVanillaBehaviorChangingByDefault() {
        return !isProvidedByJarInJar();
    }
}
