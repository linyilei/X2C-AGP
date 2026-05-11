package io.github.linyilei.x2c.compiler;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

final class LayoutSpec {
    final String name;
    final String qualifier;
    final File file;
    final LayoutNode root;
    boolean unsupported;
    final Set<String> includes = new LinkedHashSet<>();

    LayoutSpec(String name, String qualifier, File file, LayoutNode root) {
        this.name = name;
        this.qualifier = qualifier;
        this.file = file;
        this.root = root;
    }
}
