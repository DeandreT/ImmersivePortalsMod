package qouteall.dimlib.api;

/**
 * Opt-in contract for generators with a persistent seed independent of the save's main seed.
 * Implementations must serialize this seed in their generator codec. DimLib uses it for the
 * level seed, biome zoom, and structure checks during both dynamic creation and save reload.
 */
public interface IndependentDimensionSeed {
    long dimensionSeed();
}
