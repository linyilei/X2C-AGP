package io.github.linyilei.x2c.compiler;

import java.util.Map;
import java.util.Set;

final class LayoutSelection {
    final Map<String, Map<String, LayoutSpec>> layouts;
    final Set<String> targets;

    LayoutSelection(Map<String, Map<String, LayoutSpec>> layouts, Set<String> targets) {
        this.layouts = layouts;
        this.targets = targets;
    }
}
