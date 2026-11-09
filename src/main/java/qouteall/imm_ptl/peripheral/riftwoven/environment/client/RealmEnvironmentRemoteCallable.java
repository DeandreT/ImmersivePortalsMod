package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialLayout;

/** Server-to-client only. Shared RPC infrastructure dispatches this on the client thread. */
public final class RealmEnvironmentRemoteCallable {
    private RealmEnvironmentRemoteCallable() {}

    public static void receive(ResourceKey<Level> dimension, long gameTime, long physicalTicks,
        int cycle, int daylight, int suns, int moons, long obfuscatedSeed, boolean advancing) {
        receiveBiomeSeed(dimension, obfuscatedSeed);
        var level = ClientWorldLoader.getWorld(dimension);
        level.setTimeFromServer(gameTime);
        ((RealmClientEnvironmentAccess) level).riftwoven$clock().synchronize(gameTime, physicalTicks,
            cycle, daylight, new CelestialLayout(suns, moons, obfuscatedSeed, CelestialLayout.Path.ORDINARY), advancing);
        level.environmentAttributes().invalidateTickCache();
        level.updateSkyBrightness();
    }

    /** Receives only the vanilla-equivalent obfuscated biome hash, never a generation seed. */
    public static void receiveBiomeSeed(ResourceKey<Level> dimension, long obfuscatedSeed) {
        RealmBiomeSeeds.receive(dimension, obfuscatedSeed);
    }
}
