#!/usr/bin/env bash
# 计算本次要构建哪些客户包，输出一行 targets=<json数组>（CI 里写入 $GITHUB_OUTPUT）
# 本地可直接跑：bash branding/ci-targets.sh
#
# 规则：
#   1) 传入 INPUT_CUSTOMER 环境变量 -> 只构建它
#   2) 否则扫描 branding/icon/<customer>.png（排除 ic_launcher.png），一张图一个客户
#   3) 再并入 branding.properties 里的 customer
#   4) 结果为空 -> ["_base"]（使用基础包名，不追加 customer）
set -euo pipefail
cd "$(dirname "$0")/.."

out=""
add() {
  case " $out " in
    *" $1 "*) ;;
    *) out="${out:+$out }$1" ;;
  esac
}

if [ -n "${INPUT_CUSTOMER:-}" ]; then
  add "$INPUT_CUSTOMER"
else
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
  config_customer="$(sed -nE 's/^[[:space:]]*customer[[:space:]]*=[[:space:]]*//p' branding/branding.properties | tail -n 1)"
  if [ -n "$config_customer" ]; then
    add "$config_customer"
  fi
fi

if [ -z "$out" ]; then
  out="_base"
fi

# shellcheck disable=SC2086
json="$(printf '"%s",' $out)"
json="[${json%,}]"
echo "构建目标: $json" >&2
echo "targets=$json"
