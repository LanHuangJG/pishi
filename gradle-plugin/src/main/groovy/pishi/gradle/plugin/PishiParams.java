package pishi.gradle.plugin;

import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Parameters injected by AGP into {@link PishiClassVisitorFactory}.
 * Values come from robust.xml (same config surface as Robust).
 */
public interface PishiParams extends InstrumentationParameters {

    @Input
    Property<String> getVariantName();

    /** packages/classes that need instrumentation */
    @Input
    ListProperty<String> getHotfixPackages();

    /** method name patterns that need instrumentation */
    @Input
    ListProperty<String> getHotfixMethods();

    /** packages excluded from instrumentation */
    @Input
    ListProperty<String> getExceptPackages();

    /** method name patterns excluded from instrumentation */
    @Input
    ListProperty<String> getExceptMethods();

    @Input
    Property<Boolean> getHotfixMethodLevel();

    @Input
    Property<Boolean> getExceptMethodLevel();

    @Input
    Property<Boolean> getForceInsertLambda();

    /** absolute path of the methodsMap.jsonl file the visitors append to */
    @Input
    @Optional
    Property<String> getMethodMapPath();
}
