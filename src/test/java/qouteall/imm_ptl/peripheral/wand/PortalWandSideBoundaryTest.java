package qouteall.imm_ptl.peripheral.wand;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads class bytes without initializing Minecraft, either item, or a client class. */
class PortalWandSideBoundaryTest {
    @Test
    void commonWorldServicesDoNotVerifyClientWorlds() throws IOException {
        for (String path : new String[]{"portal/global_portals/GlobalPortalStorage", "collision/CollisionHelper", "ScaleUtils"}) {
            try (InputStream stream = getClass().getResourceAsStream("/qouteall/imm_ptl/core/" + path + ".class")) {
                assertNotNull(stream);
                String classBytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(classBytes.contains("net/minecraft/client/"),
                    "Common world services must not verify client world implementations: " + path);
            }
        }
    }

    @Test
    void commonPacketRecordsDoNotExposePhysicalClientTypes() throws IOException {
        for (String name : new String[]{"ImmPtlNetworking", "ImmPtlNetworking$PortalSyncPacket", "ImmPtlNetworking$GlobalPortalSyncPacket"}) {
            try (InputStream stream = getClass().getResourceAsStream(
                "/qouteall/imm_ptl/core/network/" + name + ".class")) {
                assertNotNull(stream, "The compiled common networking class must be available: " + name);
                String classBytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(classBytes.contains("net/minecraft/client/"),
                    "Server-registered packet codecs must not verify physical client world types: " + name);
            }
        }
    }

    @Test
    void commonPlatformAndPossessionBridgesDoNotExposeClientTypes() throws IOException {
        for (String name : new String[]{"O_O", "RequiemCompat"}) {
            try (InputStream stream = getClass().getResourceAsStream(
                "/qouteall/imm_ptl/core/platform_specific/" + name + ".class")) {
                assertNotNull(stream, "The compiled common bridge must be available: " + name);
                String classBytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(classBytes.contains("net/minecraft/client/"),
                    "Common bridge must not require physical client types: " + name);
            }
        }
    }

    @Test
    void commonItemDoesNotExposePhysicalClientTypes() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(
            "/qouteall/imm_ptl/peripheral/wand/PortalWandItem.class")) {
            assertNotNull(stream, "The compiled common item must be available");
            String classBytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(classBytes.contains("net/minecraft/client/"),
                "Common item signatures and verifier merges must not require a physical client type");
            assertFalse(classBytes.contains("com/mojang/blaze3d/"),
                "The rendering stack belongs only to the cold client helper");
            assertTrue(classBytes.contains("PortalWandItemClient"),
                "Client features must remain routed to their guarded helper, not removed");
        }
    }
}
