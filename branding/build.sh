#!/usr/bin/env bash
# 一键换皮打包
#
# 【日常】只出一个客户 -> 推代码 + 触发 CI + 打印下载地址（本地不需要 JDK/Android SDK）：
#   ./branding/build.sh customerA
#
# 【本地调试】本机编译：
#   ./branding/build.sh            # 只打 debug 包
#   ./branding/build.sh release    # 只打 release 包
#   ./branding/build.sh all        # debug + release 一起打
#   ./branding/build.sh release --clean    # 先 clean 再打 release
#   ./branding/build.sh release --upload   # 打 release 并上传到自己的服务器
set -euo pipefail
cd "$(dirname "$0")/.."

# ===== 上传目标（仅 --upload 用，可用环境变量覆盖）=====
UPLOAD_TARGET="${UPLOAD_TARGET:-root@foresh}"
UPLOAD_DIR="${UPLOAD_DIR:-/root/workspace/webserver/website/www/html/apk}"
# 上传成功后打印的下载地址前缀（不带结尾斜杠）
UPLOAD_URL_BASE="${UPLOAD_URL_BASE:-https://file.foresh.com/apk}"

usage() {
  echo "用法:" >&2
  echo "  $0 <客户代号>                    # 推代码 + 推同名 tag 触发 CI，只打这个客户，打印下载地址" >&2
  echo "  $0 [debug|release|all] [--clean] [--upload] [-Pkey=value ...]   # 本机编译" >&2
  echo "  --clean   打包前先 clean" >&2
  echo "  --upload  打包后上传到 ${UPLOAD_TARGET}:${UPLOAD_DIR}/agent-<customer>.apk" >&2
  echo "  -Pkey=value  透传给 Gradle，覆盖 branding.properties（例：-Pcustomer=customerA）" >&2
}

# 从 origin 推导 owner/repo，不依赖 gh
repo_slug() {
  local remote
  remote="$(git remote get-url origin 2>/dev/null || true)"
  printf '%s' "$remote" | sed -E 's#^git@github\.com:##; s#^https?://github\.com/##; s#\.git$##'
}

# ===== 日常模式：./branding/build.sh <客户代号> =====
publish_to_ci() {
  local customer="$1"
  local repo download_url

  # 用 ci-targets.sh 的同一套规则校验客户是否存在
  if ! TAG="$customer" bash branding/ci-targets.sh >/dev/null 2>&1; then
    echo "❌ 找不到客户 ${customer}" >&2
    echo "   需要 branding/customers/${customer}.properties 或 branding/icon/${customer}.png" >&2
    echo "   当前已识别的客户：$(bash branding/ci-targets.sh 2>/dev/null | sed -nE 's/^targets=//p')" >&2
    exit 1
  fi

  repo="$(repo_slug)"
  download_url="https://github.com/${repo}/releases/download/${customer}/agent-${customer}.apk"

  # 有改动就提交推送（CI 从仓库拉代码）
  if [ -n "$(git status --porcelain)" ]; then
    echo "📦 提交并推送改动..."
    git add -A
    git commit -m "release ${customer}" >/dev/null
    git push origin HEAD
  else
    echo "ℹ️ 没有未提交的改动，直接用当前代码"
  fi

  # 已存在且已指向当前提交的 tag，推送不会产生新事件 -> 提前告知
  if git rev-parse -q --verify "refs/tags/${customer}" >/dev/null 2>&1 &&
    [ "$(git rev-parse "refs/tags/${customer}")" = "$(git rev-parse HEAD)" ]; then
    echo "⚠️ tag ${customer} 已经指向当前提交，本次推送不会触发构建" >&2
    echo "   想强制重跑：到 Actions 页点 Re-run，或先改点东西再执行本命令" >&2
  fi

  echo "🚀 触发 CI 构建：${customer}"
  git tag -f "${customer}" >/dev/null
  git push -f origin "refs/tags/${customer}"

  echo ""
  echo "🔗 下载地址（编译完成后可用）:"
  echo "   ${download_url}"
  echo "📋 构建进度: https://github.com/${repo}/actions"
}

MODE="debug"
CLEAN=0
UPLOAD=0
GRADLE_ARGS=""
CUSTOMER=""
for arg in "$@"; do
  case "$arg" in
    debug|release|all) MODE="$arg" ;;
    --clean) CLEAN=1 ;;
    --upload) UPLOAD=1 ;;
    -P*) GRADLE_ARGS="${GRADLE_ARGS} ${arg}" ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "❌ 未知参数: $arg" >&2; usage; exit 1 ;;
    *)
      if [ -n "$CUSTOMER" ]; then
        echo "❌ 只能指定一个客户（已给：${CUSTOMER}，又多了：${arg}）" >&2
        exit 1
      fi
      CUSTOMER="$arg"
      ;;
  esac
done

# 日常模式不需要 Java，放在 JDK 检查之前
if [ -n "$CUSTOMER" ]; then
  publish_to_ci "$CUSTOMER"
  exit 0
fi

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

if [ -n "$GRADLE_ARGS" ]; then
  echo "⚙️  命令行覆盖（优先级高于上面的文件配置）:${GRADLE_ARGS}"
fi

case "$MODE" in
  release) TASKS="assembleRelease"; DIRS="release" ;;
  debug)   TASKS="assembleDebug"; DIRS="debug" ;;
  all)     TASKS="assembleDebug assembleRelease"; DIRS="debug release" ;;
esac

if [ "$CLEAN" -eq 1 ]; then
  TASKS="clean $TASKS"
fi

# shellcheck disable=SC2086
./gradlew $TASKS $GRADLE_ARGS --console=plain

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
    elif command -v sha256sum >/dev/null 2>&1; then
      echo "    SHA-256: $(sha256sum "$apk" | awk '{print $1}')"
    fi
  done < <(find "app/build/outputs/apk/$d" -name "*.apk" 2>/dev/null)
done

if [ "$FOUND" -eq 0 ]; then
  echo "  （未找到 APK，请检查上方构建日志）" >&2
fi

# release 未签名提醒
UNSIGNED=0
case "$MODE" in
  release|all)
    if [ ! -f keystore.properties ]; then
      echo ""
      echo "⚠️ 未找到 keystore.properties，release 包未签名（无法上架 / 覆盖升级）。"
      echo "   参考 branding/README.md 配置一次性正式签名，之后不要更换。"
      UNSIGNED=1
    fi
    ;;
esac

# ===== 上传到服务器（仅 release / all）=====
if [ "$UPLOAD" -eq 1 ]; then
  if [ "$MODE" = "debug" ]; then
    echo "❌ --upload 只能用于 release 或 all（debug 包不上传）" >&2
    exit 1
  fi
  if [ "$UNSIGNED" -eq 1 ]; then
    echo "❌ release 包未签名，取消上传" >&2
    exit 1
  fi
  if [ ! -f "$CONFIG" ]; then
    echo "❌ 上传需要 ${CONFIG} 提供 customer" >&2
    exit 1
  fi

  # $zcwx：远程文件名用的客户代号，取自 branding.properties 的 customer
  CUSTOMER="$(sed -nE 's/^[[:space:]]*customer[[:space:]]*=[[:space:]]*//p' "$CONFIG" | tail -n 1)"
  if [ -z "$CUSTOMER" ]; then
    echo "❌ 上传需要配置 customer（远程文件名依赖它，形如 agent-<customer>.apk）" >&2
    exit 1
  fi

  APK="app/build/outputs/apk/release/app-release.apk"
  if [ ! -f "$APK" ]; then
    echo "❌ 找不到 release APK: $APK" >&2
    exit 1
  fi

  REMOTE="${UPLOAD_TARGET}:${UPLOAD_DIR}/agent-${CUSTOMER}.apk"
  echo ""
  echo "⬆️  上传: ${APK} -> ${REMOTE}"
  scp "$APK" "$REMOTE"
  echo "✅ 上传完成: agent-${CUSTOMER}.apk"
  echo "🔗 下载地址: ${UPLOAD_URL_BASE%/}/agent-${CUSTOMER}.apk"
fi

echo ""
echo "提示：正式发布前把 APK 传到 VirusTotal 自检，再逐个平台申诉。"
