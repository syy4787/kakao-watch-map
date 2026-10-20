#!/usr/bin/env bash
# battery-doctor e2e (리눅스): 가짜 adb 로 실제 fat jar 를 끝까지 돌린다.
#   connect → collect(분석까지) → analyze → fix --apply wifi-scan-off,bg-restrict:com.example.drain → undo --all → evidence
# 확인: 스냅샷·report.html 생성, journal 2건, 되돌린 뒤 가짜 상태가 원래 값, 증거 zip 과 share/ 마스킹.
#   E2E_BUILD=0  이미 만든 jar 를 그대로 씀
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="$ROOT/battery-doctor/build/libs/battery-doctor.jar"
export LC_ALL=C.UTF-8
[ -d /opt/android-sdk ] && export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

if [ "${E2E_BUILD:-1}" = "1" ] || [ ! -f "$JAR" ]; then
  echo "[e2e] fatJar 빌드"
  (cd "$ROOT" && ./gradlew --no-daemon -q --project-cache-dir "${GRADLE_PROJECT_CACHE:-/home/claude/.gradle-doctor-cache}" :battery-doctor:fatJar 2>&1 | grep -v JAVA_TOOL_OPTIONS || true)
fi
[ -f "$JAR" ] || { echo "[e2e] jar 없음: $JAR"; exit 1; }

WORK="$(mktemp -d)"
export FAKE_ADB_STATE="$WORK/state"
FAKE="$HERE/fake-adb.sh"
chmod +x "$FAKE"
cd "$WORK"
echo "[e2e] 작업 폴더 $WORK"

run() { "${JAVA:-java}" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djava.awt.headless=true -Dbd.adb="$FAKE" -jar "$JAR" "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true; }
fake() { bash "$FAKE" "$@"; }
fail() { echo "[e2e] 실패: $*"; exit 1; }

echo "[e2e] connect"
run connect 192.168.0.50:5555 | tee connect.log | tail -3
grep -q "SM-L350" connect.log || fail "connect 가 모델을 출력하지 않음"
grep -q "lastAddress=192.168.0.50" data/config.properties || fail "lastAddress 저장 안 됨"

echo "[e2e] collect (+analyze)"
run collect | tee collect.log | tail -12
SNAP="$(ls -d data/snapshots/*/ | tail -1)"
for f in getprop battery batterystats batterystats_checkin power alarm sensorservice location deviceidle jobscheduler wearable gms_package packages_u packages_e packages_d packages_3 settings_global settings_secure settings_system doze_whitelist date; do
  [ -s "$SNAP/$f.txt" ] || fail "스냅샷에 $f.txt 없음"
done
[ -s "$SNAP/watch_log.csv" ] || fail "watch_log.csv 가 pull 되지 않음"
[ -s "$SNAP/report.html" ] || fail "report.html 없음"
grep -q "소프트웨어(서비스) 과소모" "$SNAP/report.html" || fail "보고서 판정이 기대(소프트웨어)와 다름"
grep -q "<svg" "$SNAP/report.html" || fail "워치 그래프 없음"
grep -q "com.example.drain" "$SNAP/report.html" || fail "범인 앱이 보고서에 없음"
grep -q "소프트웨어(서비스) 과소모" collect.log || fail "콘솔 판정 출력 없음"

echo "[e2e] analyze (재분석)"
run analyze | tail -3 | grep -q "report.html" || fail "analyze 출력 이상"

echo "[e2e] fix --apply"
[ "$(fake shell settings get global wifi_scan_always_enabled)" = "1" ] || fail "초기 wifi_scan 값"
run fix --apply wifi-scan-off,bg-restrict:com.example.drain | tee fix.log | tail -4
[ "$(wc -l < data/journal.jsonl)" = "2" ] || fail "journal 줄 수 $(wc -l < data/journal.jsonl) != 2"
[ "$(fake shell settings get global wifi_scan_always_enabled)" = "0" ] || fail "wifi_scan 이 0 으로 안 바뀜"
[ "$(fake shell cmd appops get com.example.drain RUN_ANY_IN_BACKGROUND)" = "RUN_ANY_IN_BACKGROUND: ignore" ] || fail "appops 가 ignore 로 안 바뀜"
grep -q '"before":"1"' data/journal.jsonl || fail "이전 값 기록 없음"
grep -q '"before":"allow"' data/journal.jsonl || fail "appops 이전 값 기록 없음"

echo "[e2e] undo --all"
run undo --all | tee undo.log | tail -2
grep -q "되돌린 조치: 2개" undo.log || fail "되돌린 개수"
[ "$(fake shell settings get global wifi_scan_always_enabled)" = "1" ] || fail "wifi_scan 이 복구되지 않음"
[ "$(fake shell cmd appops get com.example.drain RUN_ANY_IN_BACKGROUND)" = "RUN_ANY_IN_BACKGROUND: allow" ] || fail "appops 가 복구되지 않음"
[ "$(grep -c '"undone":true' data/journal.jsonl)" = "2" ] || fail "journal 되돌림 표시"
run undo --all | grep -q "되돌린 조치: 0개" || fail "두 번째 undo 는 0개여야 함"

echo "[e2e] evidence"
run evidence --purchase 2026-08-07 | tee evidence.log | tail -5
EV="$(ls -d data/evidence/*/ | tail -1)"
ls data/evidence/*.zip >/dev/null || fail "zip 없음"
for f in report.html summary.json watch_log.csv 01_삼성멤버스_오류보내기.txt 02_서비스센터_접수메모.txt 03_소비자분쟁해결기준_요구서.txt share/README_공유안내.txt share/snapshot/getprop.txt; do
  [ -f "$EV/$f" ] || fail "증거 패키지에 $f 없음"
done
[ ! -e "$EV/share/snapshot/location.txt" ] || fail "share 에 location.txt 가 있음"
grep -q "R3CX1234567" "$EV/snapshot/getprop.txt" || fail "원본 일련번호 누락"
! grep -q "R3CX1234567" "$EV/share/snapshot/getprop.txt" || fail "share 일련번호 마스킹 안 됨"
grep -q "74일" "$EV/03_소비자분쟁해결기준_요구서.txt" || fail "구입 경과일 계산"
grep -q "wifi-scan-off" "$EV/01_삼성멤버스_오류보내기.txt" || fail "신고문에 조치 이력 없음"

echo "[e2e] 메뉴(0 으로 종료)"
printf '0\n' | run | grep -q "증거 패키지" || fail "메뉴 출력"

echo "[e2e] OK  (작업 폴더: $WORK)"
