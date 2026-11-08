package qouteall.imm_ptl.core.platform_shared;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.nio.file.Path;
import java.util.List;

/** Uses the discovery list, which is available when mixin plugins run. */
public final class ModPlatform {
    private static String canonicalId(String id) {
        return switch (id) {
            case "iportal", "imm_ptl_core", "immersive_portals_core" -> "immersive_portals";
            default -> id;
        };
    }

    private static ModInfo mod(String id) {
        String canonical = canonicalId(id);
        return FMLLoader.getCurrent().getLoadingModList().getMods().stream()
            .filter(info -> info.getModId().equals(canonical)).findFirst().orElse(null);
    }

    public static boolean isModLoaded(String id) { return mod(id) != null; }
    public static boolean isDedicatedServer() { return FMLLoader.getCurrent().getDist() == Dist.DEDICATED_SERVER; }
    public static boolean isDevelopmentEnvironment() { return !FMLLoader.getCurrent().isProduction(); }
    public static boolean isNeoForge() { return true; }
    public static Path getGameDir() { return FMLLoader.getCurrent().getGameDir(); }
    public static String getVersion(String id) {
        ModInfo info = mod(id);
        return info == null ? null : info.getVersion().toString();
    }
    public static String getModName(String id) {
        ModInfo info = mod(id);
        return info == null ? null : info.getDisplayName();
    }
    public static String getIconPath(String id) {
        ModInfo info = mod(id);
        return info == null ? null : info.getLogoFile().orElse(null);
    }
    public static List<String> getLoadedModIds() {
        return FMLLoader.getCurrent().getLoadingModList().getMods().stream()
            .map(ModInfo::getModId).sorted().toList();
    }
    public static int getTopLevelModCount() {
        return (int) FMLLoader.getCurrent().getLoadingModList().getMods().stream()
            .filter(info -> info.getOwningFile().getFile().getDiscoveryAttributes().parent() == null).count();
    }
    public static boolean isProvidedByJarInJar() {
        return java.util.Objects.requireNonNull(mod("immersive_portals"))
            .getOwningFile().getFile().getDiscoveryAttributes().parent() != null;
    }
    public static int compareVersions(String first, String second) {
        return new DefaultArtifactVersion(first).compareTo(new DefaultArtifactVersion(second));
    }
    private ModPlatform() {}
}
