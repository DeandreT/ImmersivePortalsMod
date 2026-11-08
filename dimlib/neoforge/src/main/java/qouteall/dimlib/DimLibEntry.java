package qouteall.dimlib;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.dimlib.config.DimLibConfig;
import qouteall.dimlib.ducks.IMinecraftServer;

/** Native NeoForge entry point; no Fabric loader or API is used. */
@Mod(DimLibEntry.MODID)
public final class DimLibEntry {
    public static final String MODID = "dimlib";
    public static final Logger LOGGER = LoggerFactory.getLogger(DimLibEntry.class);

    public DimLibEntry(IEventBus modBus, ModContainer container) {
        LOGGER.info("DimLib initializing on NeoForge");
        modBus.addListener(DimLibEntry::registerPayloads);
        modBus.addListener(DimLibEntry::configLoaded);
        modBus.addListener(DimLibEntry::configReloaded);
        container.registerConfig(ModConfig.Type.LOCAL, DimLibConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(DimLibEntry::registerCommands);
        NeoForge.EVENT_BUS.addListener(DimLibEntry::serverTick);
        DynamicDimensionsImpl.init();
        DimensionTemplate.init();
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // The client-only mod entry point supplies the handler, never this class.
        event.registrar("1").optional().playToClient(
            DimLibNetworking.DimSyncPacket.TYPE, DimLibNetworking.DimSyncPacket.CODEC
        );
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        DimsCommand.register(event.getDispatcher());
    }

    private static void serverTick(ServerTickEvent.Post event) {
        ((IMinecraftServer) event.getServer()).dimlib_processTasks();
    }

    private static void configLoaded(ModConfigEvent.Loading event) {
        DimLibConfig.onConfigChanged(event);
    }

    private static void configReloaded(ModConfigEvent.Reloading event) {
        DimLibConfig.onConfigChanged(event);
    }
}
