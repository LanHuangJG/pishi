package pishi.gradle.plugin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import pishi.gradle.plugin.asm.AsmInsertImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Per-class pipeline for the AGP 8 Instrumentation API. Mirrors Robust 0.4.99's ASM path:
 *  1. buffer the class into a ClassNode
 *  2. make the class public (Robust's javassist AccessFlag.setPublic step), so patches
 *     can access every member
 *  3. for eligible classes (package filter + has declared methods + not an interface),
 *     run {@link AsmInsertImpl#transformCode} to insert the changeQuickRedirect field
 *     and dispatch stubs
 *
 * After instrumentation the class's method entries (method key -> deterministic id) are
 * appended as one JSON line to methodsMap.jsonl — the file, not memory, is the methodMap
 * store, because the ASM worker runs in an isolated classloader.
 */
public class PishiClassVisitor extends ClassVisitor implements Opcodes {

    private static final Object FILE_LOCK = new Object();

    private final ClassVisitor nextClassVisitor;
    private final AsmInsertImpl insertStrategy;
    private final String methodMapPath;

    private ClassNode classNode;
    private boolean classEligible;
    private int declaredMethodCount;

    public PishiClassVisitor(ClassVisitor nextClassVisitor, AsmInsertImpl insertStrategy, String methodMapPath) {
        super(ASM9);
        this.nextClassVisitor = nextClassVisitor;
        this.insertStrategy = insertStrategy;
        this.methodMapPath = methodMapPath;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        classNode = new ClassNode();
        cv = classNode;
        classEligible = insertStrategy.isNeedInsertClass(name.replace('/', '.'))
                && (access & ACC_INTERFACE) == 0;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (!"<init>".equals(name) && !"<clinit>".equals(name)) {
            declaredMethodCount++;
        }
        return super.visitMethod(access, name, desc, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
        classNode.access = (classNode.access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
        byte[] bytes = toBytes(classNode);
        boolean instrumented = false;
        if (classEligible && declaredMethodCount > 0) {
            try {
                bytes = insertStrategy.transformCode(bytes, classNode.name);
                instrumented = true;
            } catch (IOException e) {
                throw new RuntimeException("pishi: failed to instrument class " + classNode.name, e);
            }
        }
        new ClassReader(bytes).accept(nextClassVisitor, 0);
        if (instrumented && methodMapPath != null && !insertStrategy.methodMap.isEmpty()) {
            appendMethodMapLine();
        }
    }

    private void appendMethodMapLine() {
        StringBuilder line = new StringBuilder("{\"class\":\"").append(classNode.name.replace("/", "."))
                .append("\",\"methods\":{");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : insertStrategy.methodMap.entrySet()) {
            if (!first) {
                line.append(',');
            }
            first = false;
            line.append('"').append(entry.getKey().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\":").append(entry.getValue());
        }
        line.append("}}\n");
        Path path = java.nio.file.Paths.get(methodMapPath);
        try {
            synchronized (FILE_LOCK) {
                Files.write(path, line.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new RuntimeException("pishi: failed to append methodMap entry to " + methodMapPath, e);
        }
    }

    private static byte[] toBytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
