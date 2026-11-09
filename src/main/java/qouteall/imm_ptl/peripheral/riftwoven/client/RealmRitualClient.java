package qouteall.imm_ptl.peripheral.riftwoven.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.imm_ptl.core.platform_shared.ClientTickEvents;
import qouteall.imm_ptl.core.platform_shared.PlayNetworking;
import qouteall.imm_ptl.peripheral.riftwoven.RealmInput;

/** Lives behind the physical client entrypoint; common items do not reference client types. */
@Environment(EnvType.CLIENT)
public final class RealmRitualClient {
    private static boolean wasHeld;
    private static int ticks;

    private RealmRitualClient() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean held = client.player != null && client.gui.screen() == null && client.options.keyUse.isDown();
            if (client.getConnection() != null && (held != wasHeld || held && ++ticks % 4 == 0)) {
                client.getConnection().send(PlayNetworking.createServerboundPacket(new RealmInput(held)));
            }
            wasHeld = held;
        });
    }
}
