package qouteall.imm_ptl.peripheral.riftwoven;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;

/** Only reports vanilla use-button state. Targets, range, inventory and progress are server-owned. */
public record RealmInput(boolean held) implements CustomPacketPayload {
    public static final Type<RealmInput> TYPE = new Type<>(Identifier.parse("riftwoven:ritual_input"));
    public static final StreamCodec<FriendlyByteBuf, RealmInput> CODEC = StreamCodec.of(
        (buffer, input) -> buffer.writeBoolean(input.held), buffer -> new RealmInput(buffer.readBoolean()));

    public static void init() {
        PlatformHooks.registerServerbound(TYPE, CODEC);
        PlatformHooks.registerServerReceiver(TYPE, (input, context) -> Riftwoven.input(context.player(), input.held));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
