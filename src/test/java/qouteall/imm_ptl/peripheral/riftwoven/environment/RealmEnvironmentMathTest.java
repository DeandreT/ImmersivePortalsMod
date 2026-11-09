package qouteall.imm_ptl.peripheral.riftwoven.environment;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmEnvironmentMathTest {
    @Test
    void mapsAuthoredDayAndNightWithoutChangingCycleLength() {
        assertEquals(0, RealmEnvironmentMath.clockTime(0, 36_000, 24_000));
        assertEquals(6_000, RealmEnvironmentMath.clockTime(12_000, 36_000, 24_000));
        assertEquals(12_000, RealmEnvironmentMath.clockTime(24_000, 36_000, 24_000));
        assertEquals(18_000, RealmEnvironmentMath.clockTime(30_000, 36_000, 24_000));
        assertEquals(24_000, RealmEnvironmentMath.clockTime(36_000, 36_000, 24_000));
        assertEquals(0.5F, RealmEnvironmentMath.clockRate(0, 36_000, 24_000));
        assertEquals(1.0F, RealmEnvironmentMath.clockRate(24_000, 36_000, 24_000));
    }

    @Test
    void vanillaLengthAndHalfDayAreIdentityIncludingNegativeTimes() {
        for (long tick : new long[]{-48_001, -24_000, -1, 0, 1, 12_000, 24_000, 90_001}) {
            assertEquals((double) tick, RealmEnvironmentMath.clockTime(tick, 24_000, 12_000));
        }
    }

    @Test
    void boundariesAreContinuousAndEveryPositiveTickMovesForward() {
        for (int daylight : new int[]{3_600, 12_000, 20_400}) {
            double previous = RealmEnvironmentMath.clockTime(-1, 24_000, daylight);
            for (int tick = 0; tick <= 48_000; tick++) {
                double current = RealmEnvironmentMath.clockTime(tick, 24_000, daylight);
                assertTrue(current > previous, "Authored day/night must never reverse time");
                previous = current;
            }
            assertEquals(12_000, RealmEnvironmentMath.clockTime(daylight, 24_000, daylight));
        }
        assertThrows(IllegalArgumentException.class, () -> RealmEnvironmentMath.clockTime(0, 24_000, 0));
        assertThrows(IllegalArgumentException.class, () -> RealmEnvironmentMath.clockTime(0, 24_000, 24_000));
    }

    @Test
    void sleepUsesTheRealmsCycleNotVanillaClockUnits() {
        assertEquals(72_000, RealmEnvironmentMath.nextDawn(60_000, 72_000));
        assertEquals(144_000, RealmEnvironmentMath.nextDawn(72_000, 72_000));
    }

    @Test
    void weatherIsSeededStableWithinAnEventAndNeverHasDryThunder() {
        var events = new HashSet<RealmEnvironmentMath.WeatherEvent>();
        for (long seed = 0; seed < 100; seed++) {
            for (String regime : new String[]{"CLEAR", "CHANGEABLE", "WET", "STORMY"}) {
                var event = RealmEnvironmentMath.weather(seed, 12_000, regime);
                assertEquals(event, RealmEnvironmentMath.weather(seed, 23_999, regime));
                assertFalse(event.thundering() && !event.raining());
                if (regime.equals("CLEAR")) { assertFalse(event.raining()); }
                if (regime.equals("STORMY")) { events.add(event); }
            }
        }
        assertTrue(events.size() > 1, "Landscape seeds must produce varied compatible weather events");
        assertThrows(IllegalArgumentException.class, () -> RealmEnvironmentMath.weather(0, 0, "TYPO"));
    }
}
