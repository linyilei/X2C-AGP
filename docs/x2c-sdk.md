# X2C-AGP MVP Notes

This project contains an initial SDK-style implementation for build-time layout construction.

## Goals

- The Gradle plugin scans Android layout resources instead of relying on annotations.
- Generated code constructs views with `View(Context, AttributeSet)` so framework, AppCompat, custom attributes, styles, and theme values stay close to normal `LayoutInflater` behavior.
- `include` and root `merge` are handled explicitly in generated code.

## Modules

- `x2c-runtime`: runtime API used by apps and generated code.
- `x2c-gradle-plugin`: standalone Gradle plugin implementation, exposed as `io.github.linyilei.x2c`.
- `app`: host demo app.
- `feature-demo`: Android library demo module.

The plugin supports both application and Android library modules. Library modules generate their own group. Application modules generate a root index that maps layout IDs to groups from direct project dependencies, then runtime loads each group lazily on first use.

## Published Artifacts

- `com.github.linyilei.X2C-AGP:x2c-runtime:v0.1.0`
- `com.github.linyilei.X2C-AGP:x2c-gradle-plugin:v0.1.0`

Both artifacts are configured for JitPack. The Gradle plugin id remains `io.github.linyilei.x2c`; the implementation artifact is resolved from JitPack.

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
        id 'io.github.linyilei.x2c' version 'v0.1.0'
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
    implementation 'com.github.linyilei.X2C-AGP:x2c-runtime:v0.1.0'
}
```

Opt in a layout by adding `tools:x2c="standard"` on its root tag:

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
    implementation 'com.github.linyilei.X2C-AGP:x2c-runtime:v0.1.0'
}
```

Each Android library generates its own `X2CGroup` under `<manifest package>.x2c`. Application modules generate an app-level `X2CRootIndex` and only load a library group when a layout from that group is requested.

This allows app layouts to include a normal-root layout from a feature/library module. Cross-module root `merge` include is not handled yet because the app generator does not inspect dependency module layout metadata.

## Repository Development Mode

This repository keeps two resolution modes:

- Default local sample builds resolve both the plugin and runtime from remote JitPack.
- Only when `JITPACK=true` is present does the repository switch back to local `includeBuild('x2c-gradle-plugin')` and local `:x2c-runtime`, so JitPack can build and publish the current tag from source.

## Current Limits

- `fragment` and `requestFocus` tags are skipped and fall back to normal XML inflation.
- Root `merge` is supported for direct `X2C.inflate(..., parent, true)`, but `merge` included inside `ConstraintLayout` does not yet apply include-tag constraints as a virtual wrapper. Prefer a normal root container for ConstraintLayout includes until this is implemented.
- Variant dispatch currently supports default, `land`, and `vNN` qualifiers.
- App-level root indexing includes direct project dependencies that apply `io.github.linyilei.x2c`; deeper dependency chains are not automatically indexed yet, so the host app should directly depend on modules whose layouts need X2C.
- DataBinding and preloading are intentionally out of scope for this phase.
