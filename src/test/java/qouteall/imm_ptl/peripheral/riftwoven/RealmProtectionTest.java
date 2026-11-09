package qouteall.imm_ptl.peripheral.riftwoven;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmProtectionTest {
    @Test
    void immutableTerraceIsBoundedWithoutReservingTheMiningColumn() {
        assertTrue(RealmProtection.terrace(-10, 78, 10));
        assertTrue(RealmProtection.terrace(10, 86, -10));
        assertFalse(RealmProtection.terrace(0, 77, 0));
        assertFalse(RealmProtection.terrace(0, 87, 0));
        assertFalse(RealmProtection.terrace(11, 80, 0));
        assertFalse(RealmProtection.terrace(0, 80, -11));
        assertFalse(RealmProtection.terrace(Integer.MIN_VALUE, 80, Integer.MIN_VALUE));
    }

    @Test
    void damageProtectionCoversOnlyStandingRoom() {
        assertTrue(RealmProtection.standing(-10, 80, 10));
        assertTrue(RealmProtection.standing(10, 84, -10));
        assertFalse(RealmProtection.standing(0, 79, 0));
        assertFalse(RealmProtection.standing(0, 85, 0));
        assertFalse(RealmProtection.standing(11, 80, 0));
        assertFalse(RealmProtection.standing(0, 80, -11));
    }

    @Test
    void nestedInitializationScopeRestoresProtectionAfterFailure() {
        assertFalse(RealmProtection.arrivalEditsAllowed());
        RealmProtection.withArrivalEdits(() -> {
            assertTrue(RealmProtection.arrivalEditsAllowed());
            assertThrows(IllegalStateException.class, () -> RealmProtection.withArrivalEdits(() -> {
                assertTrue(RealmProtection.arrivalEditsAllowed());
                throw new IllegalStateException("test");
            }));
            assertTrue(RealmProtection.arrivalEditsAllowed());
        });
        assertFalse(RealmProtection.arrivalEditsAllowed());
        assertThrows(IllegalStateException.class, () -> RealmProtection.withArrivalEdits(() -> {
            throw new IllegalStateException("test");
        }));
        assertFalse(RealmProtection.arrivalEditsAllowed());
    }

    @Test
    void initializationPermissionDoesNotLeakToAnotherThread() throws InterruptedException, ExecutionException {
        FutureTask<Void> check = new FutureTask<>(() -> {
            assertFalse(RealmProtection.arrivalEditsAllowed());
            return null;
        });
        RealmProtection.withArrivalEdits(() -> new Thread(check).start());
        check.get();
        assertFalse(RealmProtection.arrivalEditsAllowed());
    }
}
