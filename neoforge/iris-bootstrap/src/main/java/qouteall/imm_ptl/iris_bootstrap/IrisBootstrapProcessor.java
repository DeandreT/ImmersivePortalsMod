package qouteall.imm_ptl.iris_bootstrap;

import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ClassProcessorIds;
import net.neoforged.neoforgespi.transformation.ProcessorName;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Prevents an early Iris helper verification from resolving the Minecraft world hierarchy. */
public final class IrisBootstrapProcessor implements ClassProcessor {
    static final String TARGET = "net/irisshaders/iris/platform/IrisForgeHelpers";
    static final String METHOD = "getBlockAppearance";
    static final String LIGHT_GETTER = "net/minecraft/world/level/BlockAndLightGetter";
    static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String DESCRIPTOR = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
        + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;"
        + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
    static final String APPEARANCE_DESCRIPTOR = "(Lnet/minecraft/world/level/BlockAndLightGetter;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;"
        + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)"
        + "Lnet/minecraft/world/level/block/state/BlockState;";

    @Override
    public ProcessorName name() { return new ProcessorName("riftwoven", "iris_bootstrap"); }

    @Override
    public Set<ProcessorName> runsAfter() { return Set.of(); }

    @Override
    public Set<ProcessorName> runsBefore() {
        return Set.of(ClassProcessorIds.MIXIN_FRAME_CONTEXT, ClassProcessorIds.MIXIN);
    }

    @Override
    public OrderingHint orderingHint() { return OrderingHint.EARLY; }

    @Override
    public boolean handlesClass(SelectionContext context) {
        return !context.empty() && TARGET.equals(context.type().getInternalName());
    }

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        if (!TARGET.equals(context.type().getInternalName())) { return ComputeFlags.NO_REWRITE; }
        if (context.empty() || !TARGET.equals(context.node().name)) {
            throw unsupported("inconsistent target class");
        }
        MethodNode target = null;
        for (var method : context.node().methods) {
            if (!METHOD.equals(method.name)) { continue; }
            if (target != null || !DESCRIPTOR.equals(method.desc)) {
                throw unsupported("unexpected method count or descriptor");
            }
            target = method;
        }
        if (target == null || target.access != Opcodes.ACC_PUBLIC || !target.tryCatchBlocks.isEmpty()) {
            throw unsupported("missing method or unexpected access/exception handlers");
        }
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (var instruction : target.instructions) {
            if (instruction.getOpcode() >= 0) { instructions.add(instruction); }
        }
        boolean alreadyPatched = instructions.size() == 9;
        int offset = alreadyPatched ? 1 : 0;
        if (instructions.size() != 8 && !alreadyPatched) { throw unsupported("unexpected instruction count"); }
        if (!load(instructions.get(0), 2) || !load(instructions.get(1), 1)
            || (alreadyPatched && !cast(instructions.get(2)))
            || !load(instructions.get(2 + offset), 4) || !load(instructions.get(3 + offset), 3)
            || instructions.get(4 + offset).getOpcode() != Opcodes.ACONST_NULL
            || instructions.get(5 + offset).getOpcode() != Opcodes.ACONST_NULL
            || !appearanceCall(instructions.get(6 + offset))
            || instructions.get(7 + offset).getOpcode() != Opcodes.ARETURN) {
            throw unsupported("unexpected appearance-call instruction pattern");
        }
        if (alreadyPatched) { return ComputeFlags.NO_REWRITE; }
        // CHECKCAST preserves stack height and all existing frames. The following call now
        // receives its exact symbolic argument type without verifier hierarchy resolution.
        target.instructions.insert(instructions.get(1), new TypeInsnNode(Opcodes.CHECKCAST, LIGHT_GETTER));
        context.audit("iris-bootstrap-cast", METHOD);
        return ComputeFlags.SIMPLE_REWRITE;
    }

    private static boolean load(AbstractInsnNode instruction, int local) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD
            && variable.var == local;
    }

    private static boolean cast(AbstractInsnNode instruction) {
        return instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST
            && LIGHT_GETTER.equals(type.desc);
    }

    private static boolean appearanceCall(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && !call.itf && BLOCK_STATE.equals(call.owner) && "getAppearance".equals(call.name)
            && APPEARANCE_DESCRIPTOR.equals(call.desc);
    }

    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException("Unsupported IrisForgeHelpers.getBlockAppearance layout: " + reason);
    }
}
