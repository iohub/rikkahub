# RikkaHub · Android APK 构建环境说明

> 本文记录在 macOS（Apple Silicon）上编译 RikkaHub Android APK 所需的完整本机工具链。
> 内容覆盖：环境快照 → 必需工具清单 → 项目构建配置 → 环境变量 → 构建命令 → 实测结果 → 新机器从零搭建 → 常见问题。
> 所有版本号、绝对路径均来自 2026-11-19 的实机探测与一次真实构建，可用于在其他机器上复现同一环境。
> 适用于：本地开发、给新同事配机器、排查「换机器后编译不过」。

## 1. 环境快照

| 项目 | 实测值 |
|---|---|
| 芯片 | Apple M1 Pro |
| CPU 核心 | 10 |
| 内存 | 16 GB（17179869184 B） |
| 操作系统 | macOS 26.4.1（build 25E253） |
| 架构 | arm64 |
| 项目所在卷 | `/dev/disk3s1`，460 GiB 总容量 / 231 GiB 已用 / **199 GiB 可用**（54%） |

```bash
$ uname -m
arm64

$ sw_vers
ProductName:      macOS
ProductVersion:   26.4.1
BuildVersion:     25E253

$ sysctl -n hw.ncpu hw.memsize machdep.cpu.brand_string
10
17179869184
Apple M1 Pro
```

> 磁盘余量充足，不存在空间风险。Android 构建的中间产物（`build/`、`.cxx/`、Gradle 缓存）体积较大，建议预留 30 GiB 以上。

## 2. 必需工具一览

| 工具 | 版本 | 绝对路径 | 是否必需 | 备注 |
|---|---|---|---|---|
| JDK（默认） | Temurin **25.0.5** | `/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home` | 必需 | `JAVA_HOME` 指向此处；项目 `jvmTarget = JVM_17`，JDK 25 已实测可完整编译 |
| JDK（备用） | Temurin **21.0.13** | `/Users/ydl/jdk/jdk-21.0.13+9/Contents/Home` | 可选 | 非标准安装位置（用户目录手动解压），遇到 JDK 兼容问题时切换 |
| Android SDK 根 | — | `/opt/homebrew/share/android-commandlinetools` | 必需 | 由 Homebrew 安装的 **command line tools**；`ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 同值 |
| Gradle | **9.6.0**（wrapper） | `/Users/ydl/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle` | 必需 | 无需手工安装；`distributionUrl` 指向 `gradle-9.6.0-bin.zip`，首次构建自动下载 |
| Android Gradle Plugin | **9.4.0** | —（Maven 依赖） | 必需 | 版本定义于 `gradle/libs.versions.toml` 的 `agp`；项目未显式声明 `buildToolsVersion` / `ndkVersion`，均由 AGP 决定 |
| Kotlin | **2.4.21** | —（Maven 依赖） | 必需 | 版本定义于 `gradle/libs.versions.toml` 的 `kotlin`；配套 KSP **2.3.10** |
| NDK | **28.2.13676358** | `/opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358` | 必需 | 构建实际使用（由 `CMakeCache.txt` 的 `ANDROID_NDK` 证实）；另装 29.0.14206865 但未参与，见下文 |
| CMake | **3.22.1** | `/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/cmake` | 必需 | `terminal/build.gradle.kts` 显式锁定 `version = "3.22.1"` |
| ninja（SDK 内置） | 随 `cmake;3.22.1` 提供 | `/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/ninja` | 必需（已随组件自带） | 系统级 `ninja` **未安装**，不影响构建 |
| 系统 CMake | 4.3.1 | `/opt/homebrew/bin/cmake` | 非必需 | 版本与项目要求不同，**不参与构建** |
| platform-tools | **37.0.1**（adb **1.0.41**） | `/opt/homebrew/share/android-commandlinetools/platform-tools/adb` | 必需 | 安装 APK、真机/模拟器调试 |
| platforms | `android-33`、`android-37.0`、**`android-37.2`** | `/opt/homebrew/share/android-commandlinetools/platforms/` | 必需 | `android-37.2` 对应 `compileSdk` 需求 |
| build-tools | `35.0.0`、`36.0.0`、`37.0.0` | `/opt/homebrew/share/android-commandlinetools/build-tools/` | 必需 | 具体使用哪一版由 AGP 9.4.0 决定 |
| cmdline-tools | `latest` | `/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/` | 必需 | 含 `sdkmanager`、`avdmanager`、`apkanalyzer`、`lint`、`d8`、`r8`、`retrace` 等 |
| git | 2.50.1（Apple Git-155） | `/usr/bin/git` | 必需 | `spotless { ratchetFrom("origin/master") }` 依赖 git 历史 |
| wget | 已安装 | `/opt/homebrew/bin/wget` | 非必需 | 与构建无关 |
| Xcode | **未安装完整版**（仅 Command Line Tools） | `/Library/Developer/CommandLineTools` | 非必需 | `xcode-select -p` 指向 CLT；Android 构建不需要 Xcode |

补充说明：

- `java` / `javac` 的实际路径是 `/usr/bin/java`、`/usr/bin/javac`（macOS 桩，经 `java_home` 转发到 25.0.5）。
- SDK 组件清单可用 `sdkmanager --list_installed` 复核，本机安装项：`build-tools;35.0.0`、`build-tools;36.0.0`、`build-tools;37.0.0`、`cmake;3.22.1`、`emulator 37.2.12`、`ndk;28.2.13676358`、`ndk;29.0.14206865`、`platform-tools 37.0.1`、`platforms;android-33`、`platforms;android-37.0`、`platforms;android-37.2`、`system-images;android-33;google_apis;arm64-v8a`。
- 构建实际使用的原生工具链由 `CMakeCache.txt` 直接读出，非推测：

```
terminal/.cxx/Debug/6g5o3z6u/armeabi-v7a/CMakeCache.txt:
ANDROID_NDK        = /opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358
CMAKE_COMMAND      = /opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/cmake
CMAKE_MAKE_PROGRAM = /opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/ninja
```

## 3. 项目构建配置

以下配置均从仓库代码中读取，是「需要什么工具、为什么要这个版本」的依据。

### 3.1 版本与工具链

| 配置项 | 值 | 来源 |
|---|---|---|
| Gradle wrapper | **9.6.0** | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | **9.4.0** | `gradle/libs.versions.toml` `[versions] agp` |
| Kotlin | **2.4.21** | `gradle/libs.versions.toml` `[versions] kotlin` |
| KSP | **2.3.10** | `gradle/libs.versions.toml` `[versions] ksp` |
| Compose BOM | **2026.09.00** | `gradle/libs.versions.toml` `[versions] composeBom` |

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
```

```toml
# gradle/libs.versions.toml（摘录）
[versions]
agp = "9.4.0"
kotlin = "2.4.21"
composeBom = "2026.09.00"
ksp = "2.3.10"
```

`./gradlew --version` 实测输出关键行：

```
Gradle 9.6.0
Launcher JVM:  25.0.5 (Eclipse Adoptium 25.0.5+8-LTS)
Daemon JVM:    /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
OS:            Mac OS X 26.4.1 aarch64
```

### 3.2 应用模块（`app/build.gradle.kts`）

| 配置项 | 值 |
|---|---|
| `namespace` / `applicationId` | `me.rerere.rikkahub` |
| `compileSdk` | `release(37) { minorApiLevel = 2 }` → **android-37.2** |
| `minSdk` | **26** |
| `targetSdk` | **37** |
| `versionCode` | **197** |
| `versionName` | **3.3.0** |
| `kotlin.compilerOptions.jvmTarget` | `JVM_17` |
| `buildToolsVersion` | 未显式声明（由 AGP 决定） |
| `ndkVersion` | 未显式声明（由 AGP 决定，实测选中 28.2.13676358） |

```kotlin
// app/build.gradle.kts（摘录）
android {
    namespace = "me.rerere.rikkahub"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        applicationId = "me.rerere.rikkahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 197
        versionName = "3.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
}
```

### 3.3 Library 模块统一约定

```kotlin
// build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt
extensions.configure<LibraryExtension> {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
```

> 因此 **Java 17 是最低要求**；JDK 17 / 21 / 25 均可，本机实测使用 JDK 25.0.5。

### 3.4 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
org.gradle.configuration-cache=true
org.gradle.tooling.parallel=true
```

### 3.5 Flavor：任务名必须带渠道

`flavorDimensions += "distribution"`，两个渠道共用同一 `applicationId`：

| Flavor | `IS_PLAY_STORE` | 说明 |
|---|---|---|
| `github` | `false` | 默认渠道（`isDefault = true`），自更新可用 |
| `play` | `true` | 上架 Google Play，关闭自更新检查 |

> **关键坑**：因为存在 flavor，任务名必须写成 `:app:assembleGithubDebug` 而非 `:app:assembleDebug`。

### 3.6 ABI 与产物形态

```kotlin
defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
}
splits {
    abi {
        isEnable = !isBuildingBundle
        reset()
        include("arm64-v8a", "x86_64")
        isUniversalApk = true
    }
}
```

- 单 ABI APK：`arm64-v8a`（真机）、`x86_64`（模拟器）。
- 另有 universal APK（两者合并），方便直接分发。
- 构建 AAB（`bundle*` 任务）时自动关闭 APK splits。

### 3.7 原生构建（本项目最容易被遗漏的要求）

`terminal` 与 `workspace` 两个模块使用 CMake 编译原生代码，其余模块为纯 Kotlin/Java：

```kotlin
// terminal/build.gradle.kts
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

- `terminal/src/main/cpp/CMakeLists.txt`
- `workspace/src/main/cpp/CMakeLists.txt`

> 结论：**只装 Android SDK 不够**，必须额外安装 SDK 里的 `cmake;3.22.1` 与 NDK，否则原生模块直接失败。

### 3.8 模块清单（`settings.gradle.kts`，共 14 个）

`:app`、`:ai`、`:search`、`:speech`、`:common`、`:document`、`:web`、`:material3`、`:ui`、`:workspace`、`:terminal`、`:app:baselineprofile`、`:mediagen`、`:oauth`

## 4. 环境变量

本机配置写在 `~/.zshrc`（第 9 / 132 / 133 行）：

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

`JAVA_HOME` 由 JDK 安装方式决定，本机为：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

必须知道的两个坑：

1. **本机没有 `~/Library/Android/sdk`**（Android Studio 的默认路径）。SDK 由 Homebrew 安装在 `/opt/homebrew/share/android-commandlinetools`，直接照抄「别人的」教程会找不到 SDK。
2. **项目根没有 `local.properties`**。SDK 定位完全依赖 `ANDROID_HOME` 环境变量；新机器或 CI 必须显式设置，否则 AGP 直接报 `SDK location not found`。若不便设环境变量，可在项目根新建 `local.properties`：

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

## 5. 构建命令速查

| 场景 | 命令 |
|---|---|
| Debug APK（日常开发） | `./gradlew :app:assembleGithubDebug` |
| 全量从零（排错用，慢） | `./gradlew clean :app:assembleGithubDebug` |
| 仅编译 Kotlin（快速验证改动能过编译） | `./gradlew :app:compileGithubDebugKotlin` |
| Play 渠道 Debug APK | `./gradlew :app:assemblePlayDebug` |
| Play 渠道 AAB（上架） | `./gradlew :app:bundlePlayRelease` |
| 单元测试 | `./gradlew :app:testGithubDebugUnitTest` |
| 全模块单元测试 / Lint | `./gradlew test` / `./gradlew lint` |
| 代码格式（改写 / 只检查） | `./gradlew spotlessApply` / `./gradlew spotlessCheck` |
| 离线构建（依赖缓存完整时） | `./gradlew :app:assembleGithubDebug --offline` |
| 安装到设备 | `adb install -r app/build/outputs/apk/github/debug/app-github-universal-debug.apk` |

`spotless` 已配置 `ratchetFrom("origin/master")`，只格式化相对 `origin/master` 改动过的文件，因此**需要可用的 git 远程分支引用**。

产物目录与文件：

```
app/build/outputs/apk/github/debug/
├── app-github-arm64-v8a-debug.apk     60M
├── app-github-x86_64-debug.apk        60M
├── app-github-universal-debug.apk     63M
└── output-metadata.json               1.0K
```

## 6. 实测验证结果

验证命令（在项目根执行）：

```bash
cd /Users/ydl/iohub/rikkahub && ./gradlew :app:assembleGithubDebug --offline
```

结果：

```
BUILD SUCCESSFUL in 14s
296 actionable tasks: 21 executed, 275 up-to-date
Configuration cache entry stored.
```

- `--offline` 一次通过、零网络依赖 → 说明 Gradle 依赖缓存完整。
- 该轮**真实重新执行**的任务包括 `:terminal:buildCMakeDebug[arm64-v8a]`、`:terminal:buildCMakeDebug[x86]`、`:workspace:buildCMakeDebug[arm64-v8a]`、`:app:dexBuilderGithubDebug`、`:app:packageGithubDebug` → 证明 **NDK/CMake 原生编译 + D8 dex + APK 打包在 JDK 25 下真实跑通**。
- ⚠️ **保真说明**：本次为**增量构建**（296 个任务中 275 个 `up-to-date`，`build/` 目录此前已存在），Kotlin 编译任务本轮未重编。如需「完全从零可复现」的背书，请执行 `./gradlew clean :app:assembleGithubDebug`。
- 唯一日志噪声（无害，JDK 25 的 JEP 472 警告）：

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by net.rubygrapefruit.platform.internal.NativeLibraryLoader
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning ...
```

产出 APK（时间戳与本轮构建时间一致，`2026-11-19 14:22:21`）：

| APK | 大小 | 路径 |
|---|---|---|
| arm64-v8a | 60M | `app/build/outputs/apk/github/debug/app-github-arm64-v8a-debug.apk` |
| universal | 63M | `app/build/outputs/apk/github/debug/app-github-universal-debug.apk` |
| x86_64 | 60M | `app/build/outputs/apk/github/debug/app-github-x86_64-debug.apk` |

## 7. 新机器从零搭建步骤

1. 安装 Homebrew（若未装）：

   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```

2. 安装 JDK 17+（推荐 Temurin 21 或 25；项目 `jvmTarget = JVM_17`，实测 JDK 25 可用）：

   ```bash
   brew install --cask temurin@21    # 或 temurin
   ```

   确认：`java -version`、`/usr/libexec/java_home -V`。

3. 安装 Android command line tools（本机即为此种安装方式，路径固定为 `/opt/homebrew/share/android-commandlinetools`）：

   ```bash
   brew install --cask android-commandlinetools
   ```

4. 写入环境变量（`~/.zshrc`）：

   ```bash
   export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
   export ANDROID_SDK_ROOT="$ANDROID_HOME"
   export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
   ```

   然后 `source ~/.zshrc`。

5. 接受许可协议：

   ```bash
   sdkmanager --licenses
   ```

6. 安装必需 SDK 组件（一条命令装齐，含原生构建所需的 CMake 与 NDK）：

   ```bash
   sdkmanager "platforms;android-37.2" "build-tools;37.0.0" "platform-tools" "ndk;28.2.13676358" "cmake;3.22.1"
   ```

   - `platforms;android-37.2` 对应 `compileSdk = release(37) + minorApiLevel 2`。
   - `cmake;3.22.1` 与 NDK 是 `terminal` / `workspace` 原生模块的硬需求，缺一不可。

7. 验证安装：

   ```bash
   java -version
   echo $ANDROID_HOME
   sdkmanager --list_installed
   ```

8. 构建首个 APK：

   ```bash
   cd /Users/ydl/iohub/rikkahub && ./gradlew :app:assembleGithubDebug
   ```

## 8. 常见问题（FAQ）

| 现象 | 原因 | 解决 |
|---|---|---|
| `SDK location not found` | 未设置 `ANDROID_HOME`，且项目无 `local.properties` | 设置 `ANDROID_HOME`（见第 4 节），或在项目根新建 `local.properties` 写 `sdk.dir=/opt/homebrew/share/android-commandlinetools` |
| `Cannot locate tasks that match ':app:compileDebugKotlin'` / `Ambiguous matches` | 项目有两个 product flavor | 用带渠道的任务名：`:app:compileGithubDebugKotlin` |
| `CMake 3.22.1 not found` / `No version of NDK matched` | 缺 `cmake;3.22.1` 或对应 NDK | 执行第 7 节第 6 步的 `sdkmanager` 命令 |
| `ninja: command not found` | 系统未装 ninja | 无需安装，AGP 使用 SDK `cmake;3.22.1` 自带的 `bin/ninja` |
| 构建很慢 / OOM | JVM 堆不足或被随意调小 | `gradle.properties` 已配 `-Xmx8g`，16 GB 内存下够用，不要随意调大 |
| `--offline` 构建失败 | Gradle 依赖缓存不完整 | 去掉 `--offline` 联网构建一次，缓存后即可离线 |
| JDK 版本相关告警（如 `Unsupported class file major version`） | 项目未锁定 JDK（无 `.java-version`、无 `org.gradle.java.home`），仅要求 Java 17 | JDK 25 实测可用；若遇工具链不兼容，切换 Temurin 21.0.13 后重试 |
| `xcodebuild: requires Xcode` | 只装了 Command Line Tools | Android 构建不需要 Xcode，忽略该报错 |
| `spotlessCheck` 报大量格式差异 | ktlint 规则与现有代码风格冲突 | 用 `./gradlew spotlessApply` 自动修复；只检查相对 `origin/master` 的改动 |

## 9. 变更记录

| 日期 | 变更内容 | 依据 |
|---|---|---|
| 2026-11-19 | 建立本文档：记录 macOS 26.4.1 / arm64（M1 Pro）上完整工具链、构建配置与实测结果 | 当日实机探测 + `./gradlew :app:assembleGithubDebug --offline` 真实构建 |

> 维护提示：本文中的版本号（Gradle wrapper、AGP、Kotlin、KSP、Compose BOM、`compileSdk`/`minSdk`/`targetSdk`、`versionCode`/`versionName`）需随 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 的变更同步更新；SDK 组件版本（NDK、CMake、build-tools）需随 AGP 升级重新核对。
