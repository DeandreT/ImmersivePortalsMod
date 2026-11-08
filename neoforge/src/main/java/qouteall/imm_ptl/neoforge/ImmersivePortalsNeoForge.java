package qouteall.imm_ptl.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import qouteall.imm_ptl.core.platform_specific.IPModEntry;
import qouteall.imm_ptl.core.platform_specific.IPModEntryDedicatedServer;
import qouteall.imm_ptl.peripheral.platform_specific.PeripheralModEntry;
import qouteall.q_misc_util.MiscUtilModEntry;

@Mod("immersive_portals")
public final class ImmersivePortalsNeoForge {
    public ImmersivePortalsNeoForge(IEventBus modBus) {
        PlatformHooks.install(modBus, ImmersivePortalsNeoForge::initializeShared);
    }

    private static void initializeShared() {
        new PeripheralModEntry().onInitialize();
        new IPModEntry().onInitialize();
        new MiscUtilModEntry().onInitialize();
        if (FMLLoader.getCurrent().getDist() == Dist.DEDICATED_SERVER) {
            new IPModEntryDedicatedServer().onInitializeServer();
        }
    }
}
