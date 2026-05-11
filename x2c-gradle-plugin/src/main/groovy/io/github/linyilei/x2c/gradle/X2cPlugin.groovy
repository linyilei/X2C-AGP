package io.github.linyilei.x2c.gradle

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.w3c.dom.Node

import javax.xml.parsers.DocumentBuilderFactory
import javax.lang.model.element.Modifier
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class X2cPlugin implements Plugin<Project> {

    private static final String MODULE_INDEX_LOADER_METHOD = 'loadX2CModuleIndexes'
    private static final String MODULE_INDEX_LOADER_DESC = '(Landroid/util/SparseIntArray;Landroid/util/SparseArray;)V'
    private static final String X2C_ROOT_INDEX_INTERNAL_NAME = 'io/github/linyilei/x2c/runtime/X2CRootIndex'

    @Override
    void apply(Project project) {
        X2cExtension extension = project.extensions.create('x2c', X2cExtension)
        project.plugins.withId('com.android.application') {
            configureAndroidProject(project, true, extension)
        }
        project.plugins.withId('com.android.library') {
            configureAndroidProject(project, false, extension)
        }
    }

    private static void configureAndroidProject(Project project, boolean applicationModule, X2cExtension extension) {
        def android = project.extensions.getByName('android')
        String rPackage = resolveManifestPackage(project, android)
        String libraryGeneratedPackage = applicationModule ? null : rPackage + '.x2c'
        Map<String, GenerateX2cTask> tasksByVariant = new LinkedHashMap<>()
        if (applicationModule) {
            android.registerTransform(new X2CRuntimeTransform(project, tasksByVariant))
        }

        def variants = applicationModule ? android.applicationVariants : android.libraryVariants
        variants.all { variant ->
            String taskName = "generate${variant.name.capitalize()}X2C"
            File outputDir = project.file("${project.buildDir}/generated/source/x2c/${variant.name}")
            File javaResOutputDir = project.file("${project.buildDir}/generated/java-res/x2c/${variant.name}")
            def task = project.tasks.create(taskName, GenerateX2cTask) {
                it.outputDir = outputDir
                it.javaResOutputDir = javaResOutputDir
                it.applicationModule = applicationModule
                it.extensionConfig = extension
                it.generatedPackage = applicationModule ? variant.applicationId + '.x2c' : libraryGeneratedPackage
                it.rPackage = rPackage
                it.resDirs = variant.sourceSets.collectMany { sourceSet ->
                    sourceSet.resDirectories.findAll { File dir -> dir.exists() }
                }
                it.sourceDirs = variant.sourceSets.collectMany { sourceSet ->
                    resolveSourceDirectories(project, sourceSet).findAll { File dir -> dir.exists() }
                }
                if (applicationModule) {
                    def runtimeClasspath = project.configurations.findByName("${variant.name}RuntimeClasspath")
                    it.moduleIndexMarkerClasspath = runtimeClasspath == null
                            ? project.files()
                            : moduleIndexMarkerClasspath(project, runtimeClasspath)
                    it.moduleIndexFallbackClasspath = runtimeClasspath == null
                            ? project.files()
                            : moduleIndexFallbackClasspath(project, runtimeClasspath)
                    tasksByVariant[variant.name] = it
                }
            }
            variant.registerJavaGeneratingTask(task, outputDir)
            if (applicationModule) {
                configureRootIndexAsmInjection(project, variant, task)
            }
            if (!applicationModule) {
                variant.processJavaResourcesProvider.configure { processTask ->
                    processTask.dependsOn(task)
                    processTask.from(task.javaResOutputDir)
                }
            }
        }
    }

    private static void configureRootIndexAsmInjection(Project project, def variant, GenerateX2cTask task) {
        variant.javaCompileProvider.configure { javaCompile ->
            javaCompile.doLast {
                List<String> moduleIndexClassNames = task.findModuleIndexClassNames()
                if (moduleIndexClassNames.isEmpty()) {
                    return
                }
                File destinationDir = javaCompile.destinationDir
                injectModuleIndexesIntoRootIndex(destinationDir, task.generatedPackage, moduleIndexClassNames, project)
            }
        }
    }

    private static void injectModuleIndexesIntoRootIndex(File classesDir, String generatedPackage,
                                                         List<String> moduleIndexClassNames, Project project) {
        if (classesDir == null || generatedPackage == null || generatedPackage.trim().isEmpty()) {
            return
        }
        File rootIndexClass = new File(classesDir, generatedPackage.replace('.', '/') + '/X2CRootIndex.class')
        if (!rootIndexClass.isFile()) {
            throw new GradleException("X2C ASM could not find generated root index class: ${rootIndexClass}")
        }
        byte[] originalBytes = rootIndexClass.bytes
        ClassReader reader = new ClassReader(originalBytes)
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        boolean[] patched = [false] as boolean[]
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM7, writer) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (MODULE_INDEX_LOADER_METHOD == name && MODULE_INDEX_LOADER_DESC == descriptor) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    mv.visitCode()
                    moduleIndexClassNames.each { String className ->
                        String internalName = className.replace('.', '/')
                        mv.visitTypeInsn(Opcodes.NEW, internalName)
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, '<init>', '()V', false)
                        mv.visitVarInsn(Opcodes.ALOAD, 0)
                        mv.visitVarInsn(Opcodes.ALOAD, 1)
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, X2C_ROOT_INDEX_INTERNAL_NAME, 'loadInto',
                                MODULE_INDEX_LOADER_DESC, true)
                    }
                    mv.visitInsn(Opcodes.RETURN)
                    mv.visitMaxs(0, 0)
                    mv.visitEnd()
                    patched[0] = true
                    return null
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }
        }
        reader.accept(visitor, 0)
        if (!patched[0]) {
            throw new GradleException("X2C ASM could not find ${MODULE_INDEX_LOADER_METHOD} in ${rootIndexClass}")
        }
        rootIndexClass.bytes = writer.toByteArray()
        project.logger.info("X2C ASM registered ${moduleIndexClassNames.size()} module indexes in ${rootIndexClass.name}.")
    }

    private static List<File> resolveSourceDirectories(Project project, def sourceSet) {
        Set<File> sourceDirs = new LinkedHashSet<>()
        def javaDirectories = sourceSet.hasProperty('javaDirectories') ? sourceSet.javaDirectories : null
        if (javaDirectories != null) {
            sourceDirs.addAll(javaDirectories.findAll { File dir -> dir != null })
        } else if (sourceSet.hasProperty('java') && sourceSet.java?.hasProperty('srcDirs')) {
            sourceDirs.addAll(sourceSet.java.srcDirs.findAll { File dir -> dir != null })
        }
        if (sourceSet.hasProperty('kotlin') && sourceSet.kotlin?.hasProperty('srcDirs')) {
            sourceDirs.addAll(sourceSet.kotlin.srcDirs.findAll { File dir -> dir != null })
        }
        String sourceSetName = sourceSet.hasProperty('name') ? sourceSet.name : 'main'
        File conventionalKotlinDir = project.file("src/${sourceSetName}/kotlin")
        if (conventionalKotlinDir.exists()) {
            sourceDirs.add(conventionalKotlinDir)
        }
        new ArrayList<>(sourceDirs)
    }

    private static FileCollection moduleIndexMarkerClasspath(Project project, def runtimeClasspath) {
        Attribute<String> artifactType = Attribute.of('artifactType', String)
        FileCollection javaRes = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'android-java-res')
        }.files
        project.files(javaRes)
    }

    private static FileCollection moduleIndexFallbackClasspath(Project project, def runtimeClasspath) {
        Attribute<String> artifactType = Attribute.of('artifactType', String)
        FileCollection androidClassesJars = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'android-classes-jar')
        }.files
        FileCollection androidClassesDirs = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'android-classes-directory')
        }.files
        FileCollection jars = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'jar')
        }.files
        project.files(androidClassesJars, androidClassesDirs, jars)
    }

    private static String resolveManifestPackage(Project project, def android) {
        File manifest = android.sourceSets.main.manifest.srcFile
        if (manifest != null && manifest.exists()) {
            try {
                def factory = DocumentBuilderFactory.newInstance()
                factory.setNamespaceAware(false)
                disableExternalEntities(factory)
                Element root = factory.newDocumentBuilder().parse(manifest).documentElement
                String pkg = root.getAttribute('package')
                if (pkg != null && pkg.trim().length() > 0) {
                    return pkg.trim()
                }
            } catch (Exception ignored) {
                project.logger.warn("X2C could not parse package from ${manifest}. Falling back to applicationId.")
            }
        }
        return android.defaultConfig.applicationId
    }

    static void disableExternalEntities(DocumentBuilderFactory factory) {
        try {
            factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        } catch (Exception ignored) {
            // Parser implementation does not expose every feature on every JDK.
        }
        [
                'http://xml.org/sax/features/external-general-entities',
                'http://xml.org/sax/features/external-parameter-entities'
        ].each { feature ->
            try {
                factory.setFeature(feature, false)
            } catch (Exception ignored) {
                // Parser implementation does not expose every feature on every JDK.
            }
        }
    }
}

class X2CRuntimeTransform extends Transform {

    private static final String X2C_CLASS_ENTRY = 'io/github/linyilei/x2c/runtime/X2C.class'
    private static final String X2C_INTERNAL_NAME = 'io/github/linyilei/x2c/runtime/X2C'
    private static final String X2C_ROOT_INDEX_INTERNAL_NAME = 'io/github/linyilei/x2c/runtime/X2CRootIndex'
    private static final String ROOT_INDEX_INTERNAL_NAME = 'io/github/linyilei/x2c/runtime/X2C$RootIndex'
    private static final String ROOT_INDEX_LOADER_METHOD = 'tryLoadGeneratedRootIndex'
    private static final String ROOT_INDEX_LOADER_DESC = '(Landroid/content/Context;)Lio/github/linyilei/x2c/runtime/X2C$RootIndex;'
    private static final String LOAD_INTO_DESC = '(Landroid/util/SparseIntArray;Landroid/util/SparseArray;)V'

    private final Project project
    private final Map<String, GenerateX2cTask> tasksByVariant

    X2CRuntimeTransform(Project project, Map<String, GenerateX2cTask> tasksByVariant) {
        this.project = project
        this.tasksByVariant = tasksByVariant
    }

    @Override
    String getName() {
        return 'x2cRuntime'
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return Collections.singleton(QualifiedContent.DefaultContentType.CLASSES)
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return EnumSet.of(QualifiedContent.Scope.PROJECT,
                QualifiedContent.Scope.SUB_PROJECTS,
                QualifiedContent.Scope.EXTERNAL_LIBRARIES)
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation invocation) {
        invocation.outputProvider.deleteAll()
        GenerateX2cTask task = tasksByVariant[invocation.context.variantName]
        String generatedRootInternalName = generatedRootInternalName(task)
        boolean patchRootLoader = generatedRootInternalName != null
        int[] patchedX2CClasses = [0] as int[]

        invocation.inputs.each { input ->
            input.directoryInputs.each { directoryInput ->
                File outputDir = invocation.outputProvider.getContentLocation(directoryInput.name,
                        directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
                copyDirectory(directoryInput.file, outputDir, generatedRootInternalName, patchRootLoader, patchedX2CClasses)
            }
            input.jarInputs.each { jarInput ->
                File outputJar = invocation.outputProvider.getContentLocation(jarInput.name,
                        jarInput.contentTypes, jarInput.scopes, Format.JAR)
                copyJar(jarInput.file, outputJar, generatedRootInternalName, patchRootLoader, patchedX2CClasses)
            }
        }

        if (patchRootLoader) {
            if (patchedX2CClasses[0] == 0) {
                project.logger.warn('X2C ASM did not find io.github.linyilei.x2c.runtime.X2C to inject the generated root index.')
            } else {
                project.logger.info("X2C ASM injected generated root index into ${patchedX2CClasses[0]} X2C runtime class file(s).")
            }
        }
    }

    private static String generatedRootInternalName(GenerateX2cTask task) {
        if (task == null || !task.applicationModule || task.generatedPackage == null
                || task.generatedPackage.trim().isEmpty()) {
            return null
        }
        File rootIndexSource = new File(task.outputDir, task.generatedPackage.replace('.', '/') + '/X2CRootIndex.java')
        return rootIndexSource.isFile() ? task.generatedPackage.replace('.', '/') + '/X2CRootIndex' : null
    }

    private static void copyDirectory(File inputDir, File outputDir, String generatedRootInternalName,
                                      boolean patchRootLoader, int[] patchedX2CClasses) {
        if (outputDir.exists()) {
            outputDir.deleteDir()
        }
        if (inputDir == null || !inputDir.exists()) {
            return
        }
        inputDir.eachFileRecurse { File child ->
            if (!child.isFile()) {
                return
            }
            String relativePath = inputDir.toPath().relativize(child.toPath()).toString()
                    .replace(File.separatorChar, '/' as char)
            File outputFile = new File(outputDir, relativePath)
            outputFile.parentFile.mkdirs()
            byte[] bytes = child.bytes
            if (patchRootLoader && relativePath == X2C_CLASS_ENTRY) {
                bytes = patchX2CRootIndexLoader(bytes, generatedRootInternalName)
                patchedX2CClasses[0]++
            }
            outputFile.bytes = bytes
        }
    }

    private static void copyJar(File inputJar, File outputJar, String generatedRootInternalName,
                                boolean patchRootLoader, int[] patchedX2CClasses) {
        outputJar.parentFile.mkdirs()
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(inputJar))
        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(outputJar))
        Set<String> writtenEntries = new HashSet<>()
        try {
            ZipEntry entry = zipInputStream.nextEntry
            while (entry != null) {
                if (!writtenEntries.add(entry.name)) {
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                    continue
                }
                ZipEntry outputEntry = new ZipEntry(entry.name)
                outputEntry.time = entry.time
                zipOutputStream.putNextEntry(outputEntry)
                if (!entry.directory) {
                    byte[] bytes = readAllBytes(zipInputStream)
                    if (patchRootLoader && entry.name == X2C_CLASS_ENTRY) {
                        bytes = patchX2CRootIndexLoader(bytes, generatedRootInternalName)
                        patchedX2CClasses[0]++
                    }
                    zipOutputStream.write(bytes)
                }
                zipOutputStream.closeEntry()
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        } finally {
            zipOutputStream.close()
            zipInputStream.close()
        }
    }

    private static byte[] readAllBytes(InputStream inputStream) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int read = inputStream.read(buffer)
        while (read != -1) {
            outputStream.write(buffer, 0, read)
            read = inputStream.read(buffer)
        }
        return outputStream.toByteArray()
    }

    private static byte[] patchX2CRootIndexLoader(byte[] originalBytes, String generatedRootInternalName) {
        String rootIndexLogMessage = 'Loaded generated root index: ' + generatedRootInternalName.replace('/', '.')
        ClassReader reader = new ClassReader(originalBytes)
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        boolean[] patched = [false] as boolean[]
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM7, writer) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (ROOT_INDEX_LOADER_METHOD == name && ROOT_INDEX_LOADER_DESC == descriptor) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    mv.visitCode()
                    mv.visitTypeInsn(Opcodes.NEW, ROOT_INDEX_INTERNAL_NAME)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ROOT_INDEX_INTERNAL_NAME, '<init>', '()V', false)
                    mv.visitVarInsn(Opcodes.ASTORE, 1)
                    mv.visitTypeInsn(Opcodes.NEW, generatedRootInternalName)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, generatedRootInternalName, '<init>', '()V', false)
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitFieldInsn(Opcodes.GETFIELD, ROOT_INDEX_INTERNAL_NAME,
                            'layoutToGroup', 'Landroid/util/SparseIntArray;')
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitFieldInsn(Opcodes.GETFIELD, ROOT_INDEX_INTERNAL_NAME,
                            'groups', 'Landroid/util/SparseArray;')
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, X2C_ROOT_INDEX_INTERNAL_NAME,
                            'loadInto', LOAD_INTO_DESC, true)
                    mv.visitLdcInsn(rootIndexLogMessage)
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, X2C_INTERNAL_NAME,
                            'log', '(Ljava/lang/String;)V', false)
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(0, 0)
                    mv.visitEnd()
                    patched[0] = true
                    return null
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }
        }
        reader.accept(visitor, 0)
        if (!patched[0]) {
            throw new GradleException("X2C ASM could not find ${ROOT_INDEX_LOADER_METHOD} in ${X2C_INTERNAL_NAME}.")
        }
        return writer.toByteArray()
    }
}

class X2cExtension {

    private final Set<String> excludedLayouts = new LinkedHashSet<>()

    Set<String> normalizedExcludedLayouts() {
        return new LinkedHashSet<>(excludedLayouts)
    }

    void setExcludes(Iterable<?> values) {
        replaceExcludedLayouts(values)
    }

    List<String> getExcludes() {
        return new ArrayList<>(excludedLayouts)
    }

    void setBlacklist(Iterable<?> values) {
        replaceExcludedLayouts(values)
    }

    List<String> getBlacklist() {
        return getExcludes()
    }

    void excludeLayouts(String... names) {
        addExcludedLayouts(names == null ? [] : Arrays.asList(names))
    }

    void blacklist(String... names) {
        excludeLayouts(names)
    }

    private void replaceExcludedLayouts(Iterable<?> values) {
        excludedLayouts.clear()
        addExcludedLayouts(values)
    }

    private void addExcludedLayouts(Iterable<?> values) {
        if (values == null) {
            return
        }
        values.each { Object value ->
            String normalized = normalizeLayoutName(value)
            if (normalized != null) {
                excludedLayouts.add(normalized)
            }
        }
    }

    private static String normalizeLayoutName(Object rawValue) {
        if (rawValue == null) {
            return null
        }
        String value = rawValue.toString().trim()
        if (value.isEmpty()) {
            return null
        }
        if (value.startsWith('@layout/')) {
            value = value.substring('@layout/'.length())
        } else if (value.startsWith('@+layout/')) {
            value = value.substring('@+layout/'.length())
        }
        if (value.endsWith('.xml')) {
            value = value.substring(0, value.length() - '.xml'.length())
        }
        return value.isEmpty() ? null : value
    }
}

class GenerateX2cTask extends DefaultTask {

    static final String MODULE_INDEX_MARKER_DIR = 'META-INF/x2c'
    static final String MODULE_INDEX_MARKER_SUFFIX = '.module-index'

    @OutputDirectory
    File outputDir

    @OutputDirectory
    File javaResOutputDir

    @org.gradle.api.tasks.Internal
    X2cExtension extensionConfig

    String rPackage
    String generatedPackage
    boolean applicationModule
    List<File> resDirs = []
    List<File> sourceDirs = []
    FileCollection moduleIndexMarkerClasspath
    FileCollection moduleIndexFallbackClasspath

    @Input
    String getX2cGeneratedPackage() {
        return generatedPackage ?: ''
    }

    @Input
    String getX2cRPackage() {
        return rPackage ?: ''
    }

    @Input
    boolean getX2cApplicationModule() {
        return applicationModule
    }

    @Input
    List<String> getX2cExcludedLayouts() {
        Set<String> excluded = extensionConfig == null
                ? new LinkedHashSet<String>()
                : extensionConfig.normalizedExcludedLayouts()
        return new ArrayList<>(excluded).sort()
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    FileCollection getX2cResourceDirs() {
        return project.files(resDirs)
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    FileCollection getX2cSourceDirs() {
        return project.files(sourceDirs)
    }

    @InputFiles
    @Classpath
    FileCollection getX2cModuleIndexMarkerClasspath() {
        return moduleIndexMarkerClasspath ?: project.files()
    }

    @InputFiles
    @Classpath
    FileCollection getX2cModuleIndexFallbackClasspath() {
        return moduleIndexFallbackClasspath ?: project.files()
    }

    @TaskAction
    void generate() {
        if (generatedPackage == null || generatedPackage.trim().isEmpty()) {
            throw new IllegalStateException('X2C requires a generated package to generate runtime classes.')
        }
        if (rPackage == null || rPackage.trim().isEmpty()) {
            rPackage = generatedPackage.endsWith('.x2c')
                    ? generatedPackage.substring(0, generatedPackage.length() - '.x2c'.length())
                    : generatedPackage
        }
        deleteDir(outputDir)
        outputDir.mkdirs()
        deleteDir(javaResOutputDir)
        javaResOutputDir.mkdirs()

        LayoutCatalog layoutCatalog = new LayoutCatalog(indexLayoutFiles(resDirs), project)
        LayoutSelection selection = resolveLayoutSelection(layoutCatalog, new LinkedHashSet<>(getX2cExcludedLayouts()))
        Map<String, Map<String, LayoutSpec>> layouts = selection.layouts
        Set<String> targets = selection.targets
        List<String> moduleIndexClassNames = findModuleIndexClassNames()
        if (targets.isEmpty() && moduleIndexClassNames.isEmpty()) {
            project.logger.lifecycle('X2C found no annotated layouts selected.')
            return
        }

        JavaWriter writer = new JavaWriter(outputDir, javaResOutputDir, generatedPackage, rPackage, layouts, targets,
                applicationModule, project)
        writer.writeAll()
    }

    List<String> findModuleIndexClassNames() {
        if (!applicationModule) {
            return []
        }
        Set<String> classNames = new LinkedHashSet<>()
        getX2cModuleIndexMarkerClasspath().files.each { File file ->
            scanModuleIndexMarkers(file, classNames)
        }
        if (classNames.isEmpty()) {
            getX2cModuleIndexFallbackClasspath().files.each { File file ->
                scanModuleIndexClasses(file, classNames)
            }
        }
        classNames.sort()
    }

    private static void scanModuleIndexMarkers(File file, Set<String> classNames) {
        if (file == null || !file.exists()) {
            return
        }
        if (file.isDirectory()) {
            File markerDir = new File(file, MODULE_INDEX_MARKER_DIR)
            File[] markerFiles = markerDir.listFiles()
            if (markerFiles != null) {
                markerFiles.findAll { File markerFile -> markerFile.isFile() }
                        .each { File markerFile ->
                    markerFile.withReader('UTF-8') { BufferedReader reader ->
                        addModuleIndexClasses(reader, classNames)
                    }
                }
            }
            return
        }
        if (file.name.endsWith('.jar') || file.name.endsWith('.zip')) {
            try {
                ZipFile zipFile = new ZipFile(file)
                try {
                    zipFile.entries().each { entry ->
                        if (entry.directory || !entry.name.startsWith(MODULE_INDEX_MARKER_DIR + '/')
                                || !entry.name.endsWith(MODULE_INDEX_MARKER_SUFFIX)) {
                            return
                        }
                        InputStream inputStream = zipFile.getInputStream(entry)
                        try {
                            addModuleIndexClasses(new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)), classNames)
                        } finally {
                            inputStream.close()
                        }
                    }
                } finally {
                    zipFile.close()
                }
            } catch (Exception ignored) {
                // Not a readable zip or no marker present.
            }
        }
    }

    private static void scanModuleIndexClasses(File file, Set<String> classNames) {
        if (file == null || !file.exists()) {
            return
        }
        if (file.isDirectory()) {
            scanClassDirectory(file, classNames)
            return
        }
        if (file.name.endsWith('.jar')) {
            scanZipFile(file, classNames, false)
            return
        }
        if (file.name.endsWith('.aar')) {
            scanZipFile(file, classNames, true)
        }
    }

    private static void scanClassDirectory(File dir, Set<String> classNames) {
        dir.eachFileRecurse { File child ->
            if (child.isFile() && child.name == 'X2CModuleIndex.class') {
                String relativePath = dir.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/' as char)
                addModuleIndexClass(relativePath, classNames)
            }
        }
    }

    private static void scanZipFile(File file, Set<String> classNames, boolean scanNestedJars) {
        try {
            ZipFile zipFile = new ZipFile(file)
            try {
                zipFile.entries().each { entry ->
                    if (entry.directory) {
                        return
                    }
                    String name = entry.name
                    if (name.endsWith('.class')) {
                        addModuleIndexClass(name, classNames)
                    } else if (scanNestedJars && (name == 'classes.jar' || (name.startsWith('libs/') && name.endsWith('.jar')))) {
                        InputStream inputStream = zipFile.getInputStream(entry)
                        try {
                            scanJarStream(inputStream, classNames)
                        } finally {
                            inputStream.close()
                        }
                    }
                }
            } finally {
                zipFile.close()
            }
        } catch (Exception ignored) {
            // Broken or non-zip files on the classpath are not X2C module indexes.
        }
    }

    private static void scanJarStream(InputStream inputStream, Set<String> classNames) {
        ZipInputStream zipInputStream = new ZipInputStream(inputStream)
        try {
            def entry = zipInputStream.nextEntry
            while (entry != null) {
                if (!entry.directory && entry.name.endsWith('.class')) {
                    addModuleIndexClass(entry.name, classNames)
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        } finally {
            zipInputStream.close()
        }
    }

    private static void addModuleIndexClass(String entryName, Set<String> classNames) {
        String normalized = entryName.replace('\\', '/')
        if (normalized == 'x2c/X2CModuleIndex.class' || normalized.endsWith('/x2c/X2CModuleIndex.class')) {
            classNames.add(normalized.substring(0, normalized.length() - '.class'.length()).replace('/', '.'))
        }
    }

    private static void addModuleIndexClasses(BufferedReader reader, Set<String> classNames) {
        String line = reader.readLine()
        while (line != null) {
            String className = line.trim()
            if (!className.isEmpty() && !className.startsWith('#')) {
                classNames.add(className)
            }
            line = reader.readLine()
        }
    }

    private LayoutSelection resolveLayoutSelection(LayoutCatalog layoutCatalog, Set<String> excludedLayouts) {
        Set<String> targets = new LinkedHashSet<>()
        Set<String> missingLayouts = new LinkedHashSet<>()
        scanAnnotatedLayoutTargets().each { String name ->
            if (excludedLayouts.contains(name)) {
                project.logger.lifecycle("X2C skipped annotated layout ${name} because it is blacklisted.")
                return
            }
            if (!layoutCatalog.contains(name)) {
                missingLayouts.add(name)
                return
            }
            targets.add(name)
        }
        if (!missingLayouts.isEmpty()) {
            throw new GradleException("X2C could not find annotated layouts: "
                    + missingLayouts.sort().join(', ')
                    + ". Check @Xml(layouts = ...) entries under ${project.path}.")
        }
        expandIncludedTargets(layoutCatalog, targets, excludedLayouts)
        return new LayoutSelection(layoutCatalog.snapshot(), targets)
    }

    private static void expandIncludedTargets(LayoutCatalog layoutCatalog, Set<String> targets, Set<String> excludedLayouts) {
        List<String> queue = new ArrayList<>(targets)
        for (int i = 0; i < queue.size(); i++) {
            String name = queue[i]
            layoutCatalog.load(name).values().each { LayoutSpec spec ->
                spec.includes.each { String includeName ->
                    if (!layoutCatalog.contains(includeName) || excludedLayouts.contains(includeName)) {
                        return
                    }
                    layoutCatalog.load(includeName)
                    if (targets.add(includeName)) {
                        queue.add(includeName)
                    }
                }
            }
        }
    }

    private Set<String> scanAnnotatedLayoutTargets() {
        return scanAnnotatedLayoutTargets(sourceDirs)
    }

    private static Set<String> scanAnnotatedLayoutTargets(List<File> sourceDirs) {
        Set<String> targets = new LinkedHashSet<>()
        sourceDirs.each { File dir ->
            if (dir == null || !dir.exists()) {
                return
            }
            dir.eachFileRecurse { File file ->
                if (!file.isFile()) {
                    return
                }
                String name = file.name
                if (!name.endsWith('.java') && !name.endsWith('.kt')) {
                    return
                }
                targets.addAll(extractAnnotatedLayouts(file))
            }
        }
        targets
    }

    private static Set<String> extractAnnotatedLayouts(File file) {
        String source
        try {
            source = file.getText('UTF-8')
        } catch (Exception ignored) {
            return [] as Set<String>
        }
        source = source
                .replaceAll(/(?s)\/\*.*?\*\//, '')
                .replaceAll(/(?m)^\s*\/\/.*$/, '')
        Set<String> layouts = new LinkedHashSet<>()
        def matcher = (source =~ /@Xml\s*\(\s*layouts\s*=\s*(\{[\s\S]*?\}|["'][A-Za-z0-9_]+["'])\s*\)/)
        while (matcher.find()) {
            String layoutsArgument = matcher.group(1)
            def nameMatcher = (layoutsArgument =~ /["']([A-Za-z0-9_]+)["']/)
            while (nameMatcher.find()) {
                layouts.add(nameMatcher.group(1))
            }
        }
        layouts
    }

    private static Map<String, Map<String, File>> indexLayoutFiles(List<File> sourceResDirs) {
        Map<String, Map<String, File>> result = [:].withDefault { [:] }
        sourceResDirs.each { File resDir ->
            File[] dirs = resDir.listFiles()
            if (dirs == null) {
                return
            }
            dirs.findAll { File dir -> dir.isDirectory() && (dir.name == 'layout' || dir.name.startsWith('layout-')) }
                    .each { File layoutDir ->
                        String qualifier = layoutDir.name == 'layout' ? '' : layoutDir.name.substring('layout-'.length())
                        File[] files = layoutDir.listFiles()
                        if (files == null) {
                            return
                        }
                        files.findAll { File file -> file.isFile() && file.name.endsWith('.xml') }
                                .each { File file ->
                                    String name = file.name.substring(0, file.name.length() - '.xml'.length())
                                    result[name][qualifier] = file
                                }
                    }
        }
        result
    }

    private static Map<String, Map<String, LayoutSpec>> parseLayouts(Map<String, Map<String, File>> layoutFiles,
                                                                     String layoutName,
                                                                     Project owner) {
        Map<String, Map<String, LayoutSpec>> result = [:].withDefault { [:] }
        Map<String, File> variants = layoutFiles[layoutName]
        if (variants == null || variants.isEmpty()) {
            return result
        }
        variants.each { String qualifier, File file ->
            LayoutSpec spec = LayoutParser.parse(file, layoutName, qualifier, owner)
            if (spec != null) {
                result[layoutName][qualifier] = spec
            }
        }
        result
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return
        }
        dir.eachFileRecurse { File file ->
            file.delete()
        }
        dir.deleteDir()
    }
}

class LayoutParser {

    static LayoutSpec parse(File file, String layoutName, String qualifier, Project project) {
        try {
            def factory = DocumentBuilderFactory.newInstance()
            factory.setNamespaceAware(false)
            X2cPlugin.disableExternalEntities(factory)
            Element root = factory.newDocumentBuilder().parse(file).documentElement
            LayoutNode rootNode = parseElement(root)
            LayoutSpec spec = new LayoutSpec(layoutName, qualifier, file, rootNode)
            collectIncludes(rootNode, spec.includes)
            spec.unsupported = containsUnsupported(rootNode)
            return spec
        } catch (Exception e) {
            project.logger.warn("X2C skipped ${file}: ${e.message}")
            return null
        }
    }

    private static LayoutNode parseElement(Element element) {
        LayoutNode node = new LayoutNode()
        node.tag = element.tagName
        node.className = element.getAttribute('class')
        if (node.tag == 'include') {
            node.includeName = normalizeLayoutName(element.getAttribute('layout'))
        }
        Node child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                node.children.add(parseElement((Element) child))
            }
            child = child.nextSibling
        }
        node
    }

    private static void collectIncludes(LayoutNode node, Set<String> includes) {
        if (node.tag == 'include' && node.includeName != null && !node.includeName.isEmpty()) {
            includes.add(node.includeName)
        }
        node.children.each { LayoutNode child -> collectIncludes(child, includes) }
    }

    private static boolean containsUnsupported(LayoutNode node) {
        if (node.tag == 'fragment' || node.tag == 'requestFocus') {
            return true
        }
        return node.children.any { LayoutNode child -> containsUnsupported(child) }
    }

    static String normalizeLayoutName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null
        }
        String value = raw.trim()
        if (value.startsWith('@layout/')) {
            return value.substring('@layout/'.length())
        }
        if (value.startsWith('@+layout/')) {
            return value.substring('@+layout/'.length())
        }
        return null
    }
}

class LayoutCatalog {
    private final Map<String, Map<String, File>> layoutFiles
    private final Map<String, Map<String, LayoutSpec>> parsedLayouts = new LinkedHashMap<>()
    private final Project project

    LayoutCatalog(Map<String, Map<String, File>> layoutFiles, Project project) {
        this.layoutFiles = layoutFiles ?: [:]
        this.project = project
    }

    boolean contains(String layoutName) {
        return layoutFiles.containsKey(layoutName)
    }

    Map<String, LayoutSpec> load(String layoutName) {
        if (parsedLayouts.containsKey(layoutName)) {
            return parsedLayouts[layoutName]
        }
        Map<String, LayoutSpec> parsed = GenerateX2cTask.parseLayouts(layoutFiles, layoutName, project)[layoutName]
        if (parsed == null) {
            parsed = [:]
        }
        parsedLayouts[layoutName] = parsed
        return parsed
    }

    Map<String, Map<String, LayoutSpec>> snapshot() {
        Map<String, Map<String, LayoutSpec>> copy = [:].withDefault { [:] }
        parsedLayouts.each { String layoutName, Map<String, LayoutSpec> variants ->
            copy[layoutName] = variants
        }
        return copy
    }
}

class LayoutSelection {
    final Map<String, Map<String, LayoutSpec>> layouts
    final Set<String> targets

    LayoutSelection(Map<String, Map<String, LayoutSpec>> layouts, Set<String> targets) {
        this.layouts = layouts
        this.targets = targets
    }
}

class JavaWriter {

    private static final ClassName CONTEXT = ClassName.get('android.content', 'Context')
    private static final ClassName CONFIGURATION = ClassName.get('android.content.res', 'Configuration')
    private static final ClassName XML_RESOURCE_PARSER = ClassName.get('android.content.res', 'XmlResourceParser')
    private static final ClassName ATTRIBUTE_SET = ClassName.get('android.util', 'AttributeSet')
    private static final ClassName SPARSE_ARRAY = ClassName.get('android.util', 'SparseArray')
    private static final ClassName SPARSE_INT_ARRAY = ClassName.get('android.util', 'SparseIntArray')
    private static final ClassName INFLATE_EXCEPTION = ClassName.get('android.view', 'InflateException')
    private static final ClassName VIEW = ClassName.get('android.view', 'View')
    private static final ClassName VIEW_GROUP = ClassName.get('android.view', 'ViewGroup')
    private static final ClassName I_VIEW_FACTORY = ClassName.get('io.github.linyilei.x2c.runtime', 'IViewFactory')
    private static final ClassName X2C_GROUP = ClassName.get('io.github.linyilei.x2c.runtime', 'X2CGroup')
    private static final ClassName X2C_ROOT_INDEX = ClassName.get('io.github.linyilei.x2c.runtime', 'X2CRootIndex')
    private static final ClassName INFLATE_UTILS = ClassName.get('io.github.linyilei.x2c.runtime', 'InflateUtils')
    private static final ClassName STRING = ClassName.get('java.lang', 'String')
    private static final ClassName EXCEPTION = ClassName.get('java.lang', 'Exception')

    private final File outputDir
    private final File javaResOutputDir
    private final String generatedPackage
    private final String rPackage
    private final Map<String, Map<String, LayoutSpec>> layouts
    private final Set<String> targets
    private final boolean applicationModule
    private final Project project

    JavaWriter(File outputDir, File javaResOutputDir, String generatedPackage, String rPackage,
               Map<String, Map<String, LayoutSpec>> layouts, Set<String> targets,
               boolean applicationModule, Project project) {
        this.outputDir = outputDir
        this.javaResOutputDir = javaResOutputDir
        this.generatedPackage = generatedPackage
        this.rPackage = rPackage
        this.layouts = layouts
        this.targets = targets
        this.applicationModule = applicationModule
        this.project = project
    }

    void writeAll() {
        targets.each { String layoutName ->
            if (!canGenerateLayout(layoutName)) {
                project.logger.lifecycle("X2C skipped optimized layout ${layoutName} because ${skipReason(layoutName)}.")
            } else {
                writeDispatcher(layoutName)
                layouts[layoutName].values().each { LayoutSpec spec ->
                    writeVariantFactory(spec)
                }
            }
        }
        writeGroup()
        if (applicationModule) {
            writeRootIndex()
        } else {
            writeModuleIndex()
            writeModuleIndexMarker()
        }
    }

    private void writeGroup() {
        ParameterizedTypeName factoryArray = ParameterizedTypeName.get(SPARSE_ARRAY, I_VIEW_FACTORY)
        MethodSpec.Builder loadInto = MethodSpec.methodBuilder('loadInto')
                .addAnnotation(Override)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(factoryArray, 'factories')
        targets.each { String layoutName ->
            if (canGenerateLayout(layoutName)) {
                loadInto.addStatement('factories.put($T.layout.$L, new $T())',
                        rClass(), layoutName, ClassName.get(generatedPackage, dispatcherName(layoutName)))
            }
        }
        TypeSpec typeSpec = TypeSpec.classBuilder('X2CGroup')
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(X2C_GROUP)
                .addMethod(loadInto.build())
                .build()
        writeJavaFile(typeSpec)
    }

    private void writeModuleIndex() {
        String groupClassName = "${generatedPackage}.X2CGroup"
        MethodSpec.Builder loadInto = rootLoadIntoBuilder()
        loadInto.addStatement('int groupId = ensureGroup(groups, $S, new $T())',
                groupClassName, ClassName.get(generatedPackage, 'X2CGroup'))
        targets.each { String layoutName ->
            if (canGenerateLayout(layoutName)) {
                loadInto.addStatement('layoutToGroup.put($T.layout.$L, groupId)', rClass(), layoutName)
            }
        }
        TypeSpec typeSpec = TypeSpec.classBuilder('X2CModuleIndex')
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(rawSparseArraySuppressWarnings())
                .addSuperinterface(X2C_ROOT_INDEX)
                .addMethod(loadInto.build())
                .addMethod(ensureGroupMethod())
                .addMethod(nextGroupIdMethod())
                .build()
        writeJavaFile(typeSpec)
    }

    private void writeModuleIndexMarker() {
        if (!targets.any { String layoutName -> canGenerateLayout(layoutName) }) {
            return
        }
        File markerFile = new File(javaResOutputDir,
                "${GenerateX2cTask.MODULE_INDEX_MARKER_DIR}/${generatedPackage}${GenerateX2cTask.MODULE_INDEX_MARKER_SUFFIX}")
        markerFile.parentFile.mkdirs()
        markerFile.setText("${generatedPackage}.X2CModuleIndex\n", 'UTF-8')
    }

    private void writeRootIndex() {
        MethodSpec.Builder loadInto = rootLoadIntoBuilder()
        if (targets.any { String layoutName -> canGenerateLayout(layoutName) }) {
            loadInto.addStatement('groups.put(0, new $T())', ClassName.get(generatedPackage, 'X2CGroup'))
            targets.each { String layoutName ->
                if (canGenerateLayout(layoutName)) {
                    loadInto.addStatement('layoutToGroup.put($T.layout.$L, 0)', rClass(), layoutName)
                }
            }
        }
        // The class names are intentionally kept out of source-level references; ASM injects direct calls after javac.
        loadInto.addStatement('loadX2CModuleIndexes(layoutToGroup, groups)')
        TypeSpec typeSpec = TypeSpec.classBuilder('X2CRootIndex')
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(rawSparseArraySuppressWarnings())
                .addSuperinterface(X2C_ROOT_INDEX)
                .addMethod(loadInto.build())
                .addMethod(loadX2CModuleIndexesMethod())
                .addMethod(ensureGroupMethod())
                .addMethod(nextGroupIdMethod())
                .build()
        writeJavaFile(typeSpec)
    }

    private void writeDispatcher(String layoutName) {
        List<LayoutSpec> supported = layouts[layoutName].values().findAll { LayoutSpec spec ->
            canGenerateVariant(spec)
        }
        if (supported.isEmpty()) {
            return
        }
        supported.sort { LayoutSpec spec -> -variantScore(spec.qualifier) }

        MethodSpec.Builder createView = createViewBuilder()
        List<LayoutSpec> conditionalVariants = supported.findAll { LayoutSpec spec -> qualifierCondition(spec.qualifier) != null }
        conditionalVariants.eachWithIndex { LayoutSpec spec, int index ->
            String condition = qualifierCondition(spec.qualifier)
            if (index == 0) {
                createView.beginControlFlow("if (${condition})")
            } else {
                createView.nextControlFlow("else if (${condition})")
            }
            createView.addStatement('return new $T().createView(context, parent, attachToParent)',
                    ClassName.get(generatedPackage, variantFactoryName(spec)))
        }
        LayoutSpec fallback = supported.find { it.qualifier == '' } ?: supported.last()
        if (!conditionalVariants.isEmpty()) {
            createView.nextControlFlow('else')
            createView.addStatement('return new $T().createView(context, parent, attachToParent)',
                    ClassName.get(generatedPackage, variantFactoryName(fallback)))
            createView.endControlFlow()
        } else {
            createView.addStatement('return new $T().createView(context, parent, attachToParent)',
                    ClassName.get(generatedPackage, variantFactoryName(fallback)))
        }
        TypeSpec typeSpec = TypeSpec.classBuilder(dispatcherName(layoutName))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(I_VIEW_FACTORY)
                .addMethod(createView.build())
                .build()
        writeJavaFile(typeSpec)
    }

    private void writeVariantFactory(LayoutSpec spec) {
        String className = variantFactoryName(spec)
        CodeBlock.Builder body = CodeBlock.builder()
        body.addStatement('$T parser = context.getResources().getXml($T.layout.$L)', XML_RESOURCE_PARSER, rClass(), spec.name)
        body.beginControlFlow('try')
        if (spec.root.tag == 'merge') {
            body.beginControlFlow('if (parent == null || !attachToParent)')
            body.addStatement('throw new $T($S)', INFLATE_EXCEPTION,
                    "<merge> root requires a parent and attachToParent=true: ${spec.name}")
            body.endControlFlow()
            body.addStatement('$T.nextStartTag(parser)', INFLATE_UTILS)
            CodeGenContext context = new CodeGenContext()
            spec.root.children.each { LayoutNode child ->
                emitNode(body, child, 'parent', context, false)
            }
            body.addStatement('return parent')
        } else {
            CodeGenContext context = new CodeGenContext()
            String rootVar = emitNode(body, spec.root, 'parent', context, true)
            body.addStatement('return $N', rootVar)
        }
        body.nextControlFlow('catch ($T e)', INFLATE_EXCEPTION)
        body.addStatement('throw e')
        body.nextControlFlow('catch ($T e)', EXCEPTION)
        body.addStatement('throw new $T($S, e)', INFLATE_EXCEPTION, "X2C failed to inflate ${spec.file.name}")
        body.nextControlFlow('finally')
        body.addStatement('parser.close()')
        body.endControlFlow()

        TypeSpec typeSpec = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(I_VIEW_FACTORY)
                .addMethod(createViewBuilder().addCode(body.build()).build())
                .build()
        writeJavaFile(typeSpec)
    }

    private String emitNode(CodeBlock.Builder body, LayoutNode node, String parentVar, CodeGenContext context, boolean root) {
        int index = context.nextIndex()
        String attrs = "attrs${index}"
        body.addStatement('$T $N = $T.nextStartTag(parser)', ATTRIBUTE_SET, attrs, INFLATE_UTILS)

        if (node.tag == 'include') {
            if (node.includeName == null || node.includeName.isEmpty()) {
                throw new IllegalStateException("X2C include tag is missing layout attribute.")
            }
            if (hasMixedRootKinds(node.includeName)) {
                body.addStatement('$T.includeDynamic(context, $N, $T.layout.$L, $N)', INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs)
            } else if (isMergeRoot(node.includeName)) {
                body.addStatement('$T.includeMerge(context, $N, $T.layout.$L, $N)', INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs)
            } else {
                body.addStatement('$T.include(context, $N, $T.layout.$L, $N)', INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs)
            }
            return null
        }

        String view = "view${index}"
        if (shouldUseRuntimeViewCreation(node)) {
            body.addStatement('$T $N = $T.createView(context, $N, $N, $S)', VIEW, view, INFLATE_UTILS,
                    parentVar, attrs, node.tag)
        } else {
            ClassName type = ClassName.bestGuess(resolveViewType(node))
            body.addStatement('$T $N = new $T(context, $N)', type, view, type, attrs)
        }
        if (root) {
            body.addStatement('$T.attachRoot(parent, $N, $N, attachToParent)', INFLATE_UTILS, view, attrs)
        } else {
            body.addStatement('$T.addView($N, $N, $N)', INFLATE_UTILS, parentVar, view, attrs)
        }

        String childParent = view
        if (!node.children.isEmpty()) {
            childParent = "group${index}"
            body.addStatement('$T $N = ($T) $N', VIEW_GROUP, childParent, VIEW_GROUP, view)
            node.children.each { LayoutNode child ->
                emitNode(body, child, childParent, context, false)
            }
        }
        body.addStatement('$T.finishInflate($N)', INFLATE_UTILS, view)
        return view
    }

    private MethodSpec.Builder createViewBuilder() {
        return MethodSpec.methodBuilder('createView')
                .addAnnotation(Override)
                .addModifiers(Modifier.PUBLIC)
                .returns(VIEW)
                .addParameter(CONTEXT, 'context')
                .addParameter(VIEW_GROUP, 'parent')
                .addParameter(TypeName.BOOLEAN, 'attachToParent')
    }

    private MethodSpec.Builder rootLoadIntoBuilder() {
        return MethodSpec.methodBuilder('loadInto')
                .addAnnotation(Override)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(SPARSE_INT_ARRAY, 'layoutToGroup')
                .addParameter(SPARSE_ARRAY, 'groups')
    }

    private MethodSpec loadX2CModuleIndexesMethod() {
        return MethodSpec.methodBuilder('loadX2CModuleIndexes')
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(SPARSE_INT_ARRAY, 'layoutToGroup')
                .addParameter(SPARSE_ARRAY, 'groups')
                .build()
    }

    private MethodSpec ensureGroupMethod() {
        CodeBlock.Builder body = CodeBlock.builder()
        body.beginControlFlow('for (int i = 0; i < groups.size(); i++)')
        body.addStatement('int key = groups.keyAt(i)')
        body.addStatement('Object existing = groups.valueAt(i)')
        body.beginControlFlow('if (existing != null && groupClassName.equals(existing.getClass().getName()))')
        body.addStatement('return key')
        body.endControlFlow()
        body.endControlFlow()
        body.addStatement('int groupId = nextGroupId(groups)')
        body.addStatement('groups.put(groupId, group)')
        body.addStatement('return groupId')
        return MethodSpec.methodBuilder('ensureGroup')
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(SPARSE_ARRAY, 'groups')
                .addParameter(STRING, 'groupClassName')
                .addParameter(Object, 'group')
                .addCode(body.build())
                .build()
    }

    private MethodSpec nextGroupIdMethod() {
        CodeBlock.Builder body = CodeBlock.builder()
        body.addStatement('int next = 0')
        body.beginControlFlow('for (int i = 0; i < groups.size(); i++)')
        body.addStatement('next = Math.max(next, groups.keyAt(i) + 1)')
        body.endControlFlow()
        body.addStatement('return next')
        return MethodSpec.methodBuilder('nextGroupId')
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(SPARSE_ARRAY, 'groups')
                .addCode(body.build())
                .build()
    }

    private static AnnotationSpec rawSparseArraySuppressWarnings() {
        return AnnotationSpec.builder(SuppressWarnings)
                .addMember('value', '{$S, $S}', 'rawtypes', 'unchecked')
                .build()
    }

    private boolean isMergeRoot(String layoutName) {
        Map<String, LayoutSpec> variants = layouts[layoutName]
        if (variants == null || variants.isEmpty()) {
            return false
        }
        return variants.values().every { LayoutSpec spec -> spec.root.tag == 'merge' }
    }

    private static boolean shouldUseRuntimeViewCreation(LayoutNode node) {
        return node.tag != 'view' && !node.tag.contains('.')
    }

    private boolean hasMixedRootKinds(String layoutName) {
        Map<String, LayoutSpec> variants = layouts[layoutName]
        if (variants == null || variants.isEmpty()) {
            return false
        }
        boolean hasMerge = variants.values().any { LayoutSpec spec -> spec.root.tag == 'merge' }
        boolean hasNonMerge = variants.values().any { LayoutSpec spec -> spec.root.tag != 'merge' }
        return hasMerge && hasNonMerge
    }

    private boolean canGenerateLayout(String layoutName) {
        Map<String, LayoutSpec> variants = layouts[layoutName]
        if (variants == null || variants.isEmpty()) {
            return false
        }
        return variants.values().every { LayoutSpec spec -> canGenerateVariant(spec) }
    }

    private static boolean canGenerateVariant(LayoutSpec spec) {
        return spec != null && !spec.unsupported && isSupportedQualifier(spec.qualifier)
    }

    private String skipReason(String layoutName) {
        Map<String, LayoutSpec> variants = layouts[layoutName]
        if (variants == null || variants.isEmpty()) {
            return 'it has no parsed variants'
        }
        List<String> reasons = []
        if (variants.values().any { LayoutSpec spec -> spec.unsupported }) {
            reasons.add('one or more variants contain unsupported tags')
        }
        List<String> unsupportedQualifiers = variants.values()
                .findAll { LayoutSpec spec -> !isSupportedQualifier(spec.qualifier) }
                .collect { LayoutSpec spec -> spec.qualifier ?: '(default)' }
        if (!unsupportedQualifiers.isEmpty()) {
            reasons.add("it uses unsupported qualifiers ${unsupportedQualifiers}")
        }
        return reasons.isEmpty() ? 'it is not safe to optimize' : reasons.join(' and ')
    }

    private static String resolveViewType(LayoutNode node) {
        if (node.tag == 'view') {
            return node.className
        }
        if (node.tag.contains('.')) {
            return node.tag
        }
        switch (node.tag) {
            case 'View':
                return 'android.view.View'
            case 'ViewStub':
                return 'android.view.ViewStub'
            case 'WebView':
                return 'android.webkit.WebView'
            case 'TextureView':
                return 'android.view.TextureView'
            case 'SurfaceView':
                return 'android.view.SurfaceView'
            default:
                return 'android.widget.' + node.tag
        }
    }

    private static int variantScore(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) {
            return 0
        }
        int score = 0
        qualifier.split('-').each { String token ->
            if (token == 'land') {
                score += 1000
            } else if (token.startsWith('v')) {
                score += token.substring(1).isInteger() ? token.substring(1).toInteger() : 0
            } else {
                score += 1
            }
        }
        score
    }

    private static String qualifierCondition(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) {
            return null
        }
        if (!isSupportedQualifier(qualifier)) {
            return null
        }
        List<String> conditions = []
        qualifier.split('-').each { String token ->
            if (token == 'land') {
                conditions.add("context.getResources().getConfiguration().orientation == ${CONFIGURATION.canonicalName()}.ORIENTATION_LANDSCAPE")
            } else if (token.startsWith('v') && token.substring(1).isInteger()) {
                conditions.add("android.os.Build.VERSION.SDK_INT >= ${token.substring(1)}")
            }
        }
        conditions.isEmpty() ? null : conditions.join(' && ')
    }

    private static boolean isSupportedQualifier(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) {
            return true
        }
        return qualifier.split('-').every { String token ->
            token == 'land' || (token.startsWith('v') && token.substring(1).isInteger())
        }
    }

    private ClassName rClass() {
        return ClassName.get(rPackage, 'R')
    }

    private void writeJavaFile(TypeSpec typeSpec) {
        JavaFile.builder(generatedPackage, typeSpec)
                .skipJavaLangImports(true)
                .build()
                .writeTo(outputDir)
    }

    private static String dispatcherName(String layoutName) {
        return classPrefix(layoutName)
    }

    private static String variantFactoryName(LayoutSpec spec) {
        String suffix = spec.qualifier == null || spec.qualifier.isEmpty() ? 'Base' : camel(spec.qualifier.replace('-', '_'))
        return classPrefix(spec.name) + '_' + suffix
    }

    private static String classPrefix(String layoutName) {
        return 'X2C_' + camel(layoutName)
    }

    private static String camel(String raw) {
        raw.split('_').findAll { it.length() > 0 }.collect { String part ->
            part.substring(0, 1).toUpperCase() + (part.length() > 1 ? part.substring(1) : '')
        }.join('_')
    }
}

class LayoutSpec {
    final String name
    final String qualifier
    final File file
    final LayoutNode root
    boolean unsupported
    final Set<String> includes = new LinkedHashSet<>()

    LayoutSpec(String name, String qualifier, File file, LayoutNode root) {
        this.name = name
        this.qualifier = qualifier
        this.file = file
        this.root = root
    }
}

class LayoutNode {
    String tag
    String className
    String includeName
    List<LayoutNode> children = []
}

class CodeGenContext {
    private int index

    int nextIndex() {
        return index++
    }
}
