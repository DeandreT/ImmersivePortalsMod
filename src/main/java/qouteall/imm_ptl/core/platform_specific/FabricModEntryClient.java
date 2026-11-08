package qouteall.imm_ptl.core.platform_specific;

import net.fabricmc.api.ClientModInitializer;
import qouteall.imm_ptl.peripheral.platform_specific.PeripheralModEntryClient;
import qouteall.q_misc_util.MiscUtilModEntryClient;

public final class FabricModEntryClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        new PeripheralModEntryClient().onInitializeClient();
        new IPModEntryClient().onInitializeClient();
        new MiscUtilModEntryClient().onInitializeClient();
    }
}
