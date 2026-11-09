package qouteall.imm_ptl.iris_bootstrap;

import net.neoforged.neoforgespi.transformation.ClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

final class PublishedFixture {
    static byte[] originalBytes() throws IOException {
        try (var jar = new JarFile(System.getProperty("bootstrap.publishedIris"))) {
            try (var input = jar.getInputStream(jar.getJarEntry(IrisBootstrapProcessor.TARGET + ".class"))) {
                return input.readAllBytes();
            }
        }
    }

    static ClassNode original() throws IOException {
        var node = new ClassNode();
        new ClassReader(originalBytes()).accept(node, 0);
        return node;
    }

    static MethodNode method(ClassNode node) {
        return node.methods.stream().filter(method -> method.name.equals(IrisBootstrapProcessor.METHOD)).findFirst().orElseThrow();
    }

    static ClassProcessor.TransformationContext context(ClassNode node, List<String> audit) {
        return new ClassProcessor.TransformationContext(Type.getObjectType(node.name), node, false,
            (label, arguments) -> audit.add(label + ":" + String.join(",", arguments)), () -> new byte[32]);
    }

    static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Copies the exact published appearance method into a helper without unrelated FML dependencies. */
    static byte[] model(boolean patched) throws IOException {
        var source = original();
        var node = new ClassNode();
        node.version = Opcodes.V25;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = IrisBootstrapProcessor.TARGET;
        node.superName = "java/lang/Object";
        node.methods.add(constructor());
        node.methods.add(method(source));
        if (patched) { new IrisBootstrapProcessor().processClass(context(node, new ArrayList<>())); }
        return bytes(node);
    }

    static MethodNode constructor() {
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return constructor;
    }

    static Path libraryJar() { return Path.of(System.getProperty("bootstrap.libraryJar")); }

    private PublishedFixture() {}
}
