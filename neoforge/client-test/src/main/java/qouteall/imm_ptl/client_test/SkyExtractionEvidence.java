package qouteall.imm_ptl.client_test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Additional read-only diagnostics; never increments the original qualification counters. */
public final class SkyExtractionEvidence {
    private static final ConcurrentHashMap<String, Counts> DIMENSIONS = new ConcurrentHashMap<>();

    private static final class Counts {
        final AtomicInteger heads = new AtomicInteger(), returns = new AtomicInteger();
        final AtomicInteger headsBeforeExpected = new AtomicInteger(), returnsBeforeExpected = new AtomicInteger();
        volatile int suns, moons;
        volatile long cosmeticSeed;
    }

    public static void head(String dimension, boolean expectedRealmMatches) {
        Counts counts = DIMENSIONS.computeIfAbsent(dimension, ignored -> new Counts());
        counts.heads.incrementAndGet();
        if (!expectedRealmMatches) counts.headsBeforeExpected.incrementAndGet();
    }

    public static void returned(String dimension, boolean expectedRealmMatches, int suns, int moons, long cosmeticSeed) {
        Counts counts = DIMENSIONS.computeIfAbsent(dimension, ignored -> new Counts());
        counts.returns.incrementAndGet();
        if (!expectedRealmMatches) counts.returnsBeforeExpected.incrementAndGet();
        counts.suns = suns;
        counts.moons = moons;
        counts.cosmeticSeed = cosmeticSeed;
    }

    public static Map<String, Object> snapshot(String dimension) {
        Counts counts = DIMENSIONS.get(dimension);
        if (counts == null) return Map.of("heads", 0, "returns", 0);
        return Map.of("heads", counts.heads.get(), "returns", counts.returns.get(),
            "heads_before_expected_match", counts.headsBeforeExpected.get(),
            "returns_before_expected_match", counts.returnsBeforeExpected.get(),
            "last_suns", counts.suns, "last_moons", counts.moons, "last_cosmetic_seed", counts.cosmeticSeed);
    }

    private SkyExtractionEvidence() {}
}
