package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RealmEnvironmentSideBoundaryTest {
    @Test
    void commonSimulationAndMixinsNeverVerifyPhysicalClientTypes() throws IOException {
        for (String path : new String[]{
            "riftwoven/environment/RealmEnvironment", "riftwoven/environment/RealmEnvironmentState",
            "riftwoven/environment/RealmEnvironmentAccess", "riftwoven/environment/CelestialLayout",
            "mixin/common/riftwoven/RealmServerLevelMixin", "mixin/common/riftwoven/RealmWorldInfoSenderMixin"
        }) {
            try (var stream = getClass().getResourceAsStream("/qouteall/imm_ptl/peripheral/" + path + ".class")) {
                assertNotNull(stream, path);
                String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains("net/minecraft/client/"), path);
                assertFalse(bytes.contains("com/mojang/blaze3d/"), path);
                assertFalse(bytes.contains("com/mojang/renderpearl/"), path);
            }
        }
    }
}
