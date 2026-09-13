package pishi.gradle.plugin;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.Variant;
import com.android.build.gradle.BaseExtension;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Pishi patch generation plugin (originally Robust's `auto-patch-plugin`).
 *
 * Apply it INSTEAD OF the `pishi` plugin when building the patch package — or, simpler,
 * keep `pishi` applied and pass `-Ppishi.patch=true`: both paths end up in
 * {@link #registerPatchGeneration}. It collects all classes of the variant through the
 * AGP 8 ScopedArtifacts API, then runs the javassist-based patch generation: diff
 * against methodsMap, generate patch classes, smali round-trip and produce patch.jar.
 *
 * Written in Java on purpose: the ScopedArtifacts Kotlin API (toGet with two Function1
 * getters) cannot be invoked reliably through Groovy dynamic dispatch.
 */
public class PishiAutoPatchPlugin implements Plugin<Project> {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void apply(Project project) {
        AndroidComponentsExtension components = project.getExtensions().getByType(AndroidComponentsExtension.class);
        registerPatchGeneration(project, components);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerPatchGeneration(Project project, AndroidComponentsExtension components) {
        // BaseExtension covers both application and library modules (AppExtension is
        // application-only and removed in AGP 9)
        BaseExtension baseExtension = project.getExtensions().findByType(BaseExtension.class);

        components.onVariants(components.selector().all(), (Action) variant -> {
            Variant v = (Variant) variant;
            String name = v.getName();
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            TaskProvider<PishiAutoPatchTask> taskProvider = project.getTasks().register(
                    "pishiAutoPatch" + cap, PishiAutoPatchTask.class, task -> {
                        task.getInputProjectDir().set(project.getProjectDir());
                        task.getInputBuildDir().set(project.getLayout().getBuildDirectory().get().getAsFile());
                        // resolved lazily: bootClasspath is empty until the SDK is loaded
                        task.getBootClasspathList().set(project.getProviders().provider(() ->
                                baseExtension == null ? new ArrayList<java.io.File>() : baseExtension.getBootClasspath()));
                        if (v instanceof ApplicationVariant) {
                            task.getBaseVersionName().set(((ApplicationVariant) v).getOutputs().get(0).getVersionName());
                        }
                    });
            v.getArtifacts().forScope(com.android.build.api.variant.ScopedArtifacts.Scope.ALL)
                    .use(taskProvider).toGet(ScopedArtifact.CLASSES.INSTANCE,
                            PishiAutoPatchTask::getAllJars, PishiAutoPatchTask::getAllDirs);
            // `assembleRelease -Ppishi.patch=true` produces the patch in one command
            project.getTasks().matching(t -> t.getName().equals("assemble" + cap))
                    .configureEach(t -> t.dependsOn(taskProvider));
        });
    }
}
