package qouteall.imm_ptl.client_test;

/** Dependency-free executable check; no Minecraft, graphics, Gradle or external test library. */
public final class PhotoFrameGuardTest {
    public static void main(String[] args) {
        PhotoFrameGuard guard = new PhotoFrameGuard();
        for (int draw = 1; draw <= 5; draw++) {
            guard.beginFrame();
            guard.observe(draw);
            require(guard.matches(draw), "Fresh ready draw must belong to this frame's post");
        }
        guard.beginFrame();
        require(!guard.matches(5), "A post without a main draw must reject the retained five-ready-draw observation");
        guard.observe(6);
        require(!guard.matches(5), "A different retained draw must not match the current frame");
        require(guard.matches(6), "Current frame's new main draw must match");
        guard.resetObservation();
        require(!guard.matches(6), "Starting another photographic stage must invalidate the previous observation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PhotoFrameGuardTest() {}
}
