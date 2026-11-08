package qouteall.dimlib;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;

public class ClientDimensionInfo {
    private static final Logger LOGGER = LogManager.getLogger();
    
    public static ImmutableSet<ResourceKey<Level>> dimensionIds;
    public static ImmutableMap<ResourceKey<Level>, ResourceKey<DimensionType>> dimensionIdToType;
    
    public static Set<ResourceKey<Level>> getDimensionIds() {
        if (dimensionIds == null) {
            throw new IllegalStateException("The dimension info has not been synced yet");
        }
        return dimensionIds;
    }
    
    public static Map<ResourceKey<Level>, ResourceKey<DimensionType>> getDimensionIdToType() {
        if (dimensionIdToType == null) {
            throw new IllegalStateException("The dimension info has not been synced yet");
        }
        return dimensionIdToType;
    }
    
    // Both loader adapters dispatch this on the client thread.
    public static void accept(ImmutableMap<ResourceKey<Level>, ResourceKey<DimensionType>> m) {
        dimensionIdToType = m;
        dimensionIds = dimensionIdToType.keySet();
    }
    
    public static void cleanup() {
        LOGGER.info("Cleaning up client dimension info");
        dimensionIds = null;
        dimensionIdToType = null;
    }
}
