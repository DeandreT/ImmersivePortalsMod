package qouteall.imm_ptl.peripheral.platform_specific;

import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import qouteall.imm_ptl.peripheral.PeripheralModMain;

public class PeripheralModEntry {
    
    
    public void onInitialize() {
        PeripheralModMain.registerBlocks((id, ele) -> PlatformHooks.register(
            BuiltInRegistries.BLOCK, id, ele
        ));
        PeripheralModMain.registerItems((id, ele) -> PlatformHooks.register(
            BuiltInRegistries.ITEM, id, ele
        ));
        PeripheralModMain.registerChunkGenerators((id, ele) -> PlatformHooks.register(
            BuiltInRegistries.CHUNK_GENERATOR, id, ele
        ));
        PeripheralModMain.registerBiomeSources((id, ele) -> PlatformHooks.register(
            BuiltInRegistries.BIOME_SOURCE, id, ele
        ));

        PeripheralModMain.init();
    }
}
