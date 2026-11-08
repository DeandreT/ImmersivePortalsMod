package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IrisVertexBindingsTest {
    @Test
    void retainsTheExtendedFormatAndAllSixteenNativeSlots() {
        var declared = new ArrayList<>(Collections.nCopies(16, "unused"));
        declared.set(0, "vanilla");
        declared.set(7, "additional native binding");
        declared.set(15, null);

        var result = IrisVertexBindings.restoreDeclaredSlots(declared, List.of("iris extended"));

        assertEquals(16, result.size());
        assertEquals("iris extended", result.get(0));
        assertEquals("additional native binding", result.get(7));
        assertNull(result.get(15));
        assertEquals("vanilla", declared.get(0), "The declared pipeline must not be mutated");
        assertThrows(UnsupportedOperationException.class, () -> result.set(0, "changed"));
    }

    @Test
    void preservesEveryReturnedBindingIncludingNulls() {
        assertEquals(Arrays.asList("iris", null, "native tail"),
            IrisVertexBindings.restoreDeclaredSlots(List.of("vanilla", "old", "native tail"), Arrays.asList("iris", null)));
    }

    @Test
    void keepsAnAlreadyCompleteLayoutUnchanged() {
        var complete = List.of("iris", "native");
        assertSame(complete, IrisVertexBindings.restoreDeclaredSlots(List.of("vanilla", "native"), complete));
    }
}
