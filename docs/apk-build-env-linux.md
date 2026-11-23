# RikkaHub · Android APK 构建环境说明（Linux / Ubuntu）

> 本文记录在 **Ubuntu 22.04（x86_64）** 上编译 RikkaHub Android APK 的本机环境快照与快速构建指引。
> 与同目录的 [apk-build-env.md](apk-build-env.md)（macOS / Apple Silicon 版）是**同一项目的另一台机器、另一套环境**，两份文档互补而不重复：本文只写 Linux 侧事实。
> 所有版本号、绝对路径、字节数、命令输出均来自 2026-11-23 的现场命令采集与一次真实全量构建，可据此在其他机器上复现。
> 想「从零装出一套同样环境」请看操作手册 [apk-build-toolchain-install-linux.md](apk-build-toolchain-install-linux.md)；本文定位是**查询手册**（现在是什么状态、怎么最快出包）。

## 1. 文档定位与交叉链接

| 文档 | 定位 | 面向读者 |
|---|---|---|
| **本文** `apk-build-env-linux.md` | Linux 环境的**现状快照** + 快速构建指引 | 要在这台机器上出包的人、排障的人 |
| [apk-build-toolchain-install-linux.md](apk-build-toolchain-install-linux.md) | Linux 环境的**从零安装操作手册**（9 个步骤，每步含验证命令与预期输出） | 新机器 / CI 容器 / 换机复现 |
| [apk-build-env.md](apk-build-env.md) | **macOS（Apple Silicon）** 环境说明 | 用 Mac 开发的同事 |

> 采集方式：全部为现场命令（`uname` / `df` / `du` / `java -version` / `node -v` / `sdkmanager --list_installed` / `./gradlew --version` / `apksigner` / `aapt2` / `sha256sum` / `cat`），**没有任何数值来自推测**。
> 采集日期 **2026-11-23**；关键时间戳：SDK 手工安装 15:08–15:12、首次全量构建 15:27:33 → 15:45:30、debug keystore 生成 15:42。
> 若本文与现场命令冲突，**以现场命令为准**，并请按第 11 节的维护提示更新本文。

## 2. 环境快照

| 项目 | 实测值 |
|---|---|
| 操作系统 | **Ubuntu 22.04.3 LTS（Jammy Jellyfish）** |
| 内核 | **5.15.0-136-generic** |
| 架构 | `x86_64` |
| CPU | Intel(R) Xeon(R) Gold 6330 CPU @ 2.00GHz × **14** 核（`nproc`） |
| 内存 | **773401 MB**（`free -m`，约 755 GiB） |
| 用户 / 权限 | `root`，**无 sudo**（`which sudo` 无输出） |
| 时区 | `Asia/Shanghai`（CST） |
| 项目路径 | `/root/autodl-tmp/rikkahub` |
| 容器 / 宿主 | Autodl 容器（hostname `autodl-container-...`，硬件为独立挂载的本地 NVMe） |

```bash
$ uname -a
Linux autodl-container-c47345924e-22a22115 5.15.0-136-generic #147-Ubuntu SMP Sat Mar 15 15:53:30 UTC 2025 x86_64 x86_64 x86_64 GNU/Linux

$ cat /etc/os-release | head -3
PRETTY_NAME="Ubuntu 22.04.3 LTS"
NAME="Ubuntu"
VERSION_ID="22.04"
```

### 2.1 磁盘布局（本机最关键的环境特征）

```bash
$ df -h
Filesystem      Size  Used Avail Use% Mounted on
overlay          30G   12G   19G  40% /                       # 根分区：仅 30G
tmpfs            64M     0   64M   0% /dev
/dev/nvme1n1    150M  4.0K  150M   1% /init
AutoFS:fs1      4.0T  1.4T  2.6T  35% /autodl-pub/data
AutoFS:fs24826  200G   40G  161G  20% /autodl-fs/data
shm              45G     0   45G   0% /dev/shm
tmpfs           378G     0  378G   0% /proc/asound
...
```

```bash
$ df -h /root/autodl-tmp
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme1n1     50G  7.0G   44G  14% /root/autodl-tmp      # 数据盘：50G

$ mount | grep /root/autodl-tmp
/dev/nvme1n1 on /root/autodl-tmp type xfs (rw,relatime,attr2,inode64,logbufs=8,logbsize=32k,prjquota)
```

> **根分区只有 30 GB，而 `/root/autodl-tmp` 是独立挂载的 50 GB XFS 数据盘。**
> 因此本机把 **Android SDK、Gradle 用户目录、Node 运行时、pnpm store 全部重定向到 `/root/autodl-tmp`**，
> 根分区只承载 apt 装的 JDK。装完并构建一次后根分区仍是 `12G 已用 / 19G 可用`。
> 具体规划与验证方法见 [apk-build-toolchain-install-linux.md](apk-build-toolchain-install-linux.md) 第 2 节。

实测占用分解（`du -sh`）：

| 路径 | 占用 | 说明 |
|---|---|---|
| `/root/autodl-tmp/android-sdk` | **3.0 GB** | `ndk` 2.2G / `platforms` 310M / `build-tools` 298M / `cmdline-tools` 147M / `cmake` 61M / `platform-tools` 22M / `licenses` 36K |
| `/root/autodl-tmp/gradle-home` | **2.3 GB** | `caches` 1.9G（Maven 依赖）+ `wrapper` 286M（gradle-9.6.0 发行版） |
| `/root/autodl-tmp/tools` | 223 MB | Node 22 运行时 |
| `/root/autodl-tmp/.pnpm-store` | 381 MB | pnpm 内容寻址仓库 |
| `<项目>/web-ui/node_modules` | 391 MB | 前端依赖 |
| `<项目>`（整树） | 1.2 GB | 其中 `app/build` 557M、`.gradle` 12M、`terminal/.cxx` 2.9M、`workspace/.cxx` 1.4M |
| `/root/autodl-tmp` 合计 | **7.0 GB / 50 GB（14%）** | `df -h` 实测 |

## 3. 必需工具一览表

| 工具 | 版本 | 绝对路径 | 是否必需 | 备注 |
|---|---|---|---|---|
| JDK | **21.0.13.1**（`21.0.13.1+2-1-22.04.1-Ubuntu`） | `/usr/lib/jvm/java-21-openjdk-amd64` | **必需** | apt 包 `openjdk-21-jdk-headless`；`JAVA_HOME` 指向此处；项目 `jvmTarget = JVM_17` 只是字节码兼容级别 |
| Node.js | **v22.23.5** | `/root/autodl-tmp/tools/node22/bin/node` | **必需** | `:web` 模块 `preBuild` 要跑 `pnpm run build` |
| npm | **10.9.9** | `/root/autodl-tmp/tools/node22/bin/npm` | 必需（安装期） | 随 Node 官方包自带 |
| pnpm | **11.29.0** | `/root/autodl-tmp/tools/node22/bin/pnpm` → `$NODE_HOME/lib/node_modules/pnpm/bin/pnpm.mjs` | **必需** | 由 `npm i -g pnpm@11.29.0` 装入 `$NODE_HOME` |
| corepack | **0.36.0** | `/root/autodl-tmp/tools/node22/bin/corepack` | 非必需 | 随 Node 自带、在 PATH 中可用，但**未启用 pnpm shim**（`corepack which pnpm` 报 `Command not found`）；本机 pnpm 来自 `npm -g` |
| Android SDK 根 | — | `/root/autodl-tmp/android-sdk` | **必需** | `ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 同值 |
| sdkmanager | **12.0** | `/root/autodl-tmp/android-sdk/cmdline-tools/latest/bin/sdkmanager` | **必需** | `source.properties` 中 `Pkg.Path=cmdline-tools;12.0` |
| Gradle | **9.6.0**（wrapper） | `/root/autodl-tmp/gradle-home/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle` | **必需** | 由 `./gradlew` 首次构建自动下载；**无手工安装的 Gradle** |
| Android Gradle Plugin | **9.4.0** | —（Maven 依赖） | **必需** | `gradle/libs.versions.toml:2` |
| Kotlin | **2.4.21** | —（Maven 依赖） | **必需** | `gradle/libs.versions.toml:5` |
| KSP | **2.3.10** | —（Maven 依赖） | **必需** | `gradle/libs.versions.toml:32`；Room 注解处理 |
| NDK | **28.2.13676358**（r28c） | `/root/autodl-tmp/android-sdk/ndk/28.2.13676358` | **必需** | `:terminal` / `:workspace` 的 C++ 编译；AGP 9.4.0 默认版本 |
| CMake（SDK 内置） | **3.22.1**（`3.22.1-g37088a8`） | `/root/autodl-tmp/android-sdk/cmake/3.22.1/bin/cmake` | **必需** | `terminal/build.gradle.kts:11`、`workspace/build.gradle.kts:19` 硬编码 `3.22.1` |
| CMake（系统） | **3.22.1** | `/usr/bin/cmake` | 非必需 | 版本恰好相同，但**AGP 只认 SDK 内那份** |
| ninja（SDK 自带） | **1.10.2** | `/root/autodl-tmp/android-sdk/cmake/3.22.1/bin/ninja` | 必需（已随组件自带） | **系统未安装 ninja**（`which ninja` 无输出），不影响构建 |
| platform-tools | **37.0.3**（adb **1.0.41**） | `/root/autodl-tmp/android-sdk/platform-tools/adb` | **必需** | 安装 APK / 真机调试 |
| platforms | **android-37.2**（手工）+ android-37.0（AGP 自动） | `/root/autodl-tmp/android-sdk/platforms/` | **必需** | `android-37.2` 对应 `compileSdk = release(37){minorApiLevel=2}` |
| build-tools | **37.0.0**（手工）+ 36.0.0（AGP 自动） | `/root/autodl-tmp/android-sdk/build-tools/` | **必需** | 提供 `aapt2` 2.20-15087165、`apksigner` 0.9、`d8`、`zipalign` |
| cmdline-tools | **12.0** | `/root/autodl-tmp/android-sdk/cmdline-tools/latest/` | **必需** | 另含 `avdmanager`、`apkanalyzer`、`lint`、`r8`、`retrace` |
| git | **2.34.1** | `/usr/bin/git` | **必需** | `spotless { ratchetFrom("origin/master") }` 依赖 git 历史 |
| emulator | **未安装** | — | 非必需 | 无 `emulator/`、无 `system-images/`，跑不了模拟器与 instrumentation 测试 |
| curl / unzip / tar | 系统自带 | `/usr/bin/{curl,unzip,tar}` | 安装期必需 | 下载与解包 |

关键版本命令的真实输出：

```bash
$ java -version
openjdk version "21.0.13.1" 2026-11-17
OpenJDK Runtime Environment (build 21.0.13.1+2-1-22.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.13.1+2-1-22.04.1-Ubuntu, mixed mode, sharing)

$ node -v; npm -v; pnpm -v
v22.23.5
10.9.9
11.29.0

$ adb version
Android Debug Bridge version 1.0.41
Version 37.0.3-16887417

$ /root/autodl-tmp/android-sdk/cmake/3.22.1/bin/cmake --version | head -1
cmake version 3.22.1-g37088a8

$ /root/autodl-tmp/android-sdk/build-tools/37.0.0/aapt2 version
Android Asset Packaging Tool (aapt) 2.20-15087165

$ /root/autodl-tmp/android-sdk/build-tools/37.0.0/apksigner version
0.9
```

```bash
$ ./gradlew --version
------------------------------------------------------------
Gradle 9.6.0
------------------------------------------------------------

Build time:    2026-06-18 23:19:03 UTC
Revision:      3f750f03d77e42327c5f9fcb9992110088330a32

Kotlin:        2.3.21
Groovy:        4.0.32
Ant:           Apache Ant(TM) version 1.10.17 compiled on April 6 2026
Launcher JVM:  21.0.13.1 (Ubuntu 21.0.13.1+2-1-22.04.1-Ubuntu)
Daemon JVM:    /usr/lib/jvm/java-21-openjdk-amd64 (no Daemon JVM specified, using current Java home)
OS:            Linux 5.15.0-136-generic amd64
```

> 注意两条容易误读的「版本」：`Kotlin: 2.3.21` 是 **Gradle 内置 Kotlin DSL 的版本**，与本项目编译用的 Kotlin **2.4.21** 无关；`Launcher JVM` 与 `Daemon JVM` 都是 **JDK 21.0.13.1**，说明没有任何 `org.gradle.java.home` 覆盖。

## 4. SDK 组件清单

### 4.1 `sdkmanager --list_installed` 原始输出

```text
$ sdkmanager --list_installed
Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. ...
Installed packages:
  Path                   | Version       | Description                      | Location
  -------                | -------       | -------                          | -------
  build-tools;36.0.0     | 36.0.0        | Android SDK Build-Tools 36       | build-tools/36.0.0
  build-tools;37.0.0     | 37.0.0        | Android SDK Build-Tools 37       | build-tools/37.0.0
  cmake;3.22.1           | 3.22.1        | CMake 3.22.1                     | cmake/3.22.1
  ndk;28.2.13676358      | 28.2.13676358 | NDK (Side by side) 28.2.13676358 | ndk/28.2.13676358
  platform-tools         | 37.0.3        | Android SDK Platform-Tools       | platform-tools
  platforms;android-37.0 | 2             | Android SDK Platform 37.0        | platforms/android-37.0
  platforms;android-37.2 | 2             | Android SDK Platform 37.2        | platforms/android-37.2
```

> 首行那条 `Warning: ... SDK XML versions up to 3 ...` 是 **cmdline-tools 12.0 解析 SDK XML v4 时的固有告警，无害**：安装与构建全部成功。要消掉它只能升级 cmdline-tools。

### 4.2 手工装的 vs AGP 自动补装的

`ls -ld` 的目录 mtime 是直接证据：

| 组件 | 目录 mtime | 来源 |
|---|---|---|
| `cmdline-tools/latest` | **15:08** | 手工下载 zip 解压 |
| `platforms/android-37.2` | **15:09** | 手工 `sdkmanager` |
| `build-tools/37.0.0` | **15:09** | 手工 `sdkmanager` |
| `platform-tools` | **15:09** | 手工 `sdkmanager` |
| `cmake/3.22.1` | **15:09** | 手工 `sdkmanager` |
| `ndk/28.2.13676358` | **15:12** | 手工 `sdkmanager`（单独一步，2.2 GB） |
| `platforms/android-37.0` | **15:31** | **AGP 首次构建自动补装**（构建 15:27:33 启动） |
| `build-tools/36.0.0` | **15:31** | **AGP 首次构建自动补装** |

> 所以「为什么我的 `--list_installed` 比文档多两项」的答案是：**AGP 按需自动补装了**，属正常行为，不需要也不应该手工删掉。

### 4.3 关键 `source.properties`

```properties
# /root/autodl-tmp/android-sdk/cmdline-tools/latest/source.properties
Pkg.Revision=12.0
Pkg.Path=cmdline-tools;12.0
Pkg.Desc=Android SDK Command-line Tools
```

```properties
# /root/autodl-tmp/android-sdk/ndk/28.2.13676358/source.properties
Pkg.Desc = Android NDK
Pkg.Revision = 28.2.13676358
Pkg.BaseRevision = 28.2.13676358
Pkg.ReleaseName = r28c
```

```properties
# /root/autodl-tmp/android-sdk/platforms/android-37.2/source.properties（节选）
Platform.CodeName=
Pkg.Revision=2
AndroidVersion.ApiLevel=37.2
AndroidVersion.ExtensionLevel=24
AndroidVersion.IsBaseSdk=true
Layoutlib.Api=15
```

### 4.4 已接受的许可（`licenses/`，8 个文件，共 36 KB）

```text
android-googletv-license          google-gdk-license
android-googlexr-license          microxr-sysimage-license
android-sdk-arm-dbt-license       mips-android-sysimage-license
android-sdk-license               android-sdk-preview-license
```

> `android-sdk-license` 是 platform / build-tools / NDK / CMake 安装的前置条件。删掉 `licenses/` 会导致下次 `sdkmanager` 重新索要许可。

## 5. 项目构建配置要点

以下全部**现场读取仓库文件**得到，并标注 `文件:行号`。改动请以这些文件为准，本文只是抄录。

### 5.1 版本与工具链

| 配置项 | 值 | 来源 |
|---|---|---|
| Gradle wrapper | **9.6.0** | `gradle/wrapper/gradle-wrapper.properties:4` |
| AGP | **9.4.0** | `gradle/libs.versions.toml:2` |
| Kotlin | **2.4.21** | `gradle/libs.versions.toml:5` |
| KSP | **2.3.10** | `gradle/libs.versions.toml:32` |
| Compose BOM | **2026.09.00** | `gradle/libs.versions.toml:14` |
| Spotless / ktlint | 8.10.3 / 1.8.0 | `gradle/libs.versions.toml`（`spotless` / `ktlint`） |
| Gradle 运行的 JVM | `org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8` | `gradle.properties:9` |

```properties
# gradle/wrapper/gradle-wrapper.properties:4
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
```

```toml
# gradle/libs.versions.toml（节选，行号见上表）
[versions]
agp = "9.4.0"
kotlin = "2.4.21"
composeBom = "2026.09.00"
ksp = "2.3.10"
```

### 5.2 app 模块（`app/build.gradle.kts`）

| 配置项 | 值 | 行号 |
|---|---|---|
| `namespace` | `me.rerere.rikkahub` | `:18` |
| `compileSdk` | `release(37) { minorApiLevel = 2 }` → **android-37.2** | `:19-22` |
| `applicationId` | `me.rerere.rikkahub` | `:26` |
| `minSdk` | **26** | `:27` |
| `targetSdk` | **37** | `:28` |
| `versionCode` | **520** | `:29` |
| `versionName` | **10.0.1** | `:30` |
| `ndk.abiFilters` | `arm64-v8a`、`x86_64` | `:35` |
| ABI splits | `include("arm64-v8a","x86_64")`、`isUniversalApk = true`；构建 `bundle*` 任务时自动关闭 | `:43-47` |
| `signingConfigs.release` | 从 `local.properties` 读 `storeFile`/`storePassword`/`keyAlias`/`keyPassword`（缺任一项则为空） | `:49-68` |
| debug `applicationIdSuffix` | `.debug` → 实际包名 `me.rerere.rikkahub.debug` | `:86` |
| Flavor 维度 | `distribution` | `:93` |
| `github` flavor | `isDefault = true`、`IS_PLAY_STORE = false` | `:95-99` |
| `play` flavor | `IS_PLAY_STORE = true`（关闭自更新入口） | `:100-103` |
| Java 兼容级别 | `sourceCompatibility` / `targetCompatibility` = `VERSION_17` | `:106-107` |
| Kotlin `jvmTarget` | `JVM_17` | `:175` |
| `buildToolsVersion` | 未声明（由 AGP 决定，实测用 37.0.0） | — |
| `ndkVersion` | 未声明（由 AGP 决定，实测用 **28.2.13676358**） | — |

```kotlin
// app/build.gradle.kts:19-30（节选）
compileSdk {
    version = release(37) {
        minorApiLevel = 2
    }
}
defaultConfig {
    applicationId = "me.rerere.rikkahub"
    minSdk = 26
    targetSdk = 37
    versionCode = 520
    versionName = "10.0.1"
}
```

### 5.3 Library 模块统一约定（`build-logic`）

```kotlin
// build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt
extensions.configure<LibraryExtension> {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

> `:web` 模块在自己的脚本里把 `minSdk` 覆写成了 **24**（`web/build.gradle.kts` 的 `defaultConfig { minSdk = 24 }`），其余库模块沿用约定插件的 26。

### 5.4 原生构建（本项目最容易被遗漏的要求）

`terminal` 与 `workspace` 两个模块用 CMake 编译 C++，其余模块为纯 Kotlin/Java：

```kotlin
// terminal/build.gradle.kts:9-11 与 workspace/build.gradle.kts:17-19（内容一致）
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

原生源码：

| 模块 | CMakeLists | 源码 |
|---|---|---|
| `:terminal` | `terminal/src/main/cpp/CMakeLists.txt` | `terminal/src/main/cpp/termux_pty.cpp` |
| `:workspace` | `workspace/src/main/cpp/CMakeLists.txt` | `workspace/src/main/cpp/workspace.cpp` |

> **只装 Android SDK 不够**：必须额外装 SDK 里的 `cmake;3.22.1` 与 `ndk;28.2.13676358`，否则原生模块直接失败。原生编译的中间产物落在 `terminal/.cxx`（2.9 MB）与 `workspace/.cxx`（1.4 MB）。

### 5.5 前端构建（`:web` 模块）

```kotlin
// web/build.gradle.kts:10-18
val buildWebUi = tasks.register<Exec>("buildWebUi") {
    workingDir = webUiDir.asFile
    when {
        Os.isFamily(Os.FAMILY_MAC) -> commandLine("zsh", "-ic", "pnpm run build")
        Os.isFamily(Os.FAMILY_WINDOWS) -> commandLine("cmd", "/c", "pnpm run build")
        else -> commandLine("pnpm", "run", "build")   // ← Linux 走这一支
    }
}

// web/build.gradle.kts:44-45
tasks.named("preBuild") { dependsOn(buildWebUi) }
```

```json
// web-ui/package.json（scripts）
"build": "react-router build && tsx copy.ts"
```

> 因此 **Linux 上 `node` + `pnpm` 是硬依赖**；产物被复制到 `web/src/main/resources/static/`（被该目录下的 `.gitignore` 忽略）。

### 5.6 `gradle.properties`

```properties
# gradle.properties（实际行号见注释）
org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8   # :9
org.gradle.parallel=true                          # :13
android.useAndroidX=true                          # :17
kotlin.code.style=official
android.nonTransitiveRClass=true
org.gradle.configuration-cache=true               # :24
org.gradle.tooling.parallel=true                  # :26
```

> `configuration-cache=true` 是本机增量构建能到 1–4 秒的主要原因。反面影响：**改了 `build.gradle.kts` / `libs.versions.toml` / `gradle.properties` 后第一次构建会稍慢（缓存失效重建）**，属正常。

### 5.7 模块清单（`settings.gradle.kts`，共 14 个）

`:app`、`:ai`、`:search`、`:speech`、`:common`、`:document`、`:web`、`:material3`、`:ui`、`:workspace`、`:terminal`、`:app:baselineprofile`、`:mediagen`、`:oauth`

## 6. 环境变量

### 6.1 `/root/autodl-tmp/android-env.sh`（现场 `cat`，1483 B）

```bash
#!/usr/bin/env bash
# ============================================================
# 统一 Android / Node 构建环境（本文件可被反复 source，幂等）
# 生成于 2026-11-23 (DevOps-Agent)
#   JDK      : OpenJDK 21.0.13.1 (apt, jammy-updates)
#   Node     : v22.23.5  (/root/autodl-tmp/tools/node22)
#   pnpm     : 11.29.0   (npm -g, prefix=/root/autodl-tmp/tools/node22)
# ============================================================

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export NODE_HOME=/root/autodl-tmp/tools/node22
export ANDROID_HOME=/root/autodl-tmp/android-sdk
export ANDROID_SDK_ROOT=/root/autodl-tmp/android-sdk
export GRADLE_USER_HOME=/root/autodl-tmp/gradle-home

# 本机出口代理对 HTTPS 做 MITM（issuer=autodl 自签根证书），
# Node 内置 CA 不信任它 -> npm/pnpm 会报 self-signed certificate in certificate chain。
# 追加系统 CA bundle 后，npm/pnpm/gradle 走代理均可正常校验证书。
if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
    export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
fi

# 幂等追加 PATH：已存在则不重复添加
__yagent_path_add() {
    case ":$PATH:" in
        *":$1:"*) ;;
        *) PATH="$1:$PATH" ;;
    esac
}

__yagent_path_add "$ANDROID_HOME/cmdline-tools/latest/bin"
__yagent_path_add "$ANDROID_HOME/platform-tools"
__yagent_path_add "$NODE_HOME/bin"
__yagent_path_add "$JAVA_HOME/bin"
export PATH

unset -f __yagent_path_add
unset __YAGENT_ENV_LOADED
```

### 6.2 每一项的作用

| 变量 | 值 | 作用 | 不设的后果 |
|---|---|---|---|
| `JAVA_HOME` | `/usr/lib/jvm/java-21-openjdk-amd64` | Gradle daemon 与 Kotlin 编译器的 JVM | `./gradlew` 报 `JAVA_HOME is not set`；或用到系统默认的旧 JDK |
| `NODE_HOME` | `/root/autodl-tmp/tools/node22` | 仅用于拼 PATH（非标准变量） | 无直接影响，但 PATH 追加会失效 |
| `ANDROID_HOME` | `/root/autodl-tmp/android-sdk` | AGP / `sdkmanager` / `adb` 定位 SDK 根 | `SDK location not found` |
| `ANDROID_SDK_ROOT` | 同上 | 老工具链兼容别名 | 老版本工具可能找不到 SDK |
| `GRADLE_USER_HOME` | `/root/autodl-tmp/gradle-home` | Gradle 发行版缓存 + Maven 依赖缓存 | 回落 `~/.gradle`，**会把 2.3 GB 写进 30 GB 根分区**，并重新下载 286 MB 发行版 |
| `NODE_EXTRA_CA_CERTS` | `/etc/ssl/certs/ca-certificates.crt` | 让 Node 信任系统 CA（含出口 MITM 根证书） | 走代理时 npm/pnpm 报 `SELF_SIGNED_CERT_IN_CHAIN` |
| `PATH`（追加 4 条） | `cmdline-tools/latest/bin`、`platform-tools`、`node22/bin`、`java-21/bin` | 让 `sdkmanager`/`adb`/`node`/`pnpm`/`java` 直接可用 | 命令找不到 |

> `__yagent_path_add` 是幂等的核心：先判断 `":$PATH:"` 里是否已含 `":$1:"`，已含就跳过。实测**连续 `source` 两次，`PATH` 无重复项**。

### 6.3 自动加载：`~/.bashrc`

```bash
# ~/.bashrc（末尾两处，内容相同、都幂等）
[ -f /root/autodl-tmp/android-env.sh ] && source /root/autodl-tmp/android-env.sh  # 置于 zsh 之前，确保交互会话生效
...
[ -f /root/autodl-tmp/android-env.sh ] && source /root/autodl-tmp/android-env.sh
```

> **为什么有两处？** 本机 `~/.bashrc` 里有一条**裸 `zsh`**，交互式 bash 执行到那里就切进 zsh，**其后的行不会执行**。所以必须在那行之前也 source 一次。改前备份在 `/root/.bashrc.bak-yagent-20261123`。

### 6.4 为什么 `local.properties` 里还写了 `sdk.dir`（双保险）

```properties
# /root/autodl-tmp/rikkahub/local.properties（全部内容，37 B）
sdk.dir=/root/autodl-tmp/android-sdk
```

| 定位方式 | 优先级 | 适用场景 |
|---|---|---|
| `local.properties` 的 `sdk.dir` | AGP 优先读 | IDE 打开项目、单独调 `./gradlew` 的脚本、非交互式 CI |
| `ANDROID_HOME` | 其次 | 已经 source 过 `android-env.sh` 的交互 shell |
| `ANDROID_SDK_ROOT` | 最后 | 老工具链兼容 |

> 两份都写的好处：**任何入口都能定位 SDK**。注意 `local.properties` 被 `.gitignore:11` 忽略、且 `app/build.gradle.kts:49-68` 还从这里读 release 签名参数，所以它是本地机密文件，**绝不要提交**。

## 7. 快速构建 APK

### 7.1 最简路径（三条命令）

```bash
source /root/autodl-tmp/android-env.sh    # 新开 shell 已自动加载，可省略
cd /root/autodl-tmp/rikkahub
./gradlew assembleGithubDebug
```

> 也可以 `./gradlew :app:assembleGithubDebug`，两者等价（根项目的 `assembleGithubDebug` 由 AGP 任务名匹配委派）。
> **任务名必须带 `Github` flavor**：项目有 `github` / `play` 两个渠道，`assembleDebug` 会报 `Cannot locate tasks that match` / `Ambiguous matches`。

### 7.2 一键脚本 `/root/autodl-tmp/build-apk.sh`（现场 `cat`，907 B）

```bash
#!/usr/bin/env bash
# ============================================================
# RikkaHub Android 一键构建脚本
#   用法：
#     ./build-apk.sh                          # 默认 assembleGithubDebug
#     ./build-apk.sh assembleGithubRelease    # 覆盖默认任务
#     ./build-apk.sh test                     # 跑单元测试
#     ./build-apk.sh :app:assembleGithubDebug --info   # 多参数直接透传给 gradlew
# 产物：rikkahub/app/build/outputs/apk/github/debug/
# ============================================================
set -euo pipefail

source /root/autodl-tmp/android-env.sh
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY || true

cd /root/autodl-tmp/rikkahub

# 前端依赖（首次需要）；如需重建前端，Gradle 的 :web:buildWebUi 会自动执行 pnpm run build

if [ $# -eq 0 ]; then
    set -- assembleGithubDebug
fi

exec ./gradlew "$@" --console=plain
```

它替你做了四件事，所以**推荐直接用它而不是裸 `./gradlew`**：

1. `set -euo pipefail` —— 任一步失败立刻非 0 退出，不会「构建失败但脚本显示成功」；
2. `source android-env.sh` —— 环境变量不会因为 shell 未加载而缺失；
3. `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY` —— 绕开 MITM 代理，走直连（本机直连比代理快约 20 倍）；
4. 无参时默认 `assembleGithubDebug`，有参则**原样透传**给 `gradlew`，因此任何 Gradle 任务/开关都能用。

用法示例：

```bash
cd /root/autodl-tmp
./build-apk.sh                                   # 默认：assembleGithubDebug
./build-apk.sh assembleGithubRelease             # 出 release APK（需先配好 keystore，见第 10 节）
./build-apk.sh test                              # 全模块单元测试
./build-apk.sh :app:compileGithubDebugKotlin     # 只编译 Kotlin（最快的编译校验）
./build-apk.sh :app:assembleGithubDebug --offline # 离线构建
```

### 7.3 构建命令速查表

| 场景 | 命令 |
|---|---|
| 日常 Debug APK | `./gradlew assembleGithubDebug`（或 `:app:assembleGithubDebug`） |
| 快速编译校验（只验证改动能过编译） | `./gradlew :app:compileGithubDebugKotlin` |
| 全量从零（排错用，慢） | `./gradlew clean :app:assembleGithubDebug` |
| Play 渠道 Debug APK | `./gradlew :app:assemblePlayDebug` |
| Play 渠道 AAB（上架用） | `./gradlew :app:bundlePlayRelease` |
| 单模块单元测试 | `./gradlew :app:testGithubDebugUnitTest` |
| 全模块单元测试 | `./gradlew test` |
| Lint | `./gradlew lint` |
| 代码格式（改写 / 只检查） | `./gradlew spotlessApply` / `./gradlew spotlessCheck` |
| 离线构建（依赖缓存完整时） | `./gradlew assembleGithubDebug --offline` |
| 只编原生模块（定位 NDK/CMake 问题） | `./gradlew :terminal:buildCMakeDebug :workspace:buildCMakeDebug` |
| 列出全部变体任务 | `./gradlew :app:tasks --all \| grep -i "^assemble"` |
| 安装到设备 | `adb install -r app/build/outputs/apk/github/debug/app-github-universal-debug.apk` |
| 停掉 daemon（环境变量改动后必做） | `./gradlew --stop` |

> `spotless` 配置了 `ratchetFrom("origin/master")`（`build.gradle.kts:16`），只格式化相对 `origin/master` 改动过的文件，因此**需要可用的 git 远程分支引用**。本机当前工作分支是 `agent/coding-20261123155024`，若 `spotlessCheck` 报找不到 `origin/master`，先 `git fetch origin master`。

### 7.4 产物目录与文件

```text
app/build/outputs/apk/github/debug/
├── app-github-arm64-v8a-debug.apk       61,406,613 B
├── app-github-universal-debug.apk       64,492,809 B
├── app-github-x86_64-debug.apk          61,530,334 B
└── output-metadata.json                       1,003 B
```

| APK | 适用设备 | 字节数 | 约合 |
|---|---|---|---|
| `app-github-arm64-v8a-debug.apk` | 现代真机（arm64） | **61,406,613** | 58.6 MiB |
| `app-github-universal-debug.apk` | 通用（含两种 ABI），方便直接分发 | **64,492,809** | 61.5 MiB |
| `app-github-x86_64-debug.apk` | 模拟器 / x86_64 设备 | **61,530,334** | 58.7 MiB |

`output-metadata.json` 关键字段：

```json
{
  "applicationId": "me.rerere.rikkahub.debug",
  "variantName": "githubDebug",
  "elements": [ { "versionCode": 520, "versionName": "10.0.1", "outputFile": "app-github-universal-debug.apk" }, ... ],
  "minSdkVersionForDexing": 26
}
```

### 7.5 增量构建非常快，全量很慢

| 场景 | 实测结果 | 日志 |
|---|---|---|
| **首次全量**（空缓存） | `BUILD SUCCESSFUL in 17m 31s` / `296 actionable tasks: 296 executed` / `Configuration cache entry stored.` | `build-debug-1.log` |
| **增量：改完代码第一次跑脚本** | `BUILD SUCCESSFUL in 4s` / `296 actionable tasks: 16 executed, 280 up-to-date` / `Configuration cache entry stored.`（`time` 实测 `real 0m4.392s`） | `build-apk-run.log` |
| **增量：完全无改动再跑** | `BUILD SUCCESSFUL in 1s` / `292 actionable tasks: 16 executed, 276 up-to-date` / `Configuration cache entry **reused**`（`real 0m1.358s`） | `build-apk-run2.log` |

> 结论：**日常迭代是秒级**（1–4 秒，主要是确认 up-to-date）。只有 `clean`、改依赖、改 Gradle 脚本、或第一次构建才需要分钟级时间。
> 若你看到「无改动却要 30 秒以上」，通常是 configuration cache 失效或 Gradle daemon 被 `--stop` 过——第二次跑就恢复了。

## 8. 实测验证结果

### 8.1 首次全量构建

```text
BUILD SUCCESSFUL in 17m 31s
296 actionable tasks: 296 executed
Configuration cache entry stored.
```

> 起止时间戳：15:27:33 → 15:45:30。`296 actionable tasks: 296 executed` 说明**没有任何任务被跳过**，是真正的从零全量构建，可作为「这套环境能完整编译本项目」的背书。

### 8.2 `apksigner verify`（三个 APK 逐一实测，结果一致）

```text
$ apksigner verify --verbose app-github-universal-debug.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```

```text
$ apksigner verify --print-certs app-github-universal-debug.apk
V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V2 Signer: certificate SHA-256 digest: 90e28a4f6ce3ef5f6d5d1bbfa3d4f4c106b248d614c9760edc6b7628af4021e3
V2 Signer: certificate SHA-1 digest: e29a12b69df62ad04bbcf46dacb4aca30d8bf21d
V2 Signer: certificate MD5 digest: 192527981b48a90982a14cd84ce5fa54
```

debug 签名来源：

```text
~/.android/debug.keystore   2,618 B（生成于 2026-11-23 15:42）
Alias name: androiddebugkey
Owner: C=US, O=Android, CN=Android Debug
Valid from: Mon Nov 23 15:42:34 CST 2026 until: Wed Nov 15 15:42:34 CST 2056
```

> `minSdk 26` 的包 AGP 默认只签 **v2**，所以 `v1/v3/v4 = false` 是**正确结果**，不是配置缺陷。

### 8.3 `aapt2 dump badging`

```text
$ aapt2 dump badging app-github-universal-debug.apk
package: name='me.rerere.rikkahub.debug' versionCode='520' versionName='10.0.1' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'26'
targetSdkVersion:'37'
uses-permission: name='android.permission.MANAGE_EXTERNAL_STORAGE'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.CAMERA'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
...
application-label:'RikkaHub'
launchable-activity: name='me.rerere.rikkahub.RouteActivity'  label='' icon=''
native-code: 'arm64-v8a' 'x86_64'
```

关键结论对照表：

| 项 | 实测值 | 与配置是否一致 |
|---|---|---|
| package | `me.rerere.rikkahub.debug` | ✅ `applicationId` + `.debug` 后缀（`:26` + `:86`） |
| versionName | `10.0.1` | ✅ `:30` |
| versionCode | `520` | ✅ `:29` |
| minSdkVersion | `26` | ✅ `:27` |
| targetSdkVersion | `37` | ✅ `:28` |
| compileSdkVersion | `37`（`platformBuildVersionCode=37`） | ✅ `:19-22`（minorApiLevel 2 → 平台 37.2） |
| native-code（universal） | `'arm64-v8a' 'x86_64'` | ✅ `abiFilters`（`:35`）+ splits（`:43-47`） |
| native-code（arm64 分包） | `'arm64-v8a'`（仅此一项） | ✅ splits 生效 |

### 8.4 三个 APK 的 SHA-256（现场 `sha256sum` 重算）

```text
bc9cbe7b1fbf17105ef6f15c8d5e0c2d6666c4ce6c65efda6afe51776f3e7dfe  app-github-arm64-v8a-debug.apk
325dc147e1e877a9335ad7aa244efff45c6aae72c450736f2577e34ea0f025ca  app-github-universal-debug.apk
00b87dca21eaf0902b439b3a1b9e95da0d27e3ac293a5e6bdbd5aaf043001f97  app-github-x86_64-debug.apk
```

| APK | 字节数 | SHA-256 |
|---|---|---|
| arm64-v8a | 61,406,613 | `bc9cbe7b1fbf17105ef6f15c8d5e0c2d6666c4ce6c65efda6afe51776f3e7dfe` |
| universal | 64,492,809 | `325dc147e1e877a9335ad7aa244efff45c6aae72c450736f2577e34ea0f025ca` |
| x86_64 | 61,530,334 | `00b87dca21eaf0902b439b3a1b9e95da0d27e3ac293a5e6bdbd5aaf043001f97` |

> **这些哈希只对应 2026-11-23 15:45 那一轮构建的产物**。源码、依赖、签名时间戳任一变化都会导致哈希变化——比较哈希只对「同一份代码在同一台机器上重建」有意义；跨机器验证请以 `apksigner verify` 输出 `Verifies` 和 `aapt2 dump badging` 的 package/version 为准。

## 9. 与 macOS 环境的差异要点

macOS 侧的完整细节见 [apk-build-env.md](apk-build-env.md)，下表只做差异对照（macOS 列的内容全部摘自该文档，未做推测）。

| 维度 | **本机 Linux（本文）** | macOS 版（`apk-build-env.md`） |
|---|---|---|
| 操作系统 | Ubuntu 22.04.3 LTS / 内核 5.15.0-136-generic | macOS 26.4.1（build 25E253） |
| 架构 / 芯片 | x86_64 / Intel Xeon Gold 6330 | arm64 / Apple M1 Pro |
| CPU 核数 / 内存 | 14 核 / 773401 MB（约 755 GiB） | 10 核 / 16 GB |
| 权限模型 | `root`，**无 sudo** | 普通用户 + Homebrew |
| SDK 根路径 | `/root/autodl-tmp/android-sdk`（手动解压 cmdline-tools） | `/opt/homebrew/share/android-commandlinetools`（Homebrew cask 安装） |
| JDK 来源与版本 | apt `openjdk-21-jdk-headless` → **OpenJDK 21.0.13.1**（Ubuntu 构建） | Temurin **25.0.5**（`/Library/Java/JavaVirtualMachines/temurin-25.jdk/...`），另有备用 Temurin **21.0.13** 在用户目录 |
| JDK 是否需切换 | 不需要，单 JDK 直接跑通全部 296 个任务 | 默认 25.0.5；遇兼容问题可切 21.0.13 |
| Node / pnpm | **必需**：Node v22.23.5 + pnpm 11.29.0 | 该文档的工具表**未列入 Node/pnpm**（本表不补充其未记录的内容） |
| Gradle 用户目录 | `/root/autodl-tmp/gradle-home`（重定向到数据盘） | `~/.gradle`（`/Users/ydl/.gradle/...`，未重定向） |
| 磁盘策略 | 系统盘 30 GB / 数据盘 50 GB，**SDK + Gradle 缓存 + Node 全部落数据盘**；安装+构建后 7.0 GB / 50 GB | 单一卷 460 GiB，**199 GiB 可用**，无重定向需求 |
| `local.properties` | **存在**，`sdk.dir=/root/autodl-tmp/android-sdk` | **不存在**，完全依赖 `ANDROID_HOME` |
| 环境变量落点 | `/root/autodl-tmp/android-env.sh` + `~/.bashrc`（两处 source） | `~/.zshrc`（第 9 / 132 / 133 行） |
| platforms | `android-37.2`（手工）+ `android-37.0`（AGP 自动补装） | `android-33`、`android-37.0`、`android-37.2` |
| build-tools | `37.0.0`（手工）+ `36.0.0`（AGP 自动补装） | `35.0.0`、`36.0.0`、`37.0.0` |
| NDK | 仅 `28.2.13676358` | `28.2.13676358`（实际使用）+ `29.0.14206865`（未参与） |
| platform-tools | **37.0.3**（adb 1.0.41） | **37.0.1**（adb 1.0.41） |
| 系统 CMake | `/usr/bin/cmake` 3.22.1（**版本恰好相同**，但 AGP 仍用 SDK 内那份） | `/opt/homebrew/bin/cmake` 4.3.1（**版本不同，不参与构建**） |
| ninja | 系统未装；用 SDK `cmake;3.22.1` 自带的 1.10.2 | 系统未装；用 SDK 自带的 |
| emulator / 模拟器 | **未安装**（无 `emulator/`、无 `system-images/`） | 已装 `emulator 37.2.12` + `system-images;android-33;google_apis;arm64-v8a` |
| Xcode | 不适用 | 仅 Command Line Tools，Android 构建不需要完整 Xcode |
| 本机验证方式 | 编译 + `apksigner` / `aapt2` **静态校验**（无法跑 instrumentation 测试） | 增量构建 + `--offline` 校验；文档记录 `BUILD SUCCESSFUL in 14s`（21 executed / 275 up-to-date） |

> 两个平台的**项目侧配置完全一致**：Gradle wrapper 9.6.0、AGP 9.4.0、Kotlin 2.4.21、KSP 2.3.10、`compileSdk 37.2`、`minSdk 26`、`targetSdk 37`、`versionCode 520`、`versionName 10.0.1` 都来自同一份仓库文件，差别只在**宿主环境**。

## 10. 本机已知限制 / 注意事项

1. **`app/google-services.json` 是占位 mock**
   里面 project number 全 0、API key 是占位串，仅为让 `google-services` / `firebase-crashlytics` 插件通过编译阶段。
   **后果：Firebase Analytics / Crashlytics 在运行时不可用**（上报失败，可能静默）。不要据此判断「Firebase 功能坏了」。
   该文件被 `app/.gitignore:6` 忽略，不入库。

2. **release 构建需要 keystore**
   `app/build.gradle.kts:49-68` 从 `local.properties` 读四个键，缺任一个则 release 签名配置为空、构建会失败：

   ```properties
   # local.properties 追加（仅出 release 时）
   storeFile=/绝对路径/到/release.jks
   storePassword=…
   keyAlias=…
   keyPassword=…
   ```

   **debug 构建不需要这些**，也是本机目前的验证范围。

3. **没有 emulator / system-image，跑不了 instrumentation 测试**
   `androidTest` 相关任务（`connectedAndroidTest` 等）在本机无法执行。本机的验证手段是：
   - 编译通过（`296 actionable tasks: 296 executed`，含 Kotlin/KSP/D8/CMake）；
   - `apksigner verify` 静态校验签名（v2 `true`）；
   - `aapt2 dump badging` 静态校验包名/版本/SDK 级别/ABI。
   需要真机验证时：`adb devices` 确认设备已连上，再 `adb install -r <apk>`。

4. **debug 包的 applicationId 带 `.debug` 后缀**
   `app-github-*-debug.apk` 的包名是 `me.rerere.rikkahub.debug`（`app/build.gradle.kts:86`），
   因此 **debug 包与正式版可以共存**；同时这也意味着 `adb install` 不会覆盖正式版，数据互不影响。

5. **只有 v2 签名**
   debug 包 `v1 = false`、`v3/v4 = false`。对 `minSdk 26` 的目标设备完全够用；
   但如果哪天把 `minSdk` 降到 24 以下，需要留意 v1（JAR signing）的需求。

6. **`debug.keystore` 删不得**
   `~/.android/debug.keystore` 一旦删除重建，debug 包签名指纹就变了，**已安装的旧 debug 包无法覆盖升级**（只能先卸载）。

7. **本机出口有 MITM 代理，直连更快**
   `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY` 后下载速度显著更高（`dl.google.com` 实测 19.7 MB/s vs 代理 0.88 MB/s）。
   `build-apk.sh` 已内置这一步；**手工跑 `./gradlew` 时请自己 unset**。

8. **`maven.google.com` 在本机不可达（503 / 连接失败）**
   但这**不影响构建**：`google()` 仓库实际解析到 `dl.google.com/dl/android/maven2`。详见安装手册第 4.3 节。

9. **configuration cache 是默认开启的**
   `gradle.properties:24` 设了 `org.gradle.configuration-cache=true`。
   好处：增量构建 1–4 秒；代价：改 Gradle 脚本后首次构建会稍慢，且个别插件在 cache 下可能报不兼容（届时可加 `--no-configuration-cache` 临时绕过，但**不要改仓库配置**）。

10. **磁盘余量**
    `/root/autodl-tmp` 当前 7.0 GB / 50 GB（44 GB 可用）。`app/build`（557 MB）、各模块 `build/`、`.cxx` 都可随时删除重生成，删完跑一次构建即可恢复。

11. **无 sudo**
    所有系统级操作只能以 `root` 直接执行；不存在 `sudo` 命令。写脚本时**不要加 `sudo`**。

## 11. 维护提示

**本文的数值都有「事实来源」，改动时请同步改两边：**

| 本文中的数值 | 事实来源 | 需要复核的时机 |
|---|---|---|
| Gradle 9.6.0 | `gradle/wrapper/gradle-wrapper.properties:4` | 升级 wrapper |
| AGP 9.4.0 / Kotlin 2.4.21 / KSP 2.3.10 / Compose BOM 2026.09.00 | `gradle/libs.versions.toml:2/5/32/14` | 升级依赖版本 |
| `compileSdk 37.2` / `minSdk 26` / `targetSdk 37` / `versionCode 520` / `versionName 10.0.1` | `app/build.gradle.kts:19-30` | 发新版 / 提升 targetSdk |
| ABI 列表 `arm64-v8a` + `x86_64` | `app/build.gradle.kts:35`、`:43-47` | 增减 ABI |
| CMake 3.22.1 | `terminal/build.gradle.kts:11`、`workspace/build.gradle.kts:19` | 原生模块改版本 |
| NDK `28.2.13676358` | AGP 9.4.0 的默认 `ndkVersion`（项目未显式声明） | **AGP 升级后必须复核**，默认值可能变 |
| SDK 组件版本（build-tools / platforms / platform-tools） | `sdkmanager --list_installed` 现场输出 | 每次 AGP 升级、每次换机 |
| JDK / Node / pnpm 版本与路径 | `java -version`、`node -v`、`pnpm -v`、`which ...` | 换机、重装工具链 |
| APK 字节数与 SHA-256 | `stat -c %s`、`sha256sum` | 每次重新构建（这些是**快照值**，不追求长期有效） |

**清理与重建边界（安全操作）**：

```bash
# 只删构建产物，不动 SDK / 缓存（下一次构建会重生成）
cd /root/autodl-tmp/rikkahub
./gradlew clean

# 彻底重来（会重新下载 286 MB Gradle 发行版 + 1.9 GB 依赖，慎用）
rm -rf /root/autodl-tmp/gradle-home/caches

# 绝对不要删：SDK 的 licenses/（删了要重新接受许可）、~/.android/debug.keystore（删了签名指纹变）
```

**同步义务**：本文与 [apk-build-toolchain-install-linux.md](apk-build-toolchain-install-linux.md) 共享大量数值（路径、版本、体积）。任一数值变化时**两份一起改**，否则会出现「手册说 21、快照说 25」的矛盾。本文不改变任何构建产物，它只是现有环境的描述。

## 12. 变更记录

| 日期 | 变更内容 | 依据 |
|---|---|---|
| 2026-11-23 | 建立本文档：记录 Ubuntu 22.04.3 / x86_64 上的完整 APK 构建环境（JDK 21.0.13.1、Node v22.23.5、pnpm 11.29.0、Android SDK 7 个组件、Gradle 9.6.0 wrapper、AGP 9.4.0、Kotlin 2.4.21、KSP 2.3.10、NDK r28c、CMake 3.22.1），以及首次全量构建（`BUILD SUCCESSFUL in 17m 31s`）与 `apksigner` / `aapt2` 校验结果 | 当日现场命令采集（`uname` / `df` / `du` / `java -version` / `node -v` / `pnpm -v` / `sdkmanager --list_installed` / `./gradlew --version` / `apksigner` / `aapt2` / `sha256sum`）+ 一次真实 `assembleGithubDebug` 构建 |
