package qouteall.imm_ptl.client_test;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** The common entrypoint never loads client helpers on a physical dedicated server. */
@Mod("ip_native_client_test")
public final class NativeClientTest {
    public NativeClientTest() {
        if (FMLEnvironment.getDist() == Dist.CLIENT) { new NativeClientSmoke(); }
    }
}
