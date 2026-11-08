package qouteall.imm_ptl.core.platform_specific;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.platform_shared.ModPlatform;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;

import java.nio.file.Path;
import java.util.List;

/** Platform-facing operations; implementations retain each loader's events and metadata. */
public class O_O {
    public static boolean isDimensionalThreadingPresent = false;

    public static boolean isForge() { return ModPlatform.isNeoForge(); }

    public static void onPlayerTravelOnServer(ServerPlayer player, ServerLevel fromWorld, ServerLevel toWorld) {
        RequiemCompat.onPlayerTeleportedServer(player);
        PlatformHooks.onPlayerChangedDimension(player, fromWorld, toWorld);
    }

    public static Path getGameDir() { return ModPlatform.getGameDir(); }
    public static boolean isObsidian(BlockState state) { return state == Blocks.OBSIDIAN.defaultBlockState(); }

    public static boolean isDedicatedServer() { return ModPlatform.isDedicatedServer(); }
    public static void postPortalSpawnEventForge(PortalGenInfo info) { PlatformHooks.postPortalSpawn(info); }

    public static boolean getIsPehkuiPresent() { return ModPlatform.isModLoaded("pehkui"); }

    @Nullable
    public static String getImmPtlModInfoUrl() {
        // Unofficial ports must not request compatibility information for the original releases.
        return null;
    }

    public static boolean isModLoadedWithinVersion(String modId, @Nullable String start, @Nullable String end) {
        String version = ModPlatform.getVersion(modId);
        if (version == null) { return false; }
        try {
            return (start == null || ModPlatform.compareVersions(start, version) <= 0)
                && (end == null || ModPlatform.compareVersions(end, version) >= 0);
        }
        catch (IllegalArgumentException exception) {
            qouteall.q_misc_util.Helper.LOGGER.warn("Could not compare mod version {}", version, exception);
            return true;
        }
    }

    public static @NotNull ImmPtlNetworkConfig.ModVersion getImmPtlVersion() {
        String version = getImmPtlVersionStr();
        var matcher = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matcher(version);
        if (!matcher.matches()) { return ImmPtlNetworkConfig.ModVersion.OTHER; }
        try {
            return new ImmPtlNetworkConfig.ModVersion(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        }
        catch (NumberFormatException exception) { return ImmPtlNetworkConfig.ModVersion.OTHER; }
    }

    public static String getImmPtlVersionStr() {
        return java.util.Objects.requireNonNull(ModPlatform.getVersion("immersive_portals"), "Immersive Portals metadata missing");
    }

    public static boolean shouldUpdateImmPtl(String latest) {
        if (ModPlatform.isDevelopmentEnvironment()) { return false; }
        try { return ModPlatform.compareVersions(latest, getImmPtlVersionStr()) > 0; }
        catch (IllegalArgumentException exception) { return false; }
    }

    public static String getModDownloadLink() { return "https://modrinth.com/mod/immersiveportals"; }
    public static String getIssueLink() { return "https://github.com/iPortalTeam/ImmersivePortalsMod/discussions"; }

    @Nullable
    public static Identifier getModIconLocation(String modId) {
        String path = ModPlatform.getIconPath(modId);
        if (path == null) { return null; }
        path = path.replaceFirst("^/?assets/", "");
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) { return null; }
        return Identifier.tryParse(path.substring(0, slash) + ":" + path.substring(slash + 1));
    }

    @Nullable
    public static String getModName(String modId) { return ModPlatform.getModName(modId); }
    public static boolean isQuilt() { return ModPlatform.isModLoaded("quilted_fabric_api"); }
    public static List<String> getLoadedModIds() { return ModPlatform.getLoadedModIds(); }
    public static boolean allowTeleportingEntity(Entity entity, Portal portal) { return PlatformHooks.allowDimensionTravel(entity, portal); }
    public static boolean isDevEnv() { return ModPlatform.isDevelopmentEnvironment(); }
}
