package qouteall.imm_ptl.core.platform_specific;

import net.fabricmc.api.ModInitializer;
import qouteall.imm_ptl.peripheral.platform_specific.PeripheralModEntry;
import qouteall.q_misc_util.MiscUtilModEntry;

/** Fabric's entrypoint interface stays out of the shared initialization code. */
public final class FabricModEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        new PeripheralModEntry().onInitialize();
        new IPModEntry().onInitialize();
        new MiscUtilModEntry().onInitialize();
    }
}
