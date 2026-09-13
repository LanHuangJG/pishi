package pishi.gradle.plugin

import com.pishi.hotfix.Constants
import com.pishi.hotfix.autopatch.Config
import com.pishi.hotfix.autopatch.InlineClassFactory
import com.pishi.hotfix.autopatch.NameManger
import com.pishi.hotfix.autopatch.PatchesControlFactory
import com.pishi.hotfix.autopatch.PatchesFactory
import com.pishi.hotfix.autopatch.PatchesInfoFactory
import com.pishi.hotfix.autopatch.ReadAnnotation
import com.pishi.hotfix.autopatch.ReadMapping
import com.pishi.hotfix.autopatch.ReadXML
import com.pishi.hotfix.autopatch.ReflectUtils
import com.pishi.hotfix.utils.JavaUtils
import com.pishi.hotfix.utils.SmaliTool
import javassist.CannotCompileException
import javassist.CtClass
import javassist.CtMethod
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
/**
 * Ported from Robust's AutoPatchTransform: generates the patch jar by diffing the
 * patched build's classes against the methodsMap recorded by the `pishi` plugin.
 *
 * Unlike Robust, this task does NOT fail the build when the patch is generated
 * successfully — it simply logs loudly and lets the build continue.
 */
abstract class PishiAutoPatchTask extends DefaultTask {

    @InputFiles
    @Classpath
    abstract ListProperty<FileSystemLocation> getAllClasses()

    @Internal
    File inputProjectDir

    @Internal
    File inputBuildDir

    @Internal
    List<java.io.File> bootClasspathList

    private static String dex2SmaliCommand
    private static String smali2DexCommand
    private static String jar2DexCommand
    private static String ROBUST_DIR

    @TaskAction
    void run() {
        def startTime = System.currentTimeMillis()
        logger.lifecycle '================pishi autoPatch start================'
        initConfig()
        copyJarToRobust()
        def classPool = Config.classPool
        bootClasspathList.each {
            classPool.appendClassPath((String) it.absolutePath)
        }
        def locations = allClasses.get().collect { it.asFile }
        def box = ReflectUtils.toCtClasses(locations, classPool)
        logger.lifecycle "pishi: loaded ${box.size()} classes"
        autoPatch(box)
        JavaUtils.printMap(Config.methodMap)
        logger.lifecycle "pishi autoPatch cost ${(System.currentTimeMillis() - startTime) / 1000} second"
        logger.lifecycle '================pishi autoPatch end: patch.jar generated, build continues================'
    }

    def initConfig() {
        NameManger.init()
        InlineClassFactory.init()
        ReadMapping.init()
        Config.init()

        ROBUST_DIR = "${inputProjectDir}${File.separator}robust${File.separator}"
        def baksmaliFilePath = "${ROBUST_DIR}${Constants.LIB_NAME_ARRAY[0]}"
        def smaliFilePath = "${ROBUST_DIR}${Constants.LIB_NAME_ARRAY[1]}"
        Config.robustGenerateDirectory = "${inputBuildDir}" + File.separator + "$Constants.ROBUST_GENERATE_DIRECTORY" + File.separator
        dex2SmaliCommand = "  java -jar ${baksmaliFilePath} -o classout" + File.separator + "  $Constants.CLASSES_DEX_NAME"
        smali2DexCommand = "   java -jar ${smaliFilePath} classout" + File.separator + " -o " + Constants.PATACH_DEX_NAME
        // D8 replaced the discontinued dx; the r8 jar ships on the plugin classpath
        def androidJar = bootClasspathList.isEmpty() ? "" : bootClasspathList.first().absolutePath
        jar2DexCommand = "   java -cp ${resolveR8Jar()} com.android.tools.r8.D8 --release --min-api 21 --lib ${androidJar} --output . ${Constants.ZIP_FILE_NAME}"
        ReadXML.readXMl(inputProjectDir.path)
        def methodsMapFile = new File(inputProjectDir.path + Constants.METHOD_MAP_PATH)
        Config.methodMap = new LinkedHashMap<String, Integer>()
        if (methodsMapFile.exists()) {
            def json = new groovy.json.JsonSlurper()
            methodsMapFile.eachLine { line ->
                if (line.trim()) {
                    def entry = json.parseText(line)
                    ((Map) entry.methods).each { k, v -> Config.methodMap.put(k as String, v as Integer) }
                }
            }
        } else {
            logger.warn("pishi: ${methodsMapFile} not found — copy it from the instrumented module's build/outputs/robust/ first")
        }
    }

    static def copyJarToRobust() {
        File targetDir = new File(ROBUST_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        for (String libName : Constants.LIB_NAME_ARRAY) {
            InputStream inputStream = JavaUtils.class.getResourceAsStream("/libs/" + libName)
            if (inputStream == null) {
                System.out.println("Warning!!!  Did not find " + libName + " ，you must add it to your project's libs ")
                continue
            }
            File inputFile = new File(ROBUST_DIR + libName)
            try {
                OutputStream inputFileOut = new FileOutputStream(inputFile)
                JavaUtils.copy(inputStream, inputFileOut)
            } catch (Exception e) {
                e.printStackTrace()
                System.out.println("Warning!!! " + libName + " copy error " + e.getMessage())
            }
        }
    }

    /** locates the r8 jar (provides the D8 entry point) on the plugin classpath */
    static String resolveR8Jar() {
        def url = PishiAutoPatchTask.class.getResource("/com/android/tools/r8/D8.class")
        if (url == null) {
            throw new RuntimeException("com.android.tools:r8 not found on the pishi-autopatch classpath")
        }
        def path = url.toString()   // jar:file:/.../r8-x.y.jar!/com/android/tools/r8/D8.class
        def jarPath = path.substring(path.indexOf("file:") + 5, path.indexOf("!"))
        return URLDecoder.decode(jarPath, "UTF-8")
    }

    def autoPatch(List<CtClass> box) {
        String patchPath = inputBuildDir.getAbsolutePath() + File.separator + Constants.ROBUST_GENERATE_DIRECTORY + File.separator
        clearPatchPath(patchPath)
        ReadAnnotation.readAnnotation(box, logger)
        if (Config.supportProGuard) {
            ReadMapping.getInstance().initMappingInfo()
        }
        generatPatch(box, patchPath)
        zipPatchClassesFile()
        executeCommand(jar2DexCommand)
        executeCommand(dex2SmaliCommand)
        SmaliTool.getInstance().dealObscureInSmali()
        executeCommand(smali2DexCommand)
        packagePatchDex2Jar()
        deleteTmpFiles()
    }

    def zipPatchClassesFile() {
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(Config.robustGenerateDirectory + Constants.ZIP_FILE_NAME))
        zipAllPatchClasses(Config.robustGenerateDirectory + Config.patchPackageName.substring(0, Config.patchPackageName.indexOf(".")), "", zipOut)
        zipOut.close()
    }

    def zipAllPatchClasses(String path, String fullClassName, ZipOutputStream zipOut) {
        File file = new File(path)
        if (file.exists()) {
            fullClassName = fullClassName + file.name
            if (file.isDirectory()) {
                fullClassName += File.separator
                File[] files = file.listFiles()
                if (files.length == 0) {
                    return
                } else {
                    for (File file2 : files) {
                        zipAllPatchClasses(file2.getAbsolutePath(), fullClassName, zipOut)
                    }
                }
            } else {
                zipFile(file, zipOut, fullClassName)
            }
        } else {
            logger.debug("文件不存在!")
        }
    }

    def generatPatch(List<CtClass> box, String patchPath) {
        if (!Config.isManual) {
            if (Config.patchMethodSignatureSet.size() < 1) {
                throw new RuntimeException(" patch method is empty ,please check your Modify annotation or use RobustModify.modify() to mark modified methods")
            }
            Config.methodNeedPatchSet.addAll(Config.patchMethodSignatureSet)
            InlineClassFactory.dealInLineClass(patchPath, Config.newlyAddedClassNameList)
            initSuperMethodInClass(Config.modifiedClassNameList)
            for (String fullClassName : Config.modifiedClassNameList) {
                CtClass ctClass = Config.classPool.get(fullClassName)
                CtClass patchClass = PatchesFactory.createPatch(patchPath, ctClass, false, NameManger.getInstance().getPatchName(ctClass.name), Config.patchMethodSignatureSet)
                patchClass.writeFile(patchPath)
                patchClass.defrost()
                createControlClass(patchPath, ctClass)
            }
            createPatchesInfoClass(patchPath)
            if (Config.methodNeedPatchSet.size() > 0) {
                throw new RuntimeException(" some methods haven't patched,see unpatched method list : " + Config.methodNeedPatchSet.toListString())
            }
        } else {
            autoPatchManually(box, patchPath)
        }
    }

    def deleteTmpFiles() {
        File directory = new File(Config.robustGenerateDirectory)
        if (!directory.isDirectory()) {
            throw new RuntimeException("patch directory " + Config.robustGenerateDirectory + " does not exist")
        }
        directory.listFiles(new FilenameFilter() {
            @Override
            boolean accept(File file, String s) {
                return !(Constants.PATACH_JAR_NAME.equals(s))
            }
        }).each {
            if (it.isDirectory()) {
                it.deleteDir()
            } else {
                it.delete()
            }
        }
    }

    def autoPatchManually(List<CtClass> box, String patchPath) {
        box.forEach { ctClass ->
            if (Config.isManual && ctClass.name.startsWith(Config.patchPackageName)) {
                Config.modifiedClassNameList.add(ctClass.name)
                ctClass.writeFile(patchPath)
            }
        }
    }

    def executeCommand(String command) {
        Process output = command.execute(null, new File(Config.robustGenerateDirectory))
        output.inputStream.eachLine { println command + " inputStream output   " + it }
        output.errorStream.eachLine {
            println command + " errorStream output   " + it
            throw new RuntimeException("execute command " + command + " error")
        }
    }

    def initSuperMethodInClass(List originClassList) {
        for (String modifiedFullClassName : originClassList) {
            List<CtMethod> invokeSuperMethodList = Config.invokeSuperMethodMap.getOrDefault(modifiedFullClassName, new ArrayList())
            def modifiedCtClass = Config.classPool.get(modifiedFullClassName)
            modifiedCtClass.defrost()
            modifiedCtClass.declaredMethods.findAll {
                return Config.patchMethodSignatureSet.contains(it.longName) || InlineClassFactory.allInLineMethodLongname.contains(it.longName)
            }.each { behavior ->
                behavior.instrument(new ExprEditor() {
                    @Override
                    void edit(MethodCall m) throws CannotCompileException {
                        if (m.isSuper()) {
                            if (!invokeSuperMethodList.contains(m.method)) {
                                invokeSuperMethodList.add(m.method)
                            }
                        }
                    }
                })
            }
            Config.invokeSuperMethodMap.put(modifiedFullClassName, invokeSuperMethodList)
        }
    }

    def createControlClass(String patchPath, CtClass modifiedClass) {
        CtClass controlClass = PatchesControlFactory.createPatchesControl(modifiedClass)
        controlClass.writeFile(patchPath)
        return controlClass
    }

    def createPatchesInfoClass(String patchPath) {
        PatchesInfoFactory.createPatchesInfo().writeFile(patchPath)
    }

    def clearPatchPath(String patchPath) {
        new File(patchPath).deleteDir()
    }

    def packagePatchDex2Jar() throws IOException {
        File inputFile = new File(Config.robustGenerateDirectory, Constants.PATACH_DEX_NAME)
        if (!inputFile.exists() || !inputFile.canRead()) {
            throw new RuntimeException("patch.dex is not exists or readable")
        }
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(new File(Config.robustGenerateDirectory, Constants.PATACH_JAR_NAME)))
        zipOut.setLevel(Deflater.NO_COMPRESSION)
        zipFile(inputFile, zipOut, Constants.CLASSES_DEX_NAME)
        zipOut.close()
    }

    def zipFile(File inputFile, ZipOutputStream zos, String entryName) {
        ZipEntry entry = new ZipEntry(entryName)
        zos.putNextEntry(entry)
        FileInputStream fis = new FileInputStream(inputFile)
        byte[] buffer = new byte[4092]
        int byteCount = 0
        while ((byteCount = fis.read(buffer)) != -1) {
            zos.write(buffer, 0, byteCount)
        }
        fis.close()
        zos.closeEntry()
        zos.flush()
    }
}
