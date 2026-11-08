package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Preserves the native binding slots when Iris extends a vertex format. */
public final class IrisVertexBindings {
    /** Retains every Iris replacement and any declared slots omitted by its single-binding list. */
    public static <T> List<T> restoreDeclaredSlots(List<T> declared, List<T> extended) {
        if (extended.size() >= declared.size()) { return extended; }
        var result = new ArrayList<>(declared);
        for (int i = 0; i < extended.size(); i++) { result.set(i, extended.get(i)); }
        return Collections.unmodifiableList(result);
    }

    private IrisVertexBindings() {}
}
