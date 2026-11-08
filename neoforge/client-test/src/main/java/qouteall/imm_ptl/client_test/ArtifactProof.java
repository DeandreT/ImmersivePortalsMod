package qouteall.imm_ptl.client_test;

import com.google.gson.JsonParser;
import net.neoforged.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Checks the real FML mod-file paths, not a source-set name or a classpath declaration. */
final class ArtifactProof {
    static Map<String, Object> verify(Path report) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        var parent = modPath("immersive_portals");
        require(parent.equals(Path.of(property("parentArtifact")).toRealPath()), "Parent was substituted with dev sources");
        result.put("parent_path", parent.toString());
        result.put("parent_sha256", verifyHash(parent, property("parentArtifactSha256")));
        result.put("dimlib_sha256", verifyHash(modPath("dimlib"), property("dimlibSha256")));
        result.put("cloth_sha256", verifyHash(modPath("cloth_config"), property("clothSha256")));
        require(ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion().toString().equals("26.3.0.101"),
            "Wrong actual NeoForge runtime");
        for (String forbidden : List.of("fabricloader", "connector", "ip_native_recovery_test")) {
            require(!ModList.get().isLoaded(forbidden), "Forbidden substitution/other harness: " + forbidden);
        }
        var optional = new ArrayList<Map<String, Object>>();
        var expected = switch (property("profile")) {
            case "vanilla" -> Set.<String>of();
            case "performance" -> Set.of("sodium", "lithium", "ferritecore", "entityculling", "immediatelyfast");
            case "performance-iris" -> Set.of("sodium", "lithium", "ferritecore", "entityculling", "immediatelyfast", "iris");
            default -> throw new IllegalStateException("Unknown profile");
        };
        var seen = new java.util.HashSet<String>();
        for (var element : JsonParser.parseString(Files.readString(report.resolve("runtime-mods.json"))).getAsJsonArray()) {
            var entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            require(expected.contains(id) && seen.add(id), "Unexpected/duplicate optional mod: " + id);
            Path outer = Path.of(entry.get("path").getAsString()).toRealPath();
            String outerHash = verifyHash(outer, entry.get("sha256").getAsString());
            Path actual = modPath(id);
            String actualHash = hash(Files.readAllBytes(actual));
            boolean matches = outerHash.equals(actualHash);
            // Sodium's real native mod is inside the untouched published launcher jar.
            // Never replace that outer jar with the compile-only extracted mod.
            if (!matches) {
                try (var zip = new ZipFile(outer.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var child = entries.nextElement();
                        if (child.getName().startsWith("META-INF/jarjar/") && child.getName().endsWith(".jar")) {
                            try (var stream = zip.getInputStream(child)) {
                                if (hash(stream.readAllBytes()).equals(actualHash)) { matches = true; }
                            }
                        }
                    }
                }
            }
            require(matches, "Loaded optional mod does not belong to its original runtime artifact: " + id);
            optional.add(Map.of("id", id, "outer_path", outer.toString(), "outer_sha256", outerHash,
                "loaded_path", actual.toString(), "loaded_sha256", actualHash));
        }
        require(seen.equals(expected), "Not all requested profile mods loaded");
        for (String id : List.of("sodium", "lithium", "ferritecore", "entityculling", "immediatelyfast", "iris")) {
            require(ModList.get().isLoaded(id) == expected.contains(id), "Actual mod set differs from profile: " + id);
        }
        result.put("optional_mods", optional);
        return result;
    }

    private static Path modPath(String id) throws Exception {
        Path path = ModList.get().getModFileById(id).getFile().getFilePath();
        require(Files.isRegularFile(path), "Loaded mod is not an actual packaged jar: " + id);
        return path.toRealPath();
    }
    private static String verifyHash(Path path, String expected) throws Exception {
        String actual = hash(Files.readAllBytes(path));
        require(actual.equals(expected), "Artifact changed: " + path);
        return actual;
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String property(String name) { return System.getProperty("ip.nativeClient." + name, ""); }
    static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
    private ArtifactProof() {}
}
