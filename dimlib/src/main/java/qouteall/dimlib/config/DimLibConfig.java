package qouteall.dimlib.config;

import eu.midnightdust.lib.config.MidnightConfig;
import qouteall.dimlib.DimLibEntry;

public class DimLibConfig extends MidnightConfig {
    @Entry
    public static boolean suppressExperimentalWarning = false;

    public static void save() {
        MidnightConfig.write(DimLibEntry.MODID);
    }
}
