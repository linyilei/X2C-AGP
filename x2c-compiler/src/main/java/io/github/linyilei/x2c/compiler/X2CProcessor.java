package io.github.linyilei.x2c.compiler;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes("*")
public final class X2CProcessor extends AbstractProcessor {

    private static final String XML_ANNOTATION = "io.github.linyilei.x2c.runtime.Xml";

    private boolean generated;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedOptions() {
        return new LinkedHashSet<>(Arrays.asList(
                X2COptions.OPTION_GENERATED_PACKAGE,
                X2COptions.OPTION_R_PACKAGE,
                X2COptions.OPTION_APPLICATION_MODULE,
                X2COptions.OPTION_RES_DIRS,
                X2COptions.OPTION_GROUP_SIZE,
                X2COptions.OPTION_EXCLUDES
        ));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (generated || roundEnv.processingOver()) {
            return false;
        }
        generated = true;
        try {
            X2COptions options = X2COptions.from(processingEnv);
            Set<String> annotatedTargets = collectAnnotatedTargets(roundEnv);
            LayoutCatalog layoutCatalog = new LayoutCatalog(indexLayoutFiles(options.resDirs), processingEnv.getMessager());
            LayoutSelection selection = resolveLayoutSelection(layoutCatalog, annotatedTargets, options.excludes);
            if (!options.applicationModule && selection.targets.isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "X2C found no annotated layouts selected.");
                return false;
            }
            new JavaWriter(processingEnv.getFiler(), processingEnv.getMessager(), options,
                    selection.layouts, selection.targets).writeAll();
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "X2C annotation processing failed: " + e.getMessage());
        }
        return false;
    }

    private static Set<String> collectAnnotatedTargets(RoundEnvironment roundEnv) {
        Set<String> targets = new LinkedHashSet<>();
        for (Element root : roundEnv.getRootElements()) {
            collectAnnotatedTargets(root, targets);
        }
        return targets;
    }

    private static void collectAnnotatedTargets(Element element, Set<String> targets) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (!XML_ANNOTATION.equals(mirror.getAnnotationType().toString())) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : mirror.getElementValues().entrySet()) {
                if (!"layouts".contentEquals(entry.getKey().getSimpleName())) {
                    continue;
                }
                Object value = entry.getValue().getValue();
                if (value instanceof List) {
                    for (Object item : (List<?>) value) {
                        if (item instanceof AnnotationValue) {
                            targets.add(String.valueOf(((AnnotationValue) item).getValue()));
                        }
                    }
                } else if (value != null) {
                    targets.add(String.valueOf(value));
                }
            }
        }
        for (Element child : element.getEnclosedElements()) {
            collectAnnotatedTargets(child, targets);
        }
    }

    private LayoutSelection resolveLayoutSelection(LayoutCatalog layoutCatalog, Set<String> annotatedTargets,
                                                   Set<String> excludedLayouts) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> missingLayouts = new LinkedHashSet<>();
        for (String rawName : annotatedTargets) {
            String name = X2COptions.normalizeLayoutName(rawName);
            if (name == null) {
                continue;
            }
            if (excludedLayouts.contains(name)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "X2C skipped annotated layout " + name + " because it is blacklisted.");
                continue;
            }
            if (!layoutCatalog.contains(name)) {
                missingLayouts.add(name);
                continue;
            }
            targets.add(name);
        }
        if (!missingLayouts.isEmpty()) {
            throw new IllegalStateException("X2C could not find annotated layouts: "
                    + String.join(", ", missingLayouts)
                    + ". Check @Xml(layouts = ...) entries.");
        }
        expandIncludedTargets(layoutCatalog, targets, excludedLayouts);
        return new LayoutSelection(layoutCatalog.snapshot(), targets);
    }

    private static void expandIncludedTargets(LayoutCatalog layoutCatalog, Set<String> targets,
                                              Set<String> excludedLayouts) {
        java.util.List<String> queue = new java.util.ArrayList<>(targets);
        for (int i = 0; i < queue.size(); i++) {
            String name = queue.get(i);
            for (LayoutSpec spec : layoutCatalog.load(name).values()) {
                for (String includeName : spec.includes) {
                    if (!layoutCatalog.contains(includeName) || excludedLayouts.contains(includeName)) {
                        continue;
                    }
                    layoutCatalog.load(includeName);
                    if (targets.add(includeName)) {
                        queue.add(includeName);
                    }
                }
            }
        }
    }

    private static Map<String, Map<String, File>> indexLayoutFiles(java.util.List<File> sourceResDirs) {
        Map<String, Map<String, File>> result = new LinkedHashMap<>();
        for (File resDir : sourceResDirs) {
            File[] dirs = resDir.listFiles();
            if (dirs == null) {
                continue;
            }
            for (File dir : dirs) {
                if (!dir.isDirectory() || (!"layout".equals(dir.getName()) && !dir.getName().startsWith("layout-"))) {
                    continue;
                }
                String qualifier = "layout".equals(dir.getName()) ? "" : dir.getName().substring("layout-".length());
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (!file.isFile() || !file.getName().endsWith(".xml")) {
                        continue;
                    }
                    String name = file.getName().substring(0, file.getName().length() - ".xml".length());
                    Map<String, File> variants = result.get(name);
                    if (variants == null) {
                        variants = new LinkedHashMap<>();
                        result.put(name, variants);
                    }
                    variants.put(qualifier, file);
                }
            }
        }
        return result;
    }
}
