#!/usr/bin/env bash
# =============================================================================
#  RikkaHub 自动化 APK 构建脚本
#
#  把一次手工验证成功的完整构建流程固化下来：
#    AGP 9.4.0 / Kotlin 2.4.21 / KSP 2.3.10 / Gradle 9.6，macOS + JDK 21 +
#    Android SDK 37.2。实测 `./gradlew :app:assembleGithubDebug` 12m27s 成功。
#
#  固化内容（每一步都在下面有对应函数）：
#    1. 代理          → http_proxy / https_proxy（默认 http://127.0.0.1:7897）
#    2. JDK 21        → 默认 JDK 可能是 25，脚本优先用 21，缺失时告警回退
#    3. ANDROID_HOME  → 多来源探测 + 4 个 SDK 组件按需安装
#                       platforms;android-37.2 / build-tools;37.0.0 /
#                       cmake;3.22.1 / ndk;28.2.13676358
#    4. Firebase 配置 → app/google-services.json 缺失时生成占位文件
#                       （必须同时包含 me.rerere.rikkahub 与 .debug 两个包名）
#    5. 前端依赖      → web-ui 的 pnpm install --frozen-lockfile
#                       （:web 的 preBuild 会执行 pnpm run build）
#    6. 构建          → ./gradlew :app:assembleGithubDebug
#    7. 产物          → 列出 APK 大小 + apksigner 签名者 DN + 安装命令
#
#  设计原则：
#    * 幂等：已存在的 SDK 组件 / node_modules 不重复下载
#    * 可观测：分节日志 + 每步耗时 + 完整构建日志落盘
#    * 不硬崩：外部命令缺失时给出可直接复制的补救指令
#    * 可预览：--dry-run 只打印将要执行的步骤与命令
#
#  写入范围（除此之外不动仓库任何文件）：
#    * scripts/build-apk.sh 自身
#    * workspace/logs/ 下的构建日志
#    * 仅在缺失时创建的 app/google-services.json 占位文件
# =============================================================================

set -euo pipefail
# 让 ERR trap 在函数内部也能拿到准确行号（bash 3.2 亦支持）
set -o errtrace

# ---------------------------------------------------------------------------
# 路径常量
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${REPO_ROOT}/workspace/logs"
GRADLEW="${REPO_ROOT}/gradlew"

# ---------------------------------------------------------------------------
# 工程固定的 SDK / 工具版本（与 build.gradle.kts、gradle/libs.versions.toml 对齐）
#   compileSdk = 37 (minorApiLevel = 2) → platforms;android-37.2
#   AGP 9.4.0 默认 NDK 28.2.13676358 → :terminal / :workspace 的 native 构建需要
#   CMake 3.22.1 → native 构建需要
# ---------------------------------------------------------------------------
SDK_PACKAGE_PLATFORM="platforms;android-37.2"
SDK_PROBE_PLATFORM="platforms/android-37.2"

SDK_PACKAGE_BUILD_TOOLS="build-tools;37.0.0"
SDK_PROBE_BUILD_TOOLS="build-tools/37.0.0"

SDK_PACKAGE_CMAKE="cmake;3.22.1"
SDK_PROBE_CMAKE="cmake/3.22.1"

SDK_PACKAGE_NDK="ndk;28.2.13676358"
SDK_PROBE_NDK="ndk/28.2.13676358"
# NDK 目录残缺时（只剩 .installer、没有 source.properties）sdkmanager 会误判为已安装，
# 所以额外探测这个文件
SDK_PROBE_NDK_EXTRA="source.properties"

# 签名校验优先使用的 build-tools 版本
APKSIGNER_BUILD_TOOLS="37.0.0"

# ---------------------------------------------------------------------------
# 颜色（非 tty 或 NO_COLOR 时自动关闭）
# ---------------------------------------------------------------------------
if [[ -t 1 ]] && [[ -z "${NO_COLOR:-}" ]]; then
  C_RESET=$'\033[0m'
  C_INFO=$'\033[36m'
  C_OK=$'\033[32m'
  C_WARN=$'\033[33m'
  C_ERROR=$'\033[31m'
  C_HEAD=$'\033[1;35m'
  C_DIM=$'\033[2m'
else
  C_RESET=""
  C_INFO=""
  C_OK=""
  C_WARN=""
  C_ERROR=""
  C_HEAD=""
  C_DIM=""
fi

# ---------------------------------------------------------------------------
# 日志
# ---------------------------------------------------------------------------
_log() {
  # $1 颜色, $2 标签, 其余为消息
  local color="$1" tag="$2"
  shift 2
  printf '%s[%s] [%-5s]%s %s\n' "$color" "$(date '+%H:%M:%S')" "$tag" "$C_RESET" "$*"
}

log_info() { _log "$C_INFO" "INFO" "$@"; }
log_ok()   { _log "$C_OK" "OK" "$@"; }
log_warn() { _log "$C_WARN" "WARN" "$@"; }
log_error() { _log "$C_ERROR" "ERROR" "$@" >&2; }

log_section() {
  printf '\n%s━━━ %s ━━━%s\n' "$C_HEAD" "$*" "$C_RESET"
}

log_dim() {
  printf '%s    %s%s\n' "$C_DIM" "$*" "$C_RESET"
}

die() {
  log_error "$*"
  exit 1
}

# ---------------------------------------------------------------------------
# 失败定位：打印出错文件:行号、失败命令与退出码，并摘出构建日志里的错误行
#   注意：macOS 自带 bash 3.2 在 ERR trap 内部取 $? / LINENO 不可靠（会是 0 或
#   陷阱定义所在行），因此行号与退出码一律由 trap 参数显式传入。
# ---------------------------------------------------------------------------
on_error() {
  local line_no="${1:-?}"
  local exit_code="${2:-1}"
  local failed_cmd="${3:-}"
  local func_name="${4:-}"
  set +e

  # bash 3.2 偶尔把状态归一成 0，兜底为 1，保证失败路径一定非零退出
  if [[ -z "$exit_code" || "$exit_code" == "0" ]]; then
    exit_code=1
  fi

  if [[ -n "$func_name" ]]; then
    log_error "命令执行失败：${0##*/} 第 ${line_no} 行（函数 ${func_name}，退出码 ${exit_code}）"
  else
    log_error "命令执行失败：${0##*/} 第 ${line_no} 行（退出码 ${exit_code}）"
  fi
  if [[ -n "$failed_cmd" ]]; then
    log_error "失败命令：${failed_cmd}"
  fi

  if [[ -n "${BUILD_LOG:-}" && -f "${BUILD_LOG:-}" ]]; then
    log_error "构建日志：${BUILD_LOG}"
    local summary
    summary="$(grep -nE '^e: |FAILURE: |What went wrong|Caused by:' "$BUILD_LOG" 2>/dev/null | head -n 20)"
    if [[ -n "$summary" ]]; then
      log_error "错误摘要（前 20 行）："
      printf '%s\n' "$summary" | sed 's/^/    /' >&2
    fi
  fi

  log_error "排查建议：先 ./scripts/build-apk.sh --dry-run 看将要执行的命令，再按上面的日志路径定位。"
  log_error "（bash 3.2 下错误若发生在函数内，行号会指向该函数起始行；bash 4+ 为精确行号）"
  exit "$exit_code"
}
trap 'on_error "${LINENO}" "$?" "${BASH_COMMAND}" "${FUNCNAME[0]:-}"' ERR

# ---------------------------------------------------------------------------
# 计时
# ---------------------------------------------------------------------------
TIMER_START=$(date +%s)

format_duration() {
  local total=${1:-0}
  if [[ "$total" -ge 60 ]]; then
    printf '%dm%02ds' $(( total / 60 )) $(( total % 60 ))
  else
    printf '%ds' "$total"
  fi
}

# 打印自上次 mark（或脚本开始）以来的耗时
timer_mark() {
  local now elapsed
  now=$(date +%s)
  elapsed=$(( now - TIMER_START ))
  TIMER_START=$now
  _log "$C_DIM" "TIME" "$1 用时 $(format_duration "$elapsed")"
}

# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

# 统一的小写转换（bash 3.2 没有 ${var,,}）
to_lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

human_size() {
  # macOS/BSD 与 GNU 的 ls -lh 都以第 5 列输出人类可读大小
  ls -lh "$1" 2>/dev/null | awk '{print $5}'
}

# ---------------------------------------------------------------------------
# 用法
# ---------------------------------------------------------------------------
usage() {
  cat <<'USAGE'
RikkaHub 自动化 APK 构建脚本

用法:
  ./scripts/build-apk.sh [选项]

构建选项:
  --release               构建 Release APK（默认 Debug）；构建前检查 local.properties 的
                          keystore 配置，缺失时只告警并产出未签名包
  --variant <Name>        直接指定 Gradle 变体后缀，如 GithubDebug / PlayRelease
                          （内部拼成 :app:assemble<Name>；与 --release 同时给出时以本项为准）
  --bundle                产出 AAB（:app:bundle<Name>）而不是 APK，用于 Google Play 上架
  --clean                 构建前先执行 ./gradlew clean
  --no-daemon             给 gradle 传 --no-daemon（默认不加，复用 daemon 加速二次构建）
  --install               构建完成后，若恰好一台 adb 设备在线，自动 adb install -r universal 包

环境准备:
  --skip-sdk              跳过 Android SDK 组件检测与安装
  --skip-web              跳过 web-ui 前端依赖准备（node_modules 已就绪时也会自动跳过）
  --proxy <url>           指定 HTTP/HTTPS 代理，默认 http://127.0.0.1:7897
  --no-proxy              不设置代理（离线/内网环境）
  --jvm-proxy             除环境变量外，再把代理以 -D 系统属性传给 Gradle JVM
                          （仅当系统未配置代理、Gradle 仍无法联网时使用）

诊断:
  --dry-run               只打印将要执行的步骤和命令，不实际执行任何构建
  -h, --help              显示本帮助并退出

环境变量覆盖（优先级从高到低）:
  ANDROID_HOME / ANDROID_SDK_ROOT    Android SDK 根目录；未设置时按顺序探测
                                     local.properties 的 sdk.dir、~/Library/Android/sdk、
                                     /opt/homebrew/share/android-commandlinetools 等
  JAVA_HOME                          JDK 目录；脚本默认自动选用 JDK 21，
                                     找不到 21 时回退到已有 JAVA_HOME 并告警
  http_proxy / https_proxy           若已导出则会被 --proxy 的值覆盖

产物（github + debug 为例）:
  app/build/outputs/apk/github/debug/app-github-arm64-v8a-debug.apk   真机安装用
  app/build/outputs/apk/github/debug/app-github-universal-debug.apk   全 ABI，推荐分发
  app/build/outputs/apk/github/debug/app-github-x86_64-debug.apk      模拟器用

示例:
  ./scripts/build-apk.sh                          # 默认：github debug，产出 3 个 APK
  ./scripts/build-apk.sh --release                # github release（需 keystore，否则未签名）
  ./scripts/build-apk.sh --clean --no-daemon      # 干净构建，不复用 daemon
  ./scripts/build-apk.sh --variant PlayRelease --bundle   # Play 渠道 AAB
  ./scripts/build-apk.sh --install                # 构建后装到唯一在线设备
  ./scripts/build-apk.sh --dry-run                # 只预览步骤，不动任何东西

说明:
  * 脚本幂等：已安装的 SDK 组件、已存在的 node_modules 不会重复处理。
  * 缺失的 app/google-services.json 会生成占位文件，仅够编译，
    Firebase / Crashlytics 功能不可用；真实配置需从 CI secret 获取。
  * 构建日志写入 workspace/logs/build-<variant>-<timestamp>.log。
USAGE
}

# ---------------------------------------------------------------------------
# 参数（默认值）
# ---------------------------------------------------------------------------
BUILD_TYPE="debug"       # debug | release
RELEASE_REQUESTED=0
VARIANT_SUFFIX=""        # 用户显式指定的变体后缀
VARIANT=""               # 规范化后的变体，如 GithubDebug
GRADLE_TASK=""           # 最终 Gradle 任务，如 :app:assembleGithubDebug
BUNDLE=0
DO_CLEAN=0
DO_INSTALL=0
SKIP_SDK=0
SKIP_WEB=0
NO_DAEMON=0
DRY_RUN=0
USE_PROXY=1
JVM_PROXY=0
PROXY_URL="http://127.0.0.1:7897"
JVM_PROXY_OPTS=""        # 传给 gradle 的 -D 代理参数（--jvm-proxy 时非空）

# 运行期状态
BUILD_LOG=""
BUILD_STAMP=""
GENERATED_GOOGLE_SERVICES=0
SKIPPED_WEB_INSTALL=0
RECOMMENDED_APK=""
SDK_LOG=""
# gradlew 的实际调用方式：正常情况下是自身路径；无执行权限时改成 `bash gradlew`（不改仓库文件权限）
GRADLE_EXEC=("$GRADLEW")

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --release)
        BUILD_TYPE="release"
        RELEASE_REQUESTED=1
        ;;
      --variant)
        [[ $# -ge 2 && -n "${2:-}" ]] || die "--variant 需要一个变体后缀，例如 GithubDebug"
        VARIANT_SUFFIX="$2"
        shift
        ;;
      --variant=*)
        VARIANT_SUFFIX="${1#*=}"
        [[ -n "$VARIANT_SUFFIX" ]] || die "--variant 的值不能为空，例如 GithubDebug"
        ;;
      --bundle)
        BUNDLE=1
        ;;
      --clean)
        DO_CLEAN=1
        ;;
      --install)
        DO_INSTALL=1
        ;;
      --skip-sdk)
        SKIP_SDK=1
        ;;
      --skip-web)
        SKIP_WEB=1
        ;;
      --no-daemon)
        NO_DAEMON=1
        ;;
      --proxy)
        [[ $# -ge 2 && -n "${2:-}" ]] || die "--proxy 需要一个地址，例如 http://127.0.0.1:7897"
        PROXY_URL="$2"
        USE_PROXY=1
        shift
        ;;
      --proxy=*)
        PROXY_URL="${1#*=}"
        [[ -n "$PROXY_URL" ]] || die "--proxy 的值不能为空"
        USE_PROXY=1
        ;;
      --no-proxy)
        USE_PROXY=0
        ;;
      --jvm-proxy)
        JVM_PROXY=1
        ;;
      --dry-run)
        DRY_RUN=1
        ;;
      -h|--help|help)
        usage
        exit 0
        ;;
      --)
        shift
        [[ $# -eq 0 ]] || die "不支持的透传参数: $*（本脚本不接受位置参数）"
        break
        ;;
      *)
        log_error "未知参数: $1"
        printf '\n'
        usage
        exit 2
        ;;
    esac
    shift
  done
}

# 把用户输入的变体名规范成 Gradle 的 camelCase 形式
#   githubdebug / GithubDebug / GITHUBDEBUG → GithubDebug
#   playrelease                              → PlayRelease
# 已知变体表覆盖 flavor(github|play) × buildType(debug|release|nonMinifiedRelease)
canonicalize_variant() {
  local input="$1"
  local lower candidate first rest
  lower="$(to_lower "$input")"

  for candidate in \
    GithubDebug GithubRelease GithubNonMinifiedRelease \
    PlayDebug PlayRelease PlayNonMinifiedRelease
  do
    if [[ "$(to_lower "$candidate")" == "$lower" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done

  # 未知变体：只把首字母大写，其余保持原样，交给 Gradle 报错
  first="$(printf '%s' "${input:0:1}" | tr '[:lower:]' '[:upper:]')"
  rest="${input:1}"
  printf '%s' "${first}${rest}"
}

# 把 --variant / --release 归一化成 VARIANT 与 GRADLE_TASK
resolve_variant() {
  if [[ -n "$VARIANT_SUFFIX" ]]; then
    case "$VARIANT_SUFFIX" in
      *[!A-Za-z0-9]*)
        die "--variant 只接受字母和数字，例如 GithubDebug / PlayRelease（收到: ${VARIANT_SUFFIX}）"
        ;;
    esac

    if [[ "$RELEASE_REQUESTED" -eq 1 ]]; then
      log_warn "同时指定了 --release 与 --variant，以 --variant（${VARIANT_SUFFIX}）为准"
    fi

    VARIANT="$(canonicalize_variant "$VARIANT_SUFFIX")"

    # 从变体名推断 buildType，决定是否做 keystore 检查
    case "$(to_lower "$VARIANT")" in
      *release*) BUILD_TYPE="release" ;;
      *debug*)   BUILD_TYPE="debug" ;;
      *)         BUILD_TYPE="unknown" ;;
    esac

    if [[ "$BUILD_TYPE" == "unknown" ]]; then
      log_warn "无法从变体名「${VARIANT}」推断 buildType，跳过 Release 签名检查与产物口径判断"
    fi
  else
    if [[ "$BUILD_TYPE" == "release" ]]; then
      VARIANT="GithubRelease"
    else
      VARIANT="GithubDebug"
    fi
  fi

  if [[ "$BUNDLE" -eq 1 ]]; then
    GRADLE_TASK=":app:bundle${VARIANT}"
  else
    GRADLE_TASK=":app:assemble${VARIANT}"
  fi
}

# ===========================================================================
# 环境准备
# ===========================================================================

# ---------------------------------------------------------------------------
# 0) 预检：仓库结构 + Gradle wrapper 可用性
# ---------------------------------------------------------------------------
preflight_checks() {
  log_section "0/7 预检"

  [[ -f "${REPO_ROOT}/settings.gradle.kts" ]] \
    || die "仓库结构不对：找不到 ${REPO_ROOT}/settings.gradle.kts"
  [[ -f "$GRADLEW" ]] \
    || die "找不到 Gradle wrapper：${GRADLEW}（仓库不完整？）"

  if [[ -x "$GRADLEW" ]]; then
    log_ok "Gradle wrapper 可执行：${GRADLEW}"
  else
    log_warn "gradlew 缺少执行权限，改用 bash 调用（不会修改仓库文件权限）"
    GRADLE_EXEC=(bash "$GRADLEW")
  fi

  local dist
  dist="$(grep -E '^distributionUrl=' "${REPO_ROOT}/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null | tail -n 1 | sed 's/.*gradle-\(.*\)-bin\.zip/\1/' || true)"
  if [[ -n "$dist" ]]; then
    log_info "Gradle 版本: ${dist}"
  fi
}

# ---------------------------------------------------------------------------
# 1) 代理：Gradle 拉依赖必需
# ---------------------------------------------------------------------------
setup_proxy() {
  log_section "1/7 代理"

  if [[ "$USE_PROXY" -ne 1 ]]; then
    log_info "--no-proxy：不设置代理（Gradle 直连；内网请自备 Maven 镜像）"
    if [[ "$JVM_PROXY" -eq 1 ]]; then
      log_warn "--no-proxy 优先，--jvm-proxy 被忽略"
    fi
    return 0
  fi

  # 必须带协议，否则 host/port 解析会得到无意义的值
  case "$PROXY_URL" in
    *://*) : ;;
    *)
      die "--proxy 需要带协议的地址，例如 http://127.0.0.1:7897（收到: ${PROXY_URL}）"
      ;;
  esac

  # 从 URL 里拆出 host/port，供 --jvm-proxy 使用
  local scheme rest hostport host port
  scheme="${PROXY_URL%%://*}"
  rest="${PROXY_URL#*://}"
  rest="${rest%%/*}"
  hostport="$rest"
  if [[ "$hostport" == *:* ]]; then
    host="${hostport%%:*}"
    port="${hostport##*:}"
  else
    host="$hostport"
    case "$scheme" in
      https)          port=443 ;;
      socks5|socks5h) port=1080 ;;
      *)              port=8080 ;;
    esac
  fi

  [[ -n "$host" && -n "$port" ]] \
    || die "无法解析代理地址: ${PROXY_URL}（期望形如 http://127.0.0.1:7897）"

  export http_proxy="$PROXY_URL"
  export https_proxy="$PROXY_URL"
  export HTTP_PROXY="$PROXY_URL"
  export HTTPS_PROXY="$PROXY_URL"
  log_ok "http_proxy / https_proxy = ${PROXY_URL}"

  if [[ "$JVM_PROXY" -eq 1 ]]; then
    JVM_PROXY_OPTS="-Dhttp.proxyHost=${host} -Dhttp.proxyPort=${port} -Dhttps.proxyHost=${host} -Dhttps.proxyPort=${port} -Dhttp.nonProxyHosts=localhost|127.0.0.1"
    log_ok "--jvm-proxy：Gradle JVM 使用 ${host}:${port}（localhost 直连）"
  else
    log_dim "Gradle 依赖下载依赖系统代理或上述环境变量；若仍无法联网，追加 --jvm-proxy 重试"
  fi
}

# ---------------------------------------------------------------------------
# 2) JDK 21：本机默认 JDK 是 25，CI 用 21，优先 21
# ---------------------------------------------------------------------------
detect_java_home() {
  log_section "2/7 JDK"

  local candidate="" version_line="" d

  # macOS 官方机制
  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi

  # 非 macOS 或 java_home 未命中：扫常见安装位置
  if [[ -z "$candidate" ]]; then
    for d in \
      "${JAVA_HOME:-}" \
      "$HOME/.sdkman/candidates/java/21"* \
      "/usr/lib/jvm/"*21* \
      "/opt/homebrew/opt/openjdk@21" \
      "/Library/Java/JavaVirtualMachines/"*21*"/Contents/Home"
    do
      if [[ -n "$d" && -x "${d}/bin/java" ]]; then
        candidate="$d"
        break
      fi
    done
  fi

  if [[ -n "$candidate" && -x "${candidate}/bin/java" ]]; then
    export JAVA_HOME="$candidate"
    version_line="$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
    log_ok "JAVA_HOME=${JAVA_HOME}"
    log_dim "$version_line"
    return 0
  fi

  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    log_warn "未找到 JDK 21，回退到已有 JAVA_HOME=${JAVA_HOME}（JDK 25 未验证，可能编译失败）"
    log_dim "补救：brew install --cask temurin@21"
    return 0
  fi

  if have java; then
    log_warn "未找到 JDK 21，沿用 PATH 中的 java：$(java -version 2>&1 | head -n 1)"
    log_dim "补救：brew install --cask temurin@21"
    return 0
  fi

  die "找不到可用的 JDK。请安装 JDK 21（brew install --cask temurin@21）或显式设置 JAVA_HOME。"
}

# ---------------------------------------------------------------------------
# 3) Android SDK 根目录探测
# ---------------------------------------------------------------------------

# 从 local.properties 读取 sdk.dir（Android Studio 通常会写这一行）
read_sdk_dir_from_local_properties() {
  local lp="${REPO_ROOT}/local.properties"
  [[ -f "$lp" ]] || return 0

  local line value
  line="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$lp" 2>/dev/null | tail -n 1 || true)"
  [[ -n "$line" ]] || return 0

  value="${line#*=}"
  # 去首尾空白，并把 properties 里转义的 "\:" 还原成 ":"
  printf '%s' "$value" \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/\\:/:/g'
}

is_valid_sdk_root() {
  local root="${1:-}"
  [[ -n "$root" && -d "$root" ]] || return 1
  [[ -d "${root}/platforms" || -d "${root}/build-tools" || -d "${root}/cmdline-tools" || -d "${root}/licenses" ]]
}

detect_android_home() {
  log_section "3/7 Android SDK"

  local local_props_sdk candidate
  local_props_sdk="$(read_sdk_dir_from_local_properties)"

  # 优先级：显式环境变量 > local.properties > 常见默认位置
  for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$local_props_sdk" \
    "$HOME/Library/Android/sdk" \
    "/opt/homebrew/share/android-commandlinetools" \
    "/usr/local/share/android-commandlinetools" \
    "$HOME/Android/Sdk"
  do
    [[ -n "$candidate" ]] || continue

    if is_valid_sdk_root "$candidate"; then
      export ANDROID_HOME="$candidate"
      export ANDROID_SDK_ROOT="$candidate"
      log_ok "ANDROID_HOME=${ANDROID_HOME}"

      if [[ -n "$local_props_sdk" && "$candidate" == "$local_props_sdk" ]]; then
        log_dim "来源：local.properties 的 sdk.dir"
      else
        log_dim "来源：环境变量或常见安装路径"
      fi

      # 让 adb / sdkmanager 直接可用
      if [[ -d "${ANDROID_HOME}/platform-tools" && ":${PATH}:" != *":${ANDROID_HOME}/platform-tools:"* ]]; then
        export PATH="${ANDROID_HOME}/platform-tools:${PATH}"
      fi
      return 0
    fi

    log_warn "跳过无效的 SDK 路径: ${candidate}"
  done

  die "未找到 Android SDK。请安装 Android Studio，或设置 ANDROID_HOME 后重试。"
}

# sdkmanager 路径（cmdline-tools/latest 优先）
sdkmanager_path() {
  local p
  for p in \
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
    "${ANDROID_HOME}/cmdline-tools/bin/sdkmanager" \
    "${ANDROID_HOME}/tools/bin/sdkmanager"
  do
    if [[ -x "$p" ]]; then
      printf '%s' "$p"
      return 0
    fi
  done

  if have sdkmanager; then
    command -v sdkmanager
    return 0
  fi

  return 1
}

# ensure_sdk_component <package> <probe_relative_path> [probe_extra_file]
#   package            sdkmanager 的包名，如 "ndk;28.2.13676358"
#   probe_relative_path 安装完成后应存在的相对路径，如 "ndk/28.2.13676358"
#   probe_extra_file   该路径下还必须存在的文件（NDK 用它区分"目录残缺"与"装好了"）
ensure_sdk_component() {
  local pkg="$1"
  local probe_rel="$2"
  local probe_extra="${3:-}"
  local probe="${ANDROID_HOME}/${probe_rel}"
  local sdk_log="${SDK_LOG:-/dev/null}"
  local installed=1

  [[ -e "$probe" ]] || installed=0

  # 目录存在但缺少 source.properties，说明上次安装中断，sdkmanager 会误判为已安装
  if [[ "$installed" -eq 1 && -n "$probe_extra" && ! -e "${probe}/${probe_extra}" ]]; then
    installed=0
    log_warn "${pkg} 目录存在但缺少 ${probe_extra}，判定为残缺安装"
  fi

  if [[ "$installed" -eq 1 ]]; then
    log_ok "${pkg} 已安装"
    return 0
  fi

  local sdkmanager=""
  if ! sdkmanager="$(sdkmanager_path)"; then
    log_error "缺少 ${pkg}，且找不到 sdkmanager"
    log_error "手动安装：sdkmanager \"${pkg}\""
    log_error "若本机没有 sdkmanager：Android Studio → SDK Manager → SDK Tools 勾选 cmdline-tools (latest)"
    return 1
  fi

  log_info "安装 ${pkg}"
  log_dim "sdkmanager: ${sdkmanager}"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_dim "[dry-run] yes | ${sdkmanager} --sdk_root=${ANDROID_HOME} \"${pkg}\""
    return 0
  fi

  # yes 收到 SIGPIPE 会让 pipefail 返回 141，这里临时关掉 pipefail 取 sdkmanager 真实退出码
  local rc=0
  set +o pipefail
  yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" "$pkg" >>"$sdk_log" 2>&1 || rc=$?
  set -o pipefail

  if [[ "$rc" -ne 0 ]]; then
    log_error "sdkmanager 安装 ${pkg} 失败（退出码 ${rc}）"
    log_error "sdkmanager 输出末尾："
    tail -n 20 "$sdk_log" 2>/dev/null | sed 's/^/    /' >&2
    return 1
  fi

  if [[ -e "$probe" && ( -z "$probe_extra" || -e "${probe}/${probe_extra}" ) ]]; then
    log_ok "${pkg} 安装完成"
    return 0
  fi

  log_error "${pkg} 安装命令已执行，但探测路径仍不完整: ${probe}"
  log_error "常见原因：目录残缺（只剩 .installer）。先移走再重试："
  log_error "    mv \"${probe}\" \"${probe}.broken\" && ./scripts/build-apk.sh --skip-web"
  return 1
}

# 一次性把 4 个必需组件过一遍（幂等）
ensure_sdk_components() {
  log_section "SDK 组件（platforms 37.2 / build-tools 37 / cmake 3.22.1 / ndk 28.2）"

  if [[ "$SKIP_SDK" -eq 1 ]]; then
    log_info "--skip-sdk：跳过组件检测与安装"
    return 0
  fi

  SDK_LOG="${LOG_DIR}/sdkmanager-$(date '+%Y%m%d-%H%M%S').log"
  if [[ "$DRY_RUN" -eq 0 ]]; then
    mkdir -p "$LOG_DIR"
    : > "$SDK_LOG"
  fi

  local sdkmanager=""
  if sdkmanager="$(sdkmanager_path)"; then
    log_dim "sdkmanager: ${sdkmanager}"

    # 先接受许可，避免安装阶段被交互式协议卡死
    log_info "接受 SDK 许可（幂等）"
    if [[ "$DRY_RUN" -eq 1 ]]; then
      log_dim "[dry-run] yes | ${sdkmanager} --sdk_root=${ANDROID_HOME} --licenses"
    else
      local lic_rc=0
      set +o pipefail
      yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >>"$SDK_LOG" 2>&1 || lic_rc=$?
      set -o pipefail
      if [[ "$lic_rc" -eq 0 ]]; then
        log_ok "许可已全部接受"
      else
        log_warn "接受许可返回退出码 ${lic_rc}（通常不影响构建，继续）"
      fi
    fi
  else
    log_warn "找不到 sdkmanager：仅在组件缺失时才会报错并给出手动安装指令"
  fi

  ensure_sdk_component "$SDK_PACKAGE_PLATFORM"    "$SDK_PROBE_PLATFORM"
  ensure_sdk_component "$SDK_PACKAGE_BUILD_TOOLS" "$SDK_PROBE_BUILD_TOOLS"
  ensure_sdk_component "$SDK_PACKAGE_CMAKE"       "$SDK_PROBE_CMAKE"
  ensure_sdk_component "$SDK_PACKAGE_NDK"         "$SDK_PROBE_NDK" "$SDK_PROBE_NDK_EXTRA"

  log_ok "SDK 组件齐备"
}

# ---------------------------------------------------------------------------
# 4) google-services.json：app/build.gradle.kts 无条件 apply google-services
#    与 crashlytics，但该文件被 app/.gitignore 忽略，缺失时生成占位文件。
#    注意：debug 的 applicationIdSuffix=".debug" 会触发第二次校验，
#    所以 client[] 必须同时包含 me.rerere.rikkahub 与 me.rerere.rikkahub.debug。
# ---------------------------------------------------------------------------
GOOGLE_SERVICES_FILE="${REPO_ROOT}/app/google-services.json"

ensure_google_services() {
  log_section "4/7 Firebase 配置"

  if [[ -f "$GOOGLE_SERVICES_FILE" ]]; then
    log_ok "app/google-services.json 已存在，保持不变"
    return 0
  fi

  log_warn "app/google-services.json 缺失（被 app/.gitignore 忽略）"
  log_info "生成占位文件：仅够编译，Firebase / Crashlytics 功能不可用"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_dim "[dry-run] 将写入 ${GOOGLE_SERVICES_FILE}"
    log_dim "[dry-run] 内容：占位 JSON，含 me.rerere.rikkahub 与 me.rerere.rikkahub.debug 两个 client"
    GENERATED_GOOGLE_SERVICES=1
    return 0
  fi

  cat > "$GOOGLE_SERVICES_FILE" <<'JSON'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "rikkahub-local-build",
    "storage_bucket": "rikkahub-local-build.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000001",
        "android_client_info": {
          "package_name": "me.rerere.rikkahub"
        }
      },
      "oauth_client": [],
      "api_key": [
        {
          "current_key": "AIzaSyDummyKeyForLocalBuild0000000000000"
        }
      ],
      "services": {
        "appinvite_service": {
          "other_platform_oauth_client": []
        }
      }
    },
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000002",
        "android_client_info": {
          "package_name": "me.rerere.rikkahub.debug"
        }
      },
      "oauth_client": [],
      "api_key": [
        {
          "current_key": "AIzaSyDummyKeyForLocalBuild0000000000000"
        }
      ],
      "services": {
        "appinvite_service": {
          "other_platform_oauth_client": []
        }
      }
    }
  ],
  "configuration_version": "1"
}
JSON

  GENERATED_GOOGLE_SERVICES=1

  # 合法性校验：必须是合法 JSON，且两个包名都在
  if have python3; then
    if ! python3 -m json.tool "$GOOGLE_SERVICES_FILE" >/dev/null 2>&1; then
      die "生成的 google-services.json 不是合法 JSON：${GOOGLE_SERVICES_FILE}"
    fi
    log_ok "JSON 语法校验通过（python3 -m json.tool）"
  else
    log_warn "缺少 python3，跳过 JSON 语法校验"
  fi

  local pkg
  for pkg in me.rerere.rikkahub me.rerere.rikkahub.debug; do
    if grep -q "\"${pkg}\"" "$GOOGLE_SERVICES_FILE"; then
      log_ok "包含包名 ${pkg}"
    else
      die "占位文件缺少包名 ${pkg}，google-services 插件会报 No matching client found for package name"
    fi
  done
}

# ---------------------------------------------------------------------------
# 5) 前端依赖：:web 的 preBuild → buildWebUi → zsh -ic "pnpm run build"
# ---------------------------------------------------------------------------
ensure_web_ui() {
  log_section "5/7 前端依赖（web-ui）"

  if [[ "$SKIP_WEB" -eq 1 ]]; then
    log_info "--skip-web：跳过前端依赖准备（若 web-ui 未构建过，:web:preBuild 会失败）"
    return 0
  fi

  local web_dir="${REPO_ROOT}/web-ui"
  [[ -d "$web_dir" ]] || die "找不到 web-ui 目录：${web_dir}"

  # pnpm 可用性
  if ! have pnpm; then
    log_warn "未找到 pnpm，尝试用 corepack 启用"
    if have corepack; then
      if [[ "$DRY_RUN" -eq 1 ]]; then
        log_dim "[dry-run] corepack enable"
        log_dim "[dry-run] corepack prepare pnpm@11 --activate"
      else
        corepack enable || log_warn "corepack enable 失败（可能需要写权限）"
        corepack prepare pnpm@11 --activate || log_warn "corepack prepare pnpm@11 失败"
      fi
    else
      log_warn "corepack 也不可用"
    fi
  fi

  if ! have pnpm; then
    die "pnpm 不可用。补救：corepack enable && corepack prepare pnpm@11 --activate（或 npm i -g pnpm@11）"
  fi
  log_ok "pnpm $(pnpm --version 2>/dev/null || printf '?')"

  # :web 用 zsh -ic 调 pnpm，要求登录交互式 shell 里也能定位到 pnpm
  if [[ "$(uname -s)" == "Darwin" ]] && have zsh; then
    if zsh -ic 'command -v pnpm' >/dev/null 2>&1; then
      log_ok "zsh -ic 可定位 pnpm（:web:preBuild 需要）"
    else
      log_warn "zsh -ic 里找不到 pnpm，而 web/build.gradle.kts 用 zsh -ic 调 pnpm，构建可能失败"
      log_warn "补救：把 pnpm 所在目录加入 ~/.zshrc 的 PATH"
    fi
  fi

  if [[ -d "${web_dir}/node_modules" ]]; then
    SKIPPED_WEB_INSTALL=1
    log_ok "web-ui/node_modules 已存在（$(du -sh "$web_dir/node_modules" 2>/dev/null | cut -f1)），跳过安装"
    return 0
  fi

  log_info "安装前端依赖：pnpm install --frozen-lockfile（首次约 381M，耗时较长）"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_dim "[dry-run] (cd ${web_dir} && pnpm install --frozen-lockfile)"
    return 0
  fi

  local start rc=0
  start=$(date +%s)
  ( cd "$web_dir" && pnpm install --frozen-lockfile ) || rc=$?

  if [[ "$rc" -ne 0 ]]; then
    log_error "pnpm install 失败（退出码 ${rc}）"
    log_error "补救：cd web-ui && pnpm install（去掉 --frozen-lockfile 允许更新 lockfile）"
    return 1
  fi

  log_ok "前端依赖就绪（耗时 $(format_duration $(( $(date +%s) - start )))）"
}

# ---------------------------------------------------------------------------
# Release 签名检查：local.properties 的 storeFile/storePassword/keyAlias/keyPassword
# 缺失时只告警并继续，产出未签名包
# ---------------------------------------------------------------------------
read_local_property() {
  local key="$1"
  local lp="${REPO_ROOT}/local.properties"
  [[ -f "$lp" ]] || return 0

  grep -E "^[[:space:]]*${key}[[:space:]]*=" "$lp" 2>/dev/null \
    | tail -n 1 \
    | sed 's/^[^=]*=//' \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
    || true
}

check_release_keystore() {
  [[ "$BUILD_TYPE" == "release" ]] || return 0

  log_section "Release 签名检查"

  local missing="" key value
  for key in storeFile storePassword keyAlias keyPassword; do
    value="$(read_local_property "$key")"
    if [[ -z "$value" ]]; then
      missing="${missing} ${key}"
    fi
  done

  if [[ -n "$missing" ]]; then
    log_warn "local.properties 缺少签名配置:${missing}"
    log_warn "本次继续构建，但 Release 产物将未签名（无法直接安装/分发）"
    log_warn "补齐方式：在 ${REPO_ROOT}/local.properties 写入"
    log_dim "storeFile=../keystore.jks    # 相对路径按 app/ 模块解析"
    log_dim "storePassword=***"
    log_dim "keyAlias=***"
    log_dim "keyPassword=***"
    return 0
  fi

  log_ok "keystore 四项配置齐全"

  # storeFile 指向的文件是否存在
  local store_file
  store_file="$(read_local_property "storeFile")"
  if [[ "$store_file" == /* ]]; then
    if [[ -f "$store_file" ]]; then
      log_ok "keystore 文件存在: ${store_file}"
    else
      log_warn "storeFile 指向的文件不存在: ${store_file}（构建会在签名阶段失败）"
    fi
  else
    if [[ -f "${REPO_ROOT}/app/${store_file}" ]]; then
      log_ok "keystore 文件存在: ${REPO_ROOT}/app/${store_file}"
    else
      log_warn "storeFile=「${store_file}」按 app/ 解析后不存在：${REPO_ROOT}/app/${store_file}"
      log_warn "构建会在签名阶段失败，请检查路径（相对路径基准是 app/ 模块目录）"
    fi
  fi
}

# ---------------------------------------------------------------------------
# --clean
# ---------------------------------------------------------------------------
run_clean() {
  log_section "清理（--clean）"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_dim "[dry-run] (cd ${REPO_ROOT} && ./gradlew clean --stacktrace)"
    return 0
  fi

  local start rc=0
  start=$(date +%s)
  ( cd "$REPO_ROOT" && "${GRADLE_EXEC[@]}" clean --stacktrace ) || rc=$?

  if [[ "$rc" -ne 0 ]]; then
    log_error "gradlew clean 失败（退出码 ${rc}）"
    return 1
  fi

  log_ok "清理完成（耗时 $(format_duration $(( $(date +%s) - start )))）"
}

# ---------------------------------------------------------------------------
# 6) 构建
# ---------------------------------------------------------------------------
run_build() {
  log_section "6/7 Gradle 构建"

  local variant_lower
  variant_lower="$(to_lower "$VARIANT")"
  BUILD_LOG="${LOG_DIR}/build-${variant_lower}-$(date '+%Y%m%d-%H%M%S').log"
  BUILD_STAMP="${LOG_DIR}/.build-stamp-${variant_lower}-$(date '+%Y%m%d-%H%M%S')"

  # 数组首元素非空，bash 3.2 下 "${arr[@]}" 配合 set -u 也安全
  local gradle_args=("$GRADLE_TASK" "--stacktrace")
  if [[ "$NO_DAEMON" -eq 1 ]]; then
    gradle_args+=("--no-daemon")
  fi
  if [[ -n "$JVM_PROXY_OPTS" ]]; then
    # 拆成多个 -D 参数（值内不含空格）
    # shellcheck disable=SC2206
    gradle_args+=($JVM_PROXY_OPTS)
  fi

  log_info "任务: ${GRADLE_TASK}"
  log_info "日志: ${BUILD_LOG}"
  log_dim "命令: (cd ${REPO_ROOT} && ./gradlew ${gradle_args[*]}) 2>&1 | tee ${BUILD_LOG}"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_info "[dry-run] 不执行 Gradle（真实构建实测约 12 分钟）"
    return 0
  fi

  mkdir -p "$LOG_DIR"
  : > "$BUILD_STAMP"

  local start rc=0 elapsed
  start=$(date +%s)
  ( cd "$REPO_ROOT" && "${GRADLE_EXEC[@]}" "${gradle_args[@]}" ) 2>&1 | tee "$BUILD_LOG" || rc=$?
  elapsed=$(( $(date +%s) - start ))

  if [[ "$rc" -ne 0 ]]; then
    log_error "构建失败（退出码 ${rc}，耗时 $(format_duration "$elapsed")）"
    log_error "日志：${BUILD_LOG}"
    log_error "错误摘要（前 20 行）："
    { grep -nE '^e: |FAILURE: |What went wrong|Caused by:' "$BUILD_LOG" 2>/dev/null | head -n 20 | sed 's/^/    /' >&2; } || true
    exit "$rc"
  fi

  log_ok "构建成功（耗时 $(format_duration "$elapsed")）"
}

# ---------------------------------------------------------------------------
# 7) 产物报告
# ---------------------------------------------------------------------------
find_apksigner() {
  local preferred="${ANDROID_HOME}/build-tools/${APKSIGNER_BUILD_TOOLS}/apksigner"
  if [[ -x "$preferred" ]]; then
    printf '%s' "$preferred"
    return 0
  fi

  local candidates found
  candidates="$(ls -1 "${ANDROID_HOME}"/build-tools/*/apksigner 2>/dev/null || true)"
  if [[ -n "$candidates" ]]; then
    found="$(printf '%s\n' "$candidates" | sort | tail -n 1)"
    if [[ -x "$found" ]]; then
      printf '%s' "$found"
      return 0
    fi
  fi

  return 1
}

report_artifacts() {
  log_section "7/7 产物"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_info "[dry-run] 跳过产物检查（构建未执行）"
    return 0
  fi

  local out_dir="${REPO_ROOT}/app/build/outputs"
  if [[ ! -d "$out_dir" ]]; then
    log_warn "不存在 ${out_dir}，本次没有产物"
    return 0
  fi

  # 只列本次构建新产出的文件（构建开始时 touch 的 stamp 作为时间基准）
  local files
  files="$(find "$out_dir" -type f \( -name '*.apk' -o -name '*.aab' \) -newer "$BUILD_STAMP" 2>/dev/null | sort || true)"
  if [[ -z "$files" ]]; then
    log_warn "没有发现本次新产出的文件，退化为列出目录中已有的产物（可能来自上一次构建）"
    files="$(find "$out_dir" -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null | sort || true)"
  fi
  if [[ -z "$files" ]]; then
    log_warn "app/build/outputs 下没有 APK / AAB（可能只执行了中间任务）"
    return 0
  fi

  local apksigner=""
  if apksigner="$(find_apksigner)"; then
    log_dim "apksigner: ${apksigner}"
  else
    log_warn "找不到 apksigner，跳过签名校验（期望位置：\$ANDROID_HOME/build-tools/${APKSIGNER_BUILD_TOOLS}/apksigner）"
  fi

  printf '\n'
  local f size out dn
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    size="$(human_size "$f")"
    printf '  %s%s%s  %s\n' "$C_OK" "$(basename "$f")" "$C_RESET" "$size"
    printf '%s      %s%s\n' "$C_DIM" "$f" "$C_RESET"

    case "$f" in
      *.aab)
        log_dim "AAB：签名交给 Play 上传流程，apksigner 不适用"
        ;;
      *.apk)
        if [[ -n "$apksigner" ]]; then
          if out="$("$apksigner" verify --print-certs "$f" 2>&1)"; then
            dn="$(printf '%s\n' "$out" | grep 'certificate DN' | head -n 1 | sed 's/.*certificate DN: //')"
            log_dim "签名: ${dn:-（已通过校验，未解析到 DN）}"
          else
            log_warn "签名校验未通过：产物可能未签名（Release 未配置 keystore 时属预期）"
          fi
        fi
        ;;
      *)
        : ;;
    esac

    # 推荐包优先级：universal > arm64-v8a > 其它第一个 APK
    case "$f" in
      *universal*.apk)
        RECOMMENDED_APK="$f"
        ;;
      *arm64-v8a*.apk)
        if [[ -z "$RECOMMENDED_APK" ]]; then
          RECOMMENDED_APK="$f"
        fi
        ;;
      *.apk)
        if [[ -z "$RECOMMENDED_APK" ]]; then
          RECOMMENDED_APK="$f"
        fi
        ;;
      *)
        : ;;
    esac
  done <<< "$files"

  printf '\n'
  if [[ -n "$RECOMMENDED_APK" ]]; then
    log_ok "推荐安装：${RECOMMENDED_APK}"
    log_dim "adb install -r \"${RECOMMENDED_APK}\""
  else
    log_info "本次只产出 AAB（--bundle）：上架直接上传 Play Console，或本地验证用 bundletool"
    log_dim "java -jar bundletool.jar build-apks --bundle=<file.aab> --output=<out.apks> --mode=universal"
  fi
}

# ---------------------------------------------------------------------------
# --install：恰好一台设备时自动安装
# ---------------------------------------------------------------------------
install_apk() {
  [[ "$DO_INSTALL" -eq 1 ]] || return 0

  log_section "安装（--install）"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_info "[dry-run] 跳过 adb 安装"
    return 0
  fi

  if ! have adb; then
    log_warn "找不到 adb（通常位于 \$ANDROID_HOME/platform-tools/adb），跳过安装"
    return 0
  fi

  if [[ -z "$RECOMMENDED_APK" ]]; then
    log_warn "没有可安装的 APK（--bundle 只产出 AAB），跳过安装"
    return 0
  fi

  local devices count
  devices="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' || true)"
  count=0
  if [[ -n "$devices" ]]; then
    count="$(printf '%s\n' "$devices" | grep -c . || true)"
  fi

  if [[ "$count" -ne 1 ]]; then
    log_warn "在线设备数 = ${count}，未自动安装（需要恰好一台）"
    log_dim "手动安装：adb install -r \"${RECOMMENDED_APK}\""
    return 0
  fi

  log_info "安装到设备 $(printf '%s' "$devices") ..."
  if adb install -r "$RECOMMENDED_APK"; then
    log_ok "安装完成"
  else
    log_warn "adb install 失败；若为签名冲突，先执行：adb uninstall me.rerere.rikkahub.debug"
  fi
}

# ---------------------------------------------------------------------------
# 收尾汇总
# ---------------------------------------------------------------------------
print_summary() {
  log_section "完成"

  log_ok "任务: ${GRADLE_TASK}"
  if [[ -n "$BUILD_LOG" && -f "$BUILD_LOG" ]]; then
    log_info "构建日志: ${BUILD_LOG}"
  fi
  if [[ -n "$RECOMMENDED_APK" ]]; then
    log_info "推荐安装包: ${RECOMMENDED_APK}"
  fi

  if [[ "$GENERATED_GOOGLE_SERVICES" -eq 1 ]]; then
    log_warn "app/google-services.json 由脚本自动生成（占位）："
    log_warn "  → Firebase 功能不可用（Crashlytics / Analytics 不会上报）"
    log_warn "  → 如需真实配置，请从 CI secret 获取并覆盖该文件"
  fi

  if [[ "$SKIPPED_WEB_INSTALL" -eq 1 ]]; then
    log_dim "web-ui/node_modules 复用已有安装；前端代码改动后建议 pnpm run build 或加 --clean 验证"
  fi

  log_info "总耗时: $(format_duration $(( $(date +%s) - SCRIPT_START )))"
}

# ===========================================================================
# 主流程
# ===========================================================================
SCRIPT_START=0

main() {
  SCRIPT_START=$(date +%s)

  parse_args "$@"
  resolve_variant

  log_section "RikkaHub APK 构建"
  log_info "仓库     : ${REPO_ROOT}"
  log_info "Gradle   : ${GRADLE_TASK}"
  log_info "变体     : ${VARIANT}（buildType=${BUILD_TYPE}）"
  if [[ "$USE_PROXY" -eq 1 ]]; then
    log_info "代理     : ${PROXY_URL}"
  else
    log_info "代理     : 已禁用"
  fi
  log_info "日志目录 : ${LOG_DIR}"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log_warn "--dry-run：只打印步骤与命令，不执行任何构建 / 安装"
  fi

  preflight_checks
  timer_mark "预检"
  setup_proxy
  timer_mark "代理"
  detect_java_home
  timer_mark "JDK"
  detect_android_home
  ensure_sdk_components
  timer_mark "Android SDK"
  ensure_google_services
  timer_mark "Firebase 配置"
  ensure_web_ui
  timer_mark "前端依赖"
  check_release_keystore

  if [[ "$DO_CLEAN" -eq 1 ]]; then
    run_clean
    timer_mark "clean"
  fi

  run_build
  report_artifacts
  install_apk
  print_summary
}

main "$@"
exit 0
