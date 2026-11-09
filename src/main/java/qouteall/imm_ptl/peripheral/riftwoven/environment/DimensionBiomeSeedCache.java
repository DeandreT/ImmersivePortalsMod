package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** Dimension-addressed obfuscated seeds, owned by one connection rather than one rendered world. */
public final class DimensionBiomeSeedCache<K> {
    private Object connection;
    private final Map<K, Long> seeds = new HashMap<>();

    public void bind(Object connection) {
        java.util.Objects.requireNonNull(connection);
        if (this.connection != connection) {
            seeds.clear();
            this.connection = connection;
        }
    }

    public OptionalLong seed(Object connection, K dimension) {
        bind(connection);
        Long value = seeds.get(dimension);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public void put(Object connection, K dimension, long obfuscatedSeed) {
        bind(connection);
        seeds.put(java.util.Objects.requireNonNull(dimension), obfuscatedSeed);
    }

    public void retain(Object connection, Set<K> dimensions) {
        bind(connection);
        seeds.keySet().retainAll(dimensions);
    }

    /** A late close from an old connection must not clear a newer connection's metadata. */
    public void close(Object connection) {
        if (this.connection == connection) {
            clear();
        }
    }

    public void clear() {
        connection = null;
        seeds.clear();
    }
}
