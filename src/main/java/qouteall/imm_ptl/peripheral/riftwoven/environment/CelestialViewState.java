package qouteall.imm_ptl.peripheral.riftwoven.environment;

/** One extracted view's traits; pooled sky states must clear this alongside their vanilla data. */
public final class CelestialViewState {
    private CelestialLayout layout = CelestialLayout.VANILLA;

    public CelestialLayout layout() { return layout; }
    public void extract(CelestialLayout layout) { this.layout = java.util.Objects.requireNonNull(layout); }
    public void reset() { layout = CelestialLayout.VANILLA; }
}
