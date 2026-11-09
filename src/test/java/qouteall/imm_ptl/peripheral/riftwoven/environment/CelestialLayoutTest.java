package qouteall.imm_ptl.peripheral.riftwoven.environment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CelestialLayoutTest {
    @Test
    void pooledSkyStatesResetAndSeparateViewsCannotOverwriteEachOther() {
        var firstView = new CelestialViewState();
        var secondView = new CelestialViewState();
        var firstRealm = new CelestialLayout(2, 1, 14, CelestialLayout.Path.ORDINARY);
        var secondRealm = new CelestialLayout(1, 2, 15, CelestialLayout.Path.ORDINARY);
        firstView.extract(firstRealm);
        secondView.extract(secondRealm);
        assertSame(firstRealm, firstView.layout());
        assertSame(secondRealm, secondView.layout());
        secondView.reset();
        assertSame(CelestialLayout.VANILLA, secondView.layout());
        assertSame(firstRealm, firstView.layout());
        firstView.extract(CelestialLayout.VANILLA);
        assertSame(CelestialLayout.VANILLA, firstView.layout());
    }

    @Test
    void ordinaryAdditionalBodiesHaveSeparatedStableUntiltedOrbits() {
        var layout = new CelestialLayout(2, 2, 14, CelestialLayout.Path.ORDINARY);
        assertEquals(layout.orbit(1, false, 0), layout.orbit(1, false, 3));
        assertEquals(0, layout.orbit(1, false, 0).inclinationRadians());
        assertNotEquals(layout.orbit(1, false, 0), layout.orbit(1, true, 0));
        assertThrows(IllegalArgumentException.class, () -> layout.orbit(0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> layout.orbit(2, false, 0));
    }

    @Test
    void explicitlySelectedWanderingMathIsRepeatableAndTimeDependent() {
        var first = new CelestialLayout(2, 2, 14, CelestialLayout.Path.WANDERING);
        var same = new CelestialLayout(2, 2, 14, CelestialLayout.Path.WANDERING);
        assertEquals(first.orbit(1, false, 0.4), same.orbit(1, false, 0.4));
        assertNotEquals(first.orbit(1, false, 0), first.orbit(1, false, 0.4));
        assertNotEquals(first.orbit(1, false, 0.4),
            new CelestialLayout(2, 2, 15, CelestialLayout.Path.WANDERING).orbit(1, false, 0.4));
    }

    @Test
    void nestedPortalViewsRestoreTheirOwnSnapshotIncludingExceptionalExit() {
        var views = new ViewStateScope<CelestialLayout>();
        var outer = new CelestialLayout(2, 1, 10, CelestialLayout.Path.ORDINARY);
        var inner = new CelestialLayout(1, 2, 11, CelestialLayout.Path.ORDINARY);
        assertNull(views.current());
        try (var ignored = views.enter(outer)) {
            assertSame(outer, views.current());
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = views.enter(inner)) {
                    assertSame(inner, views.current());
                    throw new IllegalStateException("Simulated portal draw failure");
                }
            });
            assertSame(outer, views.current());
        }
        assertNull(views.current());
        try (var ignored = views.enter(CelestialLayout.VANILLA)) {
            assertSame(CelestialLayout.VANILLA, views.current());
        }
        assertNull(views.current());
    }

    @Test
    void identicalSnapshotObjectsStillRequireStrictScopeOrder() {
        var views = new ViewStateScope<CelestialLayout>();
        var outer = views.enter(CelestialLayout.VANILLA);
        var inner = views.enter(CelestialLayout.VANILLA);
        assertThrows(IllegalStateException.class, outer::close);
        inner.close();
        outer.close();
        assertNull(views.current());
        assertThrows(IllegalStateException.class, outer::close);
    }
}
