package pishi.gradle.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.pishi.hotfix.Constants
import groovy.xml.XmlSlurper
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Pishi instrumentation plugin (originally Robust's `robust` plugin).
 * Applies to Android modules, reads its config from robust.xml in the module directory.
 *
 * Differences from Robust:
 *  - uses the AGP 8 Instrumentation API instead of the removed Transform API
 *  - debug variants are skipped precisely by buildType (not by task-name heuristics)
 *  - apk-hash support is dropped
 *  - only classes under the configured hotfix packages are visited/rewritten
 *
 * Groovy note: onVariants() is overloaded in AGP, and Groovy does not do implicit
 * closure-to-SAM coercion for overloaded methods, so the Action argument must be cast
 * explicitly (`as Action`).
 */
class PishiPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def components = project.extensions.findByType(AndroidComponentsExtension)
        if (components == null) {
            throw new IllegalStateException("pishi plugin must be applied to an Android module " +
                    "(after 'com.android.application' or 'com.android.library')")
        }

        boolean forceInsert = false
        boolean hotfixMethodLevel = false
        boolean exceptMethodLevel = false
        boolean forceInsertLambda = false
        List<String> hotfixPackages = []
        List<String> hotfixMethods = []
        List<String> exceptPackages = []
        List<String> exceptMethods = []

        def robustXml = new File(project.projectDir, Constants.ROBUST_XML)
        if (robustXml.exists()) {
            def robust = new XmlSlurper().parse(robustXml)
            hotfixPackages = robust.packname.name.collect { it.text() }
            exceptPackages = robust.exceptPackname.name.collect { it.text() }
            hotfixMethods = robust.hotfixMethod.name.collect { it.text() }
            exceptMethods = robust.exceptMethod.name.collect { it.text() }
            // GPathResult never returns null for missing nodes; check the text instead.
            // Missing turnOnRobust defaults to ON (same as Robust's documented default).
            boolean turnOn = String.valueOf(robust.switch.turnOnRobust.text()) != "false"
            forceInsert = String.valueOf(robust.switch.forceInsert.text()) == "true"
            forceInsertLambda = String.valueOf(robust.switch.forceInsertLambda.text()) == "true"
            hotfixMethodLevel = String.valueOf(robust.switch.turnOnHotfixMethod.text()) == "true"
            exceptMethodLevel = String.valueOf(robust.switch.turnOnExceptMethod.text()) == "true"
            if (!turnOn) {
                project.logger.lifecycle("pishi: turnOnRobust=false, instrumentation disabled")
                return
            }
            if (!forceInsert) {
                components.onVariants(components.selector().all(), { variant ->
                    if ("debug" != variant.buildType) {
                        registerInstrumentation(project, components, variant, hotfixPackages,
                                hotfixMethods, exceptPackages, exceptMethods,
                                hotfixMethodLevel, exceptMethodLevel, forceInsertLambda)
                    }
                } as Action)
            } else {
                components.onVariants(components.selector().all(), { variant ->
                    registerInstrumentation(project, components, variant, hotfixPackages,
                            hotfixMethods, exceptPackages, exceptMethods,
                            hotfixMethodLevel, exceptMethodLevel, forceInsertLambda)
                } as Action)
            }
        } else {
            project.logger.warn("pishi: robust.xml not found in ${project.projectDir}, " +
                    "no package will be instrumented until you configure hotfixPackage")
        }
    }

    private void registerInstrumentation(Project project, AndroidComponentsExtension components,
                                         def variant, List<String> hotfixPackages, List<String> hotfixMethods,
                                         List<String> exceptPackages, List<String> exceptMethods,
                                         boolean hotfixMethodLevel, boolean exceptMethodLevel,
                                         boolean forceInsertLambda) {
        variant.instrumentation.transformClassesWith(PishiClassVisitorFactory.class, InstrumentationScope.ALL) { params ->
            params.variantName.set(project.path + ":" + variant.name)
            params.hotfixPackages.set(hotfixPackages)
            params.hotfixMethods.set(hotfixMethods)
            params.exceptPackages.set(exceptPackages)
            params.exceptMethods.set(exceptMethods)
            params.hotfixMethodLevel.set(hotfixMethodLevel)
            params.exceptMethodLevel.set(exceptMethodLevel)
            params.forceInsertLambda.set(forceInsertLambda)
            params.methodMapPath.set(new File(project.layout.buildDirectory.get().asFile,
                    Constants.METHOD_MAP_OUT_PATH).absolutePath)
        }
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
        project.logger.lifecycle("pishi: instrumentation registered for variant ${variant.name}")

        // methodMap persistence: the ASM worker runs in an isolated classloader, so the
        // visitors append to methodsMap.jsonl directly; the file just starts empty per run
        File methodMapFile = new File(project.layout.buildDirectory.get().asFile, Constants.METHOD_MAP_OUT_PATH)
        project.tasks.configureEach { task ->
            if (task.name.contains("ClassesWithAsm") || task.name.contains("AsmClassVisitorFactory")) {
                task.doFirst {
                    methodMapFile.parentFile.mkdirs()
                    methodMapFile.delete()
                }
            }
        }
    }
}
