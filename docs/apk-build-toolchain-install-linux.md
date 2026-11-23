# RikkaHub · Linux 从零安装 Android 编译工具链

> 本文是一份「照抄即可复现」的操作手册：在一台干净的 Ubuntu 22.04（Debian 系 x86_64）机器上，
> 从 apt 装 JDK 开始，一路装到 `./gradlew assembleGithubDebug` 产出可安装的 APK。
> 所有命令、路径、版本号、体积与命令输出均来自 2026-11-23 的本机实机执行，可直接用于新机器、CI 容器或换机排错。
> 只想查「这台机器现在装了什么」请看同目录的 [apk-build-env-linux.md](apk-build-env-linux.md)；macOS（Apple Silicon）环境的等价说明见 [apk-build-env.md](apk-build-env.md)。

## 1. 前置说明

### 1.1 目标

装完本文全部步骤后，应当满足：

1. `java -version`、`node -v`、`pnpm -v`、`sdkmanager --version`、`./gradlew --version` 全部可用；
2. `sdkmanager --list_installed` 能看到 platform / build-tools / platform-tools / NDK / CMake 五类组件；
3. `cd <项目根> && ./gradlew assembleGithubDebug` 能 `BUILD SUCCESSFUL`，并在 `app/build/outputs/apk/github/debug/` 下产出 3 个 APK；
4. `apksigner verify --verbose <apk>` 输出 `Verifies`。

**本文只管「让编译链跑通」**，不涉及发布签名、不上架、不配 CI。

### 1.2 适用系统

| 项 | 要求 / 本机实测值 |
|---|---|
| 发行版 | Ubuntu 22.04+ / Debian 系（apt 可用）；本机 **Ubuntu 22.04.3 LTS（jammy）** |
| 架构 | x86_64（本文所有预编译包均取 `linux-x64`）；本机 `uname -m` → `x86_64` |
| 内核 | 本机 `5.15.0-136-generic` |
| CPU | 本机 `Intel(R) Xeon(R) Gold 6330 CPU @ 2.00GHz` × **14** 核 |
| 内存 | 本机 **773401 MB**（约 755 GiB），实测首次全量构建峰值无压力 |
| 用户 | 需要 **root**；本机为 `root` 且**没有 sudo**（`which sudo` 无输出），故所有命令都不带 `sudo` |
| 外网 | 需能访问 `dl.google.com`、`repo1.maven.org`、`registry.npmjs.org`、`nodejs.org`、`services.gradle.org` |

> 若不是 root：把本文所有写入 `/root/autodl-tmp/...` 的路径换成你有写权限的大容量目录即可（如 `/opt/android-sdk`、`$HOME/android-sdk`），其余不变。

### 1.3 磁盘与耗时预估

首次全量装完并构建一次的真实占用（本机实测，详见第 2.4 节）：

| 类别 | 实测占用 |
|---|---|
| Android SDK 根（含 NDK） | **3.0 GB** |
| Gradle 用户目录（wrapper 发行版 + 依赖缓存） | **2.3 GB** |
| Node 22 运行时（解压后） | **223 MB** |
| pnpm store | 381 MB |
| 仓库整树（含 `web-ui/node_modules` 391 MB、`app/build` 557 MB 等） | 1.2 GB |
| **合计** | **约 7.1 GB**（与 `df -h /root/autodl-tmp` 的 `7.0G` 一致，差额为文件系统统计粒度） |

> **规划建议：准备 ≥ 12 GB 可用空间**，并且**不要把 SDK 和 Gradle 缓存放在根分区**（原因见第 2 节）。

耗时（本机实测时间戳）：

| 阶段 | 实测 |
|---|---|
| apt 装 JDK | 14:57:57 → 14:58:02（**5 秒**） |
| Node 解压落盘 → 环境变量脚本就绪 | 15:02:53 → 15:06:59 |
| SDK 组件安装（含 2.2 GB 的 NDK） | 15:08 → 15:12（**约 4 分钟**，直连 17 MB/s 量级） |
| 首次 `./gradlew assembleGithubDebug` | 15:27:33 → 15:45:30 = **17m 31s** |
| **从装 JDK 到拿到 APK** | **约 48 分钟** |

> 耗时的方差几乎全部来自下载带宽。依赖缓存（`GRADLE_USER_HOME`、`.pnpm-store`）一旦建好，后续增量构建是秒级（本机实测 1–4 秒）。

## 2. 磁盘规划（本机最重要的坑）

### 2.1 症状

本机（以及绝大多数 Autodl / 云容器镜像）的磁盘布局是「系统盘小、数据盘大」：

```bash
$ df -h
Filesystem      Size  Used Avail Use% Mounted on
overlay          30G   12G   19G  40% /            # ← 根分区只有 30G
/dev/nvme1n1     50G  7.0G   44G  14% /root/autodl-tmp   # ← 数据盘 50G，且挂在 /root 下
```

而 Android 编译链的默认落点是**全在根分区**：

| 默认位置 | 内容 | 规模 |
|---|---|---|
| `~/Android/Sdk` 或 `$ANDROID_HOME` | SDK 本体 + NDK | 3.0 GB（NDK 独占 2.2 GB） |
| `~/.gradle` | Gradle 发行版 + 全部 Maven 依赖 | 2.3 GB |
| `~/.npm` / pnpm store | 前端依赖缓存 | 0.4 GB |

> 如果按默认位置装，30 GB 根分区在装完 SDK + NDK + 首次构建缓存后只剩个位数 GB，**下一次 `clean build` 很容易 `ENOSPC`**。本机根分区在完成全部安装后仍是 `12G 已用 / 19G 可用`——正是因为下面这套重定向。

### 2.2 方案：把工具链全部重定向到大容量盘

核心就三个变量：

```bash
export ANDROID_HOME=/root/autodl-tmp/android-sdk      # SDK 根（含 NDK / CMake / build-tools）
export ANDROID_SDK_ROOT="$ANDROID_HOME"               # 兼容老工具链的别名
export GRADLE_USER_HOME=/root/autodl-tmp/gradle-home  # Gradle 发行版 + 依赖缓存
```

配套地：

- Node 不要用 apt / nvm 装到 `~` 下，改成**解压到数据盘**：`/root/autodl-tmp/tools/node22`；
- pnpm store 用 `pnpm config set store-dir /root/autodl-tmp/.pnpm-store` 或靠 `PNPM_HOME` 派生；
- `local.properties` 里再写一份 `sdk.dir`（双保险，见步骤 7）。

> `ANDROID_HOME` 必须在**任何** `sdkmanager` / Gradle 调用之前生效。`GRADLE_USER_HOME` 同样——它决定了 Gradle 从哪里找 wrapper 发行版，换值等于「重新下载一遍 286 MB」。

### 2.3 自查方法

```bash
# 1) 看两个挂载点各自的余量
df -h / /root/autodl-tmp

# 2) 确认数据盘确实是独立挂载（不是根分区的软链）
mount | grep /root/autodl-tmp
# 预期：/dev/nvme1n1 on /root/autodl-tmp type xfs (rw,...,prjquota)

# 3) 装完后确认占用落在数据盘
du -sh /root/autodl-tmp/android-sdk /root/autodl-tmp/gradle-home
```

> 若你的机器没有独立数据盘，只有一个大根分区，那就不需要重定向——直接把 `ANDROID_HOME` / `GRADLE_USER_HOME` 指到任何空间足够的位置即可，本文其余步骤不变。

### 2.4 本机实测占用分解

```bash
$ du -sh /root/autodl-tmp/android-sdk/*
2.2G    /root/autodl-tmp/android-sdk/ndk
310M    /root/autodl-tmp/android-sdk/platforms
298M    /root/autodl-tmp/android-sdk/build-tools
147M    /root/autodl-tmp/android-sdk/cmdline-tools
61M     /root/autodl-tmp/android-sdk/cmake
22M     /root/autodl-tmp/android-sdk/platform-tools
36K     /root/autodl-tmp/android-sdk/licenses

$ du -sh /root/autodl-tmp/gradle-home/*
1.9G    /root/autodl-tmp/gradle-home/caches      # Maven 依赖
286M    /root/autodl-tmp/gradle-home/wrapper     # gradle-9.6.0-bin 发行版
680K    /root/autodl-tmp/gradle-home/native
204K    /root/autodl-tmp/gradle-home/daemon
```

> `platforms` 里有两个版本（`android-37.0` 与 `android-37.2`）、`build-tools` 里有两个版本（`36.0.0` 与 `37.0.0`）：其中 `android-37.2` + `build-tools;37.0.0` 是手工装的，`android-37.0` + `build-tools;36.0.0` 是 AGP 首次构建时**自动补装**的（见第 5 节对应条目）。

## 3. 分步安装

九步总览：

| 步骤 | 做什么 | 落盘位置 | 实测体积 / 耗时 |
|---|---|---|---|
| 1 | 装 JDK 21 | `/usr/lib/jvm/java-21-openjdk-amd64` | apt 包约 286 MB / 5 秒 |
| 2 | 装 Node 22 + pnpm 11 | `/root/autodl-tmp/tools/node22` | 223 MB（tarball 31,062,912 B） |
| 3 | 装 Android cmdline-tools | `<SDK>/cmdline-tools/latest` | 147 MB（zip 153,607,504 B） |
| 4 | 接受 SDK 许可 | `<SDK>/licenses/` | 8 个 license 文件 |
| 5 | 装 SDK 组件 | `<SDK>/{platforms,build-tools,platform-tools,ndk,cmake}` | 约 2.6 GB / 4 分钟 |
| 6 | 写环境变量脚本并挂到 `~/.bashrc` | `/root/autodl-tmp/android-env.sh` | 1.5 KB |
| 7 | 项目侧准备（`local.properties` / mock / 前端依赖） | 项目根 | 前端 `node_modules` 391 MB |
| 8 | 首次构建 | `app/build/` | 557 MB / 17m31s |
| 9 | 产物验证 | — | 3 个 APK，合计约 183 MB |

下文每一步都给出 **命令 → 目的 → 验证命令 → 预期输出**。

### 步骤 1：安装 JDK 21（apt）

```bash
apt-get update
apt-get install -y openjdk-21-jdk-headless
```

**目的**：提供 Gradle daemon、Kotlin 编译器、`keytool`、`apksigner`（脚本形式，依赖 JVM）所需的 JVM。

**说明：项目到底要 JDK 几？** 这是最容易混淆的一点：

| 概念 | 本项目取值 | 出处 |
|---|---|---|
| `sourceCompatibility` / `targetCompatibility` | `JavaVersion.VERSION_17` | `app/build.gradle.kts:106`；`build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt` |
| Kotlin `jvmTarget` | `JvmTarget.JVM_17` | `app/build.gradle.kts:175` |
| **运行 Gradle/AGP 的 JDK** | **21**（本机） | 本机实测 |

> 也就是说：**Java 17 只是「编译产物的字节码兼容级别」**，不是「要装的 JDK 版本」。AGP 9.x / Gradle 9.x 自身要求较新的 JDK，JDK 17 已不被推荐；本机实测 **JDK 21.0.13.1 可直接跑通全部 296 个任务**，无需额外配置 `org.gradle.java.home`。

**验证命令与预期输出**：

```bash
$ java -version
openjdk version "21.0.13.1" 2026-11-17
OpenJDK Runtime Environment (build 21.0.13.1+2-1-22.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.13.1+2-1-22.04.1-Ubuntu, mixed mode, sharing)

$ javac -version
javac 21.0.13.1

$ echo $JAVA_HOME
/usr/lib/jvm/java-21-openjdk-amd64

$ which java
/usr/lib/jvm/java-21-openjdk-amd64/bin/java
```

> `openjdk-21-jdk-headless` 会连带装上 `openjdk-21-jre-headless`（`dpkg -l | grep openjdk` 可见两条）。headless 版没有 GUI 相关依赖，服务器上体积更小，构建完全够用。

### 步骤 2：安装 Node 22 + pnpm 11

**为什么必须装 Node？** 因为 `:web` 模块不是纯 Kotlin——它的 `preBuild` 任务依赖一个 `Exec` 任务 `buildWebUi`，而该任务在 Linux 上执行的就是 `pnpm run build`：

```kotlin
// web/build.gradle.kts:10-18、:44-45
val buildWebUi = tasks.register<Exec>("buildWebUi") {
    workingDir = webUiDir.asFile
    when {
        Os.isFamily(Os.FAMILY_MAC) -> commandLine("zsh", "-ic", "pnpm run build")
        Os.isFamily(Os.FAMILY_WINDOWS) -> commandLine("cmd", "/c", "pnpm run build")
        else -> commandLine("pnpm", "run", "build")
    }
}
tasks.named("preBuild") { dependsOn(buildWebUi) }
```

> **没有 `node` + `pnpm`，`:web:preBuild` 直接失败，整个 `assembleGithubDebug` 连编译都进不去。** 这不是可选依赖。

安装（官方 tar.xz 解压到数据盘，不用 apt、不用 nvm）：

```bash
export NODE_HOME=/root/autodl-tmp/tools/node22
mkdir -p "$NODE_HOME"

NODE_VER=v22.23.5
curl -fL -o /tmp/node.tar.xz \
  "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-x64.tar.xz"

# 可选但推荐：校验完整性（官方 SHASUMS256.txt）
curl -fsSL "https://nodejs.org/dist/${NODE_VER}/SHASUMS256.txt" -o /tmp/SHASUMS256.txt
grep "node-${NODE_VER}-linux-x64.tar.xz" /tmp/SHASUMS256.txt | sha256sum -c -

# 解压到临时目录后「提层」，使 $NODE_HOME/bin/node 成立
rm -rf /tmp/node-extract && mkdir -p /tmp/node-extract
tar -xJf /tmp/node.tar.xz -C /tmp/node-extract --strip-components=1
cp -a /tmp/node-extract/. "$NODE_HOME/"

export PATH="$NODE_HOME/bin:$PATH"

# 全局装 pnpm 11（装进 $NODE_HOME/lib/node_modules，bin 落在 $NODE_HOME/bin）
npm install -g pnpm@11.29.0
```

**目的**：提供 `node` / `npm` / `pnpm` 三个可执行文件，供 `:web` 模块的 `pnpm run build` 使用。

**替代方案（corepack）**：Node 官方完整包自带 `corepack`（本机版本 **0.36.0**，位于 `$NODE_HOME/bin/corepack`，已随 `$NODE_HOME/bin` 进入 PATH），但**本机没有用它启用 pnpm shim**——`which pnpm` 解析到的是 `$NODE_HOME/lib/node_modules/pnpm/bin/pnpm.mjs`（即 `npm -g` 装的那份）。若偏好用 corepack 管理 pnpm 版本：

```bash
corepack enable pnpm && corepack prepare pnpm@11.29.0 --activate
```

> 二者选一即可，**不要同时用**——corepack 生成的 shim 与 `npm -g` 装出的 `pnpm` 会争抢 `$NODE_HOME/bin/pnpm` 这个名字。本机采用后者（`npm -g`），故 `corepack which pnpm` 会报 `Command not found`。

**验证命令与预期输出**：

```bash
$ node -v
v22.23.5

$ npm -v
10.9.9

$ pnpm -v
11.29.0

$ which node npm pnpm
/root/autodl-tmp/tools/node22/bin/node
/root/autodl-tmp/tools/node22/bin/npm
/root/autodl-tmp/tools/node22/bin/pnpm
```

> 校验参考：本机 `$NODE_HOME/bin/node` 二进制的 SHA-256 为
> `2f822d544601dfff107f57d945b649d15c5bf6302595735a6bd9cdc7ee9669bd`（v22.23.5 / linux-x64）。
> 官方 tarball 大小 31,062,912 B。

### 步骤 3：安装 Android cmdline-tools（注意目录层级）

```bash
export ANDROID_HOME=/root/autodl-tmp/android-sdk
mkdir -p "$ANDROID_HOME/cmdline-tools"

curl -fL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
# 本机实测：HTTP 200，Content-Length = 153,607,504

rm -rf /tmp/cmdline-tools /tmp/cmdline-unzip
mkdir -p /tmp/cmdline-unzip
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-unzip
```

**【最常见的坑】解压后必须落到 `latest` 这一层**。zip 内部的顶层目录名是 `cmdline-tools/`，而 `sdkmanager` 要求自身路径严格是：

```text
<SDK>/cmdline-tools/latest/bin/sdkmanager
```

因此正确做法是「先解到临时目录，再把内层提上来」：

```bash
# ✅ 正确做法：解压产物顶层就是 cmdline-tools/，把它整体搬成 .../cmdline-tools/latest
mv /tmp/cmdline-unzip/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
```

```text
✅ 结构正确（sdkmanager 能跑）
<SDK>/cmdline-tools/latest/bin/sdkmanager
<SDK>/cmdline-tools/latest/lib/...

❌ 结构错误（sdkmanager 报 "Could not determine SDK root"，或 shell 找不到命令）
<SDK>/cmdline-tools/cmdline-tools/bin/sdkmanager      ← 多套了一层目录名
<SDK>/cmdline-tools/bin/sdkmanager                    ← 少了 latest 这一层
```

**目的**：`cmdline-tools` 提供 `sdkmanager`、`avdmanager`、`apkanalyzer`、`lint`、`d8`、`r8`、`retrace` 等命令行工具；它是整个 SDK 的「包管理器入口」，必须先有它才能装下面的组件。

**验证命令与预期输出**：

```bash
$ ANDROID_HOME=/root/autodl-tmp/android-sdk
$ "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --version
12.0

$ cat "$ANDROID_HOME/cmdline-tools/latest/source.properties"
Pkg.Revision=12.0
Pkg.Path=cmdline-tools;12.0
Pkg.Desc=Android SDK Command-line Tools
```

> 版本说明：本机装的是 **cmdline-tools 12.0**（对应 zip `commandlinetools-linux-11076708_latest.zip`）。官方仓库索引（`https://dl.google.com/android/repository/repository2-3.xml`）当前最新已是 `cmdline-tools;23.0`（zip `commandlinetools-linux-16111833_latest.zip`），但**本机未升级**：12.0 已实测可完整装齐本文全部组件。换新版本时只需改 zip 文件名，目录层级规则不变。

### 步骤 4：接受 SDK 许可

```bash
export ANDROID_HOME=/root/autodl-tmp/android-sdk
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  --sdk_root="$ANDROID_HOME" --licenses
```

**目的**：`sdkmanager` 安装任何组件前都要求对应许可已被接受。不先做这一步，步骤 5 会卡在交互式许可提示上（非交互环境直接失败）。

> `yes |` 是把 `y` 无限次喂给许可提示的标准写法。末尾应看到 `All SDK package licenses accepted.`

**验证命令与预期输出**：

```bash
$ ls "$ANDROID_HOME/licenses/"
android-googletv-license          google-gdk-license
android-googlexr-license          microxr-sysimage-license
android-sdk-arm-dbt-license       mips-android-sysimage-license
android-sdk-license               android-sdk-preview-license
```

> 本机 `licenses/` 下共 **8 个**文件（合计 36 KB）。只要 `android-sdk-license` 在，platform / build-tools / NDK / CMake 的安装就不会再被阻塞。

### 步骤 5：安装 SDK 组件

```bash
export ANDROID_HOME=/root/autodl-tmp/android-sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "platforms;android-37.2" \
  "build-tools;37.0.0" \
  "platform-tools" \
  "ndk;28.2.13676358" \
  "cmake;3.22.1"
```

**目的**：装齐「编译 SDK」「打包工具」「原生编译链」「设备通信工具」。

**每个组件为什么必需**：

| 组件 | 本机版本 | 为什么必需 | 依据 |
|---|---|---|---|
| `platforms;android-37.2` | 37.2（`AndroidVersion.ApiLevel=37.2`、`ExtensionLevel=24`） | 项目 `compileSdk` 写的是 `release(37) { minorApiLevel = 2 }`，AGP 会去找 **android-37.2** 这个平台 | `app/build.gradle.kts:19-22` |
| `build-tools;37.0.0` | 37.0.0 | 提供 `aapt2`、`apksigner`、`d8`、`zipalign` 等打包/签名工具 | `app/build.gradle.kts` 未声明 `buildToolsVersion`，由 AGP 9.4.0 选择 |
| `platform-tools` | 37.0.3（adb **1.0.41**） | `adb install` / `adb devices` / logcat 调试 | 真机安装与日常调试 |
| `ndk;28.2.13676358` | 28.2.13676358（r28c） | `:terminal` 与 `:workspace` 有 C++ 源码（`src/main/cpp/CMakeLists.txt` + `.cpp`），必须交叉编译出 `.so` | 项目未声明 `ndkVersion`，AGP 9.4.0 的默认值即 **28.2.13676358** |
| `cmake;3.22.1` | 3.22.1（`cmake version 3.22.1-g37088a8`） | 上述两个模块**硬编码** `version = "3.22.1"` | `terminal/build.gradle.kts:9-11`、`workspace/build.gradle.kts:17-19` |

> `cmake;3.22.1` 组件自带 `bin/ninja`（本机 `1.10.2`），**不需要单独装系统 ninja**。本机 `/usr/bin/cmake` 恰好也是 3.22.1，但那纯属巧合，AGP 用的是 SDK 内的 `.../android-sdk/cmake/3.22.1/bin/cmake`。

**验证命令与预期输出**：

```bash
$ "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed
[===============================] 100% Fetch remote repository...
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

**两条必须知道的预期行为**：

1. **`platforms;android-37.0` 与 `build-tools;36.0.0` 不是手工装的**——它们是**首次 `./gradlew assembleGithubDebug` 时 AGP 自动补装**的。本机时间戳可佐证：手工装的四类组件目录是 `15:09`（NDK 是 `15:12`），而 `platforms/android-37.0` 与 `build-tools/36.0.0` 都是 `15:31`，首次构建 15:27:33 启动。**属于正常现象，不是装错了。**
2. **stderr 会出现一条 SDK XML 版本警告**：

   ```text
   Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of
   version 4 was encountered. This can happen if you use versions of Android Studio and the
   command-line tools that were released at different times.
   ```

   **无害**：cmdline-tools 12.0 的解析上限是 SDK XML v3，而新组件用的是 v4。实测安装与构建全部成功。要彻底消掉这条警告就升级 cmdline-tools（见步骤 3 的版本说明）。

### 步骤 6：写环境变量脚本（幂等）

**目的**：把步骤 1–5 的所有路径一次性、可重复地灌进 shell 环境；并保证脚本被反复 `source` 时 `PATH` 不会无限增长。

把下面内容存为 `/root/autodl-tmp/android-env.sh`（本机文件 1483 B）：

```bash
#!/usr/bin/env bash
# ============================================================
# 统一 Android / Node 构建环境（本文件可被反复 source，幂等）
#   JDK      : OpenJDK 21.0.13.1 (apt, jammy-updates)
#   Node     : v22.23.5  (/root/autodl-tmp/tools/node22)
#   pnpm     : 11.29.0   (npm -g, prefix=/root/autodl-tmp/tools/node22)
# ============================================================

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export NODE_HOME=/root/autodl-tmp/tools/node22
export ANDROID_HOME=/root/autodl-tmp/android-sdk
export ANDROID_SDK_ROOT=/root/autodl-tmp/android-sdk
export GRADLE_USER_HOME=/root/autodl-tmp/gradle-home

# 若出口有 MITM 代理（自签根证书），Node 内置 CA 不信任它 ->
# npm/pnpm 会报 self-signed certificate in certificate chain。
# 追加系统 CA bundle 后，npm/pnpm 走代理也能正常校验证书。
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
```

挂到 `~/.bashrc`：

```bash
echo '[ -f /root/autodl-tmp/android-env.sh ] && source /root/autodl-tmp/android-env.sh' >> ~/.bashrc
```

> **`~/.bashrc` 陷阱（本机真实踩到）**：本机 `~/.bashrc` 里本来就有一条**裸 `zsh`** 命令，交互式 bash 会切进 zsh 并**阻塞其后所有行**。所以只在文件末尾追加 `source` 是不生效的——必须在 `zsh` 那一行**之前**再插一份同样的幂等 `source`，末尾那份保留作为兜底。改前备份为 `/root/.bashrc.bak-yagent-20261123`。
>
> 如果你的 `~/.bashrc` 里没有裸 `zsh`，末尾追加一行就够了。

**验证命令与预期输出**：

```bash
$ bash -n /root/autodl-tmp/android-env.sh && echo "语法 OK"
语法 OK

# 幂等性：连续 source 两次，PATH 不应出现重复项
$ source /root/autodl-tmp/android-env.sh && source /root/autodl-tmp/android-env.sh
$ echo "$PATH" | tr ':' '\n' | sort | uniq -d
                                       # ← 应无输出（无重复）

$ echo $ANDROID_HOME $JAVA_HOME $GRADLE_USER_HOME
/root/autodl-tmp/android-sdk /usr/lib/jvm/java-21-openjdk-amd64 /root/autodl-tmp/gradle-home

$ which sdkmanager adb node pnpm java
/root/autodl-tmp/android-sdk/cmdline-tools/latest/bin/sdkmanager
/root/autodl-tmp/android-sdk/platform-tools/adb
/root/autodl-tmp/tools/node22/bin/node
/root/autodl-tmp/tools/node22/bin/pnpm
/usr/lib/jvm/java-21-openjdk-amd64/bin/java
```

> 新开一个交互 shell 也应直接生效（`echo $ANDROID_HOME` 有值）。若没有，先检查 `~/.bashrc` 是否被裸 `zsh`/`exec` 之类提前截断。

### 步骤 7：项目侧准备（三个「不入库但必需」的东西）

#### 7.1 `local.properties`：告诉 Gradle 去哪找 SDK

```bash
cd /root/autodl-tmp/rikkahub
printf 'sdk.dir=/root/autodl-tmp/android-sdk\n' > local.properties
cat local.properties
```

```properties
sdk.dir=/root/autodl-tmp/android-sdk
```

**目的**：AGP 定位 SDK 的顺序是「`local.properties` 的 `sdk.dir` → `ANDROID_HOME` → `ANDROID_SDK_ROOT`」。写一份 `local.properties` 是**双保险**：即使某个 shell / IDE 没 source 环境变量，构建也不会因为找不到 SDK 而失败。

> 本机 `local.properties` 只有这一行（37 B）。它已被仓库根 `.gitignore:11` 忽略（`git check-ignore -v local.properties` → `.gitignore:11:local.properties`），**不会也不应该进版本库**；同理，`app/build.gradle.kts:49-68` 会从这个文件里读取 release 签名参数（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），所以这个文件天然属于「本地机密」。

#### 7.2 `app/google-services.json`：必需，但只是占位 mock

`app/build.gradle.kts:12-13` 应用了 `libs.plugins.google.services` + `libs.plugins.firebase.crashlytics`。**这两个插件在配置阶段就要求 `google-services.json` 存在且能被解析**，否则 `./gradlew` 连 configuration 都过不去（报 `File google-services.json is missing`）。

该文件被 `app/.gitignore:6` 忽略，所以仓库里没有，必须本地自己放一份。最小结构（占位值，仅供插件通过，**不要提交、不要用于任何真实环境**）：

```text
app/google-services.json
├── project_info
│   ├── project_number  : 全 0（占位项目号）
│   ├── project_id      : 任意本地标识，例如 rikkahub-local-build
│   └── storage_bucket  : 与 project_id 配套的占位值
├── client[]            : 每个 applicationId 一条，本项目需要 2 条
│   ├── [0] client_info.android_client_info.package_name = me.rerere.rikkahub
│   └── [1] client_info.android_client_info.package_name = me.rerere.rikkahub.debug
│         （debug 因 applicationIdSuffix = ".debug" 而成为独立包名，见 app/build.gradle.kts:86）
├── client[].client_info.mobilesdk_app_id : 形如 1:<数字>:android:<十六进制> 的占位串
├── client[].api_key[].current_key        : 占位字符串
└── configuration_version : "1"
```

> ⚠️ **Firebase 运行时一定失效**。这份 mock 里的项目号、app id、API key 全是假的：Analytics / Crashlytics 上报会失败（静默或日志报错），**不要据此判断「Firebase 功能坏了」**。要让 Firebase 真正可用，必须从 Firebase 控制台下载真实 `google-services.json` 覆盖它——注意它同样被 gitignore，仍然不会入库。

#### 7.3 `web-ui` 前端依赖

```bash
cd /root/autodl-tmp/rikkahub/web-ui
pnpm install --frozen-lockfile
```

**目的**：`:web` 的 `preBuild` 会跑 `pnpm run build`（即 `react-router build && tsx copy.ts`，见 `web-ui/package.json`）。没有 `node_modules`，该 `Exec` 任务必然失败。`--frozen-lockfile` 保证依赖版本与 `pnpm-lock.yaml` 完全一致，避免「本机能编、CI 编不过」。

**验证命令与预期输出**：

```bash
$ du -sh node_modules
391M    node_modules

$ pnpm run build
# ... react-router build 输出 ...
# Build output copied successfully!
$ ls -la ../web/src/main/resources/static
index.html  favicon.ico  assets/
```

> `web/src/main/resources/static/` 被 `web/src/main/resources/.gitignore` 的 `static/` 规则忽略，属构建产物，不入库。构建时 `:web:buildWebUi` 也会自动重跑（它有 `inputs`/`outputs` 声明，命中缓存就跳过）。

### 步骤 8：首次构建

```bash
source /root/autodl-tmp/android-env.sh
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY   # 本机关键，原因见第 4 节
cd /root/autodl-tmp/rikkahub
./gradlew assembleGithubDebug
```

**目的**：产出 debug 渠道的全部 APK。

**首次构建会做三件「额外」的事，耗时长属正常**：

1. **下载 Gradle 9.6.0 发行版**（`gradle-9.6.0-bin.zip`，解压后 286 MB）到
   `$GRADLE_USER_HOME/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/`。
   本机是通过 `./gradlew` 自动下载的，**无需手工装 Gradle**。
2. **下载全部 Maven 依赖**（本机最终 `$GRADLE_USER_HOME/caches` 达 1.9 GB）。
3. **AGP 自动补装 `platforms;android-37.0` + `build-tools;36.0.0`**（见步骤 5 的两条预期行为）。

> **任务名必须带 flavor**：项目有 `github` / `play` 两个渠道（`app/build.gradle.kts:93-103`），
> 所以是 `assembleGithubDebug` 而**不是** `assembleDebug`。写错会得到
> `Cannot locate tasks that match ':app:assembleDebug'` 或 `Ambiguous matches`。

**验证命令与预期输出**（本机首次全量构建的真实尾部）：

```text
> Task :app:processGithubDebugJavaRes
> Task :app:mergeGithubDebugJavaResource
> Task :app:compileGithubDebugJavaWithJavac
> Task :app:dexBuilderGithubDebug
> Task :app:mergeProjectDexGithubDebug
> Task :app:packageGithubDebug
> Task :app:assembleGithubDebug
> Task :app:createGithubDebugApkListingFileRedirect

[Incubating] Problems report is available at: file:///root/autodl-tmp/rikkahub/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 17m 31s
296 actionable tasks: 296 executed
Configuration cache entry stored.
```

```bash
$ ./gradlew --version | head -12
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

> `[Incubating] Problems report` 是 Gradle 9 对「有告警但构建成功」的常规提示，**不是错误**。
> 编译期还会刷出若干 Kotlin `w:` 弃用告警（`ACTION_MEDIA_SCANNER_SCAN_FILE`、`WifiInfo` 等），同样不影响 `BUILD SUCCESSFUL`。

### 步骤 9：产物验证

```bash
cd /root/autodl-tmp/rikkahub
ls -la app/build/outputs/apk/github/debug/
```

```text
-rw-r--r-- 1 root root 61406613  app-github-arm64-v8a-debug.apk
-rw-r--r-- 1 root root 64492809  app-github-universal-debug.apk
-rw-r--r-- 1 root root 61530334  app-github-x86_64-debug.apk
-rw-r--r-- 1 root root     1003  output-metadata.json
```

#### 9.1 `apksigner verify --verbose`：确认签名有效

```bash
BT=$ANDROID_HOME/build-tools/37.0.0
$BT/apksigner verify --verbose app/build/outputs/apk/github/debug/app-github-universal-debug.apk
```

```text
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

> 首行 `Verifies` 即校验通过。debug 包只走 **v2** 方案（AGP 默认对 `minSdk 26` 的包不启 v1/v3），所以 `v2: true`、其余 `false` 是**正常且正确**的结果。三个 APK 本机均逐个实测为同一结果。

如需查看签名证书：

```bash
$ $BT/apksigner verify --print-certs app/build/outputs/apk/github/debug/app-github-universal-debug.apk
V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V2 Signer: certificate SHA-256 digest: 90e28a4f6ce3ef5f6d5d1bbfa3d4f4c106b248d614c9760edc6b7628af4021e3
V2 Signer: certificate SHA-1 digest: e29a12b69df62ad04bbcf46dacb4aca30d8bf21d
V2 Signer: certificate MD5 digest: 192527981b48a90982a14cd84ce5fa54
```

> 证书是 `~/.android/debug.keystore` 自动生成的（本机 `2618 B`，alias `androiddebugkey`，有效期 `2026-11-23 → 2056-11-15`）。**删掉它会导致 debug 包签名指纹变化，与已装的旧 debug 包无法覆盖升级。**

#### 9.2 `aapt2 dump badging`：确认包名、版本、SDK 级别、ABI

```bash
$BT/aapt2 dump badging app/build/outputs/apk/github/debug/app-github-universal-debug.apk | head -3
```

```text
package: name='me.rerere.rikkahub.debug' versionCode='520' versionName='10.0.1' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'26'
targetSdkVersion:'37'
```

```bash
$ $BT/aapt2 dump badging app/build/outputs/apk/github/debug/app-github-universal-debug.apk \
    | grep -E "^application-label:|^launchable-activity:|^native-code:"
application-label:'RikkaHub'
launchable-activity: name='me.rerere.rikkahub.RouteActivity'  label='' icon=''
native-code: 'arm64-v8a' 'x86_64'
```

> `arm64-v8a` 分包的 `native-code` 只会有 `'arm64-v8a'`（本机实测）。
> 注意 `applicationId` 带 `.debug` 后缀（`app/build.gradle.kts:86`），所以 **debug 包可以与正式版共存于同一台设备**。

#### 9.3 三件套自检脚本

```bash
cd /root/autodl-tmp/rikkahub
BT=$ANDROID_HOME/build-tools/37.0.0
for apk in app/build/outputs/apk/github/debug/*.apk; do
  echo "=== $(basename "$apk")  $(stat -c %s "$apk") bytes"
  sha256sum "$apk"
  "$BT/apksigner" verify --verbose "$apk" | grep -E "^Verifies|v2 scheme"
done
```

## 4. 网络与代理注意事项

本机出口有 **HTTPS MITM 代理**（代理对 `dl.google.com` 等站点做 TLS 中间人，根证书为环境自签）。这带来三个必须处理的点。**本节不记录代理地址与端口**，只讲取舍策略。

### 4.1 JVM / Gradle 默认不读 `http_proxy` 环境变量 —— 而这恰好是好事

Java 的 HTTP 客户端只认 `-Dhttp.proxyHost` / `-Dhttps.proxyHost` 这类 **系统属性**，**不读** `http_proxy` / `https_proxy` 环境变量。所以 Gradle wrapper 下载发行版、AGP 下载 SDK 组件、Gradle 解析 Maven 依赖，**默认都是直连**。

而本机实测「**直连远快于代理**」：

| 目标 | 直连 | 走本机代理 |
|---|---|---|
| `dl.google.com`（NDK / cmdline-tools zip 同域） | HTTP 206，**19,736,265 B/s**（约 18.8 MiB/s） | HTTP 206，881,044 B/s（约 861 KiB/s），同一文件耗时 22.7 s |
| `repo1.maven.org` | HTTP 200 | 未单独对比 |
| `registry.npmjs.org` | 约 900 KB/s（历史实测） | 8–46 KiB/s（历史实测，`pnpm install` 曾因此超时） |
| `nodejs.org/dist` | HTTP 200 | 未单独对比 |

结论：**构建前显式清掉代理变量**，让所有工具都走直连：

```bash
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
```

> `curl`、`wget` 会读这两个变量。所以要「测直连速度」必须 `unset` 或加 `--noproxy '*'`：
> `curl --noproxy '*' -o /dev/null -w '%{speed_download}\n' -r 0-20000000 <url>`

### 4.2 Node / npm / pnpm 会读代理变量 → 典型 `SELF_SIGNED_CERT_IN_CHAIN`

Node 的 TLS 栈**用内置 CA 列表**，不读系统 CA 库。因此当 npm/pnpm 走 MITM 代理时，会报：

```text
npm error code SELF_SIGNED_CERT_IN_CHAIN
npm error errno SELF_SIGNED_CERT_IN_CHAIN
npm error request to https://registry.npmjs.org/... failed, reason: self signed certificate in certificate chain
```

两种处理方式（本机两种都做了）：

| 方式 | 命令 | 适用 |
|---|---|---|
| ① 直接绕开代理 | `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY` | 最快，本机 `pnpm install` 从超时变成 **9.3 秒**完成 |
| ② 让 Node 信任系统 CA | `export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt` | 必须走代理时；本机已写进 `android-env.sh` |

> 方式 ② 只是「不再报证书错」，不解决代理下的龟速。本机能直连就优先 ①。

### 4.3 `maven.google.com` 不可达，但不影响构建

| 现象 | 说明 |
|---|---|
| `curl https://maven.google.com/` → 走代理 **HTTP 503**，直连**连接失败** | 该域在本机**不可达**，属环境网络限制 |
| `curl https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0.pom` → **HTTP 200** | 真正的制品仓库存放在 `dl.google.com` 上 |
| `settings.gradle.kts` 用的是 `google()` 仓库 | `google()` 实际解析到 `dl.google.com/dl/android/maven2`，**不依赖 `maven.google.com`** |

> 结论：**`maven.google.com` 不通不是阻塞项，不要去改 `settings.gradle.kts`**。若真遇到 Google 仓库拉不到包，先 `curl` 上面那条 pom 链接确认 `dl.google.com` 是否可达。

### 4.4 各源连通性自检清单

装之前先跑一遍，任一条不通就先解决网络，别硬着头皮装：

```bash
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

# 1) Google Android 制品仓库（AGP / AndroidX 依赖来源）
curl -sS -o /dev/null -w 'google-maven  %{http_code}\n' \
  https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0.pom

# 2) Google Android SDK 仓库（sdkmanager 的包来源，含 NDK）
curl -sS -o /dev/null -w 'google-sdk    %{http_code}\n' \
  https://dl.google.com/android/repository/repository2-3.xml

# 3) Maven Central
curl -sS -o /dev/null -w 'maven-central %{http_code}\n' https://repo1.maven.org/maven2/

# 4) npm registry（:web 模块依赖）
curl -sS -o /dev/null -w 'npm-registry  %{http_code}\n' https://registry.npmjs.org/

# 5) Node 官方分发（Node 本体）
curl -sS -o /dev/null -w 'nodejs-dist   %{http_code}\n' https://nodejs.org/dist/

# 6) Gradle 发行版（wrapper）
curl -sSI -o /dev/null -w 'gradle-dist   %{http_code}\n' \
  https://services.gradle.org/distributions/gradle-9.6.0-bin.zip
```

本机实测（直连）：

```text
google-maven  200
google-sdk    200
maven-central 200
npm-registry  200
nodejs-dist   200
gradle-dist   307          ← 重定向到 downloads.gradle.org，属正常；wrapper 实际下载成功 286 MB
```

### 4.5 带宽自检（判断该不该走代理）

```bash
# 直连基准：只下前 20 MB，看 speed_download（字节/秒）
curl --noproxy '*' -sS -o /dev/null -r 0-20000000 \
  -w 'direct  %{http_code}  %{speed_download} B/s\n' \
  https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
```

> 本机这条命令给出 `206  19736265 B/s`。若你的直连只有几百 KB/s 而代理更快，那就反过来保留代理——本文的取舍以**本机实测**为准，不是通用结论。

## 5. 常见安装错误对照表

| 现象 | 原因 | 解决 |
|---|---|---|
| `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local.properties file` | 既没设 `ANDROID_HOME`，项目根也没有 `local.properties` | 二选一：`export ANDROID_HOME=<SDK 根>`，或 `printf 'sdk.dir=<SDK 根>\n' > local.properties`（步骤 6 / 7.1） |
| `sdkmanager: command not found` / `Could not determine SDK root` | cmdline-tools 目录层级错：解压成 `<SDK>/cmdline-tools/cmdline-tools/bin/`，或漏了 `latest` 那一层 | 必须严格是 `<SDK>/cmdline-tools/latest/bin/sdkmanager`（步骤 3） |
| 只装了 `cmdline-tools`，`sdkmanager --list_installed` 是空表 | 只装了工具，没装组件 | 跑步骤 5 的那条 `sdkmanager` 安装命令 |
| `No version of NDK matched the requested version 28.2.13676358` / `NDK not configured` | 没装 AGP 默认 NDK，或装了别的版本 | `sdkmanager "ndk;28.2.13676358"`（步骤 5）。项目未声明 `ndkVersion`，AGP 9.4.0 默认要这个版本 |
| `CMake 3.22.1 not found` / `[CXX1101] No version of CMake found` | `terminal` / `workspace` 硬编码 `version = "3.22.1"`，而 SDK 里没有该版本 | `sdkmanager "cmake;3.22.1"`（步骤 5）。**装系统 cmake 不算数**，AGP 只认 SDK 内的 |
| `UnsatisfiedLinkError: dlopen failed: library "libtermux.so" not found` 或 native 编译阶段直接失败 | NDK / CMake 缺失或版本不匹配，`.so` 没被打进 APK | 先用 `./gradlew :terminal:buildCMakeDebug` 单独跑原生编译定位；再核对 NDK/CMake 版本；`grep -r "ANDROID_NDK" terminal/.cxx workspace/.cxx` 可看到实际使用的 NDK 路径 |
| `npm error code SELF_SIGNED_CERT_IN_CHAIN` | Node 内置 CA 不信任出口 MITM 代理的自签根证书 | `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY`，或 `export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt`（第 4.2 节） |
| `pnpm install` 卡在最后几个包、超时被杀（`SIGTERM` / exit 143） | 走 MITM 代理时被限速到 8–46 KiB/s | 同上，`unset` 代理后重跑；本机实测从超时变成 9.3 秒 |
| `ENOSPC: no space left on device` | SDK / Gradle 缓存 / 构建产物落在 30 GB 根分区 | 按第 2 节重定向 `ANDROID_HOME` / `GRADLE_USER_HOME`；清理 `app/build`、`*/build`、`.cxx`；`df -h /` 复核 |
| `pnpm: command not found`（Gradle 报 `A problem occurred starting process 'command 'pnpm''`） | `$NODE_HOME/bin` 不在 `PATH`，或 Gradle daemon 是**在 `pnpm` 装好之前**启动的、缓存了旧环境 | `source android-env.sh` 后 `pnpm -v` 确认；然后 `./gradlew --stop` 杀掉旧 daemon 再构建 |
| `Cannot locate tasks that match ':app:assembleDebug'` / `Ambiguous matches` | 项目有 `github` / `play` 两个 flavor | 用带渠道的任务名：`assembleGithubDebug`（步骤 8） |
| release 构建报签名相关错误（`Keystore was tampered with` / `signingConfig 'release' ... storeFile`） | `app/build.gradle.kts:49-68` 从 `local.properties` 读 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`，缺任一项则 release 签名配置为空 | 只构建 debug 就不需要；要出 release 必须在 `local.properties` 补全这四项并指向真实 keystore |
| `File google-services.json is missing` / `No matching client found for package name 'me.rerere.rikkahub.debug'` | `app/google-services.json` 缺失，或里面的 `package_name` 与 `applicationId(+.debug)` 对不上 | 放一份含 `me.rerere.rikkahub` 与 `me.rerere.rikkahub.debug` 两条 client 的占位 mock（步骤 7.2） |
| `Warning: This version only understands SDK XML versions up to 3 ...` | cmdline-tools 12.0 的解析上限低于新组件的 XML v4 | **无害，可忽略**；要消掉就升级 cmdline-tools |
| 新开 shell 后 `ANDROID_HOME` 为空 | `~/.bashrc` 里有裸 `zsh`（或提前 `exec`），其后追加的 `source` 不执行 | 把 `source` 行插到那行**之前**（步骤 6 的陷阱说明） |
| `./build-apk.sh: Permission denied` | 脚本没有可执行位 | `chmod +x build-apk.sh`，或用 `bash build-apk.sh` |

## 6. 一键复现清单

> 假设：root、Ubuntu 22.04 x86_64、数据盘挂在 `/root/autodl-tmp`、项目已在 `/root/autodl-tmp/rikkahub`。
> 若路径不同，替换对应的三个目录变量即可。**整段是可复制执行的**。

```bash
set -euo pipefail
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

# ---------- 0. 目录骨架（全部落数据盘） ----------
export ANDROID_HOME=/root/autodl-tmp/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=/root/autodl-tmp/gradle-home
export NODE_HOME=/root/autodl-tmp/tools/node22
mkdir -p "$ANDROID_HOME/cmdline-tools" "$GRADLE_USER_HOME" "$NODE_HOME"

# ---------- 1. JDK 21 ----------
apt-get update && apt-get install -y openjdk-21-jdk-headless
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# ---------- 2. Node 22 + pnpm 11 ----------
NODE_VER=v22.23.5
curl -fL -o /tmp/node.tar.xz "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-x64.tar.xz"
rm -rf /tmp/node-extract && mkdir -p /tmp/node-extract
tar -xJf /tmp/node.tar.xz -C /tmp/node-extract --strip-components=1
cp -a /tmp/node-extract/. "$NODE_HOME/"
export PATH="$NODE_HOME/bin:$PATH"
npm install -g pnpm@11.29.0

# ---------- 3. Android cmdline-tools（必须落成 .../cmdline-tools/latest） ----------
curl -fL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
rm -rf /tmp/cmdline-unzip && mkdir -p /tmp/cmdline-unzip
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-unzip
rm -rf "$ANDROID_HOME/cmdline-tools/latest"
mv /tmp/cmdline-unzip/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# ---------- 4. 许可 ----------
yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses

# ---------- 5. SDK 组件 ----------
"$SDKM" --sdk_root="$ANDROID_HOME" \
  "platforms;android-37.2" "build-tools;37.0.0" "platform-tools" \
  "ndk;28.2.13676358" "cmake;3.22.1"

# ---------- 6. 环境变量（写到文件 + 挂 bashrc） ----------
cat > /root/autodl-tmp/android-env.sh <<'ENVEOF'
#!/usr/bin/env bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export NODE_HOME=/root/autodl-tmp/tools/node22
export ANDROID_HOME=/root/autodl-tmp/android-sdk
export ANDROID_SDK_ROOT=/root/autodl-tmp/android-sdk
export GRADLE_USER_HOME=/root/autodl-tmp/gradle-home
[ -f /etc/ssl/certs/ca-certificates.crt ] && export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
__p() { case ":$PATH:" in *":$1:"*) ;; *) PATH="$1:$PATH" ;; esac; }
__p "$ANDROID_HOME/cmdline-tools/latest/bin"
__p "$ANDROID_HOME/platform-tools"
__p "$NODE_HOME/bin"
__p "$JAVA_HOME/bin"
export PATH
unset -f __p
ENVEOF
grep -q 'autodl-tmp/android-env.sh' ~/.bashrc || \
  echo '[ -f /root/autodl-tmp/android-env.sh ] && source /root/autodl-tmp/android-env.sh' >> ~/.bashrc
source /root/autodl-tmp/android-env.sh

# ---------- 7. 项目侧 ----------
cd /root/autodl-tmp/rikkahub
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
# app/google-services.json 必须自己放一份占位 mock（结构见步骤 7.2）
( cd web-ui && pnpm install --frozen-lockfile )

# ---------- 8. 构建 ----------
./gradlew assembleGithubDebug
```

**全部验证命令（一段脚本，任一失败即非 0 退出）**：

```bash
set -euo pipefail
source /root/autodl-tmp/android-env.sh
cd /root/autodl-tmp/rikkahub
BT="$ANDROID_HOME/build-tools/37.0.0"

echo "--- 版本 ---"
java -version 2>&1 | head -1
echo "node $(node -v)  npm $(npm -v)  pnpm $(pnpm -v)"
echo "sdkmanager $("$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --version 2>/dev/null | tail -1)"
./gradlew --version 2>/dev/null | grep -E '^(Gradle|Kotlin|Launcher JVM)'
"$BT/aapt2" version
"$BT/apksigner" version

echo "--- SDK 组件 ---"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed 2>/dev/null \
  | grep -E '^  (build-tools|cmake|ndk|platform-tools|platforms)'

echo "--- 产物 ---"
ls -l app/build/outputs/apk/github/debug/*.apk
for apk in app/build/outputs/apk/github/debug/*.apk; do
  echo "== $(basename "$apk") $(stat -c %s "$apk") bytes"
  sha256sum "$apk"
  "$BT/apksigner" verify --verbose "$apk" | grep -E '^Verifies|v2 scheme'
done

echo "--- 元信息 ---"
"$BT/aapt2" dump badging app/build/outputs/apk/github/debug/app-github-universal-debug.apk \
  | grep -E "^package:|^minSdkVersion|^targetSdkVersion|^native-code"

echo "ALL CHECKS PASSED"
```

本机这三个 APK 的期望 SHA-256（现场 `sha256sum` 重算值，供对照）：

```text
bc9cbe7b1fbf17105ef6f15c8d5e0c2d6666c4ce6c65efda6afe51776f3e7dfe  app-github-arm64-v8a-debug.apk
325dc147e1e877a9335ad7aa244efff45c6aae72c450736f2577e34ea0f025ca  app-github-universal-debug.apk
00b87dca21eaf0902b439b3a1b9e95da0d27e3ac293a5e6bdbd5aaf043001f97  app-github-x86_64-debug.apk
```

> **注意**：SHA-256 会随源码、依赖、签名时间戳变化。上面这三行只能用来验证「同一台机器、同一份代码、可复现的产物」，**换机器或改代码后必然不同**，此时只需保证 `apksigner` 输出 `Verifies` 与 `aapt2` 的 package/version 正确。

## 7. 变更记录

| 日期 | 变更内容 | 依据 |
|---|---|---|
| 2026-11-23 | 建立本文档：Linux（Ubuntu 22.04.3 / x86_64）从零安装 JDK 21 + Node 22 + pnpm 11 + Android cmdline-tools 12.0 + SDK 组件（platform 37.2 / build-tools 37.0.0 / platform-tools 37.0.3 / NDK 28.2.13676358 / CMake 3.22.1），直到产出并校验 APK | 本机 14:57:57 起执行 `apt-get install openjdk-21-jdk-headless`，15:45:30 首次 `./gradlew assembleGithubDebug` 结束（`BUILD SUCCESSFUL in 17m 31s`、`296 actionable tasks: 296 executed`）；全部版本号/体积/输出均现场命令采集 |

> 维护提示：本文的命令序列与路径（`/root/autodl-tmp/{android-sdk,gradle-home,tools}`）一旦变更，需同步更新同目录的 [apk-build-env-linux.md](apk-build-env-linux.md)；项目侧的版本约束（`compileSdk`、`ndkVersion` 默认值、CMake 版本）以 `gradle/libs.versions.toml`、`app/build.gradle.kts`、`terminal/build.gradle.kts`、`workspace/build.gradle.kts` 为准，AGP 升级后需重新核对。
