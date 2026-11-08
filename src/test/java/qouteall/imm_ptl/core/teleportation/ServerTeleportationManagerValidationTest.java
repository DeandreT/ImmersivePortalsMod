package qouteall.imm_ptl.core.teleportation;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTeleportationManagerValidationTest {
    private static boolean valid(Vec3 pos) throws ReflectiveOperationException {
        Method method = ServerTeleportationManager.class.getDeclaredMethod("isValidTeleportPosition", Vec3.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, pos);
    }

    @Test
    void rejectsEveryNonFiniteCoordinateAndFiniteOverflowInput() throws ReflectiveOperationException {
        for (double value : new double[]{
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE, -Double.MAX_VALUE
        }) {
            assertFalse(valid(new Vec3(value, 64, 0)), "Invalid X must be rejected: " + value);
            assertFalse(valid(new Vec3(0, value, 0)), "Invalid Y must be rejected: " + value);
            assertFalse(valid(new Vec3(0, 64, value)), "Invalid Z must be rejected: " + value);
        }
        assertFalse(valid(null));
    }

    @Test
    void enforcesVanillaSpawnableCoordinateBoundsWithoutRestrictingBuildHeight() throws ReflectiveOperationException {
        assertTrue(valid(new Vec3(0, -1024, 0)));
        assertTrue(valid(new Vec3(0, 4096, 0)));
        assertTrue(valid(new Vec3(-Level.MAX_LEVEL_SIZE, Level.MIN_ENTITY_SPAWN_Y, -Level.MAX_LEVEL_SIZE)));
        assertTrue(valid(new Vec3(Level.MAX_LEVEL_SIZE - 1, Level.MAX_ENTITY_SPAWN_Y - 1, 0)));
        assertFalse(valid(new Vec3(Level.MAX_LEVEL_SIZE, 64, 0)));
        assertFalse(valid(new Vec3(0, 64, -Level.MAX_LEVEL_SIZE - 1)));
        assertFalse(valid(new Vec3(0, Level.MAX_ENTITY_SPAWN_Y, 0)));
        assertFalse(valid(new Vec3(0, Level.MIN_ENTITY_SPAWN_Y - 1, 0)));
    }

    private record EqualId(int id) {}

    @Test
    void hierarchyVerificationUsesOrderedIdentityNotEqualIds() throws ReflectiveOperationException {
        Method sameOrder = ServerTeleportationManager.class.getDeclaredMethod("sameEntityOrder", List.class, List.class);
        sameOrder.setAccessible(true);
        EqualId original = new EqualId(17);
        EqualId replacementWithSameId = new EqualId(17);
        EqualId sibling = new EqualId(18);
        assertTrue(original.equals(replacementWithSameId), "The fixture must reproduce equal-ID equality");
        assertTrue((boolean) sameOrder.invoke(null, List.of(original, sibling), List.of(original, sibling)));
        assertFalse((boolean) sameOrder.invoke(null, List.of(original, sibling), List.of(replacementWithSameId, sibling)),
            "An equal-ID replacement is not the captured original");
        assertFalse((boolean) sameOrder.invoke(null, List.of(original, sibling), List.of(sibling, original)),
            "Seat order is part of the captured hierarchy");
        assertFalse((boolean) sameOrder.invoke(null, List.of(original), List.of(original, sibling)));
    }
}
