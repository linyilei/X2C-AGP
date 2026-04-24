package io.github.linyilei.x2c.runtime;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

public final class X2C {

    private static final String TAG = "X2C";
    private static final String GENERATED_ROOT_INDEX_SUFFIX = ".x2c.X2CRootIndex";

    private static volatile RootIndex sRootIndex;
    private static volatile boolean sRootIndexLookupAttempted;
    private static final Map<String, SparseArray<IViewFactory>> sLoadedGroups = new HashMap<>();
    private static final SparseArray<View> sPreloadedViews = new SparseArray<>();
    private static volatile boolean sDebugLogging;
    private static volatile Field sViewContextField;
    private static volatile boolean sViewContextFieldLookupAttempted;

    private X2C() {
    }

    public static void installRoot(@Nullable X2CRootIndex rootIndex) {
        synchronized (X2C.class) {
            sRootIndex = rootIndex == null ? null : new RootIndex();
            if (rootIndex != null) {
                rootIndex.loadInto(sRootIndex.layoutToGroup, sRootIndex.groupClassNames);
            }
            sRootIndexLookupAttempted = rootIndex != null;
            sLoadedGroups.clear();
            sPreloadedViews.clear();
        }
        log("Installed custom root index: " + (rootIndex == null ? "null" : rootIndex.getClass().getName()));
    }

    public static void setDebugLogging(boolean enabled) {
        sDebugLogging = enabled;
        log("Debug logging " + (enabled ? "enabled" : "disabled"));
    }

    public static void setContentView(@NonNull Activity activity, int layoutId) {
        View view = getView(activity, layoutId, null, false);
        if (view != null) {
            log("setContentView hit generated view: " + resourceName(activity, layoutId));
            activity.setContentView(view);
        } else {
            log("setContentView fallback XML: " + resourceName(activity, layoutId));
            activity.setContentView(layoutId);
        }
    }

    @NonNull
    public static View inflate(@NonNull Context context, int layoutId, @Nullable ViewGroup parent, boolean attachToParent) {
        View view = getView(context, layoutId, parent, attachToParent);
        if (view != null) {
            log("inflate hit generated view: " + resourceName(context, layoutId));
            return view;
        }
        log("inflate fallback XML: " + resourceName(context, layoutId));
        return LayoutInflater.from(context).inflate(layoutId, parent, attachToParent);
    }

    @NonNull
    public static View inflate(@NonNull LayoutInflater inflater, int layoutId, @Nullable ViewGroup parent, boolean attachToParent) {
        return inflate(inflater.getContext(), layoutId, parent, attachToParent);
    }

    @NonNull
    public static Context withTheme(@NonNull Context context, @StyleRes int themeResId) {
        return new ContextThemeWrapper(context.getApplicationContext(), themeResId);
    }

    public static boolean preload(@NonNull Context context, int layoutId) {
        return preloadView(context, layoutId) != null;
    }

    @Nullable
    private static View preloadView(@NonNull Context context, int layoutId) {
        try {
            View view = createGeneratedView(context, layoutId, null, false);
            if (view == null) {
                log("preload fallback XML: " + resourceName(context, layoutId));
                return null;
            }
            synchronized (X2C.class) {
                sPreloadedViews.put(layoutId, view);
            }
            log("preload cached generated view: " + resourceName(context, layoutId));
            return view;
        } catch (RuntimeException e) {
            log("preload failed: " + resourceName(context, layoutId) + ", " + e.getClass().getName());
            return null;
        }
    }

    public static void clearPreload(int layoutId) {
        synchronized (X2C.class) {
            sPreloadedViews.remove(layoutId);
        }
    }

    public static void clearPreloads() {
        synchronized (X2C.class) {
            sPreloadedViews.clear();
        }
    }

    @Nullable
    static View getView(@NonNull Context context, int layoutId, @Nullable ViewGroup parent, boolean attachToParent) {
        View preloaded = consumePreloadedView(context, layoutId, parent, attachToParent);
        if (preloaded != null) {
            return preloaded;
        }
        return createGeneratedView(context, layoutId, parent, attachToParent);
    }

    @Nullable
    private static View createGeneratedView(@NonNull Context context, int layoutId, @Nullable ViewGroup parent,
                                            boolean attachToParent) {
        IViewFactory factory = resolveFactory(context, layoutId);
        if (factory == null) {
            return null;
        }
        try {
            log("Creating generated view: " + resourceName(context, layoutId)
                    + " via " + factory.getClass().getName());
            return factory.createView(context, parent, attachToParent);
        } catch (InflateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InflateException("Failed to inflate layout " + layoutId + " through generated factory.", e);
        }
    }

    @Nullable
    private static View consumePreloadedView(@NonNull Context context, int layoutId, @Nullable ViewGroup parent,
                                             boolean attachToParent) {
        if (parent != null || attachToParent) {
            return null;
        }
        View view;
        synchronized (X2C.class) {
            view = sPreloadedViews.get(layoutId);
            if (view != null) {
                sPreloadedViews.remove(layoutId);
            }
        }
        if (view == null) {
            return null;
        }
        if (view.getParent() != null) {
            log("preload discarded attached view: " + resourceName(context, layoutId));
            return null;
        }
        if (!replaceViewTreeContext(view, context)) {
            log("preload discarded because View.mContext replacement failed: " + resourceName(context, layoutId));
            return null;
        }
        log("preload consumed generated view: " + resourceName(context, layoutId));
        return view;
    }

    @Nullable
    private static IViewFactory resolveFactory(@NonNull Context context, int layoutId) {
        IViewFactory factory = resolveRootFactory(context, layoutId);
        if (factory != null) {
            log("Resolved generated factory for " + resourceName(context, layoutId)
                    + ": " + factory.getClass().getName());
            return factory;
        }
        log("No generated factory for " + resourceName(context, layoutId));
        return null;
    }

    @Nullable
    private static IViewFactory resolveRootFactory(@NonNull Context context, int layoutId) {
        RootIndex rootIndex = resolveRootIndex(context);
        if (rootIndex == null) {
            return null;
        }
        int groupId = rootIndex.layoutToGroup.get(layoutId, -1);
        String groupClassName = groupId == -1 ? null : rootIndex.groupClassNames.get(groupId);
        if (groupClassName == null) {
            log("No generated group for " + resourceName(context, layoutId));
            return null;
        }
        SparseArray<IViewFactory> factories = resolveGroupFactories(context, groupClassName);
        if (factories == null) {
            return null;
        }
        return factories.get(layoutId);
    }

    @Nullable
    private static RootIndex resolveRootIndex(@NonNull Context context) {
        if (sRootIndex != null || sRootIndexLookupAttempted) {
            return sRootIndex;
        }
        synchronized (X2C.class) {
            if (!sRootIndexLookupAttempted) {
                sRootIndex = tryLoadGeneratedRootIndex(context);
                sRootIndexLookupAttempted = true;
            }
            return sRootIndex;
        }
    }

    @Nullable
    private static SparseArray<IViewFactory> resolveGroupFactories(@NonNull Context context, @NonNull String groupClassName) {
        synchronized (X2C.class) {
            SparseArray<IViewFactory> factories = sLoadedGroups.get(groupClassName);
            if (factories != null) {
                return factories;
            }
            factories = tryLoadGeneratedGroup(context, groupClassName);
            if (factories != null) {
                sLoadedGroups.put(groupClassName, factories);
            }
            return factories;
        }
    }

    @Nullable
    private static RootIndex tryLoadGeneratedRootIndex(@NonNull Context context) {
        String className = context.getPackageName() + GENERATED_ROOT_INDEX_SUFFIX;
        try {
            Class<?> clazz = context.getClassLoader().loadClass(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof X2CRootIndex) {
                RootIndex rootIndex = new RootIndex();
                ((X2CRootIndex) instance).loadInto(rootIndex.layoutToGroup, rootIndex.groupClassNames);
                log("Loaded generated root index: " + className
                        + ", entries=" + rootIndex.layoutToGroup.size()
                        + ", groups=" + rootIndex.groupClassNames.size());
                return rootIndex;
            }
        } catch (Exception ignore) {
            log("Generated root index not found: " + className);
            return null;
        }
        return null;
    }

    private static final class RootIndex {
        final SparseIntArray layoutToGroup = new SparseIntArray();
        final SparseArray<String> groupClassNames = new SparseArray<>();
    }

    @Nullable
    private static SparseArray<IViewFactory> tryLoadGeneratedGroup(@NonNull Context context, @NonNull String className) {
        try {
            Class<?> clazz = context.getClassLoader().loadClass(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof X2CGroup) {
                SparseArray<IViewFactory> factories = new SparseArray<>();
                ((X2CGroup) instance).loadInto(factories);
                log("Loaded generated group: " + className + ", factories=" + factories.size());
                return factories;
            }
        } catch (Exception ignore) {
            log("Generated group not found: " + className);
            return null;
        }
        return null;
    }

    private static void log(@NonNull String message) {
        if (sDebugLogging) {
            Log.d(TAG, message);
        }
    }

    private static boolean replaceViewTreeContext(@NonNull View view, @NonNull Context context) {
        Field field = resolveViewContextField();
        if (field == null) {
            return false;
        }
        try {
            replaceViewTreeContext(view, context, field);
            return true;
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    private static void replaceViewTreeContext(@NonNull View view, @NonNull Context context, @NonNull Field field)
            throws IllegalAccessException {
        field.set(view, context);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            replaceViewTreeContext(group.getChildAt(i), context, field);
        }
    }

    @Nullable
    private static Field resolveViewContextField() {
        if (sViewContextField != null || sViewContextFieldLookupAttempted) {
            return sViewContextField;
        }
        synchronized (X2C.class) {
            if (!sViewContextFieldLookupAttempted) {
                try {
                    Field field = View.class.getDeclaredField("mContext");
                    field.setAccessible(true);
                    sViewContextField = field;
                } catch (Exception ignored) {
                    sViewContextField = null;
                }
                sViewContextFieldLookupAttempted = true;
            }
            return sViewContextField;
        }
    }

    @NonNull
    private static String resourceName(@NonNull Context context, int layoutId) {
        try {
            return context.getResources().getResourceName(layoutId);
        } catch (Exception ignored) {
            return String.valueOf(layoutId);
        }
    }
}
