package io.github.linyilei.x2c.runtime;

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
import java.lang.reflect.Method;

public final class InflateUtils {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

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
    public static View includeMerge(@NonNull Context context, @NonNull ViewGroup parent, int layoutId,
                                    @NonNull AttributeSet includeAttrs) {
        ViewGroup.LayoutParams params = generateLayoutParams(parent, includeAttrs);
        int id = includeAttrs.getAttributeResourceValue(ANDROID_NS, "id", View.NO_ID);
        int visibility = parseVisibility(includeAttrs.getAttributeValue(ANDROID_NS, "visibility"));
        if (params == null && id == View.NO_ID && visibility == -1) {
            return X2C.inflate(context, layoutId, parent, true);
        }

        ViewGroup wrapper = createMergeWrapper(context, parent, includeAttrs);
        if (params != null) {
            wrapper.setLayoutParams(params);
        }
        if (id != View.NO_ID) {
            wrapper.setId(id);
        }
        if (visibility != -1) {
            wrapper.setVisibility(visibility);
        }
        addView(parent, wrapper, params);
        X2C.inflate(context, layoutId, wrapper, true);
        finishInflate(wrapper);
        return wrapper;
    }

    public static void finishInflate(@Nullable View view) {
        if (view == null) {
            return;
        }
        Class<?> type = view.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("onFinishInflate");
                method.setAccessible(true);
                method.invoke(view);
                return;
            } catch (NoSuchMethodException ignore) {
                type = type.getSuperclass();
            } catch (Exception e) {
                throw new InflateException("Unable to dispatch onFinishInflate for " + view.getClass().getName(), e);
            }
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

    @NonNull
    private static ViewGroup createMergeWrapper(@NonNull Context context, @NonNull ViewGroup parent,
                                                @NonNull AttributeSet includeAttrs) {
        Class<?> parentType = parent.getClass();
        if (ViewGroup.class.isAssignableFrom(parentType)) {
            try {
                Constructor<?> constructor = parentType.getConstructor(Context.class, AttributeSet.class);
                Object instance = constructor.newInstance(context, includeAttrs);
                if (instance instanceof ViewGroup) {
                    return (ViewGroup) instance;
                }
            } catch (Exception ignored) {
                // Try a simpler constructor below.
            }
            try {
                Constructor<?> constructor = parentType.getConstructor(Context.class);
                Object instance = constructor.newInstance(context);
                if (instance instanceof ViewGroup) {
                    return (ViewGroup) instance;
                }
            } catch (Exception ignored) {
                // Fall through to a neutral container.
            }
        }
        return new FrameLayout(context, includeAttrs);
    }
}
