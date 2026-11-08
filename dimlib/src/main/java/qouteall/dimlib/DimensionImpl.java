package qouteall.dimlib;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.dimlib.ducks.IMappedRegistry;
import qouteall.dimlib.mixin.common.IHolderReference;
import qouteall.dimlib.mixin.common.IWorldGenSettings;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class DimensionImpl {
    
    public static final Logger LOGGER = LoggerFactory.getLogger(DimensionImpl.class);
    
    public static final HashSet<String> STABLE_NAMESPACES = new HashSet<>();
    public static boolean suppressExperimentalWarning = false;
    
    public static void directlyRegisterLevelStem(
        MinecraftServer server, Identifier dimensionId, LevelStem levelStem
    ) {
        RegistryAccess.Frozen registryAccess = server.registryAccess();
        
        MappedRegistry<LevelStem> levelStems = (MappedRegistry<LevelStem>)
            registryAccess.lookupOrThrow(Registries.LEVEL_STEM);
        
        if (!levelStems.containsKey(dimensionId)) {
            // the vanilla freezing mechanism is used for validating dangling object references
            // for this API, that thing won't happen
            boolean oldIsFrozen = ((IMappedRegistry) levelStems).dimlib_getIsFrozen();
            ((IMappedRegistry) levelStems).dimlib_setIsFrozen(false);
            
            try {
                Holder.Reference<LevelStem> holder = levelStems.register(
                    ResourceKey.create(Registries.LEVEL_STEM, dimensionId),
                    levelStem,
                    RegistrationInfo.BUILT_IN // use built-in registration info for now
                );
                bindLevelStem(holder, levelStem);
                updateSavedDimensions(server);
            }
            finally {
                ((IMappedRegistry) levelStems).dimlib_setIsFrozen(oldIsFrozen);
            }
        }
        else {
            LOGGER.error(
                "The dimension {} already exists",
                dimensionId,
                new Throwable()
            );
        }
    }
    
    public static MappedRegistry<LevelStem> getDimensionRegistry(MinecraftServer server) {
        return ((MappedRegistry<LevelStem>)
            server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)
        );
    }

    @SuppressWarnings("unchecked")
    static void bindLevelStem(Holder.Reference<LevelStem> holder, LevelStem levelStem) {
        // Vanilla normally binds these only when freezing the registry. Dynamic
        // registration deliberately preserves the existing frozen/tag state.
        if (!holder.isBound()) {
            ((IHolderReference<LevelStem>) (Object) holder).dimlib_bindValue(levelStem);
        }
    }

    static void updateSavedDimensions(MinecraftServer server) {
        // In 26.3, world generation is SavedData with its own dimension-map
        // snapshot. Updating only LEVEL_STEM no longer persists dimension edits.
        WorldGenSettings settings = server.getWorldGenSettings();
        ((IWorldGenSettings) (Object) settings).dimlib_setDimensions(
            new WorldDimensions(copyRegistryEntries(getDimensionRegistry(server)))
        );
        settings.setDirty();
    }

    static <T> Map<ResourceKey<T>, T> copyRegistryEntries(Registry<T> registry) {
        // MappedRegistry.listElements() walks numeric IDs, including null holes
        // left by dynamic removal. The keyed entries contain only live holders.
        Map<ResourceKey<T>, T> entries = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey<T>, T> entry : registry.entrySet()) {
            entries.put(entry.getKey(), entry.getValue());
        }
        return entries;
    }
}
