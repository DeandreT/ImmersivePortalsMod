package qouteall.imm_ptl.neoforge.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import qouteall.imm_ptl.core.platform_shared.ClientPlatformHooks;
import qouteall.imm_ptl.core.platform_specific.IPConfigGUI;
import qouteall.imm_ptl.core.platform_specific.IPModEntryClient;
import qouteall.imm_ptl.peripheral.platform_specific.PeripheralModEntryClient;
import qouteall.q_misc_util.MiscUtilModEntryClient;

/** FML filters this entrypoint before loading its client-only classes on a server. */
@Mod(value = "immersive_portals", dist = Dist.CLIENT)
public final class ImmersivePortalsNeoForgeClient {
    public ImmersivePortalsNeoForgeClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(ClientPlatformHooks::registerPayloadHandlers);
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            new PeripheralModEntryClient().onInitializeClient();
            new IPModEntryClient().onInitializeClient();
            new MiscUtilModEntryClient().onInitializeClient();
        }));
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (IConfigScreenFactory) (mod, parent) -> IPConfigGUI.createClothConfigScreen(parent));
    }
}
