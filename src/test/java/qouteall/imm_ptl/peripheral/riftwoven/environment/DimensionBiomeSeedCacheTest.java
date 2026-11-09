package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionBiomeSeedCacheTest {
    @Test
    void realmLoginDoesNotSupplyTheSeedsOfOrdinaryRemoteWorlds() {
        var cache = new DimensionBiomeSeedCache<String>();
        var connection = new Object();
        cache.put(connection, "realm", 42);
        assertTrue(cache.seed(connection, "overworld").isEmpty());
        assertTrue(cache.seed(connection, "nether").isEmpty());
        assertTrue(cache.seed(connection, "end").isEmpty());
        cache.put(connection, "overworld", 10);
        cache.put(connection, "nether", 10);
        cache.put(connection, "end", 10);
        for (String dimension : Set.of("overworld", "nether", "end")) {
            assertEquals(10, cache.seed(connection, dimension).orElseThrow());
        }
        assertEquals(42, cache.seed(connection, "realm").orElseThrow());
        // Rebinding the same connection during level/renderer recreation preserves all keys.
        cache.bind(connection);
        assertEquals(10, cache.seed(connection, "overworld").orElseThrow());
        assertEquals(42, cache.seed(connection, "realm").orElseThrow());
    }

    @Test
    void trueReconnectCannotReuseThePreviousServersSeeds() {
        var cache = new DimensionBiomeSeedCache<String>();
        var oldConnection = new Object();
        var newConnection = new Object();
        cache.put(oldConnection, "realm", 42);
        cache.put(oldConnection, "overworld", 10);
        cache.close(oldConnection);
        assertTrue(cache.seed(newConnection, "overworld").isEmpty());
        cache.put(newConnection, "overworld", 99);
        cache.close(oldConnection);
        assertEquals(99, cache.seed(newConnection, "overworld").orElseThrow(),
            "Late teardown of an old connection must not erase a new connection's metadata");
    }

    @Test
    void missingDisconnectStillCannotCarrySeedsAcrossConnectionIdentities() {
        var cache = new DimensionBiomeSeedCache<String>();
        cache.put(new Object(), "overworld", 10);
        var nextConnection = new Object();
        assertTrue(cache.seed(nextConnection, "overworld").isEmpty());
    }

    @Test
    void dimensionRemovalAndReaddReplaceOnlyThatDimensionsMetadata() {
        var cache = new DimensionBiomeSeedCache<String>();
        var connection = new Object();
        cache.put(connection, "overworld", 10);
        cache.put(connection, "realm", 42);
        cache.retain(connection, Set.of("overworld"));
        assertTrue(cache.seed(connection, "realm").isEmpty());
        cache.put(connection, "realm", 43);
        assertEquals(43, cache.seed(connection, "realm").orElseThrow());
        assertEquals(10, cache.seed(connection, "overworld").orElseThrow());
    }
}
