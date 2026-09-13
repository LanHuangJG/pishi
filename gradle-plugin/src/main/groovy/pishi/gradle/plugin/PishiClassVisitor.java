package pishi.gradle.plugin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;

import pishi.gradle.plugin.asm.AsmInsertImpl;

/**
 * Per-class pipeline for the AGP 8 Instrumentation API. Mirrors Robust 0.4.99's ASM path:
 *  1. buffer the class into a ClassNode
 *  2. make the class public (Robust's javassist AccessFlag.setPublic step), so patches
 *     can access every member
 *  3. for eligible classes (package filter + has declared methods + not an interface),
 *     run {@link AsmInsertImpl#transformCode} to insert the changeQuickRedirect field
 *     and dispatch stubs
 */
public class PishiClassVisitor extends ClassVisitor implements Opcodes {

    private final ClassVisitor nextClassVisitor;
    private final AsmInsertImpl insertStrategy;

    private ClassNode classNode;
    private boolean classEligible;
    private int declaredMethodCount;

    public PishiClassVisitor(ClassVisitor nextClassVisitor, AsmInsertImpl insertStrategy) {
        super(ASM9);
        this.nextClassVisitor = nextClassVisitor;
        this.insertStrategy = insertStrategy;
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
        if (classEligible && declaredMethodCount > 0) {
            try {
                bytes = insertStrategy.transformCode(bytes, classNode.name);
            } catch (IOException e) {
                throw new RuntimeException("pishi: failed to instrument class " + classNode.name, e);
            }
        }
        new ClassReader(bytes).accept(nextClassVisitor, 0);
    }

    private static byte[] toBytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
