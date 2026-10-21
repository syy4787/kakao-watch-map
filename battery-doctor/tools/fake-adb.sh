#!/usr/bin/env bash
# 가짜 adb (리눅스 e2e 용). 견본 덤프(src/test/resources/samples)를 돌려주고,
# settings / appops / standby-bucket / deviceidle whitelist / pm disable / pm grant 상태를 디렉터리에 보관해
# put 한 값이 다음 get/list 에 반영되게 한다. 실제 기기 없이 fat jar 전체 흐름을 돌려 보기 위한 것.
#   FAKE_ADB_SAMPLES  견본 폴더 (기본: ../src/test/resources/samples)
#   FAKE_ADB_STATE    상태 폴더 (기본: /tmp/fake-adb-state)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLES="${FAKE_ADB_SAMPLES:-$HERE/../src/test/resources/samples}"
STATE="${FAKE_ADB_STATE:-/tmp/fake-adb-state}"
OUR_PKG="com.mywatch.kakaomap"
WATCH_CSV="/sdcard/Android/data/$OUR_PKG/files/battery/log.csv"
mkdir -p "$STATE"
printf '%s\n' "$*" >> "$STATE/calls.log"

# 상태 초기화(처음 한 번): 설정 3종, doze 화이트리스트, 비활성 앱 목록, 준 권한(처음엔 없음)
for ns in global secure system; do
  [ -f "$STATE/settings_$ns" ] || cp "$SAMPLES/settings_$ns.txt" "$STATE/settings_$ns"
done
[ -f "$STATE/doze" ] || cp "$SAMPLES/doze_whitelist.txt" "$STATE/doze"
[ -f "$STATE/disabled" ] || sed 's/^package://' "$SAMPLES/packages_d.txt" > "$STATE/disabled"
touch "$STATE/appops" "$STATE/buckets" "$STATE/granted"

# -s <serial> 은 무시
if [ "${1:-}" = "-s" ]; then shift 2; fi
cmd="${1:-}"; [ $# -gt 0 ] && shift

settings_cmd() {
  local op="${1:-}" ns="${2:-global}" key="${3:-}" val="${4:-}"
  local f="$STATE/settings_$ns"
  [ -f "$f" ] || touch "$f"
  case "$op" in
    list) cat "$f" ;;
    get)
      if grep -qE "^$key=" "$f"; then grep -E "^$key=" "$f" | head -1 | cut -d= -f2-; else echo null; fi ;;
    put)
      { grep -vE "^$key=" "$f" || true; echo "$key=$val"; } | sort > "$f.tmp" && mv "$f.tmp" "$f" ;;
    delete)
      { grep -vE "^$key=" "$f" || true; } > "$f.tmp" && mv "$f.tmp" "$f"; echo "Deleted 1 rows" ;;
    *) echo "fake-adb: settings $op ?" >&2; exit 1 ;;
  esac
}

pm_cmd() {
  case "${1:-}" in
    list)
      case "${3:-}" in
        -U) cat "$SAMPLES/packages_u.txt" ;;
        -d) sed 's/^/package:/' "$STATE/disabled" ;;
        -e) grep -vxF -f <(sed 's/^/package:/' "$STATE/disabled") "$SAMPLES/packages_e.txt" || true ;;
        -3) cat "$SAMPLES/packages_3.txt" ;;
        *) sed 's/ uid:.*//' "$SAMPLES/packages_u.txt" ;;
      esac ;;
    disable-user) local pkg="${@: -1}"; echo "$pkg" >> "$STATE/disabled"; echo "Package $pkg new state: disabled-user" ;;
    enable) local pkg="${@: -1}"; { grep -vx "$pkg" "$STATE/disabled" || true; } > "$STATE/disabled.tmp" && mv "$STATE/disabled.tmp" "$STATE/disabled"; echo "Package $pkg new state: enabled" ;;
    clear|uninstall-system-updates) echo "Success" ;;
    # pm trim-caches <크기> — 실제 pm 처럼 성공 시 출력 없음, 횟수만 남긴다
    trim-caches) echo "$*" >> "$STATE/trims" ;;
    # pm grant/revoke <pkg> <permission> — 우리 앱에만 된다(실제 pm 처럼 성공 시 출력 없음, 모르는 패키지는 예외 문구)
    grant|revoke)
      local pkg="${2:-}" perm="${3:-}"
      if [ "$pkg" != "$OUR_PKG" ]; then
        printf "Exception occurred while executing '%s':\njava.lang.IllegalArgumentException: Unknown package: %s\n" "$1" "$pkg" >&2; exit 255
      fi
      if [ "$1" = "grant" ]; then grep -qx "$perm" "$STATE/granted" || echo "$perm" >> "$STATE/granted"
      else { grep -vx "$perm" "$STATE/granted" || true; } > "$STATE/granted.tmp" && mv "$STATE/granted.tmp" "$STATE/granted"; fi ;;
    *) echo "fake-adb: pm ${1:-} ?" >&2; exit 1 ;;
  esac
}

# appops get/set — 기록이 없을 때의 기본값은 op 마다 다르다(RUN_ANY_IN_BACKGROUND 는 allow, GET_USAGE_STATS 같은 특수 op 는 default)
appops_cmd() {
  local op="${1:-}" pkg="${2:-}" opname="${3:-}" mode="${4:-}"
  case "$op" in
    get)
      local m; m=$(grep -E "^$pkg $opname " "$STATE/appops" | tail -1 | awk '{print $3}')
      if [ -z "$m" ]; then case "$opname" in RUN_ANY_IN_BACKGROUND) m=allow ;; *) m=default ;; esac; fi
      echo "$opname: $m" ;;
    set) echo "$pkg $opname $mode" >> "$STATE/appops" ;;
    *) echo "fake-adb: appops $op ?" >&2; exit 1 ;;
  esac
}

# dumpsys package <pkg>: 우리 앱은 견본(app_package.txt)의 granted= 를 준 권한 상태에 맞춰 바꿔서 준다
package_dump() {
  case "${1:-}" in
    "$OUR_PKG")
      local out perm g
      out="$(cat "$SAMPLES/app_package.txt")"
      for perm in android.permission.BATTERY_STATS android.permission.WRITE_SECURE_SETTINGS android.permission.DUMP android.permission.READ_LOGS; do
        if grep -qx "$perm" "$STATE/granted"; then g=true; else g=false; fi
        out="$(printf '%s\n' "$out" | sed -E "s|^( *$perm): granted=(true\|false)|\1: granted=$g|")"
      done
      printf '%s\n' "$out" ;;
    *) cat "$SAMPLES/gms_package.txt" ;;
  esac
}

deviceidle_cmd() {
  [ "${1:-}" = "whitelist" ] || { echo "fake-adb: deviceidle ${1:-} ?" >&2; exit 1; }
  local arg="${2:-}"
  if [ -z "$arg" ]; then cat "$STATE/doze"; return; fi
  local pkg="${arg:1}"
  case "${arg:0:1}" in
    -) { grep -v ",$pkg," "$STATE/doze" || true; } > "$STATE/doze.tmp" && mv "$STATE/doze.tmp" "$STATE/doze"; echo "Removed: $pkg" ;;
    +) grep -q ",$pkg," "$STATE/doze" || echo "user,$pkg,10999" >> "$STATE/doze"; echo "Added: $pkg" ;;
    *) echo "fake-adb: whitelist arg $arg ?" >&2; exit 1 ;;
  esac
}

am_cmd() {
  case "${1:-}" in
    get-standby-bucket) local b; b=$(grep -E "^${2:-} " "$STATE/buckets" | tail -1 | awk '{print $2}'); echo "${b:-10}" ;;
    set-standby-bucket) echo "${2:-} ${3:-}" >> "$STATE/buckets" ;;
    force-stop) ;;
    *) echo "fake-adb: am ${1:-} ?" >&2; exit 1 ;;
  esac
}

dumpsys_cmd() {
  case "${1:-}" in
    battery) cat "$SAMPLES/battery.txt" ;;
    batterystats)
      case "${2:-}" in
        --checkin) cat "$SAMPLES/batterystats_checkin.txt" ;;
        --reset) echo "reset" >> "$STATE/resets"; echo "Battery stats reset." ;;
        *) cat "$SAMPLES/batterystats.txt" ;;
      esac ;;
    power|alarm|sensorservice|location|deviceidle|jobscheduler) cat "$SAMPLES/$1.txt" ;;
    activity) cat "$SAMPLES/wearable.txt" ;;
    package) package_dump "${2:-}" ;;
    *) echo "fake-adb: dumpsys ${1:-} ?" >&2; exit 1 ;;
  esac
}

shell_cmd() {
  local a="${1:-}"; [ $# -gt 0 ] && shift
  case "$a" in
    getprop)
      if [ $# -eq 0 ]; then cat "$SAMPLES/getprop.txt"
      else grep -F "[$1]:" "$SAMPLES/getprop.txt" | sed -E 's/^\[[^]]*\]: \[(.*)\]$/\1/'; fi ;;
    dumpsys) dumpsys_cmd "$@" ;;
    pm) pm_cmd "$@" ;;
    settings) settings_cmd "$@" ;;
    cmd)
      case "${1:-}" in
        appops) shift; appops_cmd "$@" ;;
        deviceidle) shift; deviceidle_cmd "$@" ;;
        *) echo "fake-adb: cmd ${1:-} ?" >&2; exit 1 ;;
      esac ;;
    appops) appops_cmd "$@" ;;   # `appops …` 는 `cmd appops …` 의 래퍼(실기기와 같음)
    am) am_cmd "$@" ;;
    ls) if [ "${1:-}" = "$WATCH_CSV" ]; then echo "$1"; else echo "ls: ${1:-}: No such file or directory" >&2; exit 1; fi ;;
    date) cat "$SAMPLES/date.txt" ;;   # 견본 CSV 의 시각과 맞추기 위해 견본 시계를 쓴다
    *) echo "fake-adb: shell $a ?" >&2; exit 1 ;;
  esac
}

case "$cmd" in
  devices) printf 'List of devices attached\n192.168.0.50:5555\tdevice\n\n' ;;
  connect) echo "connected to ${1:-}" ;;
  pair) echo "Successfully paired to ${1:-} [guid=adb-fake]" ;;
  pull) cp "$SAMPLES/watch_log.csv" "${2:-watch_log.csv}" && echo "1 file pulled, 0 skipped." ;;
  bugreport) : > "${1:-bugreport.zip}" ;;
  reboot) echo "reboot" >> "$STATE/reboots" ;;
  kill-server|start-server) ;;
  shell) shell_cmd "$@" ;;
  *) echo "fake-adb: unknown command '$cmd'" >&2; exit 1 ;;
esac
