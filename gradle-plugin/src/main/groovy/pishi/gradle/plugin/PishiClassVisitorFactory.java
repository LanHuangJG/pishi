package pishi.gradle.plugin;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import org.objectweb.asm.ClassVisitor;

import pishi.gradle.plugin.asm.AsmInsertImpl;

/**
 * AGP 8 Instrumentation-API entry point of Pishi (replaces Robust's Transform).
 * AGP instantiates one factory per instrumented variant; the per-class visitors share a
 * single {@link AsmInsertImpl} so the methodMap stays consistent across the build.
 */
public abstract class PishiClassVisitorFactory implements AsmClassVisitorFactory<PishiParams> {

    private AsmInsertImpl insertStrategy;

    private synchronized AsmInsertImpl strategy() {
        if (insertStrategy == null) {
            PishiParams params = getParameters().get();
            insertStrategy = new AsmInsertImpl(
                    params.getHotfixPackages().get(),
                    params.getHotfixMethods().get(),
                    params.getExceptPackages().get(),
                    params.getExceptMethods().get(),
                    params.getHotfixMethodLevel().get(),
                    params.getExceptMethodLevel().get(),
                    params.getForceInsertLambda().get());
            PishiRegistry.METHOD_MAPS.put(params.getVariantName().get(), insertStrategy.methodMap);
        }
        return insertStrategy;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new PishiClassVisitor(nextClassVisitor, strategy());
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        // package/method filtering happens inside PishiClassVisitor / AsmInsertImpl
        return true;
    }
}
