package io.github.linyilei.x2c.gradle

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import org.gradle.api.DefaultTask
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
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class X2cPlugin implements Plugin<Project> {

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

        def variants = applicationModule ? android.applicationVariants : android.libraryVariants
        variants.all { variant ->
            String taskName = "generate${variant.name.capitalize()}X2C"
            File outputDir = project.file("${project.buildDir}/generated/source/x2c/${variant.name}")
            def task = project.tasks.create(taskName, GenerateX2cTask) {
                it.outputDir = outputDir
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
                    it.moduleIndexClasspath = runtimeClasspath == null
                            ? project.files()
                            : moduleIndexClasspath(project, runtimeClasspath)
                }
            }
            variant.registerJavaGeneratingTask(task, outputDir)
        }
    }

    private static List<File> resolveSourceDirectories(Project project, def sourceSet) {
        Set<File> sourceDirs = new LinkedHashSet<>()
        def javaDirectories = sourceSet.hasProperty('javaDirectories') ? sourceSet.javaDirectories : null
        if (javaDirectories != null) {
            sourceDirs.addAll(javaDirectories.findAll { File dir -> dir != null })
        } else if (sourceSet.hasProperty('java') && sourceSet.java?.hasProperty('srcDirs')) {
            sourceDirs.addAll(sourceSet.java.srcDirs.findAll { File dir -> dir != null })
        }
        String sourceSetName = sourceSet.hasProperty('name') ? sourceSet.name : 'main'
        File conventionalKotlinDir = project.file("src/${sourceSetName}/kotlin")
        if (conventionalKotlinDir.exists()) {
            sourceDirs.add(conventionalKotlinDir)
        }
        new ArrayList<>(sourceDirs)
    }

    private static FileCollection moduleIndexClasspath(Project project, def runtimeClasspath) {
        Attribute<String> artifactType = Attribute.of('artifactType', String)
        FileCollection androidClassesJars = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'android-classes-jar')
        }.files
        FileCollection jars = runtimeClasspath.incoming.artifactView { view ->
            view.attributes.attribute(artifactType, 'jar')
        }.files
        project.files(androidClassesJars, jars)
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

    static final String MODE_TOOLS = 'tools'
    static final String MODE_ANNOTATION = 'annotation'

    private String mode
    private boolean modeConfigured
    private final Set<String> excludedLayouts = new LinkedHashSet<>()

    String normalizedMode() {
        normalizeMode(modeConfigured ? mode : MODE_TOOLS)
    }

    void setMode(String value) {
        mode = value
        modeConfigured = true
    }

    String getMode() {
        return mode
    }

    boolean hasConfiguredMode() {
        return modeConfigured
    }

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

    static String normalizeMode(String rawMode) {
        String normalized = rawMode == null ? MODE_TOOLS : rawMode.trim().toLowerCase(Locale.US)
        switch (normalized) {
            case MODE_TOOLS:
            case 'full':
                return MODE_TOOLS
            case MODE_ANNOTATION:
            case 'annotations':
            case 'annotationonly':
            case 'annotation-only':
            case 'annotation_only':
                return MODE_ANNOTATION
            default:
                throw new IllegalArgumentException("Unsupported X2C mode '${rawMode}'. Use 'tools' or 'annotation'.")
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

    @OutputDirectory
    File outputDir

    @org.gradle.api.tasks.Internal
    X2cExtension extensionConfig

    String rPackage
    String generatedPackage
    boolean applicationModule
    List<File> resDirs = []
    List<File> sourceDirs = []
    FileCollection moduleIndexClasspath

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
    String getX2cMode() {
        String configuredMode = extensionConfig != null && extensionConfig.hasConfiguredMode()
                ? extensionConfig.mode
                : null
        String globalMode = resolveGlobalMode()
        return X2cExtension.normalizeMode(configuredMode ?: globalMode ?: X2cExtension.MODE_TOOLS)
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
    FileCollection getX2cModuleIndexClasspath() {
        return moduleIndexClasspath ?: project.files()
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

        LayoutCatalog layoutCatalog = new LayoutCatalog(indexLayoutFiles(resDirs), project)
        LayoutSelection selection = resolveLayoutSelection(layoutCatalog, getX2cMode(), new LinkedHashSet<>(getX2cExcludedLayouts()))
        Map<String, Map<String, LayoutSpec>> layouts = selection.layouts
        Set<String> targets = selection.targets
        List<String> moduleIndexClassNames = findModuleIndexClassNames()
        if (targets.isEmpty() && moduleIndexClassNames.isEmpty()) {
            project.logger.lifecycle("X2C found no layouts selected in ${getX2cMode()} mode.")
            return
        }

        JavaWriter writer = new JavaWriter(outputDir, generatedPackage, rPackage, layouts, targets,
                moduleIndexClassNames, applicationModule, project)
        writer.writeAll()
    }

    private List<String> findModuleIndexClassNames() {
        if (!applicationModule) {
            return []
        }
        Set<String> classNames = new LinkedHashSet<>()
        getX2cModuleIndexClasspath().files.each { File file ->
            scanModuleIndexClasses(file, classNames)
        }
        classNames.sort()
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

    private String resolveGlobalMode() {
        List<Object> candidates = [
                project.findProperty('x2cMode'),
                project.findProperty('x2c.mode'),
                project.rootProject.findProperty('x2cMode'),
                project.rootProject.findProperty('x2c.mode')
        ]
        Object configured = candidates.find { Object value ->
            value != null && value.toString().trim().length() > 0
        }
        return configured == null ? null : configured.toString()
    }

    private LayoutSelection resolveLayoutSelection(LayoutCatalog layoutCatalog, String mode, Set<String> excludedLayouts) {
        switch (mode) {
            case X2cExtension.MODE_TOOLS:
                return resolveToolsSelection(layoutCatalog, excludedLayouts)
            case X2cExtension.MODE_ANNOTATION:
                return resolveAnnotationSelection(layoutCatalog, excludedLayouts)
            default:
                throw new IllegalArgumentException("Unsupported X2C mode '${mode}'.")
        }
    }

    private LayoutSelection resolveToolsSelection(LayoutCatalog layoutCatalog, Set<String> excludedLayouts) {
        Set<String> targets = new LinkedHashSet<>()
        layoutCatalog.layoutNames().each { String name ->
            Map<String, LayoutSpec> variants = layoutCatalog.load(name)
            if (!excludedLayouts.contains(name) && variants.values().any { LayoutSpec spec -> spec.marked }) {
                targets.add(name)
            }
        }
        expandIncludedTargets(layoutCatalog, targets, excludedLayouts)
        return new LayoutSelection(layoutCatalog.snapshot(), targets)
    }

    private LayoutSelection resolveAnnotationSelection(LayoutCatalog layoutCatalog, Set<String> excludedLayouts) {
        Set<String> targets = new LinkedHashSet<>()
        scanAnnotatedLayoutTargets().each { String name ->
            if (excludedLayouts.contains(name)) {
                project.logger.lifecycle("X2C skipped annotated layout ${name} because it is blacklisted.")
                return
            }
            if (!layoutCatalog.contains(name)) {
                project.logger.warn("X2C annotated layout ${name} was not found under res/layout*.")
                return
            }
            targets.add(name)
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
                    if (!layoutCatalog.contains(includeName)) {
                        return
                    }
                    layoutCatalog.load(includeName)
                    if (!excludedLayouts.contains(includeName) && targets.add(includeName)) {
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
        Set<String> layouts = new LinkedHashSet<>()
        def matcher = (source =~ /@Xml\s*\(([\s\S]*?)\)/)
        while (matcher.find()) {
            String body = matcher.group(1)
            def nameMatcher = (body =~ /["']([A-Za-z0-9_]+)["']/)
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
            spec.marked = isMarked(root)
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

    private static boolean isMarked(Element root) {
        String value = root.getAttribute('tools:x2c')
        return 'standard' == value || 'true' == value
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

    Set<String> layoutNames() {
        return new LinkedHashSet<>(layoutFiles.keySet())
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
    private static final ClassName OBJECT = ClassName.get('java.lang', 'Object')
    private static final ClassName CLASS = ClassName.get('java.lang', 'Class')
    private static final ClassName THROWABLE = ClassName.get('java.lang', 'Throwable')
    private static final ClassName EXCEPTION = ClassName.get('java.lang', 'Exception')

    private final File outputDir
    private final String generatedPackage
    private final String rPackage
    private final Map<String, Map<String, LayoutSpec>> layouts
    private final Set<String> targets
    private final List<String> moduleIndexClassNames
    private final boolean applicationModule
    private final Project project

    JavaWriter(File outputDir, String generatedPackage, String rPackage,
               Map<String, Map<String, LayoutSpec>> layouts, Set<String> targets,
               List<String> moduleIndexClassNames, boolean applicationModule, Project project) {
        this.outputDir = outputDir
        this.generatedPackage = generatedPackage
        this.rPackage = rPackage
        this.layouts = layouts
        this.targets = targets
        this.moduleIndexClassNames = moduleIndexClassNames
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
        loadInto.addStatement('int groupId = ensureGroup(groupClassNames, $S)', groupClassName)
        targets.each { String layoutName ->
            if (canGenerateLayout(layoutName)) {
                loadInto.addStatement('layoutToGroup.put($T.layout.$L, groupId)', rClass(), layoutName)
            }
        }
        TypeSpec typeSpec = TypeSpec.classBuilder('X2CModuleIndex')
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(X2C_ROOT_INDEX)
                .addMethod(loadInto.build())
                .addMethod(ensureGroupMethod())
                .addMethod(nextGroupIdMethod())
                .build()
        writeJavaFile(typeSpec)
    }

    private void writeRootIndex() {
        MethodSpec.Builder loadInto = rootLoadIntoBuilder()
        if (targets.any { String layoutName -> canGenerateLayout(layoutName) }) {
            String groupClassName = "${generatedPackage}.X2CGroup"
            loadInto.addStatement('groupClassNames.put(0, $S)', groupClassName)
            targets.each { String layoutName ->
                if (canGenerateLayout(layoutName)) {
                    loadInto.addStatement('layoutToGroup.put($T.layout.$L, 0)', rClass(), layoutName)
                }
            }
        }
        moduleIndexClassNames.each { String className ->
            loadInto.addStatement('loadModuleIndex($S, layoutToGroup, groupClassNames)', className)
        }
        TypeSpec typeSpec = TypeSpec.classBuilder('X2CRootIndex')
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(X2C_ROOT_INDEX)
                .addMethod(loadInto.build())
                .addMethod(loadModuleIndexMethod())
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
                .addParameter(ParameterizedTypeName.get(SPARSE_ARRAY, STRING), 'groupClassNames')
    }

    private MethodSpec loadModuleIndexMethod() {
        ParameterizedTypeName classType = ParameterizedTypeName.get(CLASS, WildcardTypeName.subtypeOf(OBJECT))
        ParameterizedTypeName stringArray = ParameterizedTypeName.get(SPARSE_ARRAY, STRING)
        CodeBlock.Builder body = CodeBlock.builder()
        body.beginControlFlow('try')
        body.addStatement('$T clazz = $T.forName(className)', classType, CLASS)
        body.addStatement('$T instance = clazz.getDeclaredConstructor().newInstance()', OBJECT)
        body.beginControlFlow('if (!(instance instanceof $T))', X2C_ROOT_INDEX)
        body.addStatement('return')
        body.endControlFlow()
        body.addStatement('$T moduleLayoutToGroup = new $T()', SPARSE_INT_ARRAY, SPARSE_INT_ARRAY)
        body.addStatement('$T moduleGroupClassNames = new $T<>()', stringArray, SPARSE_ARRAY)
        body.addStatement('(($T) instance).loadInto(moduleLayoutToGroup, moduleGroupClassNames)', X2C_ROOT_INDEX)
        body.beginControlFlow('for (int i = 0; i < moduleLayoutToGroup.size(); i++)')
        body.addStatement('int layoutId = moduleLayoutToGroup.keyAt(i)')
        body.addStatement('int moduleGroupId = moduleLayoutToGroup.valueAt(i)')
        body.addStatement('$T groupClassName = moduleGroupClassNames.get(moduleGroupId)', STRING)
        body.beginControlFlow('if (groupClassName == null)')
        body.addStatement('continue')
        body.endControlFlow()
        body.addStatement('layoutToGroup.put(layoutId, ensureGroup(groupClassNames, groupClassName))')
        body.endControlFlow()
        body.nextControlFlow('catch ($T ignored)', THROWABLE)
        body.add('// Missing or incompatible module indexes should not block XML fallback.\n')
        body.endControlFlow()

        return MethodSpec.methodBuilder('loadModuleIndex')
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(STRING, 'className')
                .addParameter(SPARSE_INT_ARRAY, 'layoutToGroup')
                .addParameter(stringArray, 'groupClassNames')
                .addCode(body.build())
                .build()
    }

    private MethodSpec ensureGroupMethod() {
        ParameterizedTypeName stringArray = ParameterizedTypeName.get(SPARSE_ARRAY, STRING)
        CodeBlock.Builder body = CodeBlock.builder()
        body.beginControlFlow('for (int i = 0; i < groupClassNames.size(); i++)')
        body.addStatement('int key = groupClassNames.keyAt(i)')
        body.beginControlFlow('if (groupClassName.equals(groupClassNames.valueAt(i)))')
        body.addStatement('return key')
        body.endControlFlow()
        body.endControlFlow()
        body.addStatement('int groupId = nextGroupId(groupClassNames)')
        body.addStatement('groupClassNames.put(groupId, groupClassName)')
        body.addStatement('return groupId')
        return MethodSpec.methodBuilder('ensureGroup')
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(stringArray, 'groupClassNames')
                .addParameter(STRING, 'groupClassName')
                .addCode(body.build())
                .build()
    }

    private MethodSpec nextGroupIdMethod() {
        ParameterizedTypeName stringArray = ParameterizedTypeName.get(SPARSE_ARRAY, STRING)
        CodeBlock.Builder body = CodeBlock.builder()
        body.addStatement('int next = 0')
        body.beginControlFlow('for (int i = 0; i < groupClassNames.size(); i++)')
        body.addStatement('next = Math.max(next, groupClassNames.keyAt(i) + 1)')
        body.endControlFlow()
        body.addStatement('return next')
        return MethodSpec.methodBuilder('nextGroupId')
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(stringArray, 'groupClassNames')
                .addCode(body.build())
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
    boolean marked
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
