package io.github.linyilei.x2c.runtime;

import android.content.res.TypedArray;
import android.os.Build;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class InflateUtils {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_COMPAT_VIEW_INFLATER_CLASS_NAME = "androidx.appcompat.app.AppCompatViewInflater";
    private static final String APP_COMPAT_STYLEABLE_CLASS_NAME = "androidx.appcompat.R$styleable";
    private static final String VECTOR_ENABLED_TINT_RESOURCES_CLASS_NAME =
            "androidx.appcompat.widget.VectorEnabledTintResources";
    private static final Class<?>[] VIEW_SIGNATURE = new Class<?>[]{Context.class, AttributeSet.class};
    private static final Class<?>[] CONTEXT_ONLY_SIGNATURE = new Class<?>[]{Context.class};
    private static final Class<?>[] APP_COMPAT_CREATE_VIEW_SIGNATURE = new Class<?>[]{
            View.class, String.class, Context.class, AttributeSet.class,
            boolean.class, boolean.class, boolean.class, boolean.class
    };
    private static final String[] CLASS_PREFIX_LIST = new String[]{
            "android.widget.",
            "android.view.",
            "android.webkit."
    };
    private static final Map<Class<?>, Method> sFinishInflateMethods = new HashMap<>();
    private static final Set<Class<?>> sFinishInflateMisses = new HashSet<>();
    private static final Map<String, Constructor<? extends View>> sViewConstructors = new HashMap<>();
    private static final Set<String> sViewConstructorMisses = new HashSet<>();
    private static final Map<String, AppCompatInflaterBridge> sAppCompatInflaterBridges = new HashMap<>();
    private static final Set<String> sAppCompatInflaterBridgeMisses = new HashSet<>();
    private static volatile AppCompatStyleableInfo sAppCompatStyleableInfo;
    private static volatile boolean sAppCompatStyleableLookupAttempted;
    private static volatile Boolean sWrapContextEnabled;
    private static volatile boolean sWrapContextLookupAttempted;

    private InflateUtils() {
    }

    @NonNull
    public static AttributeSet nextStartTag(@NonNull XmlPullParser parser) throws IOException, XmlPullParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
            // Skip until the next element.
        }
        if (type != XmlPullParser.START_TAG) {
            throw new InflateException("Unexpected end of XML while seeking next start tag.");
        }
        return Xml.asAttributeSet(parser);
    }

    @Nullable
    public static ViewGroup.LayoutParams generateLayoutParams(@Nullable ViewGroup parent, @NonNull AttributeSet attrs) {
        if (parent == null) {
            return null;
        }
        try {
            return parent.generateLayoutParams(attrs);
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    public static void addView(@NonNull ViewGroup parent, @NonNull View child, @Nullable AttributeSet attrs) {
        ViewGroup.LayoutParams params = attrs == null ? child.getLayoutParams() : generateLayoutParams(parent, attrs);
        if (params == null) {
            params = child.getLayoutParams();
        }
        addView(parent, child, params);
    }

    public static void addView(@NonNull ViewGroup parent, @NonNull View child, @Nullable ViewGroup.LayoutParams params) {
        if (params != null) {
            parent.addView(child, params);
        } else {
            parent.addView(child);
        }
    }

    public static void attachRoot(@Nullable ViewGroup parent, @NonNull View root, @NonNull AttributeSet attrs, boolean attachToParent) {
        if (parent == null) {
            return;
        }
        ViewGroup.LayoutParams params = generateLayoutParams(parent, attrs);
        if (params != null) {
            root.setLayoutParams(params);
        }
        if (attachToParent) {
            addView(parent, root, params);
        }
    }

    @NonNull
    public static View createView(@NonNull Context context, @Nullable ViewGroup parent,
                                  @NonNull AttributeSet attrs, @NonNull String name) {
        View view = createViewWithAppCompat(context, parent, attrs, name);
        if (view != null) {
            return view;
        }
        return createViewReflectively(context, attrs, name);
    }

    public static void prewarm(@NonNull Context context) {
        resolveAppCompatInflaterBridge(context);
        shouldWrapContext();
    }

    @NonNull
    public static View include(@NonNull Context context, @NonNull ViewGroup parent, int layoutId, @NonNull AttributeSet includeAttrs) {
        View view = X2C.inflate(context, layoutId, parent, false);
        ViewGroup.LayoutParams params = generateLayoutParams(parent, includeAttrs);
        if (params == null) {
            params = view.getLayoutParams();
        }
        if (params != null) {
            view.setLayoutParams(params);
        }
        int id = includeAttrs.getAttributeResourceValue(ANDROID_NS, "id", View.NO_ID);
        if (id != View.NO_ID) {
            view.setId(id);
        }
        int visibility = parseVisibility(includeAttrs.getAttributeValue(ANDROID_NS, "visibility"));
        if (visibility != -1) {
            view.setVisibility(visibility);
        }
        addView(parent, view, params);
        return view;
    }

    @NonNull
    public static View includeDynamic(@NonNull Context context, @NonNull ViewGroup parent, int layoutId,
                                      @NonNull AttributeSet includeAttrs) {
        return isMergeRoot(context, layoutId)
                ? includeMerge(context, parent, layoutId, includeAttrs)
                : include(context, parent, layoutId, includeAttrs);
    }

    @NonNull
    public static View includeMerge(@NonNull Context context, @NonNull ViewGroup parent, int layoutId,
                                    @NonNull AttributeSet includeAttrs) {
        // Native LayoutInflater ignores layout parameters on an <include> tag if the
        // included layout's root is <merge>. We align with this behavior to ensure
        // consistent view hierarchies between X2C and fallback XML inflation.
        return X2C.inflate(context, layoutId, parent, true);
    }

    public static void finishInflate(@Nullable View view) {
        if (view == null) {
            return;
        }
        Method method = resolveFinishInflateMethod(view.getClass());
        if (method == null) {
            return;
        }
        try {
            method.invoke(view);
        } catch (Exception e) {
            throw new InflateException("Unable to dispatch onFinishInflate for " + view.getClass().getName(), e);
        }
    }

    private static int parseVisibility(@Nullable String value) {
        if (value == null) {
            return -1;
        }
        if ("visible".equals(value)) {
            return View.VISIBLE;
        }
        if ("invisible".equals(value)) {
            return View.INVISIBLE;
        }
        if ("gone".equals(value)) {
            return View.GONE;
        }
        return -1;
    }



    @Nullable
    private static Method resolveFinishInflateMethod(@NonNull Class<?> viewType) {
        synchronized (InflateUtils.class) {
            Method method = sFinishInflateMethods.get(viewType);
            if (method != null) {
                return method;
            }
            if (sFinishInflateMisses.contains(viewType)) {
                return null;
            }
        }

        Class<?> type = viewType;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("onFinishInflate");
                method.setAccessible(true);
                synchronized (InflateUtils.class) {
                    sFinishInflateMethods.put(viewType, method);
                    sFinishInflateMisses.remove(viewType);
                }
                return method;
            } catch (NoSuchMethodException ignore) {
                type = type.getSuperclass();
            }
        }

        synchronized (InflateUtils.class) {
            sFinishInflateMisses.add(viewType);
        }
        return null;
    }

    private static boolean isMergeRoot(@NonNull Context context, int layoutId) {
        XmlPullParser parser = context.getResources().getXml(layoutId);
        try {
            nextStartTag(parser);
            return "merge".equals(parser.getName());
        } catch (IOException e) {
            throw new InflateException("Unable to inspect XML for layout " + layoutId, e);
        } catch (XmlPullParserException e) {
            throw new InflateException("Unable to inspect XML for layout " + layoutId, e);
        } finally {
            if (parser instanceof android.content.res.XmlResourceParser) {
                ((android.content.res.XmlResourceParser) parser).close();
            }
        }
    }

    @Nullable
    private static View createViewWithAppCompat(@NonNull Context context, @Nullable ViewGroup parent,
                                                @NonNull AttributeSet attrs, @NonNull String name) {
        AppCompatInflaterBridge bridge = resolveAppCompatInflaterBridge(context);
        if (bridge == null) {
            return null;
        }
        try {
            Object view = bridge.createView.invoke(bridge.inflater, parent, name, context, attrs,
                    shouldInheritContext(parent, attrs),
                    Build.VERSION.SDK_INT < 21,
                    true,
                    shouldWrapContext());
            return view instanceof View ? (View) view : null;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new InflateException("Unable to create view " + name + " through AppCompat inflater.", cause);
        } catch (Exception e) {
            throw new InflateException("Unable to create view " + name + " through AppCompat inflater.", e);
        }
    }

    @NonNull
    private static View createViewReflectively(@NonNull Context context, @NonNull AttributeSet attrs,
                                               @NonNull String name) {
        Constructor<? extends View> constructor = resolveViewConstructor(context.getClassLoader(), name);
        if (constructor == null) {
            throw new InflateException("Unable to resolve constructor for view " + name + '.');
        }
        try {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2) {
                return constructor.newInstance(context, attrs);
            }
            return constructor.newInstance(context);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new InflateException("Unable to create view " + name + '.', cause);
        } catch (Exception e) {
            throw new InflateException("Unable to create view " + name + '.', e);
        }
    }

    @Nullable
    private static Constructor<? extends View> resolveViewConstructor(@NonNull ClassLoader classLoader,
                                                                      @NonNull String name) {
        synchronized (InflateUtils.class) {
            Constructor<? extends View> constructor = sViewConstructors.get(name);
            if (constructor != null) {
                return constructor;
            }
            if (sViewConstructorMisses.contains(name)) {
                return null;
            }
        }

        Constructor<? extends View> constructor = null;
        if (name.indexOf('.') >= 0) {
            constructor = findViewConstructor(classLoader, name);
        } else {
            for (String prefix : CLASS_PREFIX_LIST) {
                constructor = findViewConstructor(classLoader, prefix + name);
                if (constructor != null) {
                    break;
                }
            }
        }

        synchronized (InflateUtils.class) {
            if (constructor != null) {
                sViewConstructors.put(name, constructor);
            } else {
                sViewConstructorMisses.add(name);
            }
        }
        return constructor;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Constructor<? extends View> findViewConstructor(@NonNull ClassLoader classLoader,
                                                                   @NonNull String className) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            if (!View.class.isAssignableFrom(clazz)) {
                return null;
            }
            Constructor<? extends View> constructor;
            try {
                constructor = ((Class<? extends View>) clazz).getConstructor(VIEW_SIGNATURE);
            } catch (NoSuchMethodException e) {
                constructor = ((Class<? extends View>) clazz).getConstructor(CONTEXT_ONLY_SIGNATURE);
            }
            constructor.setAccessible(true);
            return constructor;
        } catch (Exception ignore) {
            return null;
        }
    }

    @Nullable
    private static AppCompatInflaterBridge resolveAppCompatInflaterBridge(@NonNull Context context) {
        String className = resolveViewInflaterClassName(context);
        synchronized (InflateUtils.class) {
            AppCompatInflaterBridge bridge = sAppCompatInflaterBridges.get(className);
            if (bridge != null) {
                return bridge;
            }
            if (sAppCompatInflaterBridgeMisses.contains(className)) {
                return null;
            }
        }

        AppCompatInflaterBridge bridge = createAppCompatInflaterBridge(context, className);
        synchronized (InflateUtils.class) {
            if (bridge != null) {
                sAppCompatInflaterBridges.put(className, bridge);
            } else {
                sAppCompatInflaterBridgeMisses.add(className);
            }
        }
        return bridge;
    }

    @Nullable
    private static AppCompatInflaterBridge createAppCompatInflaterBridge(@NonNull Context context,
                                                                         @NonNull String className) {
        try {
            ClassLoader classLoader = context.getClassLoader();
            Class<?> baseClass = Class.forName(APP_COMPAT_VIEW_INFLATER_CLASS_NAME, false, classLoader);
            Class<?> inflaterClass = className.equals(APP_COMPAT_VIEW_INFLATER_CLASS_NAME)
                    ? baseClass
                    : Class.forName(className, false, classLoader);
            if (!baseClass.isAssignableFrom(inflaterClass)) {
                return null;
            }
            Object inflater = inflaterClass.getDeclaredConstructor().newInstance();
            Method createView = baseClass.getDeclaredMethod("createView", APP_COMPAT_CREATE_VIEW_SIGNATURE);
            createView.setAccessible(true);
            return new AppCompatInflaterBridge(inflater, createView);
        } catch (Exception ignore) {
            return null;
        }
    }

    @NonNull
    private static String resolveViewInflaterClassName(@NonNull Context context) {
        AppCompatStyleableInfo styleableInfo = resolveAppCompatStyleableInfo(context);
        if (styleableInfo == null) {
            return APP_COMPAT_VIEW_INFLATER_CLASS_NAME;
        }
        TypedArray array = context.obtainStyledAttributes(styleableInfo.appCompatTheme);
        try {
            String className = array.getString(styleableInfo.viewInflaterClassIndex);
            return className == null || className.length() == 0
                    ? APP_COMPAT_VIEW_INFLATER_CLASS_NAME
                    : className;
        } finally {
            array.recycle();
        }
    }

    @Nullable
    private static AppCompatStyleableInfo resolveAppCompatStyleableInfo(@NonNull Context context) {
        if (sAppCompatStyleableInfo != null || sAppCompatStyleableLookupAttempted) {
            return sAppCompatStyleableInfo;
        }
        synchronized (InflateUtils.class) {
            if (!sAppCompatStyleableLookupAttempted) {
                try {
                    Class<?> styleableClass = Class.forName(APP_COMPAT_STYLEABLE_CLASS_NAME, false,
                            context.getClassLoader());
                    Field themeField = styleableClass.getField("AppCompatTheme");
                    Field indexField = styleableClass.getField("AppCompatTheme_viewInflaterClass");
                    sAppCompatStyleableInfo = new AppCompatStyleableInfo(
                            (int[]) themeField.get(null),
                            indexField.getInt(null));
                } catch (Exception ignore) {
                    sAppCompatStyleableInfo = null;
                }
                sAppCompatStyleableLookupAttempted = true;
            }
            return sAppCompatStyleableInfo;
        }
    }

    private static boolean shouldInheritContext(@Nullable ViewGroup parent, @NonNull AttributeSet attrs) {
        if (parent == null || Build.VERSION.SDK_INT >= 21) {
            return false;
        }
        if (attrs instanceof XmlPullParser) {
            return ((XmlPullParser) attrs).getDepth() > 1;
        }
        return parent.getParent() != null;
    }

    private static boolean shouldWrapContext() {
        if (sWrapContextEnabled != null || sWrapContextLookupAttempted) {
            return Boolean.TRUE.equals(sWrapContextEnabled);
        }
        synchronized (InflateUtils.class) {
            if (!sWrapContextLookupAttempted) {
                try {
                    Class<?> clazz = Class.forName(VECTOR_ENABLED_TINT_RESOURCES_CLASS_NAME);
                    Method method = clazz.getDeclaredMethod("shouldBeUsed");
                    method.setAccessible(true);
                    Object value = method.invoke(null);
                    sWrapContextEnabled = value instanceof Boolean ? (Boolean) value : Boolean.FALSE;
                } catch (Exception ignore) {
                    sWrapContextEnabled = Boolean.FALSE;
                }
                sWrapContextLookupAttempted = true;
            }
            return Boolean.TRUE.equals(sWrapContextEnabled);
        }
    }

    private static final class AppCompatStyleableInfo {
        final int[] appCompatTheme;
        final int viewInflaterClassIndex;

        AppCompatStyleableInfo(@NonNull int[] appCompatTheme, int viewInflaterClassIndex) {
            this.appCompatTheme = appCompatTheme;
            this.viewInflaterClassIndex = viewInflaterClassIndex;
        }
    }

    private static final class AppCompatInflaterBridge {
        final Object inflater;
        final Method createView;

        AppCompatInflaterBridge(@NonNull Object inflater, @NonNull Method createView) {
            this.inflater = inflater;
            this.createView = createView;
        }
    }
}
