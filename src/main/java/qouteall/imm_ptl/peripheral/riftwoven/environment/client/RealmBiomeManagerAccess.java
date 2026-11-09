package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

/** Applied to biome managers on the physical client only. */
public interface RealmBiomeManagerAccess {
    /** Sets an already-obfuscated zoom seed and reports whether it actually changed. */
    boolean riftwoven$setBiomeZoomSeed(long obfuscatedSeed);
}
