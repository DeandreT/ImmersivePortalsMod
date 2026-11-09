package qouteall.imm_ptl.peripheral.riftwoven.environment;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmEnvironmentStateTest {
    private RealmEnvironmentState fresh() {
        return RealmEnvironmentState.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
    }

    @Test
    void realmCountersAreIndependentAndDuplicateTickCallsDoNotAdvanceTwice() {
        var first = fresh();
        var second = fresh();
        first.advance(100, true, true);
        second.advance(100, false, false);
        first.advance(120, true, true);
        first.advance(120, true, true);
        second.advance(120, false, false);
        assertEquals(20, first.timeTicks());
        assertEquals(20, first.weatherTicks());
        assertEquals(0, second.timeTicks());
        assertEquals(0, second.weatherTicks());
    }

    @Test
    void timeAndWeatherRulesFreezeOnlyTheirOwnCounter() {
        var state = fresh();
        state.advance(1, true, true);
        state.advance(11, false, true);
        state.advance(21, true, false);
        assertEquals(10, state.timeTicks());
        assertEquals(10, state.weatherTicks());
    }

    @Test
    void restartRetainsAuthoredTimeButDoesNotCatchUpOfflineTicks() {
        var before = fresh();
        before.advance(100, true, true);
        before.advance(120, true, true);
        before.sleep(36_000, true, true);
        var packed = RealmEnvironmentState.CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        var after = RealmEnvironmentState.CODEC.parse(JsonOps.INSTANCE, packed).getOrThrow();
        after.advance(50_000, true, true);
        assertEquals(36_000, after.timeTicks());
        assertEquals(20, after.weatherTicks());
        assertTrue(after.weatherSuppressed());
        after.advance(50_001, true, true);
        assertEquals(36_001, after.timeTicks());
        assertEquals(21, after.weatherTicks());
    }

    @Test
    void sleepingWithAdvanceTimeDisabledDoesNotChangeTheClock() {
        var state = fresh();
        state.advance(100, true, true);
        state.advance(200, true, true);
        state.sleep(36_000, false, true);
        assertEquals(100, state.timeTicks());
        assertTrue(state.weatherSuppressed());
        state.advance(12_100, false, true);
        assertFalse(state.weatherSuppressed());
    }
}
