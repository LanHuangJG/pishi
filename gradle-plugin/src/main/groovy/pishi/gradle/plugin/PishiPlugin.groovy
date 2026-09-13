package pishi.gradle.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.pishi.hotfix.Constants
import groovy.xml.XmlSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project

import java.util.zip.GZIPOutputStream

/**
 * Pishi instrumentation plugin (originally Robust's `robust` plugin).
 * Applies to Android modules, reads its config from robust.xml in the module directory.
 *
 * Differences from Robust:
 *  - uses the AGP 8 Instrumentation API instead of the removed Transform API
 *  - debug variants are skipped precisely by buildType (not by task-name heuristics)
 *  - turnOnRobust=false now really disables instrumentation
 *  - apk-hash support is dropped
 */
class PishiPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def components = project.extensions.findByType(AndroidComponentsExtension)
        if (components == null) {
            throw new IllegalStateException("pishi plugin must be applied to an Android module " +
                    "(after 'com.android.application' or 'com.android.library')")
        }

        boolean turnOn = true
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
            turnOn = robust.switch.turnOnRobust == null || "true" == String.valueOf(robust.switch.turnOnRobust.text())
            forceInsert = robust.switch.forceInsert != null && "true" == String.valueOf(robust.switch.forceInsert.text())
            forceInsertLambda = robust.switch.forceInsertLambda != null && "true" == String.valueOf(robust.switch.forceInsertLambda.text())
            hotfixMethodLevel = robust.switch.filterMethod != null && "true" == String.valueOf(robust.switch.turnOnHotfixMethod.text())
            exceptMethodLevel = robust.switch.filterMethod != null && "true" == String.valueOf(robust.switch.turnOnExceptMethod.text())
        } else {
            project.logger.warn("pishi: robust.xml not found in ${project.projectDir}, " +
                    "no package will be instrumented until you configure hotfixPackage")
        }

        if (!turnOn) {
            project.logger.lifecycle("pishi: turnOnRobust=false, instrumentation disabled")
            return
        }

        components.onVariants { variant ->
            // Robust only instruments non-debug builds unless forceInsert is enabled
            if (!forceInsert && "debug" == variant.buildType) {
                return
            }
            variant.instrumentation.transformClassesWith(PishiClassVisitorFactory.class, InstrumentationScope.ALL) { params ->
                params.variantName.set(variant.name)
                params.hotfixPackages.set(hotfixPackages)
                params.hotfixMethods.set(hotfixMethods)
                params.exceptPackages.set(exceptPackages)
                params.exceptMethods.set(exceptMethods)
                params.hotfixMethodLevel.set(hotfixMethodLevel)
                params.exceptMethodLevel.set(exceptMethodLevel)
                params.forceInsertLambda.set(forceInsertLambda)
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
            project.logger.lifecycle("pishi: instrumentation registered for variant ${variant.name}")
        }

        // persist methodMap once the ASM instrumentation task finishes,
        // same file + format as Robust (gzip'ed serialized map)
        project.tasks.configureEach { task ->
            if (task.name.toLowerCase().contains("asmclassvisitorfactory")) {
                task.doLast {
                    Map<String, Integer> methodMap = null
                    for (Map<String, Integer> candidate : PishiRegistry.METHOD_MAPS.values()) {
                        if (methodMap == null || candidate.size() > methodMap.size()) {
                            methodMap = candidate
                        }
                    }
                    if (methodMap == null || methodMap.isEmpty()) {
                        project.logger.warn("pishi: methodMap is empty, no method was instrumented")
                        return
                    }
                    File out = new File(project.layout.buildDirectory.get().asFile, Constants.METHOD_MAP_OUT_PATH)
                    out.parentFile.mkdirs()
                    def byteOut = new ByteArrayOutputStream()
                    def objOut = new ObjectOutputStream(byteOut)
                    objOut.writeObject(methodMap)
                    objOut.close()
                    def gzip = new GZIPOutputStream(new FileOutputStream(out))
                    gzip.write(byteOut.toByteArray())
                    gzip.flush()
                    gzip.close()
                    project.logger.lifecycle("pishi: methodMap written to ${out} (${methodMap.size()} methods)")
                }
            }
        }
    }
}
