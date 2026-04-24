package io.github.linyilei.x2c.runtime;

import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;

public interface X2CRootIndex {
    void loadInto(@NonNull SparseIntArray layoutToGroup, @NonNull SparseArray<String> groupClassNames);
}
