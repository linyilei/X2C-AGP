package io.github.linyilei.x2c.compiler;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.processing.Messager;

final class LayoutCatalog {
    private final Map<String, Map<String, File>> layoutFiles;
    private final Map<String, Map<String, LayoutSpec>> parsedLayouts = new LinkedHashMap<>();
    private final Messager messager;

    LayoutCatalog(Map<String, Map<String, File>> layoutFiles, Messager messager) {
        this.layoutFiles = layoutFiles == null ? new LinkedHashMap<String, Map<String, File>>() : layoutFiles;
        this.messager = messager;
    }

    boolean contains(String layoutName) {
        return layoutFiles.containsKey(layoutName);
    }

    Map<String, LayoutSpec> load(String layoutName) {
        if (parsedLayouts.containsKey(layoutName)) {
            return parsedLayouts.get(layoutName);
        }
        Map<String, LayoutSpec> parsed = new LinkedHashMap<>();
        Map<String, File> variants = layoutFiles.get(layoutName);
        if (variants != null) {
            for (Map.Entry<String, File> entry : variants.entrySet()) {
                LayoutSpec spec = LayoutParser.parse(entry.getValue(), layoutName, entry.getKey(), messager);
                if (spec != null) {
                    parsed.put(layoutName(entry.getKey()), spec);
                }
            }
        }
        parsedLayouts.put(layoutName, parsed);
        return parsed;
    }

    Map<String, Map<String, LayoutSpec>> snapshot() {
        Map<String, Map<String, LayoutSpec>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, LayoutSpec>> entry : parsedLayouts.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private static String layoutName(String qualifier) {
        return qualifier == null ? "" : qualifier;
    }
}
