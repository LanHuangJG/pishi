package pishi.gradle.plugin;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import org.objectweb.asm.ClassVisitor;

import pishi.gradle.plugin.asm.AsmInsertImpl;

/**
 * AGP 8 Instrumentation-API entry point of Pishi (replaces Robust's Transform).
 *
 * The factory instance is SERIALIZED into the ASM worker, so it must stay stateless:
 * each visitor builds its own {@link AsmInsertImpl} and appends its class's method
 * entries straight to methodsMap.jsonl (method IDs are deterministic hashes of the
 * method key, so order and worker isolation do not matter).
 *
 * isInstrumentable filters by the configured hotfix packages so third-party classes are
 * not visited/rewritten at all.
 */
public abstract class PishiClassVisitorFactory implements AsmClassVisitorFactory<PishiParams> {

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        PishiParams params = getParameters().get();
        AsmInsertImpl strategy = new AsmInsertImpl(
                params.getHotfixPackages().get(),
                params.getHotfixMethods().get(),
                params.getExceptPackages().get(),
                params.getExceptMethods().get(),
                params.getHotfixMethodLevel().get(),
                params.getExceptMethodLevel().get(),
                params.getForceInsertLambda().get());
        return new PishiClassVisitor(nextClassVisitor, strategy, params.getMethodMapPath().getOrNull());
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        PishiParams params = getParameters().get();
        String dotted = classData.getClassName().replace('/', '.');
        for (String exceptName : params.getExceptPackages().get()) {
            if (dotted.startsWith(exceptName)) {
                return false;
            }
        }
        for (String name : params.getHotfixPackages().get()) {
            if (dotted.startsWith(name)) {
                return true;
            }
        }
        return false;
    }
}
