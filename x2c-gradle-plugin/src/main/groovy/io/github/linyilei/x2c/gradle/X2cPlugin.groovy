package io.github.linyilei.x2c.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.w3c.dom.Element

import javax.xml.parsers.DocumentBuilderFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

class X2cPlugin implements Plugin<Project> {

    private static final String MODULE_INDEX_LOADER_METHOD = 'loadX2CModuleIndexes'
    private static final String MODULE_INDEX_LOADER_DESC = '(Landroid/util/SparseIntArray;Landroid/util/SparseArray;)V'
    private static final String X2C_ROOT_INDEX_INTERNAL_NAME = 'io/github/linyilei/x2c/runtime/X2CRootIndex'
    private static final String MODULE_INDEX_MARKER_DIR = 'META-INF/x2c'
    private static final String MODULE_INDEX_MARKER_SUFFIX = '.module-index'

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
        if (applicationModule) {
            configureRuntimeInstrumentation(project)
        }

        def variants = applicationModule ? android.applicationVariants : android.libraryVariants
        variants.all { variant ->
            String generatedPackage = applicationModule ? variant.applicationId + '.x2c' : libraryGeneratedPackage
            List<File> resDirs = variant.sourceSets.collectMany { sourceSet ->
                sourceSet.resDirectories.findAll { File dir -> dir.exists() }
            }
            configureCompilerArgs(variant, rPackage, generatedPackage, applicationModule, resDirs, extension)
            if (applicationModule) {
                def runtimeClasspath = project.configurations.findByName("${variant.name}RuntimeClasspath")
                FileCollection markerClasspath = runtimeClasspath == null
                        ? project.files()
                        : moduleIndexMarkerClasspath(project, runtimeClasspath)
                FileCollection classClasspath = runtimeClasspath == null
                        ? project.files()
                        : moduleIndexClassClasspath(project, runtimeClasspath)
                configureRootIndexAsmInjection(project, variant, generatedPackage, markerClasspath, classClasspath)
            }
        }
    }

    private static void configureCompilerArgs(def variant, String rPackage, String generatedPackage,
                                              boolean applicationModule, List<File> resDirs,
                                              X2cExtension extension) {
        variant.javaCompileProvider.configure { javaCompile ->
            javaCompile.inputs.files(resDirs)
                    .withPropertyName('x2cResDirs')
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            javaCompile.options.compilerArgs += [
                    "-Ax2c.generatedPackage=${generatedPackage}",
                    "-Ax2c.rPackage=${rPackage}",
                    "-Ax2c.applicationModule=${applicationModule}",
                    "-Ax2c.resDirs=${resDirs.collect { File file -> file.absolutePath }.join(File.pathSeparator)}",
                    "-Ax2c.groupSize=${extension.normalizedGroupSize()}",
                    "-Ax2c.excludes=${extension.normalizedExcludedLayouts().sort().join(',')}"
            ]
        }
    }

    private static void configureRuntimeInstrumentation(Project project) {
        def androidComponents = project.extensions.findByName('androidComponents')
        if (androidComponents == null) {
            throw new GradleException('X2C requires the AGP ASM instrumentation API. Use Android Gradle Plugin 4.2 or newer.')
        }
        try {
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                variant.transformClassesWith(X2CRootIndexLoaderClassVisitorFactory, InstrumentationScope.ALL) { parameters ->
                    parameters.generatedRootInternalName.set(variant.applicationId.map { String applicationId ->
                        applicationId.replace('.', '/') + '/x2c/X2CRootIndex'
                    })
                }
                variant.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
            }
            project.logger.info('X2C registered AGP ASM instrumentation for runtime root-index injection.')
        } catch (Throwable error) {
            throw new GradleException('X2C could not register AGP ASM instrumentation for runtime root-index injection.', error)
        }
    }

    private static void configureRootIndexAsmInjection(Project project, def variant, String generatedPackage,
                                                       FileCollection markerClasspath, FileCollection classClasspath) {
        variant.javaCompileProvider.configure { javaCompile ->
            javaCompile.doLast {
                List<String> moduleIndexClassNames = findModuleIndexClassNames(markerClasspath, classClasspath)
                if (moduleIndexClassNames.isEmpty()) {
                    return
                }
                injectModuleIndexesIntoRootIndex(javaCompileDestinationDir(javaCompile), generatedPackage,
                        moduleIndexClassNames, project)
            }
        }
    }

    private static File javaCompileDestinationDir(def javaCompile) {
        if (javaCompile.hasProperty('destinationDirectory') && javaCompile.destinationDirectory.present) {
            return javaCompile.destinationDirectory.get().asFile
        }
        return javaCompile.destinationDir
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

    private static FileCollection moduleIndexMarkerClasspath(Project project, def runtimeClasspath) {
        Attribute<String> artifactType = Attribute.of('artifactType', String)
        FileCollection javaRes = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'android-java-res')
        }.files
        project.files(javaRes, moduleIndexClassClasspath(project, runtimeClasspath))
    }

    private static FileCollection moduleIndexClassClasspath(Project project, def runtimeClasspath) {
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

    private static List<String> findModuleIndexClassNames(FileCollection markerClasspath, FileCollection classClasspath) {
        Set<String> classNames = new LinkedHashSet<>()
        if (markerClasspath != null) {
            markerClasspath.files.each { File file ->
                scanModuleIndexMarkers(file, classNames)
            }
        }
        validateModuleIndexClasses(classNames, classClasspath)
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

    private static void validateModuleIndexClasses(Set<String> classNames, FileCollection classClasspath) {
        if (classNames.isEmpty()) {
            return
        }
        Map<String, String> expectedEntries = new LinkedHashMap<>()
        classNames.each { String className ->
            expectedEntries[className.replace('.', '/') + '.class'] = className
        }
        Set<String> missingEntries = new LinkedHashSet<>(expectedEntries.keySet())
        if (classClasspath != null) {
            classClasspath.files.each { File file ->
                if (!missingEntries.isEmpty()) {
                    scanExpectedClassEntries(file, missingEntries)
                }
            }
        }
        List<String> missingClassNames = missingEntries.collect { String entryName -> expectedEntries[entryName] }.sort()
        if (!missingClassNames.isEmpty()) {
            throw new GradleException("X2C module-index marker points to missing classes: "
                    + missingClassNames.join(', ')
                    + ". Check that dependencies publish Java classes together with META-INF/x2c markers.")
        }
    }

    private static void scanExpectedClassEntries(File file, Set<String> missingEntries) {
        if (file == null || !file.exists() || missingEntries.isEmpty()) {
            return
        }
        if (file.isDirectory()) {
            new ArrayList<>(missingEntries).each { String entryName ->
                if (new File(file, entryName).isFile()) {
                    missingEntries.remove(entryName)
                }
            }
            return
        }
        if (!file.name.endsWith('.jar') && !file.name.endsWith('.zip')) {
            return
        }
        try {
            ZipFile zipFile = new ZipFile(file)
            try {
                new ArrayList<>(missingEntries).each { String entryName ->
                    if (zipFile.getEntry(entryName) != null) {
                        missingEntries.remove(entryName)
                    }
                }
            } finally {
                zipFile.close()
            }
        } catch (Exception ignored) {
            // Broken or non-zip files on the classpath cannot satisfy marker validation.
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

class X2cExtension {

    static final int DEFAULT_GROUP_SIZE = 64

    private final Set<String> excludedLayouts = new LinkedHashSet<>()
    private int groupSize = DEFAULT_GROUP_SIZE

    Set<String> normalizedExcludedLayouts() {
        return new LinkedHashSet<>(excludedLayouts)
    }

    int normalizedGroupSize() {
        return groupSize <= 0 ? DEFAULT_GROUP_SIZE : groupSize
    }

    int getGroupSize() {
        return groupSize
    }

    void setGroupSize(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException('x2c.groupSize must be greater than 0.')
        }
        groupSize = value
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
