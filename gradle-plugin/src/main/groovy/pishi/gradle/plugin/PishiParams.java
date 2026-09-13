package pishi.gradle.plugin;

import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Parameters injected by AGP into {@link PishiClassVisitorFactory}.
 * Values come from robust.xml (same config surface as Robust).
 */
public interface PishiParams extends InstrumentationParameters {

    Property<String> getVariantName();

    /** packages/classes that need instrumentation */
    ListProperty<String> getHotfixPackages();

    /** method name patterns that need instrumentation */
    ListProperty<String> getHotfixMethods();

    /** packages excluded from instrumentation */
    ListProperty<String> getExceptPackages();

    /** method name patterns excluded from instrumentation */
    ListProperty<String> getExceptMethods();

    Property<Boolean> getHotfixMethodLevel();

    Property<Boolean> getExceptMethodLevel();

    Property<Boolean> getForceInsertLambda();
}
