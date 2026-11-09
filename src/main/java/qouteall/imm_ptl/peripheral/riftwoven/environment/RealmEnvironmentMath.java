package qouteall.imm_ptl.peripheral.riftwoven.environment;

/** Pure, version-one clock and weather rules. Times are simulation ticks, not wall time. */
public final class RealmEnvironmentMath {
    public static final long WEATHER_PERIOD = 12_000;

    private RealmEnvironmentMath() {}

    /** Maps authored daylight to vanilla's sunrise-to-sunset half of the clock. */
    public static double clockTime(long ticks, int cycleTicks, int daylightTicks) {
        checkCycle(cycleTicks, daylightTicks);
        long day = Math.floorDiv(ticks, cycleTicks);
        long phase = Math.floorMod(ticks, cycleTicks);
        double local = phase < daylightTicks
            ? phase * 12_000.0 / daylightTicks
            : 12_000.0 + (phase - daylightTicks) * 12_000.0 / (cycleTicks - daylightTicks);
        return day * 24_000.0 + local;
    }

    public static float clockRate(long ticks, int cycleTicks, int daylightTicks) {
        checkCycle(cycleTicks, daylightTicks);
        return (float) (Math.floorMod(ticks, cycleTicks) < daylightTicks
            ? 12_000.0 / daylightTicks : 12_000.0 / (cycleTicks - daylightTicks));
    }

    /** Sleep moves to the next realm dawn, never the overworld's next dawn. */
    public static long nextDawn(long ticks, int cycleTicks) {
        if (cycleTicks <= 0) {
            throw new IllegalArgumentException("Cycle must be positive");
        }
        return Math.addExact(ticks, cycleTicks - Math.floorMod(ticks, cycleTicks));
    }

    /** Stable event choices do not consume the world generator's random stream. */
    public static WeatherEvent weather(long seed, long ticks, String regime) {
        long event = mix(seed ^ Math.floorDiv(ticks, WEATHER_PERIOD) * 0x9e3779b97f4a7c15L);
        int choice = (int) Long.remainderUnsigned(event, 100);
        return switch (regime) {
            case "CLEAR" -> new WeatherEvent(false, false);
            case "CHANGEABLE" -> new WeatherEvent(choice < 30, choice < 4);
            case "WET" -> new WeatherEvent(choice < 75, choice < 12);
            case "STORMY" -> new WeatherEvent(choice < 90, choice < 60);
            default -> throw new IllegalArgumentException("Unknown weather regime: " + regime);
        };
    }

    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static void checkCycle(int cycle, int daylight) {
        if (cycle <= 1 || daylight <= 0 || daylight >= cycle) {
            throw new IllegalArgumentException("Cycle needs nonempty daylight and night");
        }
    }

    public record WeatherEvent(boolean raining, boolean thundering) {}
}
