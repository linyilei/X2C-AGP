# X2C-AGP MVP Notes

This project contains an initial SDK-style implementation for build-time layout construction.

## Goals

- The Gradle plugin scans Android layout resources instead of relying on annotations.
- Generated code keeps standard tags close to normal `LayoutInflater` behavior by reusing AppCompat / Material inflater paths when available, while preserving `AttributeSet`, styles, and theme values.
- `include` and root `merge` are handled explicitly in generated code.

## Modules

- `x2c-runtime`: runtime API used by apps and generated code.
- `x2c-gradle-plugin`: standalone Gradle plugin implementation, exposed as `io.github.linyilei.x2c`.
- `app`: host demo app.
- `feature-demo`: Android library demo module.
- `feature-nested`: transitive Android library demo module used by `feature-demo` to exercise `app -> feature-demo -> feature-nested` root-index aggregation.

The plugin supports both application and Android library modules. Library modules generate their own group and module index. Application modules scan the dependency classpath for module indexes, merge them into the app root index, then runtime loads each group lazily on first use.

## Published Artifacts

- `com.github.linyilei.X2C-AGP:x2c-runtime:0.1.1`
- `com.github.linyilei.X2C-AGP:x2c-gradle-plugin:0.1.1`

Both artifacts are configured for local Maven and JitPack. The Gradle plugin id remains `io.github.linyilei.x2c`; local sample builds resolve the implementation artifact from `mavenLocal()` first.

## App Integration

For remote JitPack integration, expose the plugin through `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url 'https://jitpack.io' }
    }
    plugins {
        id 'io.github.linyilei.x2c' version '0.1.1'
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'io.github.linyilei.x2c') {
                useModule("com.github.linyilei.X2C-AGP:x2c-gradle-plugin:${requested.version}")
            }
        }
    }
}
```

Apply the plugin and runtime dependency in the app module:

```groovy
plugins {
    id 'io.github.linyilei.x2c'
}

apply plugin: 'com.android.application'

dependencies {
    implementation 'com.github.linyilei.X2C-AGP:x2c-runtime:0.1.1'
}
```

Choose a generation mode in Gradle first. The default `tools` mode scans `res/layout*` and starts from layouts marked with `tools:x2c="standard"`. The recommended setup is a root-level global switch:

```groovy
ext.x2cMode = 'tools'
```

If you want an explicit entry set instead, switch to `annotation` mode. The plugin will scan `@Xml(layouts = "...")` usages and then expand `include` dependencies recursively:

```groovy
ext.x2cMode = 'annotation'
```

If needed, a module can still override the global mode locally:

```groovy
x2c {
    mode = 'annotation'
}
```

You can also blacklist layouts by name so they are skipped as generation targets:

```groovy
x2c {
    mode = 'annotation'
    blacklist = ['debug_panel', 'legacy_banner']
}
```

In `tools` mode, opt in a layout by adding `tools:x2c="standard"` on its root tag:

```xml
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:x2c="standard">
</LinearLayout>
```

Inflate through the runtime entrypoint:

```java
X2C.setContentView(this, R.layout.activity_main);
```

For `include`, the plugin follows referenced layouts recursively. Included layouts do not need their own `tools:x2c` marker.

In `annotation` mode, declare the entry layout in code:

```java
@Xml(layouts = "activity_main")
public final class MainActivity extends AppCompatActivity {
}
```

Applications and libraries use the same string-based form:

```java
@Xml(layouts = "activity_feature_demo")
public final class FeatureDemoActivity extends Activity {
}
```

## ARouter Preload Integration

Preloading fits two moments: startup tasks can preload the home layout, and an ARouter interceptor can preload the next Activity layout before navigation. X2C keeps routing out of the core runtime; the interceptor locates the destination layout from route metadata, path, or destination class, then asks X2C to prewarm the generated path before navigation continues:

```java
public final class X2CStartupTask implements Runnable {
    private final Context applicationContext;

    public X2CStartupTask(Context context) {
        applicationContext = context.getApplicationContext();
    }

    @Override
    public void run() {
        Context preloadContext = X2C.withTheme(applicationContext, R.style.Theme_X2cDemo);
        X2C.preload(preloadContext, R.layout.activity_main);
    }
}
```

```java
@Interceptor(priority = 1, name = "X2C preload")
public final class X2CPreloadInterceptor implements IInterceptor {
    private Context applicationContext;

    @Override
    public void init(Context context) {
        applicationContext = context.getApplicationContext();
    }

    @Override
    public void process(Postcard postcard, InterceptorCallback callback) {
        if (FeatureRoutes.FEATURE_DEMO.equals(postcard.getPath())) {
            Context preloadContext = X2C.withTheme(applicationContext, R.style.Theme_X2cDemo);
            X2C.preload(preloadContext, com.example.featuredemo.R.layout.activity_feature_demo);
        }
        callback.onContinue(postcard);
    }
}
```

`X2C.preload(context, layoutId)` now prewarms the generated factory lookup plus the runtime AppCompat / Material inflater bridge; it no longer caches detached `View` instances. The destination page can keep using `X2C.setContentView(activity, layoutId)` or `X2C.inflate(activity, layoutId, null, false)` unchanged. Missing generated factories or runtime warm-up failures simply fall back to normal creation, so the interceptor can always continue routing.

## Runtime Verification

Enable runtime logs in debug builds:

```java
X2C.setDebugLogging(BuildConfig.DEBUG);
```

Then filter logcat by tag `X2C`. A generated-layout hit looks like:

```text
D/X2C: Loaded generated root index: com.example.glide2test.x2c.X2CRootIndex, entries=5
D/X2C: Loaded generated group: com.example.glide2test.x2c.X2CGroup, factories=3
D/X2C: Resolved generated factory for com.example.glide2test:layout/activity_main: com.example.glide2test.x2c.X2C_Activity_Main
D/X2C: Creating generated view: com.example.glide2test:layout/activity_main via com.example.glide2test.x2c.X2C_Activity_Main
D/X2C: setContentView hit generated view: com.example.glide2test:layout/activity_main
```

If a layout is not generated, you will see `fallback XML` for that layout.

## Library Module Integration

Library modules can also apply the plugin:

```groovy
apply plugin: 'com.android.library'
apply plugin: 'io.github.linyilei.x2c'

dependencies {
    implementation 'com.github.linyilei.X2C-AGP:x2c-runtime:0.1.1'
}
```

Each Android library generates its own `X2CGroup` and `X2CModuleIndex` under `<manifest package>.x2c`. Application modules generate an app-level `X2CRootIndex`, scan dependency classes/jars/AARs for `X2CModuleIndex`, and only load a library group when a layout from that group is requested.

This allows app layouts to include a normal-root layout from a feature/library module. When an included target layout has a root `<merge>`, X2C follows native `LayoutInflater` behavior: include-tag `LayoutParams`, `android:id`, and `android:visibility` are ignored, merge children are attached directly to the parent, and include-tag `android:theme` still wraps the inflated subtree.

## Repository Development Mode

This repository keeps two resolution modes:

- Default local sample builds resolve both the plugin and runtime from `mavenLocal()` first, then remote JitPack.
- Only when `JITPACK=true` is present does the repository switch back to local `includeBuild('x2c-gradle-plugin')` and local `:x2c-runtime`, so JitPack can build and publish the current tag from source.

## Current Limits

- `fragment` and `requestFocus` tags are skipped and fall back to normal XML inflation.
- Variant dispatch currently supports default, `land`, and `vNN` qualifiers.
- App-level root indexing consumes dependency `X2CModuleIndex` classes from project dependencies and remote AARs; AARs that do not contain a module index cannot be auto-indexed.
- DataBinding is intentionally out of scope for this phase.
