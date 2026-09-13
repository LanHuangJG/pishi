package pishi.gradle.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Pishi patch generation plugin (originally Robust's `auto-patch-plugin`).
 *
 * Apply it INSTEAD OF the `pishi` plugin when building the patch package. It collects all
 * classes of the variant through the AGP 8 ScopedArtifacts API (replacing the old
 * Transform), then runs the javassist-based patch generation: diff against methodsMap,
 * generate patch classes, smali round-trip and produce patch.jar.
 */
class PishiAutoPatchPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def components = project.extensions.getByType(AndroidComponentsExtension)
        components.onVariants { variant ->
            def taskProvider = project.tasks.register("pishiAutoPatch${variant.name.capitalize()}", PishiAutoPatchTask) {
                it.inputProjectDir = project.projectDir
                it.inputBuildDir = project.layout.buildDirectory.get().asFile
                it.bootClasspathList = new ArrayList<>(project.extensions.getByType(AppExtension).bootClasspath)
            }
            variant.artifacts.use(taskProvider)
                    .wiredWith(PishiAutoPatchTask::getAllClasses)
                    .toGet(ScopedArtifact.CLASSES)
        }
    }
}
