package qouteall.dimlib;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import qouteall.dimlib.client.DimLibClientNetworking;

public class DimLibEntryClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
            DimLibNetworking.DimSyncPacket.TYPE,
            (packet, context) -> DimLibClientNetworking.handle(packet, context.client().getConnection())
        );
    }
}
