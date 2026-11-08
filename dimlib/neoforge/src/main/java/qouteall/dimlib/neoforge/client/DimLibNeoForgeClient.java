package qouteall.dimlib.neoforge.client;

import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import qouteall.dimlib.DimLibEntry;
import qouteall.dimlib.DimLibNetworking;
import qouteall.dimlib.client.DimLibClientNetworking;

/** FML's distribution filter prevents this entry point loading on dedicated servers. */
@Mod(value = DimLibEntry.MODID, dist = Dist.CLIENT)
public final class DimLibNeoForgeClient {
    public DimLibNeoForgeClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(DimLibNeoForgeClient::registerClientPayloads);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        // RegisterClientPayloadHandlersEvent dispatches on the client main thread.
        event.register(DimLibNetworking.DimSyncPacket.TYPE, (packet, context) ->
            DimLibClientNetworking.handle(packet, (ClientGamePacketListener) context.listener())
        );
    }
}
