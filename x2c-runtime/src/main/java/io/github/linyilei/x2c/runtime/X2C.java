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

import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

public final class X2C {

    private static final String TAG = "X2C";
    private static final String GENERATED_ROOT_INDEX_SUFFIX = ".x2c.X2CRootIndex";
    private static final Object FACTORY_MISS = new Object();

    private static volatile RootIndex sRootIndex;
    private static volatile boolean sRootIndexLookupAttempted;
    private static final ConcurrentHashMap<String, SparseArray<IViewFactory>> sLoadedGroups = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Object> sFactoryCache = new ConcurrentHashMap<>();
    private static volatile boolean sDebugLogging;

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
            sFactoryCache.clear();
        }
        log("Installed custom root index: " + (rootIndex == null ? "null" : rootIndex.getClass().getName()));
    }

    public static void setDebugLogging(boolean enabled) {
        sDebugLogging = enabled;
        log("Debug logging " + (enabled ? "enabled" : "disabled"));
    }

    public static void setContentView(@NonNull Activity activity, int layoutId) {
        try {
            View view = getView(activity, layoutId, null, false);
            if (view != null) {
                log("setContentView hit generated view: " + resourceName(activity, layoutId));
                activity.setContentView(view);
                return;
            }
        } catch (RuntimeException e) {
            log("setContentView generated path failed, fallback XML: "
                    + resourceName(activity, layoutId) + ", " + e.getClass().getName());
        }
        log("setContentView fallback XML: " + resourceName(activity, layoutId));
        activity.setContentView(layoutId);
    }

    @NonNull
    public static View inflate(@NonNull Context context, int layoutId, @Nullable ViewGroup parent, boolean attachToParent) {
        try {
            View view = getView(context, layoutId, parent, attachToParent);
            if (view != null) {
                log("inflate hit generated view: " + resourceName(context, layoutId));
                return view;
            }
        } catch (RuntimeException e) {
            log("inflate generated path failed, fallback XML: "
                    + resourceName(context, layoutId) + ", " + e.getClass().getName());
        }
        log("inflate fallback XML: " + resourceName(context, layoutId));
        return LayoutInflater.from(context).inflate(layoutId, parent, attachToParent);
    }

    @NonNull
    public static View inflate(@NonNull LayoutInflater inflater, int layoutId, @Nullable ViewGroup parent, boolean attachToParent) {
        Context context = inflater.getContext();
        try {
            View view = getView(context, layoutId, parent, attachToParent);
            if (view != null) {
                log("inflate hit generated view: " + resourceName(context, layoutId));
                return view;
            }
        } catch (RuntimeException e) {
            log("inflate generated path failed, fallback XML: "
                    + resourceName(context, layoutId) + ", " + e.getClass().getName());
        }
        log("inflate fallback XML: " + resourceName(context, layoutId));
        return inflater.inflate(layoutId, parent, attachToParent);
    }

    @NonNull
    public static Context withTheme(@NonNull Context context, @StyleRes int themeResId) {
        return new ContextThemeWrapper(context.getApplicationContext(), themeResId);
    }

    public static boolean preload(@NonNull Context context, int layoutId) {
        try {
            IViewFactory factory = resolveFactory(context, layoutId);
            if (factory == null) {
                log("preload fallback XML: " + resourceName(context, layoutId));
                return false;
            }
            InflateUtils.prewarm(context);
            log("preload warmed generated path: " + resourceName(context, layoutId));
            return true;
        } catch (RuntimeException e) {
            log("preload failed: " + resourceName(context, layoutId) + ", " + e.getClass().getName());
            return false;
        }
    }

    public static void clearPreload(int layoutId) {
        sFactoryCache.remove(layoutId);
    }

    public static void clearPreloads() {
        sFactoryCache.clear();
    }

    @Nullable
    static View getView(@NonNull Context context, int layoutId, @Nullable ViewGroup parent, boolean attachToParent) {
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
    private static IViewFactory resolveFactory(@NonNull Context context, int layoutId) {
        Object cached = getCachedFactory(layoutId);
        if (cached instanceof IViewFactory) {
            IViewFactory factory = (IViewFactory) cached;
            log("Resolved generated factory from cache for " + resourceName(context, layoutId)
                    + ": " + factory.getClass().getName());
            return factory;
        }
        if (cached == FACTORY_MISS) {
            log("No generated factory for " + resourceName(context, layoutId) + " (cached miss)");
            return null;
        }
        IViewFactory factory = resolveRootFactory(context, layoutId);
        cacheFactory(layoutId, factory);
        if (factory != null) {
            log("Resolved generated factory for " + resourceName(context, layoutId)
                    + ": " + factory.getClass().getName());
            return factory;
        }
        log("No generated factory for " + resourceName(context, layoutId));
        return null;
    }

    @Nullable
    private static Object getCachedFactory(int layoutId) {
        return sFactoryCache.get(layoutId);
    }

    private static void cacheFactory(int layoutId, @Nullable IViewFactory factory) {
        sFactoryCache.put(layoutId, factory == null ? FACTORY_MISS : factory);
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
        SparseArray<IViewFactory> factories = sLoadedGroups.get(groupClassName);
        if (factories != null) {
            return factories;
        }
        synchronized (groupClassName.intern()) {
            factories = sLoadedGroups.get(groupClassName);
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

    @NonNull
    private static String resourceName(@NonNull Context context, int layoutId) {
        try {
            return context.getResources().getResourceName(layoutId);
        } catch (Exception ignored) {
            return String.valueOf(layoutId);
        }
    }
}
