#!/usr/bin/env bash
# BYD 自动签到 · LSPosed 模块 —— 一键构建 APK（macOS / Linux）
#
# 用法:
#   ./build-apk.sh                    # 构建 release（正式版，默认）
#   ./build-apk.sh debug              # 构建 debug（仅用于看 logcat，不可装给 LSPosed）
#   BYD_TOOLCHAIN=/opt/byd-tc ./build-apk.sh
#
# 为什么默认 release：Xposed 模块必须单 dex。AGP 在 debug 变体下会把工程类按包
# 拆成多个 dex，而模块加载器通常只读 classes.dex，于是入口类找不到，
# 表现就是「LSPosed 勾选了但没注入」。release 会合并成单个 classes.dex。
# 脚本对产物有自动检查，多 dex 直接报错。
#
# 工具链查找顺序: $BYD_TOOLCHAIN → /opt/byd-toolchain → $HOME/.byd-toolchain
# 每个目录需含 jdk-17 / gradle-8.9 / android-sdk 三个子目录，脚本会自动接管环境变量。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_ROOT="$ROOT/byd-lsp"
VARIANT="${1:-release}"

green() { printf '\033[32m  %s\033[0m\n' "$1"; }
yellow(){ printf '\033[33m  %s\033[0m\n' "$1"; }
red()   { printf '\033[31m  %s\033[0m\n' "$1"; }
info()  { printf '  %s\n' "$1"; }

echo
echo "==== BYD 自动签到 · LSPosed 模块 构建（$VARIANT）===="
echo

[[ -d "$GRADLE_ROOT" ]]     || { red "找不到模块目录: $GRADLE_ROOT"; exit 1; }
[[ -f "$ROOT/index.html" ]] || { red "找不到 index.html（控制台页面）"; exit 1; }
green "index.html 存在"

if [[ "$VARIANT" != "debug" && "$VARIANT" != "release" ]]; then
  red "变体只能是 debug 或 release"; exit 1
fi

# ---------- 定位工具链 ----------
TC=""
for cand in "${BYD_TOOLCHAIN:-}" /opt/byd-toolchain "$HOME/.byd-toolchain"; do
  [[ -n "$cand" ]] || continue
  if [[ -x "$cand/jdk-17/bin/java" && -x "$cand/gradle-8.9/bin/gradle" && -d "$cand/android-sdk/platforms" ]]; then
    TC="$cand"; break
  fi
done

GRADLE_CMD="gradle"
INIT_ARGS=()

if [[ -n "$TC" ]]; then
  export JAVA_HOME="$TC/jdk-17"
  export ANDROID_HOME="$TC/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export GRADLE_USER_HOME="$TC/gradle-home"
  export PATH="$JAVA_HOME/bin:$TC/gradle-8.9/bin:$ANDROID_HOME/platform-tools:$PATH"
  GRADLE_CMD="$TC/gradle-8.9/bin/gradle"
  green "使用工具链: $TC"
  if [[ -f "$TC/gradle-mirror-init.gradle" ]]; then
    INIT_ARGS=(--init-script "$TC/gradle-mirror-init.gradle")
    green "使用镜像初始化脚本"
  fi
else
  problems=()
  command -v java  >/dev/null 2>&1 || problems+=("未找到 java（需要 JDK 17+）")
  command -v gradle >/dev/null 2>&1 || problems+=("未找到 gradle（需要 8.7+）")
  if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" || ! -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
    problems+=("未找到 Android SDK（设置 ANDROID_HOME）")
  fi
  if (( ${#problems[@]} > 0 )); then
    echo
    red "未找到可用工具链："
    for p in "${problems[@]}"; do yellow "$p"; done
    echo
    echo "  可选路线："
    echo "    A. 用 Android Studio 打开 $GRADLE_ROOT，Build → Build APK(s)"
    echo "    B. 推到 GitHub 让 Actions 构建"
    echo "    C. 准备含 jdk-17 / gradle-8.9 / android-sdk 的工具链目录，"
    echo "       用 BYD_TOOLCHAIN=<路径> 指定"
    echo
    exit 2
  fi
  green "使用系统 PATH 中的工具链"
fi

# ---------- 签名 ----------
if [[ "$VARIANT" == "release" ]]; then
  if [[ -f "$GRADLE_ROOT/keystore.properties" ]]; then
    green "已配置 release 签名（keystore.properties）"
  else
    yellow "缺少 keystore.properties，release 产物将未签名、无法安装"
  fi
fi

# ---------- Xposed API 存根 ----------
JAR="$GRADLE_ROOT/app/libs/api-82.jar"
if [[ ! -f "$JAR" ]]; then
  mkdir -p "$(dirname "$JAR")"
  info "下载 Xposed API 82 存根…"
  curl -fsSL -o "$JAR" https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
  green "api-82.jar 已下载"
else
  green "api-82.jar 已存在"
fi

TASK="assembleRelease"
[[ "$VARIANT" == "debug" ]] && TASK="assembleDebug"

echo
info "开始构建（gradle $TASK）…"
echo
( cd "$GRADLE_ROOT" && "$GRADLE_CMD" "$TASK" --no-daemon --console=plain "${INIT_ARGS[@]}" )

# ---------- 产物校验 ----------
APK_DIR="$GRADLE_ROOT/app/build/outputs/apk/$VARIANT"
shopt -s nullglob
APKS=("$APK_DIR"/*.apk "$APK_DIR"/*/*.apk)
if (( ${#APKS[@]} == 0 )); then
  red "构建结束但没找到 APK"; exit 4
fi

# Xposed 模块必须单 dex：入口类必须在 classes.dex 里，否则「勾选了但没注入」
check_apk() {
  local apk="$1"
  if ! command -v unzip >/dev/null 2>&1; then
    yellow "未找到 unzip，跳过产物校验（请自行确认只有 classes.dex 一个 dex）"
    return 0
  fi
  local list; list="$(unzip -l "$apk")"
  local dex_count; dex_count="$(printf '%s\n' "$list" | grep -cE 'classes[0-9]*\.dex' || true)"
  info "dex: $dex_count 个"
  local ok=0
  for f in assets/webui/index.html assets/xposed_init assets/wxcode_versions.json; do
    if printf '%s\n' "$list" | grep -q "$f"; then
      info "  资源存在: $f"
    else
      red "  缺少资源: $f"; ok=1
    fi
  done
  if [[ "$dex_count" -ne 1 ]]; then
    red "该 APK 有 $dex_count 个 dex —— 模块加载器通常只读 classes.dex，"
    red "入口类 xiaojw.byd.hook.BydHook 不在里面，表现就是「勾选了但没注入」。"
    red "请用 release 变体构建（release 会合并成单个 classes.dex）。"
    return 1
  fi
  [[ "$ok" -eq 0 ]]
}

echo
echo "==== 构建成功 ===="
status=0
for a in "${APKS[@]}"; do
  cp -f "$a" "$ROOT/"
  green "$(basename "$a")  →  $ROOT/$(basename "$a")"
  check_apk "$a" || status=1
done

if [[ "$status" -ne 0 ]]; then
  echo
  red "产物校验未通过，不要安装这个包"
  exit 5
fi

echo
green "==== 产物校验通过：单 dex + 资源齐全 ===="
echo
echo "  安装与启用："
echo "    1. 装过旧版（debug 或旧签名）先卸载 —— 签名变了必须重装"
echo "    2. 安装本 APK"
echo "    3. LSPosed 管理器 → 模块 → 勾选「BYD 自动签到」"
echo "    4. 作用域勾选 微信（com.tencent.mm）"
echo "    5. 强行停止微信后重新打开，日志中应出现「模块已注入」"
echo "    6. 打开控制台 App 配置后点「立即签到」验证"
