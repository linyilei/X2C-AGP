package io.github.linyilei.x2c.compiler;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

final class JavaWriter {

    private static final ClassName CONTEXT = ClassName.get("android.content", "Context");
    private static final ClassName XML_RESOURCE_PARSER = ClassName.get("android.content.res", "XmlResourceParser");
    private static final ClassName ATTRIBUTE_SET = ClassName.get("android.util", "AttributeSet");
    private static final ClassName SPARSE_ARRAY = ClassName.get("android.util", "SparseArray");
    private static final ClassName SPARSE_INT_ARRAY = ClassName.get("android.util", "SparseIntArray");
    private static final ClassName INFLATE_EXCEPTION = ClassName.get("android.view", "InflateException");
    private static final ClassName VIEW = ClassName.get("android.view", "View");
    private static final ClassName VIEW_GROUP = ClassName.get("android.view", "ViewGroup");
    private static final ClassName I_VIEW_FACTORY = ClassName.get("io.github.linyilei.x2c.runtime", "IViewFactory");
    private static final ClassName X2C_GROUP = ClassName.get("io.github.linyilei.x2c.runtime", "X2CGroup");
    private static final ClassName X2C_ROOT_INDEX = ClassName.get("io.github.linyilei.x2c.runtime", "X2CRootIndex");
    private static final ClassName INFLATE_UTILS = ClassName.get("io.github.linyilei.x2c.runtime", "InflateUtils");
    private static final ClassName STRING = ClassName.get("java.lang", "String");

    private final Filer filer;
    private final Messager messager;
    private final X2COptions options;
    private final Map<String, Map<String, LayoutSpec>> layouts;
    private final Set<String> targets;

    JavaWriter(Filer filer, Messager messager, X2COptions options,
               Map<String, Map<String, LayoutSpec>> layouts, Set<String> targets) {
        this.filer = filer;
        this.messager = messager;
        this.options = options;
        this.layouts = layouts;
        this.targets = targets;
    }

    void writeAll() {
        for (String layoutName : targets) {
            if (!canGenerateLayout(layoutName)) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "X2C skipped optimized layout " + layoutName + " because " + skipReason(layoutName) + ".");
            } else {
                writeDispatcher(layoutName);
                for (LayoutSpec spec : layouts.get(layoutName).values()) {
                    writeVariantFactory(spec);
                }
            }
        }
        writeGroups();
        if (options.applicationModule) {
            writeRootIndex();
        } else {
            writeModuleIndex();
            writeModuleIndexMarker();
        }
    }

    private void writeGroups() {
        List<List<String>> shards = groupShards();
        for (int i = 0; i < shards.size(); i++) {
            writeGroup(i, shards.get(i));
        }
    }

    private void writeGroup(int index, List<String> shard) {
        ParameterizedTypeName factoryArray = ParameterizedTypeName.get(SPARSE_ARRAY, I_VIEW_FACTORY);
        MethodSpec.Builder loadInto = MethodSpec.methodBuilder("loadInto")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(factoryArray, "factories");
        for (String layoutName : shard) {
            loadInto.addStatement("factories.put($T.layout.$L, new $T())",
                    rClass(), layoutName, ClassName.get(options.generatedPackage, dispatcherName(layoutName)));
        }
        TypeSpec typeSpec = TypeSpec.classBuilder(groupName(index))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(X2C_GROUP)
                .addMethod(loadInto.build())
                .build();
        writeJavaFile(typeSpec);
    }

    private void writeModuleIndex() {
        MethodSpec.Builder loadInto = rootLoadIntoBuilder();
        List<List<String>> shards = groupShards();
        for (int index = 0; index < shards.size(); index++) {
            List<String> shard = shards.get(index);
            String groupClassName = options.generatedPackage + "." + groupName(index);
            String groupIdVar = "groupId" + index;
            loadInto.addStatement("int $N = ensureGroup(groups, $S, new $T())",
                    groupIdVar, groupClassName, groupType(index));
            for (String layoutName : shard) {
                loadInto.addStatement("layoutToGroup.put($T.layout.$L, $N)", rClass(), layoutName, groupIdVar);
            }
        }
        TypeSpec typeSpec = TypeSpec.classBuilder("X2CModuleIndex")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(rawSparseArraySuppressWarnings())
                .addSuperinterface(X2C_ROOT_INDEX)
                .addMethod(loadInto.build())
                .addMethod(ensureGroupMethod())
                .addMethod(nextGroupIdMethod())
                .build();
        writeJavaFile(typeSpec);
    }

    private void writeModuleIndexMarker() {
        boolean hasGeneratedLayout = false;
        for (String layoutName : targets) {
            if (canGenerateLayout(layoutName)) {
                hasGeneratedLayout = true;
                break;
            }
        }
        if (!hasGeneratedLayout) {
            return;
        }
        try {
            FileObject marker = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/x2c/" + options.generatedPackage + ".module-index");
            try (Writer writer = marker.openWriter()) {
                writer.write(options.generatedPackage + ".X2CModuleIndex\n");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write X2C module-index marker.", e);
        }
    }

    private void writeRootIndex() {
        MethodSpec.Builder loadInto = rootLoadIntoBuilder();
        List<List<String>> shards = groupShards();
        for (int index = 0; index < shards.size(); index++) {
            List<String> shard = shards.get(index);
            loadInto.addStatement("groups.put($L, new $T())", index, groupType(index));
            for (String layoutName : shard) {
                loadInto.addStatement("layoutToGroup.put($T.layout.$L, $L)", rClass(), layoutName, index);
            }
        }
        loadInto.addStatement("loadX2CModuleIndexes(layoutToGroup, groups)");
        TypeSpec typeSpec = TypeSpec.classBuilder("X2CRootIndex")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(rawSparseArraySuppressWarnings())
                .addSuperinterface(X2C_ROOT_INDEX)
                .addMethod(loadInto.build())
                .addMethod(loadX2CModuleIndexesMethod())
                .addMethod(ensureGroupMethod())
                .addMethod(nextGroupIdMethod())
                .build();
        writeJavaFile(typeSpec);
    }

    private void writeDispatcher(String layoutName) {
        List<LayoutSpec> supported = new ArrayList<>();
        for (LayoutSpec spec : layouts.get(layoutName).values()) {
            if (canGenerateVariant(spec)) {
                supported.add(spec);
            }
        }
        if (supported.isEmpty()) {
            return;
        }
        supported.sort((left, right) -> Integer.compare(variantScore(right.qualifier), variantScore(left.qualifier)));

        MethodSpec.Builder createView = createViewBuilder();
        List<LayoutSpec> conditionalVariants = new ArrayList<>();
        for (LayoutSpec spec : supported) {
            if (qualifierCondition(spec.qualifier) != null) {
                conditionalVariants.add(spec);
            }
        }
        for (int i = 0; i < conditionalVariants.size(); i++) {
            LayoutSpec spec = conditionalVariants.get(i);
            String condition = qualifierCondition(spec.qualifier);
            if (i == 0) {
                createView.beginControlFlow("if (" + condition + ")");
            } else {
                createView.nextControlFlow("else if (" + condition + ")");
            }
            createView.addStatement("return new $T().createView(context, parent, attachToParent)",
                    ClassName.get(options.generatedPackage, variantFactoryName(spec)));
        }
        LayoutSpec fallback = findDefaultVariant(supported);
        if (!conditionalVariants.isEmpty()) {
            createView.nextControlFlow("else");
            createView.addStatement("return new $T().createView(context, parent, attachToParent)",
                    ClassName.get(options.generatedPackage, variantFactoryName(fallback)));
            createView.endControlFlow();
        } else {
            createView.addStatement("return new $T().createView(context, parent, attachToParent)",
                    ClassName.get(options.generatedPackage, variantFactoryName(fallback)));
        }
        TypeSpec typeSpec = TypeSpec.classBuilder(dispatcherName(layoutName))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(I_VIEW_FACTORY)
                .addMethod(createView.build())
                .build();
        writeJavaFile(typeSpec);
    }

    private void writeVariantFactory(LayoutSpec spec) {
        if (!canGenerateVariant(spec)) {
            return;
        }
        String className = variantFactoryName(spec);
        CodeBlock.Builder body = CodeBlock.builder();
        body.addStatement("$T parser = context.getResources().getXml($T.layout.$L)",
                XML_RESOURCE_PARSER, rClass(), spec.name);
        body.beginControlFlow("try");
        if ("merge".equals(spec.root.tag)) {
            body.beginControlFlow("if (parent == null || !attachToParent)");
            body.addStatement("throw new $T($S)", INFLATE_EXCEPTION,
                    "<merge> root requires a parent and attachToParent=true: " + spec.name);
            body.endControlFlow();
            body.addStatement("$T.nextStartTag(parser)", INFLATE_UTILS);
            CodeGenContext context = new CodeGenContext();
            for (LayoutNode child : spec.root.children) {
                emitNode(body, child, "parent", context, false);
            }
            body.addStatement("return parent");
        } else {
            CodeGenContext context = new CodeGenContext();
            String rootVar = emitNode(body, spec.root, "parent", context, true);
            body.addStatement("return $N", rootVar);
        }
        body.nextControlFlow("catch ($T e)", INFLATE_EXCEPTION);
        body.addStatement("throw e");
        body.nextControlFlow("catch ($T e)", Exception.class);
        body.addStatement("throw new $T($S, e)", INFLATE_EXCEPTION, "X2C failed to inflate " + spec.file.getName());
        body.nextControlFlow("finally");
        body.addStatement("parser.close()");
        body.endControlFlow();

        TypeSpec typeSpec = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(I_VIEW_FACTORY)
                .addMethod(createViewBuilder().addCode(body.build()).build())
                .build();
        writeJavaFile(typeSpec);
    }

    private String emitNode(CodeBlock.Builder body, LayoutNode node, String parentVar,
                            CodeGenContext context, boolean root) {
        int index = context.nextIndex();
        String attrs = "attrs" + index;
        body.addStatement("$T $N = $T.nextStartTag(parser)", ATTRIBUTE_SET, attrs, INFLATE_UTILS);

        if ("include".equals(node.tag)) {
            if (node.includeName == null || node.includeName.isEmpty()) {
                throw new IllegalStateException("X2C include tag is missing layout attribute.");
            }
            if (hasMixedRootKinds(node.includeName)) {
                body.addStatement("$T.includeDynamic(context, $N, $T.layout.$L, $N)",
                        INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs);
            } else if (isMergeRoot(node.includeName)) {
                body.addStatement("$T.includeMerge(context, $N, $T.layout.$L, $N)",
                        INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs);
            } else {
                body.addStatement("$T.include(context, $N, $T.layout.$L, $N)",
                        INFLATE_UTILS, parentVar, rClass(), node.includeName, attrs);
            }
            return null;
        }

        String view = "view" + index;
        if (shouldUseRuntimeViewCreation(node)) {
            body.addStatement("$T $N = $T.createView(context, $N, $N, $S)",
                    VIEW, view, INFLATE_UTILS, parentVar, attrs, node.tag);
        } else {
            ClassName type = ClassName.bestGuess(resolveViewType(node));
            body.addStatement("$T $N = new $T(context, $N)", type, view, type, attrs);
        }
        if (root) {
            body.addStatement("$T.attachRoot(parent, $N, $N, attachToParent)", INFLATE_UTILS, view, attrs);
        } else {
            body.addStatement("$T.addView($N, $N, $N)", INFLATE_UTILS, parentVar, view, attrs);
        }

        String childParent = view;
        if (!node.children.isEmpty()) {
            childParent = "group" + index;
            body.addStatement("$T $N = ($T) $N", VIEW_GROUP, childParent, VIEW_GROUP, view);
            for (LayoutNode child : node.children) {
                emitNode(body, child, childParent, context, false);
            }
        }
        body.addStatement("$T.finishInflate($N)", INFLATE_UTILS, view);
        return view;
    }

    private MethodSpec.Builder createViewBuilder() {
        return MethodSpec.methodBuilder("createView")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(VIEW)
                .addParameter(CONTEXT, "context")
                .addParameter(VIEW_GROUP, "parent")
                .addParameter(TypeName.BOOLEAN, "attachToParent");
    }

    private MethodSpec.Builder rootLoadIntoBuilder() {
        return MethodSpec.methodBuilder("loadInto")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(SPARSE_INT_ARRAY, "layoutToGroup")
                .addParameter(SPARSE_ARRAY, "groups");
    }

    private MethodSpec loadX2CModuleIndexesMethod() {
        return MethodSpec.methodBuilder("loadX2CModuleIndexes")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(SPARSE_INT_ARRAY, "layoutToGroup")
                .addParameter(SPARSE_ARRAY, "groups")
                .build();
    }

    private MethodSpec ensureGroupMethod() {
        CodeBlock.Builder body = CodeBlock.builder();
        body.beginControlFlow("for (int i = 0; i < groups.size(); i++)");
        body.addStatement("int key = groups.keyAt(i)");
        body.addStatement("Object existing = groups.valueAt(i)");
        body.beginControlFlow("if (existing != null && groupClassName.equals(existing.getClass().getName()))");
        body.addStatement("return key");
        body.endControlFlow();
        body.endControlFlow();
        body.addStatement("int groupId = nextGroupId(groups)");
        body.addStatement("groups.put(groupId, group)");
        body.addStatement("return groupId");
        return MethodSpec.methodBuilder("ensureGroup")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(SPARSE_ARRAY, "groups")
                .addParameter(STRING, "groupClassName")
                .addParameter(Object.class, "group")
                .addCode(body.build())
                .build();
    }

    private MethodSpec nextGroupIdMethod() {
        CodeBlock.Builder body = CodeBlock.builder();
        body.addStatement("int next = 0");
        body.beginControlFlow("for (int i = 0; i < groups.size(); i++)");
        body.addStatement("next = Math.max(next, groups.keyAt(i) + 1)");
        body.endControlFlow();
        body.addStatement("return next");
        return MethodSpec.methodBuilder("nextGroupId")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(SPARSE_ARRAY, "groups")
                .addCode(body.build())
                .build();
    }

    private static AnnotationSpec rawSparseArraySuppressWarnings() {
        return AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "{$S, $S}", "rawtypes", "unchecked")
                .build();
    }

    private List<List<String>> groupShards() {
        List<String> generatedLayouts = new ArrayList<>();
        for (String layoutName : targets) {
            if (canGenerateLayout(layoutName)) {
                generatedLayouts.add(layoutName);
            }
        }
        List<List<String>> shards = new ArrayList<>();
        for (int start = 0; start < generatedLayouts.size(); start += options.groupSize) {
            int end = Math.min(start + options.groupSize, generatedLayouts.size());
            shards.add(new ArrayList<>(generatedLayouts.subList(start, end)));
        }
        return shards;
    }

    private ClassName groupType(int index) {
        return ClassName.get(options.generatedPackage, groupName(index));
    }

    private static String groupName(int index) {
        return index == 0 ? "X2CGroup" : "X2CGroup_" + index;
    }

    private boolean isMergeRoot(String layoutName) {
        Map<String, LayoutSpec> variants = layouts.get(layoutName);
        if (variants == null || variants.isEmpty()) {
            return false;
        }
        for (LayoutSpec spec : variants.values()) {
            if (!"merge".equals(spec.root.tag)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasMixedRootKinds(String layoutName) {
        Map<String, LayoutSpec> variants = layouts.get(layoutName);
        if (variants == null || variants.isEmpty()) {
            return false;
        }
        boolean hasMerge = false;
        boolean hasNonMerge = false;
        for (LayoutSpec spec : variants.values()) {
            if ("merge".equals(spec.root.tag)) {
                hasMerge = true;
            } else {
                hasNonMerge = true;
            }
        }
        return hasMerge && hasNonMerge;
    }

    private boolean canGenerateLayout(String layoutName) {
        Map<String, LayoutSpec> variants = layouts.get(layoutName);
        if (variants == null || variants.isEmpty()) {
            return false;
        }
        for (LayoutSpec spec : variants.values()) {
            if (!canGenerateVariant(spec)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canGenerateVariant(LayoutSpec spec) {
        return spec != null && !spec.unsupported && isSupportedQualifier(spec.qualifier);
    }

    private String skipReason(String layoutName) {
        Map<String, LayoutSpec> variants = layouts.get(layoutName);
        if (variants == null || variants.isEmpty()) {
            return "it has no parsed variants";
        }
        boolean unsupported = false;
        List<String> unsupportedQualifiers = new ArrayList<>();
        for (LayoutSpec spec : variants.values()) {
            unsupported |= spec.unsupported;
            if (!isSupportedQualifier(spec.qualifier)) {
                unsupportedQualifiers.add(spec.qualifier == null || spec.qualifier.isEmpty()
                        ? "(default)" : spec.qualifier);
            }
        }
        List<String> reasons = new ArrayList<>();
        if (unsupported) {
            reasons.add("one or more variants contain unsupported tags");
        }
        if (!unsupportedQualifiers.isEmpty()) {
            reasons.add("it uses unsupported qualifiers " + unsupportedQualifiers);
        }
        return reasons.isEmpty() ? "it is not safe to optimize" : String.join(" and ", reasons);
    }

    private static boolean shouldUseRuntimeViewCreation(LayoutNode node) {
        return !"view".equals(node.tag) && !node.tag.contains(".");
    }

    private static String resolveViewType(LayoutNode node) {
        if ("view".equals(node.tag)) {
            return node.className;
        }
        if (node.tag.contains(".")) {
            return node.tag;
        }
        switch (node.tag) {
            case "View":
                return "android.view.View";
            case "ViewStub":
                return "android.view.ViewStub";
            case "WebView":
                return "android.webkit.WebView";
            case "TextureView":
                return "android.view.TextureView";
            case "SurfaceView":
                return "android.view.SurfaceView";
            default:
                return "android.widget." + node.tag;
        }
    }

    private static int variantScore(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) {
            return 0;
        }
        int score = 0;
        String[] tokens = qualifier.split("-");
        for (String token : tokens) {
            if ("land".equals(token)) {
                score += 1000;
            } else if (token.startsWith("v") && isInteger(token.substring(1))) {
                score += Integer.parseInt(token.substring(1));
            } else {
                score += 1;
            }
        }
        return score;
    }

    private static String qualifierCondition(String qualifier) {
        if (qualifier == null || qualifier.isEmpty() || !isSupportedQualifier(qualifier)) {
            return null;
        }
        List<String> conditions = new ArrayList<>();
        String[] tokens = qualifier.split("-");
        for (String token : tokens) {
            if ("land".equals(token)) {
                conditions.add("context.getResources().getConfiguration().orientation == "
                        + "android.content.res.Configuration.ORIENTATION_LANDSCAPE");
            } else if (token.startsWith("v") && isInteger(token.substring(1))) {
                conditions.add("android.os.Build.VERSION.SDK_INT >= " + token.substring(1));
            }
        }
        return conditions.isEmpty() ? null : String.join(" && ", conditions);
    }

    private static boolean isSupportedQualifier(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) {
            return true;
        }
        String[] tokens = qualifier.split("-");
        for (String token : tokens) {
            if (!"land".equals(token) && !(token.startsWith("v") && isInteger(token.substring(1)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private LayoutSpec findDefaultVariant(List<LayoutSpec> supported) {
        for (LayoutSpec spec : supported) {
            if (spec.qualifier == null || spec.qualifier.isEmpty()) {
                return spec;
            }
        }
        return supported.get(supported.size() - 1);
    }

    private ClassName rClass() {
        return ClassName.get(options.rPackage, "R");
    }

    private void writeJavaFile(TypeSpec typeSpec) {
        try {
            JavaFile.builder(options.generatedPackage, typeSpec)
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write generated X2C source " + typeSpec.name + ".", e);
        }
    }

    private static String dispatcherName(String layoutName) {
        return classPrefix(layoutName);
    }

    private static String variantFactoryName(LayoutSpec spec) {
        String suffix = spec.qualifier == null || spec.qualifier.isEmpty()
                ? "Base" : camel(spec.qualifier.replace("-", "_"));
        return classPrefix(spec.name) + "_" + suffix;
    }

    private static String classPrefix(String layoutName) {
        return "X2C_" + camel(layoutName);
    }

    private static String camel(String raw) {
        String[] parts = raw.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            builder.append(part.substring(0, 1).toUpperCase());
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
            builder.append("_");
        }
        if (builder.length() > 0) {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }
}
