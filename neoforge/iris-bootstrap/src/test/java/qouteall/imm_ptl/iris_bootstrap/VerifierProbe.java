package qouteall.imm_ptl.iris_bootstrap;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A forked, fully verified JVM: only this loader owns the isolated Minecraft-shaped stubs. */
public final class VerifierProbe {
    private static final String BLOCK_GETTER = "net/minecraft/world/level/BlockGetter";
    private static final String TINT_GETTER = "net/minecraft/client/renderer/block/BlockAndTintGetter";
    private static final String DIRECTION = "net/minecraft/core/Direction";
    private static final String POSITION = "net/minecraft/core/BlockPos";

    public static void main(String[] args) throws Exception {
        require(Runtime.version().feature() == 25, "The actual verifier fork must use Java 25");
        boolean patched = args[0].equals("patched");
        System.setProperty("bootstrap.publishedIris", args[1]);
        var bytes = new HashMap<String, byte[]>();
        bytes.put(BLOCK_GETTER, emptyInterface(BLOCK_GETTER));
        bytes.put(IrisBootstrapProcessor.LIGHT_GETTER, emptyInterface(IrisBootstrapProcessor.LIGHT_GETTER, BLOCK_GETTER));
        bytes.put(TINT_GETTER, emptyInterface(TINT_GETTER, IrisBootstrapProcessor.LIGHT_GETTER));
        bytes.put(DIRECTION, emptyClass(DIRECTION));
        bytes.put(POSITION, emptyClass(POSITION));
        bytes.put("probe/SampleTint", emptyClass("probe/SampleTint", TINT_GETTER));
        bytes.put(IrisBootstrapProcessor.BLOCK_STATE, blockState());
        bytes.put(IrisBootstrapProcessor.TARGET, PublishedFixture.model(patched));
        var loader = new TrackingLoader(bytes);
        Class<?> helper = Class.forName(IrisBootstrapProcessor.TARGET.replace('/', '.'), true, loader);
        var early = List.copyOf(loader.defined);
        require(early.contains(BLOCK_GETTER) != patched,
            "Original must resolve BlockGetter during helper verification; patched must not. Actual: " + early);
        if (patched) {
            require(!early.contains(IrisBootstrapProcessor.LIGHT_GETTER) && !early.contains(TINT_GETTER),
                "CHECKCAST must avoid the entire early world hierarchy, not only its root");
        }
        Object instance = helper.getConstructor().newInstance();
        Class<?> tint = loader.loadClass(TINT_GETTER.replace('/', '.'));
        Class<?> stateClass = loader.loadClass(IrisBootstrapProcessor.BLOCK_STATE.replace('/', '.'));
        Class<?> directionClass = loader.loadClass(DIRECTION.replace('/', '.'));
        Class<?> positionClass = loader.loadClass(POSITION.replace('/', '.'));
        Object level = loader.loadClass("probe.SampleTint").getConstructor().newInstance();
        Object state = stateClass.getConstructor().newInstance();
        Object result = stateClass.getConstructor().newInstance();
        Object direction = directionClass.getConstructor().newInstance();
        Object position = positionClass.getConstructor().newInstance();
        stateClass.getField("result").set(state, result);
        var appearance = helper.getMethod(IrisBootstrapProcessor.METHOD, tint, stateClass, directionClass, positionClass);
        require(appearance.invoke(instance, level, state, direction, position) == result,
            "The original call must return the exact original result object");
        Object[] observed = (Object[]) stateClass.getField("observed").get(state);
        require(observed.length == 5 && observed[0] == level && observed[1] == position
            && observed[2] == direction && observed[3] == null && observed[4] == null,
            "The appearance receiver, argument order and both null tail arguments must be unchanged");
        require(appearance.invoke(instance, null, state, null, null) == result,
            "A null level must retain CHECKCAST/null semantics and the original result");
        observed = (Object[]) stateClass.getField("observed").get(state);
        for (Object value : observed) { require(value == null, "Null arguments must remain null"); }
        try {
            appearance.invoke(instance, level, null, direction, position);
            throw new AssertionError("Null state must retain the original NullPointerException");
        } catch (InvocationTargetException failure) {
            require(failure.getCause() instanceof NullPointerException, "Null receiver behavior changed: " + failure);
        }
        require(loader.defined.contains(BLOCK_GETTER), "Normal later use must still load the real hierarchy");
        System.out.println("VERIFIED mode=" + args[0] + " early=" + early + " later_call_preserved=true");
    }

    private static byte[] emptyInterface(String name, String... parents) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
            name, null, "java/lang/Object", parents);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name, String... interfaces) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", interfaces);
        PublishedFixture.constructor().accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] blockState() {
        var writer = new ClassWriter(0);
        String state = IrisBootstrapProcessor.BLOCK_STATE;
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, state, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "result", "L" + state + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "observed", "[Ljava/lang/Object;", null, null).visitEnd();
        PublishedFixture.constructor().accept(writer);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "getAppearance", IrisBootstrapProcessor.APPEARANCE_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_5);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        method.visitVarInsn(Opcodes.ASTORE, 6);
        for (int i = 0; i < 5; i++) {
            method.visitVarInsn(Opcodes.ALOAD, 6);
            method.visitInsn(Opcodes.ICONST_0 + i);
            method.visitVarInsn(Opcodes.ALOAD, i + 1);
            method.visitInsn(Opcodes.AASTORE);
        }
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 6);
        method.visitFieldInsn(Opcodes.PUTFIELD, state, "observed", "[Ljava/lang/Object;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, state, "result", "L" + state + ";");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(3, 7);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class TrackingLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        private final List<String> defined = new ArrayList<>();

        TrackingLoader(Map<String, byte[]> definitions) {
            super(ClassLoader.getPlatformClassLoader());
            this.definitions = definitions;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            byte[] bytes = definitions.get(internal);
            if (bytes == null) { throw new ClassNotFoundException(name); }
            Class<?> result = defineClass(name, bytes, 0, bytes.length);
            defined.add(internal);
            return result;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
