package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeManagerAccess;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeSeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmBiomeSeedTest {
    private static final class Manager implements RealmBiomeManagerAccess {
        private long zoomSeed;

        private Manager(long zoomSeed) { this.zoomSeed = zoomSeed; }

        @Override
        public boolean riftwoven$setBiomeZoomSeed(long obfuscatedSeed) {
            if (zoomSeed == obfuscatedSeed) { return false; }
            zoomSeed = obfuscatedSeed;
            return true;
        }
    }

    @Test
    void correctsOnlyTheAddressedManagerAndInvalidatesOnlyOnChange() {
        long mainSeed = BiomeManager.obfuscateSeed(10);
        var main = new Manager(mainSeed);
        var realm = new Manager(mainSeed);
        int[] invalidations = {0};
        Runnable invalidateRealm = () -> invalidations[0]++;

        assertTrue(RealmBiomeSeed.synchronizeObfuscated(realm, BiomeManager.obfuscateSeed(42), invalidateRealm));
        assertEquals(BiomeManager.obfuscateSeed(42), realm.zoomSeed);
        assertEquals(mainSeed, main.zoomSeed, "A remote realm update must not reseed the main world");
        assertEquals(1, invalidations[0]);

        assertFalse(RealmBiomeSeed.synchronizeObfuscated(realm, BiomeManager.obfuscateSeed(42), invalidateRealm));
        assertEquals(1, invalidations[0], "Periodic environment sync must not repeatedly clear tint caches");
        assertTrue(RealmBiomeSeed.synchronizeObfuscated(realm, BiomeManager.obfuscateSeed(43), invalidateRealm));
        assertEquals(BiomeManager.obfuscateSeed(43), realm.zoomSeed);
        assertEquals(2, invalidations[0]);
        assertEquals(mainSeed, main.zoomSeed);
    }

    @Test
    void rpcUsesDimensionAddressingWithoutGlobalRendererOrPlayerAccess() throws IOException {
        String path = "/qouteall/imm_ptl/peripheral/riftwoven/environment/client/RealmEnvironmentRemoteCallable.class";
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream);
            String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bytes.contains("qouteall/imm_ptl/core/ClientWorldLoader"));
            assertTrue(bytes.contains("getWorld"));
            assertTrue(bytes.contains("RealmBiomeSeed"));
            assertTrue(bytes.contains("receiveBiomeSeed"));
            assertFalse(bytes.contains("net/minecraft/client/Minecraft"));
            assertFalse(bytes.contains("net/minecraft/client/player/"));
            assertFalse(bytes.contains("LevelRenderer"));
            assertFalse(bytes.contains("LevelExtractor"));
            assertFalse(bytes.contains("allChanged"));
        }
    }

    @Test
    void metadataPublisherUsesOnlyObfuscatedSamplingSeeds() throws IOException {
        String path = "/qouteall/imm_ptl/peripheral/riftwoven/environment/RealmBiomeSeedSync.class";
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream);
            String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bytes.contains("biomeZoomSeed"));
            assertTrue(bytes.contains("receiveBiomeSeed"));
            assertFalse(bytes.contains("realmSeed"));
            assertFalse(bytes.contains("RealmChunkGenerator"));
            assertFalse(bytes.contains("net/minecraft/client/"));
        }
    }
}
