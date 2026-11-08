package qouteall.dimlib.config;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** NeoForge-backed configuration retaining the shared warning-suppression API. */
public final class DimLibConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue SUPPRESS_EXPERIMENTAL_WARNING;
    public static volatile boolean suppressExperimentalWarning = false;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SUPPRESS_EXPERIMENTAL_WARNING = builder
            .comment("Suppress the experimental world settings warning.")
            .define("suppressExperimentalWarning", false);
        SPEC = builder.build();
    }

    private DimLibConfig() {}

    public static void onConfigChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            suppressExperimentalWarning = SUPPRESS_EXPERIMENTAL_WARNING.getAsBoolean();
        }
    }

    public static void save() {
        SUPPRESS_EXPERIMENTAL_WARNING.set(suppressExperimentalWarning);
        SPEC.save();
    }
}
