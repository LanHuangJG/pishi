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
 *
 * Usage:
 * <pre>
 * pishi {
 *     hotfixPackages = ['com.example.app']
 * }
 * </pre>
 * Config lives in the pishi{} extension (robust.xml is still read as a fallback for
 * Robust migrants). Patch generation is enabled with `-Ppishi.patch=true` instead of
 * swapping plugins.
 */
class PishiPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def ext = project.extensions.create('pishi', PishiExtension)
        def components = project.extensions.findByType(AndroidComponentsExtension)
        if (components == null) {
            throw new IllegalStateException("pishi plugin must be applied to an Android module " +
                    "(after 'com.android.application' or 'com.android.library')")
        }

        // patch generation mode: build the same app with -Ppishi.patch=true
        def patchFlag = project.findProperty('pishi.patch')
        if (patchFlag != null && patchFlag != 'false') {
            project.logger.lifecycle("pishi: pishi.patch=true, registering patch generation instead of instrumentation")
            PishiAutoPatchPlugin.registerPatchGeneration(project, components)
            return
        }

        // robust.xml fallback config (for Robust migrants); pishi{} DSL wins when set
        boolean turnOn = true
        boolean forceInsertLambda = false
        boolean hotfixMethodLevel = false
        boolean exceptMethodLevel = false
        List<String> xmlHotfixPackages = []
        List<String> hotfixMethods = []
        List<String> xmlExceptPackages = []
        List<String> exceptMethods = []

        def robustXml = new File(project.projectDir, Constants.ROBUST_XML)
        if (robustXml.exists()) {
            def robust = new XmlSlurper().parse(robustXml)
            xmlHotfixPackages = robust.packname.name.collect { it.text() }
            xmlExceptPackages = robust.exceptPackname.name.collect { it.text() }
            hotfixMethods = robust.hotfixMethod.name.collect { it.text() }
            exceptMethods = robust.exceptMethod.name.collect { it.text() }
            // GPathResult never returns null for missing nodes; check the text instead.
            // Missing turnOnRobust defaults to ON (same as Robust's documented default).
            turnOn = String.valueOf(robust.switch.turnOnRobust.text()) != "false"
            forceInsertLambda = String.valueOf(robust.switch.forceInsertLambda.text()) == "true"
            hotfixMethodLevel = String.valueOf(robust.switch.turnOnHotfixMethod.text()) == "true"
            exceptMethodLevel = String.valueOf(robust.switch.turnOnExceptMethod.text()) == "true"
        }

        if (!turnOn) {
            project.logger.lifecycle("pishi: turnOnRobust=false, instrumentation disabled")
            return
        }

        List<String> hotfixPackages = !ext.hotfixPackages.isEmpty() ? ext.hotfixPackages : xmlHotfixPackages
        List<String> exceptPackages = !ext.exceptPackages.isEmpty() ? ext.exceptPackages : xmlExceptPackages
        if (ext.forceInsertLambda != null) {
            forceInsertLambda = ext.forceInsertLambda
        }

        if (hotfixPackages.isEmpty()) {
            project.logger.warn("pishi: no hotfixPackage configured (pishi{} DSL or robust.xml), " +
                    "no class will be instrumented")
        }

        components.onVariants(components.selector().all(), { variant ->
            if ("debug" != variant.buildType) {
                registerInstrumentation(project, variant, hotfixPackages, hotfixMethods,
                        exceptPackages, exceptMethods, hotfixMethodLevel,
                        exceptMethodLevel, forceInsertLambda)
            }
        } as Action)
    }

    private void registerInstrumentation(Project project, def variant, List<String> hotfixPackages,
                                         List<String> hotfixMethods, List<String> exceptPackages,
                                         List<String> exceptMethods, boolean hotfixMethodLevel,
                                         boolean exceptMethodLevel, boolean forceInsertLambda) {
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

        File methodMapFile = new File(project.layout.buildDirectory.get().asFile, Constants.METHOD_MAP_OUT_PATH)
        project.tasks.configureEach { task ->
            if (task.name.contains("ClassesWithAsm") || task.name.contains("AsmClassVisitorFactory")) {
                // fresh methodMap per run; the visitors append to it
                task.doFirst {
                    methodMapFile.parentFile.mkdirs()
                    methodMapFile.delete()
                }
                // auto-archive per app version: autopatch later picks the matching file
                task.doLast {
                    String vName = versionNameOf(variant)
                    if (vName != null && methodMapFile.exists()) {
                        File archiveDir = new File(project.projectDir, "robust" + File.separator + vName)
                        archiveDir.mkdirs()
                        java.nio.file.Files.copy(methodMapFile.toPath(),
                                new File(archiveDir, "methodsMap.jsonl").toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        project.logger.lifecycle("pishi: methodsMap archived to ${archiveDir}")
                    }
                }
            }
        }
        // same auto-archive for the R8 mapping (needed to patch obfuscated builds)
        File mappingFile = new File(project.layout.buildDirectory.get().asFile,
                "outputs/mapping/" + variant.name + "/mapping.txt")
        project.tasks.configureEach { task ->
            if (task.name.startsWith("minify") && task.name.endsWith("WithR8")) {
                task.doLast {
                    String vName = versionNameOf(variant)
                    if (vName != null && mappingFile.exists()) {
                        File archiveDir = new File(project.projectDir, "robust" + File.separator + vName)
                        archiveDir.mkdirs()
                        java.nio.file.Files.copy(mappingFile.toPath(),
                                new File(archiveDir, "mapping.txt").toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        project.logger.lifecycle("pishi: mapping.txt archived to ${archiveDir}")
                    }
                }
            }
        }
    }

    private static String versionNameOf(def variant) {
        if (variant == null) {
            return null
        }
        // AGP 8.x: versionName lives on VariantOutput (Property<String>)
        try {
            def outputs = variant.outputs
            if (outputs != null && !outputs.isEmpty()) {
                def vn = outputs[0].versionName
                return vn instanceof org.gradle.api.provider.Provider ? String.valueOf(vn.get()) : String.valueOf(vn)
            }
        } catch (Exception ignored) {
        }
        try {
            def vn = variant.versionName
            return vn instanceof org.gradle.api.provider.Provider ? String.valueOf(vn.get()) : String.valueOf(vn)
        } catch (Exception ignored) {
            return null
        }
    }
}
