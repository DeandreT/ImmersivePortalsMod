package qouteall.imm_ptl.iris_bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

final class LibraryIsolationTest {
    @Test
    void jarIsAnEarlyLibraryWithOnlyTheRawFmlServiceAndLicenseNotices() throws Exception {
        try (var jar = new JarFile(PublishedFixture.libraryJar().toFile())) {
            var attributes = jar.getManifest().getMainAttributes();
            assertEquals("LIBRARY", attributes.getValue("FMLModType"));
            assertEquals("riftwoven.iris.bootstrap", attributes.getValue("Automatic-Module-Name"));
            assertNotNull(jar.getEntry("LICENSE_immersive-portals"));
            assertNotNull(jar.getEntry("NOTICE_immersive-portals"));
            assertNull(jar.getEntry("META-INF/neoforge.mods.toml"));
            assertNull(jar.getEntry("fabric.mod.json"));
            var service = jar.getJarEntry("META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessor");
            assertNotNull(service);
            assertEquals(IrisBootstrapProcessor.class.getName(), new String(jar.getInputStream(service).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim());
            assertEquals(1, jar.stream().filter(entry -> entry.getName().endsWith(".class")).count());
        }
        var descriptor = java.lang.module.ModuleFinder.of(PublishedFixture.libraryJar()).findAll().iterator().next().descriptor();
        assertTrue(descriptor.isAutomatic());
        var service = descriptor.provides().stream().filter(provides -> provides.service().equals(
            "net.neoforged.neoforgespi.transformation.ClassProcessor")).findFirst().orElseThrow();
        assertEquals(List.of(IrisBootstrapProcessor.class.getName()), service.providers());
    }

    @Test
    void constantPoolHasNoGameIrisOrOrdinaryModTypeReferencesAndNoStaticInitializer() throws Exception {
        try (var jar = new JarFile(PublishedFixture.libraryJar().toFile())) {
            byte[] bytes = jar.getInputStream(jar.getJarEntry(IrisBootstrapProcessor.class.getName().replace('.', '/') + ".class")).readAllBytes();
            for (String reference : classReferences(bytes)) {
                assertTrue(reference.startsWith("java/") || reference.startsWith("org/objectweb/asm/")
                    || reference.startsWith("net/neoforged/neoforgespi/transformation/")
                    || reference.equals(IrisBootstrapProcessor.class.getName().replace('.', '/')),
                    "Forbidden bootstrap type reference: " + reference);
            }
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            assertFalse(node.methods.stream().anyMatch(method -> method.name.equals("<clinit>")),
                "The service must not initialize game/platform helpers while it is discovered");
            assertFalse(node.interfaces.stream().anyMatch(name -> name.contains("SimpleClassProcessor")));
        }
    }

    /** Reads actual CONSTANT_Class entries, not permitted Minecraft names stored only as strings. */
    private static List<String> classReferences(byte[] bytes) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(0xcafebabe, input.readInt());
            input.readUnsignedShort();
            assertEquals(69, input.readUnsignedShort(), "Library must target Java 25");
            int count = input.readUnsignedShort();
            String[] utf8 = new String[count];
            var classIndices = new ArrayList<Integer>();
            for (int index = 1; index < count; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> utf8[index] = input.readUTF();
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); index++; }
                    case 7 -> classIndices.add(input.readUnsignedShort());
                    case 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new AssertionError("Unexpected constant pool tag");
                }
            }
            return classIndices.stream().map(index -> utf8[index]).toList();
        }
    }
}
