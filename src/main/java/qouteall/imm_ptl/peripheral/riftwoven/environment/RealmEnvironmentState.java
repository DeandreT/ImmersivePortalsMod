package qouteall.imm_ptl.peripheral.riftwoven.environment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Saved in the owning dimension's data directory, including dynamically created realms. */
public final class RealmEnvironmentState extends SavedData {
    public static final Codec<RealmEnvironmentState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.LONG.optionalFieldOf("time_ticks", 0L).forGetter(state -> state.timeTicks),
        Codec.LONG.optionalFieldOf("weather_ticks", 0L).forGetter(state -> state.weatherTicks),
        Codec.LONG.optionalFieldOf("clear_until", 0L).forGetter(state -> state.clearUntil)
    ).apply(instance, RealmEnvironmentState::new));
    public static final SavedDataType<RealmEnvironmentState> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("riftwoven", "environment"),
        () -> new RealmEnvironmentState(0, 0, 0), CODEC, null
    );

    private long timeTicks;
    private long weatherTicks;
    private long clearUntil;
    // Do not persist this anchor: offline real time must not advance simulation.
    private long lastGameTick = Long.MIN_VALUE;

    private RealmEnvironmentState(long timeTicks, long weatherTicks, long clearUntil) {
        this.timeTicks = timeTicks;
        this.weatherTicks = weatherTicks;
        this.clearUntil = clearUntil;
    }

    public long timeTicks() { return timeTicks; }
    public long weatherTicks() { return weatherTicks; }
    public boolean weatherSuppressed() { return weatherTicks < clearUntil; }

    /** Called once per server simulation tick, not once per loaded chunk or camera. */
    public void advance(long gameTick, boolean advanceTime, boolean advanceWeather) {
        long elapsed = lastGameTick == Long.MIN_VALUE ? 0 : Math.max(0, gameTick - lastGameTick);
        lastGameTick = gameTick;
        if (elapsed == 0) {
            return;
        }
        if (advanceTime) { timeTicks += elapsed; }
        if (advanceWeather) { weatherTicks += elapsed; }
        if (advanceTime || advanceWeather) { setDirty(); }
    }

    public void sleep(int cycleTicks, boolean advanceTime, boolean clearWeather) {
        if (advanceTime) { timeTicks = RealmEnvironmentMath.nextDawn(timeTicks, cycleTicks); }
        if (clearWeather) {
            clearUntil = RealmEnvironmentMath.nextDawn(weatherTicks, (int) RealmEnvironmentMath.WEATHER_PERIOD);
        }
        if (advanceTime || clearWeather) { setDirty(); }
    }
}
