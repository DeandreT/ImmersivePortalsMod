package qouteall.imm_ptl.peripheral.platform_specific;

import qouteall.imm_ptl.peripheral.PeripheralModMain;

public class PeripheralModEntryClient {
    public static void registerBlockRenderLayers() {
        // In 26.3 the chunk section layer (cutout) of a block is derived from the transparency
        // of its model textures, and Fabric's BlockRenderLayerMap no longer exists.
        // So nothing needs to be registered for the portal helper block.
    }
    
    public void onInitializeClient() {
        PeripheralModEntryClient.registerBlockRenderLayers();
        
        PeripheralModMain.initClient();
    }
}
