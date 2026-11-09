package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

/** Corrects one addressed level's biome sampling without rebuilding terrain or other views. */
public final class RealmBiomeSeed {
    private RealmBiomeSeed() {}

    public static boolean synchronizeObfuscated(RealmBiomeManagerAccess manager, long obfuscatedSeed, Runnable clearTintCaches) {
        boolean changed = manager.riftwoven$setBiomeZoomSeed(obfuscatedSeed);
        if (changed) {
            clearTintCaches.run();
        }
        return changed;
    }
}
