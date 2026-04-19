#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEFAULT_AVD_NAME="Pixel_5_API_34"

to_shell_path() {
  local path="$1"

  if [[ "$path" =~ ^[A-Za-z]:[\\/].* ]]; then
    if command -v cygpath >/dev/null 2>&1; then
      cygpath -u "$path"
      return
    fi

    if command -v wslpath >/dev/null 2>&1; then
      wslpath -u "$path"
      return
    fi
  fi

  printf '%s\n' "$path"
}

to_windows_path() {
  local path="$1"

  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$path"
    return
  fi

  if command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$path"
    return
  fi

  printf '%s\n' "$path"
}

usage() {
  cat <<EOF
用法: $(basename "$0") [AVD_NAME]

启动 Android Emulator。
- AVD_NAME 可选，默认使用 ${AVD_NAME:-$DEFAULT_AVD_NAME}
- 也可以通过 ANDROID_SDK_ROOT / ANDROID_HOME 指定 SDK
EOF
}

resolve_sdk_root() {
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

  if [[ -n "$sdk_root" ]]; then
    printf '%s\n' "$(to_shell_path "$sdk_root")"
    return
  fi

  if [[ -d "$PROJECT_ROOT/.dev-env/android-sdk" ]]; then
    printf '%s\n' "$PROJECT_ROOT/.dev-env/android-sdk"
    return
  fi

  if [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    printf '%s\n' "/mnt/c/Android/Sdk"
    return
  fi

  printf '%s\n' "C:/Android/Sdk"
}

find_emulator_exe() {
  local sdk_root="$1"
  local candidates=(
    "$sdk_root/emulator/emulator.exe"
    "$sdk_root/emulator/emulator"
  )
  local candidate

  for candidate in "${candidates[@]}"; do
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  local avd_name="${1:-${AVD_NAME:-$DEFAULT_AVD_NAME}}"
  local sdk_root
  sdk_root="$(resolve_sdk_root)"
  local emulator_exe

  if ! emulator_exe="$(find_emulator_exe "$sdk_root")"; then
    printf '找不到 emulator 可执行文件: %s\n' "$sdk_root" >&2
    exit 1
  fi

  local avd_list
  if ! avd_list="$("$emulator_exe" -list-avds 2>/dev/null)"; then
    printf '无法列出 AVD，请检查 SDK: %s\n' "$sdk_root" >&2
    exit 1
  fi

  if [[ -z "$avd_list" ]]; then
    printf '未检测到可用 AVD，请先创建一个再启动。\n' >&2
    exit 1
  fi

  if ! printf '%s\n' "$avd_list" | grep -Fxq "$avd_name"; then
    printf '未找到 AVD: %s\n' "$avd_name" >&2
    printf '可用 AVD:\n%s\n' "$avd_list" >&2
    exit 1
  fi

  local emulator_win_path
  emulator_win_path="$(to_windows_path "$emulator_exe")"

  printf '启动 AVD: %s\n' "$avd_name"
  printf 'SDK: %s\n' "$sdk_root"

  if command -v cmd.exe >/dev/null 2>&1; then
    cmd.exe /c start "" "$emulator_win_path" -avd "$avd_name" -netdelay none -netspeed full >/dev/null 2>&1
  else
    nohup "$emulator_exe" -avd "$avd_name" -netdelay none -netspeed full >/dev/null 2>&1 &
  fi
}

main "$@"