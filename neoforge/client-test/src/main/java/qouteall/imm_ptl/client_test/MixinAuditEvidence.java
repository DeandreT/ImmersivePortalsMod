package qouteall.imm_ptl.client_test;

import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Read-only pre-audit evidence; never edits config bookkeeping or interprets a receipt as a pass. */
final class MixinAuditEvidence {
    private static final String TARGET = "net.minecraft.world.level.BlockGetter";

    static Map<String, Object> sample() {
        var result = new LinkedHashMap<String, Object>();
        result.put("target", TARGET);
        result.put("expected_mixin_owners", List.of(
            "qouteall.imm_ptl.core.mixin.common.miscellaneous.MixinBlockGetter",
            "net.caffeinemc.mods.lithium.mixin.world.raycast.BlockGetterMixin"));
        result.put("interpretation", "Actual loaded method annotations and separate Mixin metadata; neither substitutes for the unchanged audit");
        try {
            result.put("class_loader", String.valueOf(BlockGetter.class.getClassLoader()));
            var methods = new ArrayList<Map<String, Object>>();
            var owners = new TreeSet<String>();
            for (var method : BlockGetter.class.getDeclaredMethods()) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", method.getName());
                entry.put("descriptor", MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString());
                entry.put("modifiers", Modifier.toString(method.getModifiers()));
                MixinMerged merged = method.getAnnotation(MixinMerged.class);
                if (merged != null) {
                    entry.put("mixin_owner", merged.mixin());
                    entry.put("mixin_priority", merged.priority());
                    owners.add(merged.mixin());
                }
                methods.add(entry);
            }
            methods.sort(Comparator.comparing(entry -> entry.get("name") + ":" + entry.get("descriptor")));
            result.put("declared_methods", methods);
            result.put("observed_merged_owners", owners);
        } catch (RuntimeException | LinkageError failure) {
            result.put("loaded_method_inspection_error", failure.toString());
        }
        var configs = new LinkedHashMap<String, IMixinConfig>();
        try {
            var lookup = new ArrayList<Map<String, Object>>();
            // This public API reads ClassInfo metadata, not the loaded JVM methods above.
            for (var mixin : Mixins.getMixinsForClass(TARGET)) {
                var config = mixin.getConfig();
                configs.put(config.getName(), config);
                lookup.add(Map.of("class_name", mixin.getClassName(), "name", mixin.getName(),
                    "config", config.getName(), "priority", mixin.getPriority(),
                    "phase", mixin.getPhase().toString(), "targets", List.copyOf(mixin.getTargetClasses())));
            }
            lookup.sort(Comparator.comparing(entry -> entry.get("class_name").toString()));
            result.put("get_mixins_for_class", lookup);
            var queued = new ArrayList<String>();
            for (var handle : Mixins.getConfigs()) {
                queued.add(handle.getName());
                var config = handle.getConfig();
                if (config.getTargets().contains(TARGET)) { configs.put(config.getName(), config); }
            }
            result.put("queued_config_ids", queued);
        } catch (RuntimeException | LinkageError failure) {
            result.put("mixin_metadata_inspection_error", failure.toString());
        }
        var configEvidence = new ArrayList<Map<String, Object>>();
        for (var config : configs.values()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", config.getName());
            entry.put("source_id", String.valueOf(config.getCleanSourceId()));
            entry.put("required", config.isRequired());
            entry.put("priority", config.getPriority());
            entry.put("target_registered", config.getTargets().contains(TARGET));
            entry.put("plugin", config.getPlugin() == null ? "none" : config.getPlugin().getClass().getName());
            // The pinned implementation is package-private. Invoke only its known read-only getter.
            try {
                var getter = config.getClass().getDeclaredMethod("getUnhandledTargets");
                if (!getter.trySetAccessible()) {
                    entry.put("pending_inspection", "unavailable: access denied");
                } else {
                    Object pending = getter.invoke(config);
                    if (pending instanceof Set<?> targets && targets.stream().allMatch(String.class::isInstance)) {
                        var copy = new TreeSet<String>();
                        for (Object target : targets) { copy.add((String) target); }
                        entry.put("unhandled_targets", copy);
                        entry.put("target_unhandled", copy.contains(TARGET));
                    } else {
                        entry.put("pending_inspection", "unavailable: unexpected getter result");
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                entry.put("pending_inspection", "unavailable: " + failure);
            }
            configEvidence.add(entry);
        }
        result.put("target_configs", configEvidence);
        return result;
    }

    private MixinAuditEvidence() {}
}
