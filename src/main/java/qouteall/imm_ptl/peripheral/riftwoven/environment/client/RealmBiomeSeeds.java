package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.peripheral.riftwoven.environment.DimensionBiomeSeedCache;

/** Client connection-owned metadata. Ordinary dimension changes do not clear this cache. */
public final class RealmBiomeSeeds {
    private static final DimensionBiomeSeedCache<ResourceKey<Level>> CACHE = new DimensionBiomeSeedCache<>();

    private RealmBiomeSeeds() {}

    public static void receive(ResourceKey<Level> dimension, long obfuscatedSeed) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) { return; }
        CACHE.put(connection, dimension, obfuscatedSeed);
        // Do not create all registered worlds just to store their seed metadata.
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
                if (level.dimension().equals(dimension)) { apply(level, obfuscatedSeed); }
            }
        }
        // The initial vanilla world can exist before ClientWorldLoader is initialized.
        // Match its dimension explicitly: never use the player's dimension as the metadata key.
        ClientLevel initialLevel = Minecraft.getInstance().level;
        if (initialLevel != null && initialLevel.dimension().equals(dimension)) {
            apply(initialLevel, obfuscatedSeed);
        }
    }

    /** Test/diagnostic API: the authoritative cached hash for this connection and dimension. */
    public static OptionalLong cachedSeed(ResourceKey<Level> dimension) {
        var connection = Minecraft.getInstance().getConnection();
        return connection == null ? OptionalLong.empty() : CACHE.seed(connection, dimension);
    }

    /** A remote world must never inherit another dimension's seed or silently guess one. */
    public static long seedForRemoteLevel(ResourceKey<Level> dimension, ClientPacketListener connection) {
        return CACHE.seed(connection, dimension).orElseThrow(() -> new IllegalStateException(
            "Missing dimension biome seed metadata before remote world creation: " + dimension.identifier()));
    }

    /** Apply known metadata to a vanilla-recreated level; remember its login seed if not yet known. */
    public static void onLevelConstructed(ClientLevel level, ClientPacketListener connection) {
        var known = CACHE.seed(connection, level.dimension());
        if (known.isPresent()) {
            apply(level, known.getAsLong());
        } else if (!ClientWorldLoader.getIsCreatingClientWorld()) {
            CACHE.put(connection, level.dimension(), level.getBiomeManager().biomeZoomSeed);
        }
    }

    public static void retainRegistered(Set<ResourceKey<Level>> dimensions) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) { CACHE.retain(connection, dimensions); }
    }

    public static void connectionClosed(Object connection) { CACHE.close(connection); }
    public static void disconnected() { CACHE.clear(); }

    private static void apply(ClientLevel level, long obfuscatedSeed) {
        RealmBiomeSeed.synchronizeObfuscated((RealmBiomeManagerAccess) level.getBiomeManager(),
            obfuscatedSeed, level::clearTintCaches);
    }
}
