#!/usr/bin/env bash
# battery-doctor e2e (리눅스): 가짜 adb 로 실제 fat jar 를 끝까지 돌린다.
#   connect → collect(분석까지) → analyze → fix --apply wifi-scan-off,bg-restrict:com.example.drain → undo --all → evidence → 메뉴
#   → treat(자동 치료: 권한 주기 → 수집·분석 → 조치 자동 적용 → 측정 시작) → undo --all(권한 회수 포함)
# 확인: 스냅샷·report.html 생성, journal 2건, 되돌린 뒤 가짜 상태가 원래 값, 증거 zip 과 share/ 마스킹,
#       treat 가 권한 6건 + 안전·추천 조치를 기록하고 가짜 상태를 바꾸며, undo --all 이 전부(권한 포함) 복구.
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
# 워치 앱에 요약을 새로 쓰게 하고(셸 신호) 워치가 방금 짚은 Play 서비스 과소모를 소견으로
grep -q "워치 앱 요약 새로 쓰기" collect.log || fail "요약 새로 쓰기 신호를 보내지 않음"
! grep -q "있던 요약" collect.log || fail "새로 쓴 요약을 묵은 것으로 봄"
[ -s "$SNAP/watch_summary.txt" ] || fail "watch_summary.txt 가 pull 되지 않음"
grep -q "워치 앱: 최근 Google Play 서비스 과소모" "$SNAP/report.html" || fail "워치가 짚은 Play 서비스 과소모가 보고서에 없음"

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
# 구입(2026-08-07)부터 오늘까지 — 날짜가 지나도 맞게 그날 계산
DAYS=$(( ( $(date -d "$(date +%F)" +%s) - $(date -d 2026-08-07 +%s) ) / 86400 ))
grep -q "${DAYS}일" "$EV/03_소비자분쟁해결기준_요구서.txt" || fail "구입 경과일 계산 (${DAYS}일 기대)"
grep -q "wifi-scan-off" "$EV/01_삼성멤버스_오류보내기.txt" || fail "신고문에 조치 이력 없음"

echo "[e2e] 메뉴(0 으로 종료)"
printf '0\n' | run | tee menu.log | grep -q "증거 패키지" || fail "메뉴 출력"
grep -q " 7  권한 주기" menu.log || fail "메뉴 7 없음"
grep -q " 8  자동 치료" menu.log || fail "메뉴 8 없음"

echo "[e2e] treat (자동 치료: 권한 주기 → 수집·분석 → 조치 → 측정 시작)"
OUR=com.mywatch.kakaomap
BEFORE_LINES="$(wc -l < data/journal.jsonl)"
RESETS_BEFORE="$( [ -f "$FAKE_ADB_STATE/resets" ] && wc -l < "$FAKE_ADB_STATE/resets" || echo 0 )"
[ "$(fake shell settings get global wifi_scan_always_enabled)" = "1" ] || fail "treat 전 wifi_scan 값(되돌린 뒤라 1 이어야)"
fake shell dumpsys package $OUR | grep -q "android.permission.BATTERY_STATS: granted=false" || fail "treat 전 권한 상태"
run treat | tee treat.log | tail -32
grep -q "자동 치료 요약" treat.log || fail "treat 요약 없음"
grep -q "권한 주기: 6개 모두 성공" treat.log || fail "권한 주기 결과가 '모두 성공'이 아님"
grep -q "BATTERY_STATS granted=true, WRITE_SECURE_SETTINGS granted=true, GET_USAGE_STATS allow" treat.log || fail "권한 확인 줄 없음"
# 안전 4(캐시 정리 포함) + 범인 앱 주의 3 + Play 서비스 다시 시작 1 (견본 덤프는 Play 서비스도 짚는다)
grep -q "적용한 조치 8개" treat.log || fail "적용한 조치 수(8 기대)"
grep -q "(wifi-scan-off): 1 → 0" treat.log || fail "이전 값 → 새 값 표시 없음"
grep -q "(bg-restrict:com.example.drain): allow → ignore" treat.log || fail "appops 이전 값 → 새 값 표시 없음"
grep -q "건너뛴 추천" treat.log || fail "건너뛴 추천 절 없음"
grep -q "clear:com.example.drain.*--strong" treat.log || fail "강함 후보 안내(--strong) 없음"
grep -qE "범인 앱: .*com\.example\.drain" treat.log || fail "범인 앱 없음"
grep -q "24시간 뒤 메뉴 2" treat.log || fail "다음 안내 없음"
grep -q "배터리 통계 초기화: 완료" treat.log || fail "측정 시작 안내 없음"
grep -q "report.html" treat.log || fail "보고서 경로 없음"
! grep -q "정말 적용하려면" treat.log || fail "treat 가 질문을 함"
# journal: 새 기록 = 권한 6건 + 안전 4건(캐시 정리 포함) + 범인 앱 주의 3건 + Play 서비스 다시 시작 1건, 전부 성공·미되돌림, 금지 조치 없음
NEW="$(tail -n +$((BEFORE_LINES + 1)) data/journal.jsonl)"
for id in grant:android.permission.BATTERY_STATS grant:android.permission.WRITE_SECURE_SETTINGS grant:android.permission.DUMP grant:android.permission.READ_LOGS grant:GET_USAGE_STATS grant:WRITE_SETTINGS \
          wifi-scan-off ble-scan-off adaptive-battery-on trim-caches bg-restrict:com.example.drain bucket-restricted:com.example.drain doze-unwhitelist:com.example.drain gms-restart; do
  printf '%s\n' "$NEW" | grep -q "\"id\":\"$id\"" || fail "journal 에 $id 없음"
done
[ "$(printf '%s\n' "$NEW" | grep -c '"id":')" = "14" ] || fail "treat 의 journal 기록 수 $(printf '%s\n' "$NEW" | grep -c '"id":') != 14"
[ -s "$FAKE_ADB_STATE/trims" ] || fail "treat: 캐시 정리(pm trim-caches)가 실행되지 않음"
! printf '%s\n' "$NEW" | grep -q '"undone":true' || fail "새 기록이 되돌림으로 표시됨"
! printf '%s\n' "$NEW" | grep -q '"ok":false' || fail "treat 중 실패한 조치가 있음"
! printf '%s\n' "$NEW" | grep -qE '"id":"(aod-off|clear:|reboot|gms-rollback|rollback:|force-stop:|disable:|app-standby-on)' || fail "자동 치료가 적용하면 안 되는 조치를 적용함"
printf '%s\n' "$NEW" | grep -q '"id":"grant:android.permission.DUMP".*"undo":\[\["shell","pm","revoke","com.mywatch.kakaomap","android.permission.DUMP"\]\]' || fail "grant 기록의 undo 가 pm revoke 가 아님"
printf '%s\n' "$NEW" | grep -q '"id":"grant:GET_USAGE_STATS".*"undo":\[\["shell","appops","set","com.mywatch.kakaomap","GET_USAGE_STATS","default"\]\]' || fail "appops grant 기록의 undo 가 default 가 아님"
# 가짜 상태가 바뀌었나
[ "$(fake shell settings get global wifi_scan_always_enabled)" = "0" ] || fail "treat: wifi_scan 이 0 으로 안 바뀜"
[ "$(fake shell settings get global ble_scan_always_enabled)" = "0" ] || fail "treat: ble_scan 이 0 으로 안 바뀜"
[ "$(fake shell settings get global adaptive_battery_management_enabled)" = "1" ] || fail "treat: 적응형 배터리가 1 로 안 바뀜"
[ "$(fake shell settings get global app_standby_enabled)" = "1" ] || fail "treat: app_standby 는 그대로 1 이어야"
[ "$(fake shell cmd appops get com.example.drain RUN_ANY_IN_BACKGROUND)" = "RUN_ANY_IN_BACKGROUND: ignore" ] || fail "treat: appops 가 ignore 로 안 바뀜"
[ "$(fake shell am get-standby-bucket com.example.drain)" = "restricted" ] || fail "treat: 대기 버킷이 restricted 로 안 바뀜"
! fake shell cmd deviceidle whitelist | grep -q ",com.example.drain," || fail "treat: Doze 예외가 안 풀림"
fake shell cmd deviceidle whitelist | grep -q ",com.google.android.gms," || fail "treat: 시스템 Doze 예외를 건드림"
for perm in BATTERY_STATS WRITE_SECURE_SETTINGS DUMP READ_LOGS; do
  fake shell dumpsys package $OUR | grep -q "android.permission.$perm: granted=true" || fail "treat: $perm 이 granted=true 가 아님"
done
[ "$(fake shell appops get $OUR GET_USAGE_STATS)" = "GET_USAGE_STATS: allow" ] || fail "treat: GET_USAGE_STATS 가 allow 가 아님"
[ "$(fake shell appops get $OUR WRITE_SETTINGS)" = "WRITE_SETTINGS: allow" ] || fail "treat: WRITE_SETTINGS 가 allow 가 아님"
[ "$(wc -l < "$FAKE_ADB_STATE/resets")" = "$((RESETS_BEFORE + 1))" ] || fail "treat: batterystats --reset 이 한 번 더 실행되지 않음"
[ "$(ls -d data/snapshots/*/ | wc -l)" = "2" ] || fail "treat 가 새 스냅샷을 만들지 않음"
SNAP2="$(ls -d data/snapshots/*/ | tail -1)"
[ -s "$SNAP2/report.html" ] || fail "treat 의 report.html 없음"

echo "[e2e] undo --all (treat 되돌리기 — 권한 회수 포함)"
run undo --all | tee undo2.log | tail -2
# 캐시 정리·Play 서비스 다시 시작은 되돌릴 것이 없는 일회성 조치 — 되돌릴 수 있는 12개만
grep -q "되돌린 조치: 12개" undo2.log || fail "되돌린 개수(12 기대): $(cat undo2.log)"
[ "$(fake shell settings get global wifi_scan_always_enabled)" = "1" ] || fail "undo2: wifi_scan 복구 안 됨"
[ "$(fake shell settings get global ble_scan_always_enabled)" = "1" ] || fail "undo2: ble_scan 복구 안 됨"
[ "$(fake shell settings get global adaptive_battery_management_enabled)" = "0" ] || fail "undo2: 적응형 배터리 복구 안 됨"
[ "$(fake shell cmd appops get com.example.drain RUN_ANY_IN_BACKGROUND)" = "RUN_ANY_IN_BACKGROUND: allow" ] || fail "undo2: appops 복구 안 됨"
[ "$(fake shell am get-standby-bucket com.example.drain)" = "10" ] || fail "undo2: 대기 버킷 복구 안 됨"
fake shell cmd deviceidle whitelist | grep -q ",com.example.drain," || fail "undo2: Doze 예외 복구 안 됨"
for perm in BATTERY_STATS WRITE_SECURE_SETTINGS DUMP READ_LOGS; do
  fake shell dumpsys package $OUR | grep -q "android.permission.$perm: granted=false" || fail "undo2: $perm 이 회수되지 않음"
done
[ "$(fake shell appops get $OUR GET_USAGE_STATS)" = "GET_USAGE_STATS: default" ] || fail "undo2: GET_USAGE_STATS 회수 안 됨"
[ "$(fake shell appops get $OUR WRITE_SETTINGS)" = "WRITE_SETTINGS: default" ] || fail "undo2: WRITE_SETTINGS 회수 안 됨"
[ "$(grep -c '"undone":true' data/journal.jsonl)" = "$((BEFORE_LINES + 12))" ] || fail "journal 되돌림 표시 수"
run undo --all | grep -q "되돌린 조치: 0개" || fail "treat 뒤 두 번째 undo 는 0개여야 함"

echo "[e2e] OK  (작업 폴더: $WORK)"
