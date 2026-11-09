package qouteall.imm_ptl.peripheral.riftwoven.environment;

/** Visible bodies only: this never changes gameplay light, spawning, or heat. */
public record CelestialLayout(int suns, int moons, long seed, Path path) {
    public static final CelestialLayout VANILLA = new CelestialLayout(1, 1, 0, Path.ORDINARY);

    public CelestialLayout {
        if (suns < 1 || suns > 2 || moons < 1 || moons > 2) {
            throw new IllegalArgumentException("Supported visible body count is one or two");
        }
        java.util.Objects.requireNonNull(path);
    }

    /** Additional bodies follow separated ordinary orbits, or an explicitly selected tilted path. */
    public Orbit orbit(int index, boolean moon, double clockAngle) {
        if (index < 1 || index >= (moon ? moons : suns)) {
            throw new IllegalArgumentException("Not an additional body");
        }
        double separation = Math.toRadians(index * (moon ? -38.0 : 42.0));
        double inclination = 0;
        if (path == Path.WANDERING) {
            long mixed = RealmEnvironmentMath.mix(seed + index * 31L + (moon ? 17 : 0));
            double phase = Long.remainderUnsigned(mixed, 360) * Math.PI / 180.0;
            separation += Math.sin(clockAngle * 2 + phase) * 0.3;
            inclination = Math.sin(clockAngle + phase) * 0.4;
        }
        return new Orbit((float) separation, (float) inclination);
    }

    public enum Path { ORDINARY, WANDERING }
    public record Orbit(float separationRadians, float inclinationRadians) {}
}
