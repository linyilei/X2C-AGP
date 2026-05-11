package io.github.linyilei.x2c.compiler;

import java.util.ArrayList;
import java.util.List;

final class LayoutNode {
    String tag;
    String className;
    String includeName;
    final List<LayoutNode> children = new ArrayList<>();
}
