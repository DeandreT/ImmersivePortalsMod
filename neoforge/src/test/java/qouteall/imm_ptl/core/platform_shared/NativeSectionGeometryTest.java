package qouteall.imm_ptl.core.platform_shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSectionGeometryTest {
    @Test
    void nativeRebuildMixinRetainsTheAdditionalGeometryArgument() throws IOException {
        try (var stream = getClass().getResourceAsStream(
            "/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer_ForceMainThreadRebuild.class")) {
            assertNotNull(stream, "The native counterpart must replace the shared Fabric mixin");
            String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            String parameters = "(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Ljava/util/List;)V";
            assertTrue(bytes.contains("compileAsync" + parameters), "Native async compilation must target the additional-geometry overload");
            assertTrue(bytes.contains("compileSync" + parameters), "Native sync compilation must target the additional-geometry overload");
            assertTrue(bytes.contains("AdditionalSectionRenderer"), "Forced rebuilds must preserve native additional renderers");
        }
    }
}
