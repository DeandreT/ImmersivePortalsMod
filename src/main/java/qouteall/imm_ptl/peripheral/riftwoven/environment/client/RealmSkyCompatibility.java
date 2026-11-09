package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialLayout;

/** Never override a shader pack's sky ownership or disable frontend validation. */
public final class RealmSkyCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealmSkyCompatibility.class);
    private static final Set<String> warnedPacks = new HashSet<>();

    private RealmSkyCompatibility() {}

    /** Client preview/admission UI must disclose this before accepting an advanced sky offer. */
    public static String previewWarning(int suns, int moons) {
        if ((suns == 1 && moons == 1) || !IrisInterface.invoker.isShaderpackInUse()) {
            return null;
        }
        return "This shader pack controls the sky. Additional suns/moons cannot be guaranteed; "
            + "disable the pack before committing if these visible traits are required.";
    }

    public static void reportBoundary(CelestialLayout layout) {
        if (previewWarning(layout.suns(), layout.moons()) == null) {
            return;
        }
        String pack = String.valueOf(IrisInterface.invoker.getShaderpackName());
        if (warnedPacks.add(pack)) {
            String message = "Riftwoven: shader pack " + pack + " controls celestial rendering. "
                + "Extra suns/moons use its normal celestial hooks, but packs that disable vanilla bodies "
                + "or replace the sky cannot display these traits reliably. Disable the pack to see the authored sky.";
            LOGGER.warn(message);
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(message));
        }
    }
}
