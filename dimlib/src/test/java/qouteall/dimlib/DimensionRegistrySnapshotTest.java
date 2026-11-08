package qouteall.dimlib;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionRegistrySnapshotTest {
    @Test
    @SuppressWarnings("unchecked")
    void snapshotsLiveKeysWithoutReadingRemovedNumericIdHoles() throws ReflectiveOperationException {
        ResourceKey<Registry<String>> registryKey = ResourceKey.createRegistryKey(
            Identifier.parse("dimlib_test:snapshot")
        );
        MappedRegistry<String> registry = new MappedRegistry<>(registryKey, Lifecycle.stable(), true);
        ResourceKey<String> keptKey = ResourceKey.create(registryKey, Identifier.parse("dimlib_test:kept"));
        ResourceKey<String> removedKey = ResourceKey.create(registryKey, Identifier.parse("dimlib_test:removed"));
        registry.createIntrusiveHolder("kept");
        registry.register(keptKey, "kept", RegistrationInfo.BUILT_IN);
        registry.createIntrusiveHolder("removed");
        registry.register(removedKey, "removed", RegistrationInfo.BUILT_IN);

        // Reproduce the relevant state left by dimlib_forceRemove without a
        // Minecraft mixin launch: the numeric slot remains, but the live key is gone.
        Field byId = MappedRegistry.class.getDeclaredField("byId");
        byId.setAccessible(true);
        ((List<?>) byId.get(registry)).set(1, null);
        Field byKey = MappedRegistry.class.getDeclaredField("byKey");
        byKey.setAccessible(true);
        ((Map<ResourceKey<String>, ?>) byKey.get(registry)).remove(removedKey);

        assertTrue(registry.listElements().anyMatch(Objects::isNull), "The fixture must contain a removed ID hole");
        assertEquals(Map.of(keptKey, "kept"), DimensionImpl.copyRegistryEntries(registry));
    }
}
