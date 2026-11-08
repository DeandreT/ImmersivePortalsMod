package qouteall.imm_ptl.core.platform_shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeIrisCustomPassTest {
    @Test
    void nativeIrisBridgeCorrectsOnlyThePlaceholderContract() throws IOException {
        try (var stream = getClass().getResourceAsStream(
            "/qouteall/imm_ptl/core/platform_specific/mixin/client/MixinIrisCompositeRenderer.class")) {
            assertNotNull(stream);
            String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bytes.contains("iris_custom_pass_placeholder"));
            assertTrue(bytes.contains("COMPOSITE_PIPELINE"));
            assertFalse(bytes.contains("withBindGroupLayout"), "The custom placeholder must not claim managed samplers");
            assertFalse(bytes.contains("withDepthStencilState"), "The color-only custom pass must not require a depth attachment");
            assertFalse(bytes.contains("validateDraw"), "Frontend validation must not be intercepted or disabled");
            assertFalse(bytes.contains("sampleCenterDepth"), "Actual Iris depth sampling must not be intercepted or skipped");
            assertFalse(bytes.contains("drawIndexed"), "Actual Iris draws must not be intercepted or skipped");
        }
    }
}
