package qouteall.imm_ptl.iris_bootstrap;

import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ClassProcessorIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

final class IrisBootstrapProcessorTest {
    private final IrisBootstrapProcessor processor = new IrisBootstrapProcessor();

    @Test
    void fixtureIsTheExactPublishedIrisArtifact() throws Exception {
        var hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
            java.nio.file.Path.of(System.getProperty("bootstrap.publishedIris"))));
        assertEquals("2260506334a17ac566ea30169ab491201208f55c1c7212bb987bc0c7c59a8d93", HexFormat.of().formatHex(hash));
    }

    @Test
    void serviceUsesRawPublicFmlSpiWithExplicitPreMixinOrdering() {
        var services = ServiceLoader.load(ClassProcessor.class).stream()
            .filter(provider -> provider.type() == IrisBootstrapProcessor.class).toList();
        assertEquals(1, services.size());
        ClassProcessor loaded = services.getFirst().get();
        assertEquals("riftwoven:iris_bootstrap", loaded.name().toString());
        assertEquals(Set.of(), loaded.runsAfter(), "Default FML ordering would wait for frame computation");
        assertEquals(Set.of(ClassProcessorIds.MIXIN, ClassProcessorIds.MIXIN_FRAME_CONTEXT), loaded.runsBefore());
        assertEquals(ClassProcessor.OrderingHint.EARLY, loaded.orderingHint());
        assertTrue(loaded.generatesPackages().isEmpty());
    }

    @Test
    void selectionIsOnlyTheNonemptyExactIrisClass() {
        assertTrue(processor.handlesClass(new ClassProcessor.SelectionContext(Type.getObjectType(IrisBootstrapProcessor.TARGET), false)));
        assertFalse(processor.handlesClass(new ClassProcessor.SelectionContext(Type.getObjectType(IrisBootstrapProcessor.TARGET), true)));
        for (String name : List.of("net/minecraft/world/level/BlockGetter", "net/irisshaders/iris/platform/IrisFabricHelpers",
            "net/irisshaders/iris/platform/IrisForgeHelpersExtra", "qouteall/imm_ptl/core/IPGlobal")) {
            assertFalse(processor.handlesClass(new ClassProcessor.SelectionContext(Type.getObjectType(name), false)));
        }
    }

    @Test
    void addsOnlyOneCastPreservingEveryExistingInstructionFrameAndCall() throws Exception {
        var node = PublishedFixture.original();
        var method = PublishedFixture.method(node);
        var before = List.of(method.instructions.toArray());
        var others = node.methods.stream().filter(candidate -> candidate != method).toList();
        var beforeOthers = others.stream().map(candidate -> List.of(candidate.instructions.toArray())).toList();
        int maxStack = method.maxStack, maxLocals = method.maxLocals;
        var audit = new ArrayList<String>();
        assertEquals(ClassProcessor.ComputeFlags.SIMPLE_REWRITE, processor.processClass(PublishedFixture.context(node, audit)));
        var after = List.of(method.instructions.toArray());
        assertEquals(before.size() + 1, after.size());
        var inserted = after.stream().filter(instruction -> !before.contains(instruction)).toList();
        assertEquals(1, inserted.size());
        var cast = assertInstanceOf(TypeInsnNode.class, inserted.getFirst());
        assertEquals(Opcodes.CHECKCAST, cast.getOpcode());
        assertEquals(IrisBootstrapProcessor.LIGHT_GETTER, cast.desc);
        assertEquals(1, assertInstanceOf(VarInsnNode.class, cast.getPrevious()).var);
        assertEquals(before, after.stream().filter(instruction -> instruction != cast).toList());
        assertEquals(beforeOthers, others.stream().map(candidate -> List.of(candidate.instructions.toArray())).toList());
        assertEquals(maxStack, method.maxStack);
        assertEquals(maxLocals, method.maxLocals);
        assertEquals(List.of("iris-bootstrap-cast:getBlockAppearance"), audit);
        // Removing the single addition must restore identical serialization of the original tree.
        method.instructions.remove(cast);
        assertArrayEquals(PublishedFixture.bytes(PublishedFixture.original()), PublishedFixture.bytes(node));
    }

    @Test
    void aSecondApplicationIsAnExactNoOp() throws Exception {
        var node = PublishedFixture.original();
        var audit = new ArrayList<String>();
        processor.processClass(PublishedFixture.context(node, audit));
        byte[] once = PublishedFixture.bytes(node);
        assertEquals(ClassProcessor.ComputeFlags.NO_REWRITE, processor.processClass(PublishedFixture.context(node, audit)));
        assertArrayEquals(once, PublishedFixture.bytes(node));
        assertEquals(1, audit.size());
    }

    @Test
    void aForeignClassIsNotMutatedEvenWhenProcessIsCalledDirectly() throws Exception {
        var node = PublishedFixture.original();
        node.name = "unrelated/Helper";
        byte[] before = PublishedFixture.bytes(node);
        assertEquals(ClassProcessor.ComputeFlags.NO_REWRITE, processor.processClass(PublishedFixture.context(node, new ArrayList<>())));
        assertArrayEquals(before, PublishedFixture.bytes(node));
    }

    @Test
    void inconsistentSelectionAndExceptionHandlersFailExplicitly() throws Exception {
        var empty = PublishedFixture.original();
        assertThrows(IllegalStateException.class, () -> processor.processClass(
            new ClassProcessor.TransformationContext(Type.getObjectType(empty.name), empty, true,
                (label, arguments) -> fail("Empty context must not be processed"), () -> new byte[32])));
        var mismatched = PublishedFixture.original();
        mismatched.name = "other/Helper";
        assertThrows(IllegalStateException.class, () -> processor.processClass(
            new ClassProcessor.TransformationContext(Type.getObjectType(IrisBootstrapProcessor.TARGET), mismatched,
                false, (label, arguments) -> fail("Mismatched target must not be processed"), () -> new byte[32])));
        var handler = PublishedFixture.original();
        var method = PublishedFixture.method(handler);
        var originalInstructions = List.of(method.instructions.toArray());
        method.tryCatchBlocks.add(new TryCatchBlockNode(new LabelNode(), new LabelNode(), new LabelNode(), "java/lang/Exception"));
        assertThrows(IllegalStateException.class, () -> processor.processClass(PublishedFixture.context(handler, new ArrayList<>())));
        assertEquals(originalInstructions, List.of(method.instructions.toArray()));
    }

    @Test
    void existingFrameMetadataIsNotReplacedOrRecomputed() throws Exception {
        var node = PublishedFixture.original();
        var method = PublishedFixture.method(node);
        var frame = new FrameNode(Opcodes.F_SAME, 0, null, 0, null);
        method.instructions.insert(frame);
        processor.processClass(PublishedFixture.context(node, new ArrayList<>()));
        assertSame(frame, method.instructions.getFirst());
        assertEquals(Opcodes.F_SAME, frame.type);
        assertNull(frame.local);
        assertNull(frame.stack);
    }

    @Test
    void absentDuplicateOrChangedTargetMethodFailsExplicitly() throws Exception {
        var absent = PublishedFixture.original();
        absent.methods.remove(PublishedFixture.method(absent));
        assertThrows(IllegalStateException.class, () -> processor.processClass(PublishedFixture.context(absent, new ArrayList<>())));
        var duplicate = PublishedFixture.original();
        duplicate.methods.add(PublishedFixture.method(duplicate));
        assertThrows(IllegalStateException.class, () -> processor.processClass(PublishedFixture.context(duplicate, new ArrayList<>())));
        var descriptor = PublishedFixture.original();
        PublishedFixture.method(descriptor).desc = "()Ljava/lang/Object;";
        assertThrows(IllegalStateException.class, () -> processor.processClass(PublishedFixture.context(descriptor, new ArrayList<>())));
    }

    static Stream<Consumer<MethodNode>> invalidLayouts() {
        return Stream.of(
            method -> method.instructions.set(executable(method).get(0), new InsnNode(Opcodes.NOP)),
            method -> ((VarInsnNode) executable(method).get(1)).var = 4,
            method -> ((VarInsnNode) executable(method).get(2)).var = 3,
            method -> ((VarInsnNode) executable(method).get(3)).var = 4,
            method -> method.instructions.insert(new InsnNode(Opcodes.NOP)),
            method -> ((MethodInsnNode) executable(method).get(6)).owner = "other/State",
            method -> ((MethodInsnNode) executable(method).get(6)).name = "otherAppearance",
            method -> ((MethodInsnNode) executable(method).get(6)).desc = "()Ljava/lang/Object;",
            method -> ((MethodInsnNode) executable(method).get(6)).itf = true,
            method -> method.access |= Opcodes.ACC_STATIC,
            method -> method.instructions.insert(executable(method).get(1), new TypeInsnNode(Opcodes.CHECKCAST, "other/Getter")),
            method -> {
                var original = executable(method).get(1);
                method.instructions.insert(original, new TypeInsnNode(Opcodes.CHECKCAST, IrisBootstrapProcessor.LIGHT_GETTER));
                method.instructions.insert(original, new TypeInsnNode(Opcodes.CHECKCAST, IrisBootstrapProcessor.LIGHT_GETTER));
            });
    }

    @ParameterizedTest
    @MethodSource("invalidLayouts")
    void unfamiliarLayoutsFailWithoutPartiallyMutatingTheClass(Consumer<MethodNode> mutation) throws Exception {
        var node = PublishedFixture.original();
        mutation.accept(PublishedFixture.method(node));
        byte[] before = PublishedFixture.bytes(node);
        assertThrows(IllegalStateException.class, () -> processor.processClass(PublishedFixture.context(node, new ArrayList<>())));
        assertArrayEquals(before, PublishedFixture.bytes(node));
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var instruction : method.instructions) { if (instruction.getOpcode() >= 0) { result.add(instruction); } }
        return result;
    }
}
