package qouteall.dimlib.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.apache.commons.lang3.Validate;
import qouteall.dimlib.ClientDimensionInfo;
import qouteall.dimlib.DimLibNetworking;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.dimlib.mixin.client.IClientPacketListener;

/** Client-only handling, reached only through a loader's client payload registration. */
public final class DimLibClientNetworking {
    private DimLibClientNetworking() {}

    public static void handle(
        DimLibNetworking.DimSyncPacket packet, ClientGamePacketListener listener
    ) {
        Validate.isTrue(Minecraft.getInstance().isSameThread(), "Not running in client thread");
        DimLibNetworking.LOGGER.info(
            "Client received dimension info\n{}",
            String.join("\n", packet.dimIdToTypeIdTag().keySet())
        );
        var dimensionTypes = packet.toMap();
        ClientDimensionInfo.accept(dimensionTypes);
        ((IClientPacketListener) listener).ip_setLevels(dimensionTypes.keySet());
        DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT.invoker().run(
            ClientDimensionInfo.getDimensionIds()
        );
    }
}
