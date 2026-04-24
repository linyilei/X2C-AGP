package io.github.linyilei.x2c.runtime;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface IViewFactory {
    @NonNull
    View createView(@NonNull Context context, @Nullable ViewGroup parent, boolean attachToParent);
}
