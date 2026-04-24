package io.github.linyilei.x2c.runtime;

import android.util.SparseArray;

import androidx.annotation.NonNull;

public interface X2CGroup {
    void loadInto(@NonNull SparseArray<IViewFactory> factories);
}
