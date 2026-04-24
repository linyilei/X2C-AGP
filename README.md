# X2C-AGP

X2C-AGP 是一个 Android 编译期布局生成库。它会在 Gradle 构建阶段扫描被标记的 XML 布局，生成对应的 Java View 构造代码；运行时优先使用生成代码创建 View，未命中或暂不支持时自动回退到原生 `LayoutInflater`。

当前版本重点覆盖三条链路：

- AGP 统一扫描布局
- `AttributeSet` 构造路线
- `include` / `merge` 基础语义

## 特性

- 按 Android variant 扫描 `res/layout*`，通过 `tools:x2c="standard"` 显式开启生成。
- 生成代码使用 `new View(context, attrs)` 和 `parent.generateLayoutParams(attrs)`，尽量保留 XML 样式、主题、自定义属性和 `LayoutParams` 行为。
- 支持普通 `include`，支持根节点 `merge` 直接挂载。
- 支持 Android app 和 Android library 模块，library 会生成 group 和模块索引，app 会从依赖 classpath 聚合模块索引并按首次使用懒加载对应 group。
- 提供 debug 日志，便于确认某个布局是否命中生成代码。
- 未生成、未命中或暂不支持的布局会自动回退到 XML inflate。

## 环境要求

- `minSdkVersion 19`
- Android Gradle Plugin `4.2.2`
- Gradle `6.7.1`
- JDK `11`

## 安装

当前发布方式为 JitPack。将下面示例中的版本号替换为你要使用的版本，例如 `v0.1.0`。

### 方式一：`buildscript`

根工程 `build.gradle`：

```groovy
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
    dependencies {
        classpath 'com.github.linyilei.X2C-AGP:x2c-gradle-plugin:v0.1.0'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

App 或 library 模块：

```groovy
apply plugin: 'com.android.application'
apply plugin: 'io.github.linyilei.x2c'

dependencies {
    implementation 'com.github.linyilei.X2C-AGP:x2c-runtime:v0.1.0'
}
```

### 方式二：`plugins {}` DSL

如果工程使用 `plugins {}` DSL，需要在 `settings.gradle` 里把插件 ID 映射到 JitPack 模块：

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url 'https://jitpack.io' }
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

模块中：

```groovy
plugins {
    id 'com.android.application'
    id 'io.github.linyilei.x2c' version 'v0.1.0'
}

dependencies {
    implementation 'com.github.linyilei.X2C-AGP:x2c-runtime:v0.1.0'
}
```

## 快速开始

### 1. 标记需要生成的布局

在布局根节点添加 `tools:x2c="standard"`：

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:x2c="standard">

</androidx.constraintlayout.widget.ConstraintLayout>
```

被 `include` 引用的布局不需要重复标记，插件会从已标记布局开始递归收集依赖。

### 2. 通过 X2C 加载布局

作为 `Activity` 内容视图：

```java
X2C.setContentView(this, R.layout.activity_main);
```

作为普通 inflate：

```java
View view = X2C.inflate(context, R.layout.item_card, parent, false);
```

### 3. `include` / `merge` 说明

- 普通 `include` 会按引用链继续尝试命中生成代码。
- 根节点 `merge` 适合通过 `X2C.inflate(context, layoutId, parent, true)` 直接挂载到父容器。
- 当 `include` 的目标布局是 `<merge>` 根节点，且 include 标签自身携带 `LayoutParams`、`android:id` 或 `android:visibility` 时，X2C 会创建一个包装层承载 include 标签参数，再把 merge 子节点挂到包装层内。

## 验证是否命中生成代码

Debug 构建中开启日志：

```java
X2C.setDebugLogging(BuildConfig.DEBUG);
```

然后查看：

```bash
adb logcat -s X2C
```

命中生成代码时会看到类似日志：

```text
D/X2C: Loaded generated root index: your.application.id.x2c.X2CRootIndex, entries=3
D/X2C: Loaded generated group: your.application.id.x2c.X2CGroup, factories=3
D/X2C: Resolved generated factory for your.package:layout/activity_main: your.application.id.x2c.X2C_Activity_Main
D/X2C: Creating generated view: your.package:layout/activity_main via your.application.id.x2c.X2C_Activity_Main
D/X2C: setContentView hit generated view: your.package:layout/activity_main
```

如果看到 `fallback XML`，说明该布局当前没有走生成代码。

## 支持范围

- Android application 模块
- Android library 模块
- app 生成 root 索引，聚合依赖 classpath 中的 library 模块索引，library group 按需懒加载
- 默认目录、`land`、`vNN` 这几类常见 qualifier
- 普通 `include`
- 根节点 `merge`

## 当前限制

- `fragment`、`requestFocus` 暂不生成，遇到后回退 XML。
- app 会扫描依赖 classpath 中的 `X2CModuleIndex` 并聚合 project / 远端 AAR 的 library group；未由 X2C 插件生成模块索引的 AAR 无法自动纳入。
- DataBinding、ViewBinding、预加载暂不在当前版本范围内。
