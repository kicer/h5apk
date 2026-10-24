#!/usr/bin/env bash
# 计算本次要构建哪些客户包，输出一行 targets=<json数组>（CI 里写入 $GITHUB_OUTPUT）
# 本地可直接跑：bash branding/ci-targets.sh
#
# 现在默认只出【一个包】（基础包名 com.foresh.agent，产物 agent.apk）：
#   1) INPUT_CUSTOMER（网页手动 Run workflow 填的）-> 只构建它；填 all = 所有客户
#   2) TAG（推 tag 触发）：
#        - tag 名是已知客户 -> 只构建它（历史兼容，按客户换皮）
#        - tag 名形如 v1.2.3  -> 构建单包（_base）
#        - 其他 tag           -> 报错退出
#   3) 都没有 -> 构建单包（_base）
#
# 「已知客户」= branding/customers/<customer>.properties
#            或 branding/icon/<customer>.png
# 「全部客户」= 上述两个目录里的文件名（_ 开头的除外）
# 结果为空 -> ["_base"]（使用基础包名，不追加 customer）
set -euo pipefail
cd "$(dirname "$0")/.."

out=""
add() {
  case " $out " in
    *" $1 "*) ;;
    *) out="${out:+$out }$1" ;;
  esac
}

is_known_customer() {
  local name="$1"
  if [ -z "$name" ]; then
    return 1
  fi
  if [ -f "branding/customers/${name}.properties" ]; then
    return 0
  fi
  if [ -f "branding/icon/${name}.png" ]; then
    return 0
  fi
  return 1
}

collect_all() {
  local f name
  for f in branding/icon/*.png; do
    if [ ! -e "$f" ]; then
      continue
    fi
    name="$(basename "$f" .png)"
    if [ "$name" = "ic_launcher" ]; then
      continue
    fi
    add "$name"
  done
  for f in branding/customers/*.properties; do
    if [ ! -e "$f" ]; then
      continue
    fi
    name="$(basename "$f" .properties)"
    case "$name" in
      _*) continue ;;
    esac
    add "$name"
  done
}

if [ -n "${INPUT_CUSTOMER:-}" ]; then
  if [ "$INPUT_CUSTOMER" = "all" ]; then
    collect_all
  else
    add "$INPUT_CUSTOMER"
  fi
elif [ -n "${TAG:-}" ]; then
  if is_known_customer "$TAG"; then
    add "$TAG"
  elif printf '%s' "$TAG" | grep -qE '^v[0-9]'; then
    add "_base"
  else
    echo "❌ 未知 tag：${TAG}" >&2
    echo "   版本 tag 用 v1.2.3 形式（出单包）；客户 tag 必须与 branding/customers/<客户>.properties 同名" >&2
    exit 1
  fi
else
  add "_base"
fi

if [ -z "$out" ]; then
  out="_base"
fi

# shellcheck disable=SC2086
json="$(printf '"%s",' $out)"
json="[${json%,}]"
echo "构建目标: $json" >&2
echo "targets=$json"
