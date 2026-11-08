package qouteall.dimlib.mixin.common;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.dimlib.DimLibNetworking;
import qouteall.dimlib.platform.DimLibPlatform;

@Mixin(PlayerList.class)
public class MixinPlayerList {
    // send it right after login packet
    @Inject(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundChangeDifficultyPacket;<init>(Lnet/minecraft/world/Difficulty;Z)V"
        )
    )
    private void onConnectionEstablished(
        Connection connection,
        ServerPlayer player,
        CommonListenerCookie commonListenerCookie,
        CallbackInfo ci
    ) {
        if (DimLibPlatform.canSendDimensionSync(player)) {
            player.connection.send(
                DimLibNetworking.createClientboundPacket(player.level().getServer())
            );
        }
    }
}
