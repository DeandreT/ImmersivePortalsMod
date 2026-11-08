package qouteall.imm_ptl.core.platform_shared;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

import java.nio.file.Path;
import java.util.List;

/** Loader metadata only; safe to call from mixin plugins before Minecraft initializes. */
public final class ModPlatform {
    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    public static boolean isDedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
    }

    public static boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    public static boolean isNeoForge() {
        return false;
    }

    public static Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static String getVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    public static String getModName(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getName()).orElse(null);
    }

    public static String getIconPath(String id) {
        return FabricLoader.getInstance().getModContainer(id).flatMap(mod -> mod.getMetadata().getIconPath(512)).orElse(null);
    }

    public static List<String> getLoadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).sorted().toList();
    }

    public static int getTopLevelModCount() {
        return (int) FabricLoader.getInstance().getAllMods().stream().filter(mod -> mod.getContainingMod().isEmpty()).count();
    }

    public static boolean isProvidedByJarInJar() {
        return FabricLoader.getInstance().getModContainer("iportal").orElseThrow().getContainingMod().isPresent();
    }

    public static int compareVersions(String first, String second) {
        try {
            return Version.parse(first).compareTo(Version.parse(second));
        }
        catch (VersionParsingException exception) {
            throw new IllegalArgumentException("Invalid mod version", exception);
        }
    }

    private ModPlatform() {}
}
