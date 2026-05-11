package io.github.linyilei.x2c.compiler;

final class CodeGenContext {
    private int index;

    int nextIndex() {
        return index++;
    }
}
