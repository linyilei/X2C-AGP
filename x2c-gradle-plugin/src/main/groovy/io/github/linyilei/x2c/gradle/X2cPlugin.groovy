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
        project.plugins.withId('com.android.application') {
            configureAndroidProject(project, true)
        }
        project.plugins.withId('com.android.library') {
            configureAndroidProject(project, false)
        }
    }

    private static void configureAndroidProject(Project project, boolean applicationModule) {
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
                it.generatedPackage = applicationModule ? variant.applicationId + '.x2c' : libraryGeneratedPackage
                it.rPackage = rPackage
                it.resDirs = variant.sourceSets.collectMany { sourceSet ->
                    sourceSet.resDirectories.findAll { File dir -> dir.exists() }
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

class GenerateX2cTask extends DefaultTask {

    @OutputDirectory
    File outputDir

    String rPackage
    String generatedPackage
    boolean applicationModule
    List<File> resDirs = []
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

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    FileCollection getX2cResourceDirs() {
        return project.files(resDirs)
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

        Map<String, Map<String, LayoutSpec>> layouts = scanLayouts()
        Set<String> targets = resolveGenerationTargets(layouts)
        List<String> moduleIndexClassNames = findModuleIndexClassNames()
        if (targets.isEmpty() && moduleIndexClassNames.isEmpty()) {
            project.logger.lifecycle('X2C found no layouts marked with tools:x2c="standard".')
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

    private Map<String, Map<String, LayoutSpec>> scanLayouts() {
        return scanLayouts(resDirs, project)
    }

    private static Map<String, Map<String, LayoutSpec>> scanLayouts(List<File> sourceResDirs, Project owner) {
        Map<String, Map<String, LayoutSpec>> result = [:].withDefault { [:] }
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
                                    LayoutSpec spec = LayoutParser.parse(file, name, qualifier, owner)
                                    if (spec != null) {
                                        result[name][qualifier] = spec
                                    }
                                }
                    }
        }
        result
    }

    private static Set<String> resolveGenerationTargets(Map<String, Map<String, LayoutSpec>> layouts) {
        Set<String> targets = new LinkedHashSet<>()
        layouts.each { String name, Map<String, LayoutSpec> variants ->
            if (variants.values().any { LayoutSpec spec -> spec.marked }) {
                targets.add(name)
            }
        }

        List<String> queue = new ArrayList<>(targets)
        for (int i = 0; i < queue.size(); i++) {
            String name = queue[i]
            layouts[name]?.values()?.each { LayoutSpec spec ->
                spec.includes.each { String includeName ->
                    if (layouts.containsKey(includeName) && targets.add(includeName)) {
                        queue.add(includeName)
                    }
                }
            }
        }
        targets
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
            writeDispatcher(layoutName)
            layouts[layoutName].values().each { LayoutSpec spec ->
                if (!spec.unsupported) {
                    writeVariantFactory(spec)
                } else {
                    project.logger.lifecycle("X2C skipped optimized variant ${spec.file} because it contains unsupported tags.")
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
            if (hasSupportedVariant(layoutName)) {
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
            if (hasSupportedVariant(layoutName)) {
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
        if (targets.any { String layoutName -> hasSupportedVariant(layoutName) }) {
            String groupClassName = "${generatedPackage}.X2CGroup"
            loadInto.addStatement('groupClassNames.put(0, $S)', groupClassName)
            targets.each { String layoutName ->
                if (hasSupportedVariant(layoutName)) {
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
        List<LayoutSpec> supported = layouts[layoutName].values().findAll { LayoutSpec spec -> !spec.unsupported }
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
            boolean mergeRoot = isMergeRoot(node.includeName)
            if (mergeRoot) {
                body.addStatement('$T.includeMerge(context, $N, $T.layout.$L, $N)', INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs)
            } else {
                body.addStatement('$T.include(context, $N, $T.layout.$L, $N)', INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs)
            }
            return null
        }

        ClassName type = ClassName.bestGuess(resolveViewType(node))
        String view = "view${index}"
        body.addStatement('$T $N = new $T(context, $N)', type, view, type, attrs)
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

    private boolean hasSupportedVariant(String layoutName) {
        layouts[layoutName]?.values()?.any { LayoutSpec spec -> !spec.unsupported }
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
