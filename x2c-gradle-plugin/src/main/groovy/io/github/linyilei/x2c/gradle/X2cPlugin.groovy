package io.github.linyilei.x2c.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.w3c.dom.Node

import javax.xml.parsers.DocumentBuilderFactory

class X2cPlugin implements Plugin<Project> {

    static final String GROUP_CLASS_PROPERTY = 'codexX2cGeneratedGroupClassName'
    static final String R_PACKAGE_PROPERTY = 'codexX2cRPackageName'

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
        if (!applicationModule) {
            project.extensions.extraProperties.set(GROUP_CLASS_PROPERTY, libraryGeneratedPackage + '.X2CGroup')
            project.extensions.extraProperties.set(R_PACKAGE_PROPERTY, rPackage)
        }

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
            }
            variant.registerJavaGeneratingTask(task, outputDir)
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

class GenerateX2cTask extends DefaultTask {

    @OutputDirectory
    File outputDir

    String rPackage
    String generatedPackage
    boolean applicationModule
    List<File> resDirs = []

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
    List<String> getX2cAggregateGroupEntries() {
        return findAggregatedGroupSpecs().collectMany { AggregateGroupSpec spec ->
            spec.layoutNames.collect { String layoutName ->
                "${spec.rPackage}:${layoutName}:${spec.groupClassName}"
            }
        }.sort()
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    FileCollection getX2cResourceDirs() {
        return project.files(resDirs)
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
        List<AggregateGroupSpec> aggregateGroupSpecs = findAggregatedGroupSpecs()
        if (targets.isEmpty() && aggregateGroupSpecs.isEmpty()) {
            project.logger.lifecycle('X2C found no layouts marked with tools:x2c="standard".')
            return
        }

        JavaWriter writer = new JavaWriter(outputDir, generatedPackage, rPackage, layouts, targets,
                aggregateGroupSpecs, applicationModule, project)
        writer.writeAll()
    }

    private List<AggregateGroupSpec> findAggregatedGroupSpecs() {
        Set<Project> dependencies = new LinkedHashSet<>()
        collectDirectProjectDependencies(project, dependencies)
        dependencies.collect { Project dependency ->
            if (!dependency.extensions.extraProperties.has(X2cPlugin.GROUP_CLASS_PROPERTY)
                    || !dependency.extensions.extraProperties.has(X2cPlugin.R_PACKAGE_PROPERTY)) {
                return null
            }
            String groupClassName = dependency.extensions.extraProperties.get(X2cPlugin.GROUP_CLASS_PROPERTY).toString()
            String dependencyRPackage = dependency.extensions.extraProperties.get(X2cPlugin.R_PACKAGE_PROPERTY).toString()
            def android = dependency.extensions.findByName('android')
            if (android == null) {
                return null
            }
            List<File> dependencyResDirs = android.sourceSets.collectMany { sourceSet ->
                sourceSet.resDirectories.findAll { File dir -> dir.exists() }
            }
            Map<String, Map<String, LayoutSpec>> dependencyLayouts = scanLayouts(dependencyResDirs, dependency)
            Set<String> dependencyTargets = resolveGenerationTargets(dependencyLayouts)
            Set<String> supportedLayouts = dependencyTargets.findAll { String layoutName ->
                dependencyLayouts[layoutName]?.values()?.any { LayoutSpec spec -> !spec.unsupported }
            } as Set<String>
            supportedLayouts.isEmpty() ? null : new AggregateGroupSpec(groupClassName, dependencyRPackage, supportedLayouts)
        }.findAll { AggregateGroupSpec spec -> spec != null }
                .sort { AggregateGroupSpec spec -> spec.groupClassName }
    }

    private static void collectDirectProjectDependencies(Project owner, Set<Project> result) {
        ['api', 'implementation', 'compile'].each { String configurationName ->
            def configuration = owner.configurations.findByName(configurationName)
            if (configuration == null) {
                return
            }
            configuration.dependencies.withType(ProjectDependency).each { ProjectDependency dependency ->
                result.add(dependency.dependencyProject)
            }
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

    private final File outputDir
    private final String generatedPackage
    private final String rPackage
    private final Map<String, Map<String, LayoutSpec>> layouts
    private final Set<String> targets
    private final List<AggregateGroupSpec> aggregateGroupSpecs
    private final boolean applicationModule
    private final Project project

    JavaWriter(File outputDir, String generatedPackage, String rPackage,
               Map<String, Map<String, LayoutSpec>> layouts, Set<String> targets,
               List<AggregateGroupSpec> aggregateGroupSpecs, boolean applicationModule, Project project) {
        this.outputDir = outputDir
        this.generatedPackage = generatedPackage
        this.rPackage = rPackage
        this.layouts = layouts
        this.targets = targets
        this.aggregateGroupSpecs = aggregateGroupSpecs
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
        }
    }

    private void writeGroup() {
        StringBuilder body = new StringBuilder()
        body << "package ${generatedPackage};\n\n"
        body << "import android.util.SparseArray;\n"
        body << "import io.github.linyilei.x2c.runtime.IViewFactory;\n"
        body << "import ${rPackage}.R;\n\n"
        body << "public final class X2CGroup implements io.github.linyilei.x2c.runtime.X2CGroup {\n"
        body << "  @Override public void loadInto(SparseArray<IViewFactory> factories) {\n"
        targets.each { String layoutName ->
            if (hasSupportedVariant(layoutName)) {
                body << "    factories.put(R.layout.${layoutName}, new ${dispatcherName(layoutName)}());\n"
            }
        }
        body << "  }\n"
        body << "}\n"
        writeJavaFile('X2CGroup', body.toString())
    }

    private void writeRootIndex() {
        Map<String, Integer> groupIds = new LinkedHashMap<>()
        if (targets.any { String layoutName -> hasSupportedVariant(layoutName) }) {
            groupIds.put("${generatedPackage}.X2CGroup", groupIds.size())
        }
        aggregateGroupSpecs.each { AggregateGroupSpec spec ->
            if (!groupIds.containsKey(spec.groupClassName)) {
                groupIds.put(spec.groupClassName, groupIds.size())
            }
        }

        StringBuilder body = new StringBuilder()
        body << "package ${generatedPackage};\n\n"
        body << "import android.util.SparseArray;\n"
        body << "import android.util.SparseIntArray;\n"
        body << "import ${rPackage}.R;\n\n"
        body << "public final class X2CRootIndex implements io.github.linyilei.x2c.runtime.X2CRootIndex {\n"
        body << "  @Override public void loadInto(SparseIntArray layoutToGroup, SparseArray<String> groupClassNames) {\n"
        groupIds.each { String groupClassName, Integer groupId ->
            body << "    groupClassNames.put(${groupId}, \"${groupClassName}\");\n"
        }
        targets.each { String layoutName ->
            if (hasSupportedVariant(layoutName)) {
                body << "    layoutToGroup.put(R.layout.${layoutName}, ${groupIds.get("${generatedPackage}.X2CGroup")});\n"
            }
        }
        aggregateGroupSpecs.each { AggregateGroupSpec spec ->
            spec.layoutNames.each { String layoutName ->
                body << "    layoutToGroup.put(${spec.rPackage}.R.layout.${layoutName}, ${groupIds.get(spec.groupClassName)});\n"
            }
        }
        body << "  }\n"
        body << "}\n"
        writeJavaFile('X2CRootIndex', body.toString())
    }

    private void writeDispatcher(String layoutName) {
        List<LayoutSpec> supported = layouts[layoutName].values().findAll { LayoutSpec spec -> !spec.unsupported }
        if (supported.isEmpty()) {
            return
        }
        supported.sort { LayoutSpec spec -> -variantScore(spec.qualifier) }

        StringBuilder body = new StringBuilder()
        body << "package ${generatedPackage};\n\n"
        body << "import android.content.Context;\n"
        body << "import android.os.Build;\n"
        body << "import android.view.View;\n"
        body << "import android.view.ViewGroup;\n"
        body << "import io.github.linyilei.x2c.runtime.IViewFactory;\n\n"
        body << "public final class ${dispatcherName(layoutName)} implements IViewFactory {\n"
        body << "  @Override public View createView(Context context, ViewGroup parent, boolean attachToParent) {\n"
        List<LayoutSpec> conditionalVariants = supported.findAll { LayoutSpec spec -> qualifierCondition(spec.qualifier) != null }
        conditionalVariants.eachWithIndex { LayoutSpec spec, int index ->
            String condition = qualifierCondition(spec.qualifier)
            body << "    ${index == 0 ? 'if' : 'else if'} (${condition}) {\n"
            body << "      return new ${variantFactoryName(spec)}().createView(context, parent, attachToParent);\n"
            body << "    }\n"
        }
        LayoutSpec fallback = supported.find { it.qualifier == '' } ?: supported.last()
        body << "    return new ${variantFactoryName(fallback)}().createView(context, parent, attachToParent);\n"
        body << "  }\n"
        body << "}\n"
        writeJavaFile(dispatcherName(layoutName), body.toString())
    }

    private void writeVariantFactory(LayoutSpec spec) {
        String className = variantFactoryName(spec)
        StringBuilder body = new StringBuilder()
        body << "package ${generatedPackage};\n\n"
        body << "import android.content.Context;\n"
        body << "import android.content.res.XmlResourceParser;\n"
        body << "import android.util.AttributeSet;\n"
        body << "import android.view.InflateException;\n"
        body << "import android.view.View;\n"
        body << "import android.view.ViewGroup;\n"
        body << "import io.github.linyilei.x2c.runtime.IViewFactory;\n"
        body << "import io.github.linyilei.x2c.runtime.InflateUtils;\n"
        body << "import ${rPackage}.R;\n\n"
        body << "public final class ${className} implements IViewFactory {\n"
        body << "  @Override public View createView(Context context, ViewGroup parent, boolean attachToParent) {\n"
        body << "    XmlResourceParser parser = context.getResources().getXml(R.layout.${spec.name});\n"
        body << "    try {\n"
        if (spec.root.tag == 'merge') {
            body << "      if (parent == null || !attachToParent) {\n"
            body << "        throw new InflateException(\"<merge> root requires a parent and attachToParent=true: ${spec.name}\");\n"
            body << "      }\n"
            body << "      InflateUtils.nextStartTag(parser);\n"
            CodeGenContext context = new CodeGenContext()
            spec.root.children.each { LayoutNode child ->
                emitNode(body, child, 'parent', context, false)
            }
            body << "      return parent;\n"
        } else {
            CodeGenContext context = new CodeGenContext()
            String rootVar = emitNode(body, spec.root, 'parent', context, true)
            body << "      return ${rootVar};\n"
        }
        body << "    } catch (InflateException e) {\n"
        body << "      throw e;\n"
        body << "    } catch (Exception e) {\n"
        body << "      throw new InflateException(\"X2C failed to inflate ${spec.file.name}\", e);\n"
        body << "    } finally {\n"
        body << "      parser.close();\n"
        body << "    }\n"
        body << "  }\n"
        body << "}\n"
        writeJavaFile(className, body.toString())
    }

    private String emitNode(StringBuilder body, LayoutNode node, String parentVar, CodeGenContext context, boolean root) {
        int index = context.nextIndex()
        String attrs = "attrs${index}"
        body << "      AttributeSet ${attrs} = InflateUtils.nextStartTag(parser);\n"

        if (node.tag == 'include') {
            if (node.includeName == null || node.includeName.isEmpty()) {
                throw new IllegalStateException("X2C include tag is missing layout attribute.")
            }
            boolean mergeRoot = isMergeRoot(node.includeName)
            if (mergeRoot) {
                body << "      InflateUtils.includeMerge(context, ${parentVar}, R.layout.${node.includeName});\n"
            } else {
                body << "      InflateUtils.include(context, ${parentVar}, R.layout.${node.includeName}, ${attrs});\n"
            }
            return null
        }

        String type = resolveViewType(node)
        String view = "view${index}"
        body << "      ${type} ${view} = new ${type}(context, ${attrs});\n"
        if (root) {
            body << "      InflateUtils.attachRoot(parent, ${view}, ${attrs}, attachToParent);\n"
        } else {
            body << "      InflateUtils.addView(${parentVar}, ${view}, ${attrs});\n"
        }

        String childParent = view
        if (!node.children.isEmpty()) {
            childParent = "group${index}"
            body << "      ViewGroup ${childParent} = (ViewGroup) ${view};\n"
            node.children.each { LayoutNode child ->
                emitNode(body, child, childParent, context, false)
            }
        }
        body << "      InflateUtils.finishInflate(${view});\n"
        return view
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
                conditions.add('context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE')
            } else if (token.startsWith('v') && token.substring(1).isInteger()) {
                conditions.add("Build.VERSION.SDK_INT >= ${token.substring(1)}")
            }
        }
        conditions.isEmpty() ? null : conditions.join(' && ')
    }

    private void writeJavaFile(String className, String source) {
        File pkgDir = new File(outputDir, generatedPackage.replace('.', '/'))
        pkgDir.mkdirs()
        new File(pkgDir, className + '.java').text = source
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

class AggregateGroupSpec {
    final String groupClassName
    final String rPackage
    final List<String> layoutNames

    AggregateGroupSpec(String groupClassName, String rPackage, Set<String> layoutNames) {
        this.groupClassName = groupClassName
        this.rPackage = rPackage
        this.layoutNames = layoutNames.sort()
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
