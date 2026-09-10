#!/usr/bin/env bash
# 一键换皮打包：改完 branding/branding.properties（和可选的 branding/icon/ic_launcher.png）后执行：
#   ./branding/build.sh            # 只打 debug 包
#   ./branding/build.sh release    # 只打 release 包
#   ./branding/build.sh all        # debug + release 一起打
#   ./branding/build.sh release --clean   # 先 clean 再打 release
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  echo "用法: $0 [debug|release|all] [--clean]" >&2
}

MODE="debug"
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    debug|release|all) MODE="$arg" ;;
    --clean) CLEAN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "❌ 未知参数: $arg" >&2; usage; exit 1 ;;
  esac
done

# 终端没有 JAVA_HOME 时，用 Android Studio 自带的 JBR
if [ -z "${JAVA_HOME:-}" ]; then
  for c in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home; do
    if [ -x "$c/bin/java" ]; then export JAVA_HOME="$c"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "❌ 找不到 Java，请安装 JDK 或 Android Studio" >&2
  exit 1
fi

# 打印本次生效的配置（地址为编译期写死，装成 APK 后不可修改）
CONFIG="branding/branding.properties"
if [ -f "$CONFIG" ]; then
  echo "==== 本次打包配置: ${CONFIG} ===="
  grep -vE '^[[:space:]]*(#|$)' "$CONFIG" || true
  echo "================================"
else
  echo "⚠️ 未找到 ${CONFIG} ，将使用 build.gradle 中的默认值" >&2
fi

case "$MODE" in
  release) TASKS="assembleRelease"; DIRS="release" ;;
  debug)   TASKS="assembleDebug"; DIRS="debug" ;;
  all)     TASKS="assembleDebug assembleRelease"; DIRS="debug release" ;;
esac

if [ "$CLEAN" -eq 1 ]; then
  TASKS="clean $TASKS"
fi

./gradlew $TASKS --console=plain

echo ""
echo "✅ 完成，本次生成的 APK："
FOUND=0
for d in $DIRS; do
  while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    FOUND=1
    echo "  $apk"
    if command -v shasum >/dev/null 2>&1; then
      echo "    SHA-256: $(shasum -a 256 "$apk" | awk '{print $1}')"
    fi
  done < <(find "app/build/outputs/apk/$d" -name "*.apk" 2>/dev/null)
done

if [ "$FOUND" -eq 0 ]; then
  echo "  （未找到 APK，请检查上方构建日志）" >&2
fi

# release 未签名提醒
case "$MODE" in
  release|all)
    if [ ! -f keystore.properties ]; then
      echo ""
      echo "⚠️ 未找到 keystore.properties，release 包未签名（无法上架 / 覆盖升级）。"
      echo "   参考 branding/README.md 配置一次性正式签名，之后不要更换。"
    fi
    ;;
esac

echo ""
echo "提示：正式发布前把 APK 传到 VirusTotal 自检，再逐个平台申诉。"
