# 워치9 배터리 닥터 — 설계 (2026-10-19)

## 목표

갤럭시 워치9(44mm, One UI 9 Watch / Wear OS 7)의 배터리가 비정상적으로 빨리 닳을 때, **루트 없이** 할 수 있는 모든 것을 도구로 만든다.

1. **원인을 숫자로 못 박는다** — 시스템 배터리 화면이 보여 주지 않는 실제 소모(mAh/h), 잔량 표시(게이지) 오류, 화면 꺼진 동안 깨어 있던 비율, 웨이크락·알람·센서·무선의 범인 앱.
2. **소프트웨어 소모를 차단한다** — ADB로만 바꿀 수 있는 설정·앱 비활성화·백그라운드 제한을 항목별로 고르고, 전부 되돌릴 수 있게 기록한다.
3. **증거를 남긴다** — 서비스센터·삼성멤버스·소비자원에 그대로 낼 수 있는 로그 묶음과 신고문을 만든다.

## 하지 않는 것

- 삼성 펌웨어(게이지 버그, 시스템 서비스) 자체의 수정, 배터리 셀 교체, 강제 재시작(물리 버튼) 자동화, 루트·부트로더 해제.
- 서버·클라우드 전송. 모든 기록은 워치와 PC 안에만 남는다.
- 이 앱(갤럭시워치 전용앱) 자체의 소모 분석에 치우치지 않는다 — 다른 앱과 똑같이 한 항목으로만 다룬다.

## 사용 환경 (확인된 사실)

- 기기: 갤럭시 워치9 44mm(445 mAh, 40mm는 390 mAh), 스냅드래곤 Wear Elite SW6100, One UI 9.0 Watch / Wear OS 7. 모델명 SM-L350(44mm BT)·SM-L355(44mm LTE)·SM-L340(40mm BT)·SM-L345(40mm LTE)로 추정 — 첫 수집 결과의 `ro.product.model`로 확정.
- 삼성 공식: AOD 끔 최대 40시간, AOD 켬 최대 30시간(시간당 약 2.5~3.3%). 전문 리뷰 실측: AOD 끔 30~35시간, 수면 측정 포함 24시간에 60~70% 소모.
- 비정상 기준(Android vitals 워치페이스 기준 준용): 앱을 안 쓰고 충전 중이 아닌 구간에서 **시간당 4.44% 초과 = 과소모, 3.2% 미만이 목표**. 기여 요인: CPU ≥90초/시간, partial wakelock ≥18.5초/시간. 백그라운드에서 1시간 연속 wakelock = stuck.
- 워치 내장 배터리 화면의 앱 목록은 **앱 소모만** 보여 준다(시스템·BT·Wi-Fi 제외) — 합계가 실제보다 작은 것은 설계이지 숨은 소모의 증거가 아니다. 범인 찾기는 ADB `dumpsys batterystats`로만 가능.
- ADB: `설정 > 워치 정보 > 소프트웨어 > 소프트웨어 버전` 5번 탭 → 개발자 옵션 → ADB 디버깅 + 무선 디버깅(+ 연결 작업 중엔 '자동 Wi-Fi 끄기') → `adb pair IP:페어링포트 코드` → `adb connect IP:연결포트`. 블루투스 디버깅은 Wear OS 3부터 불가, USB 없음 → Wi-Fi 전용. ADB 30 이상, PC와 같은 Wi-Fi.
- 사용자 PC: Windows, Android Studio 설치됨(= JBR Java + Android SDK platform-tools의 adb.exe 있음). 별도 설치 없이 돌아가야 한다.
- 워치 앱 배포: `main` 푸시 → GitHub Actions가 서명 APK·델타를 릴리스 → 워치의 전용앱이 자동 업데이트.

## 전체 구조

```
kakao-watch-map/
├─ battery-core/      순수 Kotlin(JVM) 라이브러리 — 표본 모델, CSV, 소모율·게이지 이상·용량 추정·판정 (워치 앱과 PC 툴킷이 같이 씀)
├─ app/               워치 앱 — battery 패키지: 15분 기록, 배터리 화면, 알림, 내보내기 (battery-core 사용)
└─ battery-doctor/    PC 툴킷(Kotlin/JVM CLI) — ADB 수집, dumpsys 파서, 분석·판정, HTML 보고서, 차단 조치·되돌리기, 증거 패키지 (battery-core 사용)
```

데이터 흐름: 워치가 15분마다 표본을 CSV에 쌓음 → PC 툴킷이 ADB로 CSV와 dumpsys 묶음을 가져와 분석 → 보고서·조치·증거.

## 1. battery-core (공용 라이브러리)

순수 Kotlin, 안드로이드 의존 없음(java.time 허용 — minSdk 30). JUnit 4 테스트.

### 표본 `BatterySample`

| 필드 | 뜻 |
|---|---|
| `t: Long` | epoch ms |
| `level: Int` | 잔량 % |
| `chargeUah: Int?` | 남은 전하량 µAh (`BATTERY_PROPERTY_CHARGE_COUNTER`; 미지원이면 null) |
| `voltageMv: Int?` | 전압 mV |
| `tempDeciC: Int?` | 온도 0.1℃ |
| `currentUa: Int?` | 순간 전류 µA (`BATTERY_PROPERTY_CURRENT_NOW`; 부호는 기기마다 달라 절댓값과 충전 상태로 해석) |
| `status: Status` | CHARGING / DISCHARGING / FULL / NOT_CHARGING / UNKNOWN |
| `plugged: Boolean` | 충전기 연결 |
| `interactive: Boolean` | 표본 시점에 화면 켜짐 |
| `event: Event` | PERIODIC / POWER_CONNECTED / POWER_DISCONNECTED / BOOT / APP_OPEN / LOW / EXPERIMENT_START / EXPERIMENT_END |
| `note: String` | 실험 이름 등 (쉼표·줄바꿈 없음) |

### CSV `SampleCsv`

한 줄 한 표본, 헤더 1줄: `t,level,chargeUah,voltageMv,tempDeciC,currentUa,status,plugged,interactive,event,note`. 빈 값은 빈 칸. 파서는 깨진 줄을 건너뛴다(기록 중 전원 꺼짐 대비). 버전 표시를 위해 첫 줄 주석 `# battery-log v1`.

### 분석 `DrainAnalyzer`

입력: 표본 목록(시간순 정렬 보장), 공칭 용량(mAh, 모델로 결정: L350/L355 → 445, L340/L345 → 390, 모르면 null).

- **방전 구간**: 연속한 표본 중 `plugged == false`이고 level이 오르지 않는 구간. 충전·꽂힘은 제외.
- **창 소모율** `rate(windowHours)`: 창 안 방전 구간들의 Δlevel 합 / 방전 시간 합 → %/h. `chargeUah`가 있으면 ΔµAh 합으로 **mAh/h**도.
- **대기 소모율**: 양 끝 표본이 모두 `interactive == false`인 방전 구간만으로 계산(워치페이스 기준 비교용).
- **밤사이**: 가장 최근의 00:00~07:00(기기 시간대) 창의 소모율과 총 소모.
- **실험 구간**: EXPERIMENT_START ~ EXPERIMENT_END(같은 note) 구간의 소모율. 끝 표시가 없으면 "진행 중".
- **게이지 이상** `GaugeAnomaly`:
  - JUMP_UP: 충전기 없이 연속 표본 사이 level이 +5 이상.
  - REBOOT_JUMP: BOOT 이벤트 표본의 level이 직전 표본보다 +10 이상(꺼졌다 켜지며 잔량 점프 — 17%→꺼짐→50% 패턴).
  - CLIFF: 20분 이하 간격에서 level이 −15 이상 떨어짐(화면 꺼짐, 전류 낮음일 때).
  - COUNTER_MISMATCH: `chargeUah`가 있을 때 한 방전 구간에서 전하량으로 계산한 Δ%(ΔµAh / 공칭 용량)와 표시 Δ%의 차이가 10포인트 이상.
- **용량 추정** `CapacityEstimate`: 충전 구간 중 level이 40포인트 이상 오른 구간에서 ΔµAh / Δlevel × 100 → 추정 만충 용량(mAh). 여러 구간이면 중앙값. 공칭 대비 % 표시. (`chargeUah` 없으면 "측정 불가").
- **판정** `Verdict` (퍼센트 기준이라 40mm도 같은 표를 쓴다 — 보고서에 그렇게 밝힘):

| 구간 | 정상 | 주의 | 비정상 |
|---|---|---|---|
| 대기(화면 꺼짐) | ≤3.2 %/h | 3.2~4.4 | >4.4 (Android vitals) |
| 24시간 평균(혼합 사용) | ≤3.3 (≥30시간) | 3.3~5.0 | ≥5.0 (≤20시간) |
| 밤사이(00~07시, 대기 구간이라 같은 vitals 기준) | ≤3.2 | 3.2~4.44 | >4.44 |
| 실험: 비행기 모드 / 절전 / 시계만 | ≤1.0 | 1.0~2.0 | >2.0 (경험칙 — 보고서에 그렇게 표시) |

  게이지 이상이 하나라도 있으면 판정에 "잔량 표시 오류 의심" 태그. 용량 추정이 공칭의 80% 미만이면 "배터리 용량 저하 의심" 태그.
- 표본이 2시간치 미만이면 "기록 모으는 중"으로 판정 보류.

## 2. 워치 앱 모듈 (`app/.../battery`)

### 기록

- `BatterySampleWorker`: WorkManager 주기 작업(15분, 고유 이름 `battery-sample`, KEEP). 표본 하나를 읽어 CSV에 덧붙인다. 제약 없음(충전 중에도 기록 — 충전 곡선이 용량 추정에 필요). `MapApp.onCreate`와 BOOT_COMPLETED에서 예약.
- `BatteryEventsReceiver`(매니페스트): `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, `ACTION_BOOT_COMPLETED`, `ACTION_BATTERY_LOW` → 해당 event로 즉시 표본 1개(OneTimeWorkRequest, expedited 아님 — 수 초 늦어도 됨). 권한 `RECEIVE_BOOT_COMPLETED` 추가.
  - **v2.40 고침:** 충전기 꽂음·뺌·배터리 부족은 암시적 방송이라 targetSdk 26+ 앱의 매니페스트 수신기로는 오지 않는다(Android 8 제한, 예외 목록에 없음) — 매니페스트엔 `BOOT_COMPLETED` 만 남기고, 충전 시작은 `ChargeWatchWorker`('충전 중' 조건의 한 번짜리 작업, 돌면 POWER_CONNECTED 한 줄·10분 뒤 다시 걸기)가 잡는다. 그래도 놓친 짧은 충전 때문에 게이지 판정이 뒤집히지 않게, 잔량 상승은 그 사이 충전으로 오를 수 없는 만큼(분당 2%p 넘게)일 때만 '충전 없이 상승'·충전기 위 부팅 점프로 센다.
- `BatteryProbe`: 현재 표본 읽기 — sticky `ACTION_BATTERY_CHANGED` + `BatteryManager.getIntProperty`(CHARGE_COUNTER, CURRENT_NOW; `Int.MIN_VALUE`/0 은 미지원으로 보고 null) + `PowerManager.isInteractive`.
- `BatteryLog`: 파일 `getExternalFilesDir(null)/battery/log.csv`(ADB `adb pull`이 닿는 곳; 없으면 `filesDir`). 덧붙이기 전용. 30일보다 오래된 줄은 하루 한 번 잘라낸다(임시 파일에 다시 써서 교체). 실험 표시 줄도 같은 파일.
- 비용: 15분에 한 번 수십 ms, 파일 수백 KB/월. 웨이크락 없음.

### 화면 `BatteryActivity` (첫 화면 '배터리' 버튼)

기존 `WearList`/`Rows` 스타일의 세로 목록. 위에서부터:

1. **지금**: 잔량 %, 전압, 온도, 전류(화면이 열려 있는 동안 2초마다 갱신), 충전 상태.
2. **소모율**: 1시간 / 8시간 / 24시간의 %/h(그리고 mAh/h), 각 칸 색(정상 초록·주의 노랑·비정상 빨강).
3. **판정**: 한 줄 결론 + 근거 2~3줄(예: "대기 중 시간당 5.8% — 비정상(기준 4.4%). 잔량 점프 2회 — 잔량 표시 오류 의심").
4. **밤사이**: 00:00~07:00 소모와 %/h.
5. **잔량 이상**: 최근 7일의 게이지 이상 목록(시각·종류·전후 %).
6. **용량 추정**: 추정 mAh / 공칭 mAh (%), 근거 충전 세션 수. 미지원이면 "이 워치는 전하량 계수기를 앱에 열어 주지 않음".
7. **실험**: 버튼 '실험 시작'(비행기 모드 / 절전 모드 / 시계만 / 평소대로 중 고름 → EXPERIMENT_START 기록, 모드 설정은 사용자가 직접) / '실험 끝'. 진행 중이면 경과와 지금까지 %/h.
8. **내보내기**: CSV 경로 표시, '요약 새로 쓰기' 버튼(`summary.txt` 생성 — PC 없이도 서비스센터에 보여 줄 수 있게).
9. **도움말**: 강제 재시작(홈+뒤로 7초 이상, 안 되면 30초), 설정 > 배터리 > 헬스 기능 제한 / 대기 중인 앱, 설정 > 버튼 및 제스처 > 손목 올려 말하기 끄기, 리커버리의 캐시 파티션 삭제(구형 워치에 있던 메뉴 — 있으면 시도), PC 툴킷 안내 한 줄.

접근성: 모든 칸에 contentDescription. 둥근 화면 여백은 기존 `RoundScreen` 규칙.

### 알림

주기 표본마다 검사: 최근 2시간이 모두 방전·화면 꺼짐 비율 ≥75%이고 %/h ≥5.0이면 알림(채널 `battery-watch`, 낮은 중요도, 6시간에 1번까지). 본문: "2시간 동안 시간당 X.X% — 비정상. 홈+뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작해 보세요". 누르면 배터리 화면.

강제 재시작의 근거: 삼성 사용 설명서·삼성전자서비스는 "홈 + 뒤로 가기 버튼을 7초 이상(꺼질 때까지)". 미국 커뮤니티 매니저는 "약 32초 이상"을 안내했고 사용자들이 "30초"로 전한다 — 도움말에는 "7초 이상 누르고 있다가, 효과가 없으면 30초까지"로 둘 다 적는다.

One UI 9 Watch의 배터리 메뉴(공식 설명서 확인): 설정 > 배터리에 절전 모드 · **헬스 기능 제한(Limit health features)** · 배터리 사용량 · 대기 중인 앱(Sleeping apps) · 시계만 사용 · 배터리 보호 · 충전 정보 표시. 손목 올려 말하기·제스처는 설정 > **버튼 및 제스처**. 도움말 화면은 이 경로를 그대로 안내한다.

### 첫 화면

`activity_hub`의 업데이트 버튼 줄을 가로 두 칸으로: `업데이트` | `배터리`(아이콘 `ic_battery`). 글자 크기를 키운 워치에서도 양 끝이 잘리지 않게 각 버튼 최소 너비 76dp, 글자 13sp. `HubActivity.open(BatteryActivity::class.java)`는 새 작업으로 열지 않고 같은 작업에서 연다(뒤로 가면 첫 화면).

### 테스트 (Robolectric, 기존 `rt.sh`)

- `BatteryLogTest`: 덧붙이기·깨진 줄 건너뛰기·30일 잘라내기.
- `BatterySampleWorkerTest`: 앱 시작 시 주기 작업이 예약됨; 작업 실행 시 표본 한 줄(ShadowBatteryManager로 값 주입).
- `BatteryEventsReceiverTest`: 충전기 꽂음 → POWER_CONNECTED 표본.
- `BatteryAlertTest`: 2시간 5%/h 화면 꺼짐 → 알림 1회, 6시간 안에 재알림 없음.
- `BatteryActivityTest`: 표본이 있을 때 소모율·판정 글이 보임; '실험 시작' → EXPERIMENT_START 줄.
- `HubTest`: '배터리' 버튼이 `BatteryActivity`를 연다.

## 3. PC 툴킷 `battery-doctor`

Kotlin/JVM 17 CLI, 의존성은 kotlin-stdlib + battery-core만(JSON은 직접 쓰는 최소 직렬화, HTML은 문자열 템플릿). 배포물은 `battery-doctor.zip` = `BatteryDoctor.bat` + `battery-doctor.jar` + `README.txt`.

### 실행기 `BatteryDoctor.bat`

1. `chcp 65001`, `cd /d %~dp0`.
2. Java 찾기: `%JAVA_HOME%\bin\java.exe` → `%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe` → `%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe` → `where java`. 없으면 안내 후 종료.
3. adb 찾기: `%ANDROID_HOME%` / `%ANDROID_SDK_ROOT%` `\platform-tools\adb.exe` → `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` → `where adb`. 없으면 안내(SDK Manager에서 Platform-Tools).
4. `java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dbd.adb="<adb>" -jar battery-doctor.jar %*`. 인자가 없으면 대화형 메뉴.

### 메뉴 / 하위 명령

| 메뉴 | 명령 | 하는 일 |
|---|---|---|
| 1 연결 | `connect [ip:port]` | 무선 디버깅 켜는 법 안내(5번 탭 등) → 페어링 필요하면 `adb pair` 대화 → `adb connect` → `adb devices`로 확인, 모델·펌웨어 출력. 마지막 주소를 `data/config.properties`에 저장 |
| 2 수집·분석 | `collect [--bugreport]` → `analyze <snapshot>` | 아래 수집 목록을 `data/snapshots/<yyyyMMdd-HHmmss>/`에 저장(워치 CSV `pull` 포함) → 바로 분석 → `report.html` 생성·브라우저로 열기 |
| 3 측정 시작 | `reset` | `dumpsys batterystats --reset`(깨끗한 측정 구간 시작) + 안내: 자동 Wi-Fi 끄기를 다시 끄고 평소처럼 쓰다가 충전기에 올리기 전에 2번 |
| 4 차단 조치 | `fix [--apply ids]` | 최신 스냅샷을 바탕으로 조치 목록(현재 상태·추천 표시) → 번호로 고름 → 적용 → `data/journal.jsonl`에 기록 |
| 5 되돌리기 | `undo [--all|id]` | 기록 역순으로 복구 |
| 6 증거 패키지 | `evidence [--purchase 2026-08-07]` | 최신 스냅샷·보고서·워치 CSV·신고문 3종을 `data/evidence/<날짜>/`와 zip으로; 공유용 마스킹본 `share/` |

### 수집 목록 (`collect`)

모두 `adb -s <serial> shell …`, 각각 `<이름>.txt`로. 실패해도 다음으로 넘어가고 보고서에 "수집 실패"로 표시.

`getprop`, `dumpsys battery`, `dumpsys batterystats`(기본 텍스트), `dumpsys batterystats --checkin`, `dumpsys power`, `dumpsys alarm`, `dumpsys sensorservice`, `dumpsys location`, `dumpsys deviceidle`, `dumpsys jobscheduler`, `dumpsys activity service WearableService`, `dumpsys package com.google.android.gms`, `pm list packages -U`, `pm list packages -e`, `pm list packages -d`, `pm list packages -3`, `settings list global`, `settings list secure`, `settings list system`, `cmd deviceidle whitelist`, `date +%s`, 워치 CSV(`/sdcard/Android/data/com.mywatch.kakaomap/files/battery/log.csv` → `adb pull`). `--bugreport`면 `adb bugreport <snapshot>/bugreport.zip`.

### 파서 (각각 단위 테스트, 입력은 견본 덤프 + 사용자 실제 덤프가 오면 교체)

- `BatteryDump`: level, scale, voltage, temperature, health, status, `Charge counter`, 삼성 확장 필드(`mSavedBatteryAsoc`, `mSavedBatteryMaxTableWh`, `mSavedBatteryUsage` 등 `mSavedBattery*`는 이름 그대로 전부 보관).
- `BatteryStatsText`: "Statistics since last charge" 아래 — Time on battery(실시간·%), Time on battery screen off, Screen on 시간, Screen off awake(= 꺼진 동안 깨어 있던 시간; "Screen off: … (xx%) … Awake" 형태를 정규식으로), Discharge(총·화면 켬·화면 끔·doze), Estimated power use(Capacity, Computed drain, actual drain, 항목별 mAh: Uid u0aNN/Screen/Idle/Cell standby/Bluetooth/Wifi/…), All kernel wake locks(이름·시간·횟수), All partial wake locks(uid·태그·시간·횟수), All wakeup reasons, 앱별 섹션(`u0aNN:` 아래 Wake lock 줄, Sensor 줄, GPS 줄). 어떤 섹션이 없어도 나머지는 채운다.
- `BatteryStatsCheckin`: `9,<uid>,l,<key>,…` 행에서 `uid`(uid→패키지), `wl`(wakelock), `kwl`, `wr`, `pwi`(power use item), `bt`(battery time), `dc`(discharge), `sr`(sensor), `gn`. 텍스트 파서의 보조·검증용(둘 중 하나만 성공해도 됨).
- `AlarmDump`: "Top Alarms"/"Alarm Stats"의 패키지별 wakeup 횟수·시간.
- `SensorDump`: 활성 센서와 등록 앱·샘플링 주기(`Active sensors`/`Connections` 또는 `Sensor Listeners`).
- `LocationDump`: 위치 요청 중인 패키지와 간격(화면·보고서에만 쓰고 공유본에서는 통째로 제외).
- `DeviceIdleDump`: 현재 상태(ACTIVE/IDLE 등), whitelist.
- `Packages`: `pm list packages -U` → uid→패키지, 비활성 목록, 서드파티 목록.
- `SettingsDump`: key=value 맵 3종.
- `PowerDump`: 지금 쥔 웨이크락(태그·패키지).
- 워치 CSV: battery-core의 `SampleCsv`.

### 분석·판정 (`Analyzer`)

사실(facts) → 소견(findings, 각각 심각도·근거 줄·관련 패키지) → 판정(verdict).

소견 규칙(수치는 보고서에 기준 출처와 함께 표시):

- **화면 꺼짐 중 깨어 있던 비율** = screen-off awake / screen-off time. >10% 주의, >20% 비정상(경험칙).
- **앱 partial wakelock**: 배터리 사용 시간 기준 시간당 18.5초 초과(vitals 기여 요인) → 주의, 합계 1시간 초과 또는 시간당 5분 초과 → 비정상. uid→패키지로 이름 붙임.
- **커널 웨이크락** 상위 5개와 시간 — 설명 표(예: `PowerManagerService.WakeLocks`는 앱 웨이크락 합, `*radio*`/`qcom_rx_wakelock` 같은 무선 계열은 통신).
- **깨움 이유(wakeup reasons)** 상위 5개.
- **알람**: 패키지별 wakeup 알람이 시간당 20회 초과 → 주의, 60회 초과 → 비정상.
- **센서**: 화면 꺼진 상태에서 시스템 외 앱이 가속도·심박 센서를 연속 등록 → 주의(패키지 표시).
- **무선·GPS**: 셀룰러 라디오 활성 시간, Wi-Fi 스캔 횟수, GPS 시간(가능한 범위에서).
- **추정 전력 순위**: 상위 10개(패키지명 + mAh + %). Google Play 서비스가 15% 초과면 2026년 Play 서비스 과소모 패턴으로 표시. `Computed drain` 대 `actual drain` 차이도 표시.
- **게이지**: `dumpsys battery`의 Charge counter / level로 역산한 용량이 공칭의 60% 미만 또는 140% 초과 → 잔량 표시 불일치. 워치 CSV의 GaugeAnomaly도 합산.
- **용량**: 워치 CSV의 CapacityEstimate, 삼성 `mSavedBatteryAsoc`(있으면 그대로 "삼성 기록 배터리 건강도").
- **설정**: `wifi_scan_always_enabled=1`, `ble_scan_always_enabled=1`, `adaptive_battery_management_enabled=0` 등 위험 설정; 존재하지 않는 키는 "해당 없음".
- **사용 패턴**: 화면 켜짐 비율, 워치 CSV의 interactive 비율, 운동(GPS) 시간이 크면 "사용량 때문" 소견.

판정 트리(위에서 처음 맞는 것, 신뢰도 높음/중간/낮음 표시):

1. 표본·통계가 2시간 미만 → "측정 더 필요" + 측정 시작 안내.
2. 게이지 이상(REBOOT_JUMP·JUMP_UP·COUNTER_MISMATCH) 있음 → **잔량 표시(게이지)/펌웨어 문제 의심** — 강제 재시작, 증거 패키지, 서비스센터.
3. 깨어 있던 비율·웨이크락·알람·센서 중 비정상 있음 → **소프트웨어(서비스) 과소모** — 범인 패키지, 해당 조치 추천(캐시·백그라운드 제한·비활성화), 조치 후 `reset`·재측정.
4. 화면 켜짐·GPS·무선이 지배적이고 대기 소모율은 정상 → **사용 패턴** — 설정 조치 추천.
5. 모두 정상인데 %/h가 높고(실험 구간에서도) → **하드웨어 의심** — 용량 추정 첨부, 서비스센터·소비자 절차.
6. 아무 이상 없음 → "정상 범위(이 모델의 한계)".

### 보고서 `report.html`

한국어, 한 파일(CSS 인라인), 폰 화면에서도 읽히게. 순서: 결론 카드(판정·신뢰도·한 줄 이유) → 지금 할 일 3가지 → 수치 요약 표 → 소견 목록(심각도 색) → 범인 앱 표 → 워치 기록 그래프(SVG 선그래프: 잔량 vs 시간, 충전 구간 음영, 이상 지점 표시) → 설정 점검 → 원자료 보기(접힘). 기준치마다 출처 한 줄.

### 차단 조치 카탈로그 (`Catalog`)

항목 = id, 이름, 설명, 등급(안전/주의/강함), 조건(키 존재·패키지 설치), 현재 상태 읽기, 적용 명령, 되돌리기 명령. 적용 전 현재 값을 `journal.jsonl`에 기록.

안전(기능 손실 없음 또는 설정에서 바로 복구):
- `wifi-scan-off` `settings put global wifi_scan_always_enabled 0`
- `ble-scan-off` `settings put global ble_scan_always_enabled 0`
- `adaptive-battery-on` `settings put global adaptive_battery_management_enabled 1`
- `app-standby-on` `settings put global app_standby_enabled 1`
- `disable:<pkg>` 안 쓰는 앱 비활성화 `pm disable-user --user 0 <pkg>` / 되돌리기 `pm enable --user 0 <pkg>`. 후보 카탈로그(설치되어 있고 켜진 것만 보임; 이름은 첫 수집의 패키지 목록으로 확정): Bixby(`com.samsung.android.bixby.agent`), Google 앱/어시스턴트·Gemini(`com.google.android.googlequicksearchbox`, 관련 패키지), Google 지도(`com.google.android.apps.maps`), Google 월렛(`com.google.android.apps.walletnfcrel`), 삼성 월렛(`com.samsung.android.samsungpay.gear`), Gmail·캘린더·메시지(Google), 날씨, 리마인더 등. 시스템 핵심(Play 서비스, Samsung Health, 시스템 UI, 전화, 설정, Galaxy Wearable 연결)은 목록에서 제외.
- `screen-timeout:<ms>` `settings put system screen_off_timeout <ms>` (키가 있을 때만)
- `batterystats-reset` 조치 뒤 깨끗한 측정 시작

주의(기능 영향 있음, 되돌리기 가능):
- `bg-restrict:<pkg>` `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` / 되돌리기 이전 모드
- `bucket-restricted:<pkg>` `am set-standby-bucket <pkg> restricted` / 이전 버킷
- `doze-unwhitelist:<pkg>` `cmd deviceidle whitelist -<pkg>` / `+<pkg>`
- `force-stop:<pkg>` (일회성)
- `aod-off` Wear 설정 키(`ambient_enabled` 등)가 존재할 때만 — 사용자가 AOD를 쓰면 고르지 않음

강함(기본 꺼짐, 빨간 경고, 적용 전 한 번 더 확인):
- `gms-rollback` `pm uninstall-system-updates com.google.android.gms` — Play 스토어가 다시 올릴 수 있음(워치 Play 스토어 자동 업데이트 끄기 안내). 되돌리기 = Play 스토어 업데이트.
- `rollback:<pkg>` 같은 명령을 Google 앱 등 시스템 앱에.
- `clear:<pkg>` `pm clear <pkg>` — 데이터 삭제, 되돌릴 수 없음(계정 재로그인 가능성 경고).
- `reboot` `adb reboot`

추천 로직: 분석 소견의 범인 패키지에 따라 해당 항목을 미리 체크(강함은 절대 자동 체크 안 함). 사용자는 번호로 토글 후 `적용`.

### 증거 패키지 (`evidence`)

`data/evidence/<yyyyMMdd>/`: `report.html`, `snapshot/`(원자료), `watch_log.csv`, `summary.json`(판정·수치), 신고문 3종(값은 스냅샷에서 채움, 모르는 값은 `[  ]`로 남김):

1. `01_삼성멤버스_오류보내기.txt` — 증상(측정치), 기기·펌웨어, 시도한 조치, 첨부 안내(Galaxy Wearable > ⋮ > 설정 > 문의하기 > 오류 보내기, 시스템 로그 포함, 증상 직후 5분 안).
2. `02_서비스센터_접수메모.txt` — 요구 사항(배터리 효율 측정값 서면, 소프트웨어 재설치 이력 기재, 접수증·점검 결과지 수령), 측정 요약, 1588-3366.
3. `03_소비자분쟁해결기준_요구서.txt` — 공정위 고시 스마트폰(스마트워치 포함) 항목 인용: 구입 10일 이내 중요한 수리 → 교환/환급, 1개월 이내 → 교환/무상수리, 보증기간 내 수리 불가(동일 하자 2회 수리 후 재발 등) → 교환/환급; 웨어러블 품질보증 1년(배터리 1년); 1372 소비자상담센터 안내. `--purchase` 날짜가 있으면 경과일 계산.

`share/`에는 마스킹본: 이메일(`[email]`), 전화번호, `ro.serialno`·IMEI류, Wi-Fi SSID, 좌표 숫자(소수 4자리 이상 위경도 패턴) 마스킹, `dumpsys location`은 통째로 제외. 사용자가 저(Claude)나 커뮤니티에 올릴 때 이 폴더만 쓰도록 README에 적는다.

### 테스트

- 파서별 단위 테스트(견본 덤프 문자열).
- `AnalyzerTest`: 각 판정 가지가 맞는 소견에서 나오는지(게이지 → 2, 웨이크락 → 3 …).
- `CatalogTest`: 조건(키·패키지 존재)에 따라 항목이 보이거나 숨겨짐; 적용/되돌리기 명령 문자열.
- `JournalTest`: 적용 → 기록 → 되돌리기 역순.
- `EvidenceTest`: 마스킹 규칙(이메일·좌표·SSID), location 제외.
- `HtmlReportTest`: 필수 섹션·수치가 들어감, HTML 이스케이프.
- e2e: `FakeAdb`(리눅스 셸 스크립트, 견본 덤프를 돌려줌)로 `collect → analyze → fix --apply → undo` 전 과정을 실제 JAR로 실행해 파일·기록을 확인. 보고서는 Playwright(Chromium)로 열어 스크린샷 확인.

## 4. 빌드·배포

- `settings.gradle.kts`: `include(":app", ":battery-core", ":battery-doctor")`.
- `battery-core`: `org.jetbrains.kotlin.jvm`, `java { toolchain 17 }`(JVM 타깃은 app과 호환되게 17), 의존성 없음.
- `battery-doctor`: `org.jetbrains.kotlin.jvm`, `application`(mainClass `doctor.MainKt`), `fatJar` 태스크(stdlib + battery-core 포함), `distZip` 태스크(`BatteryDoctor.bat`, jar, README.txt → `build/dist/battery-doctor.zip`).
- `app`: `implementation(project(":battery-core"))`.
- release.yml: 테스트·빌드 명령을 `:app:testDebugUnitTest :battery-core:test :battery-doctor:test :app:assembleRelease :battery-doctor:distZip`로, 릴리스 파일에 `battery-doctor/build/dist/battery-doctor.zip` 추가. 릴리스 노트에 툴킷 한 줄.
- 버전: 다음 릴리스(v128, 2.28)에 워치 모듈과 툴킷이 같이 나감.

## 5. 개인정보·안전

- 저장소(공개)에는 사용자 집·계정·로그가 들어가지 않는다. 견본 덤프는 가공한 가짜 데이터.
- 툴킷은 네트워크를 쓰지 않는다(adb 외 프로세스 실행 없음). 수집물은 PC의 `data/`에만.
- 강함 등급 조치는 기본 꺼짐·재확인. 모든 조치는 기록·되돌리기.
- `pm disable-user`는 핵심 시스템 패키지를 거부 목록으로 막는다(Play 서비스, Samsung Health, 시스템 UI, 설정, 전화, 연결 관리자 등).

## 5-1. 배터리 지킴이 (2차, 2026-10-20) — 진단을 넘어 워치가 스스로 막기

사용자 요구: "증거 말고 해결책을 만들어라". 루트 없이 가능한 최대치는 **PC 에서 한 번 `pm grant` 로 개발용 권한을 받은 뒤, 워치 앱이 스스로 레버를 당기는 것**이다.

- 권한(PC 툴킷 메뉴 7 '권한 주기', `pm grant com.mywatch.kakaomap …`): `BATTERY_STATS`(다른 앱의 HealthStats — 웨이크락·CPU·센서·GPS·동기화), `WRITE_SECURE_SETTINGS`(절전 `low_power`, AOD `ambient_enabled`, 위치 `location_mode`), `DUMP`·`READ_LOGS`(예비), appops `GET_USAGE_STATS`·`WRITE_SETTINGS`. 동기화는 일반 권한(`WRITE_SYNC_SETTINGS`). 모두 개발용(development) 보호 수준이라 adb 로 줄 수 있고, 매니페스트에 선언해 두어야 한다.
- `battery-core`: `AppDrain`(누적 → 구간 사용량 차, 순위·표시: 웨이크락 시간당 18.5초(vitals)·구간 절반 이상이면 stuck·GPS·화면 밖 센서), `GuardianRules`(밤 모드 시작/끝, 비정상 대응, 사용자 override 12시간, 잠자기 전 충전 알림 — 순수 함수, 시험 7개).
- 워치 앱: `HealthReader`(SystemHealthManager.takeUidSnapshots, QUERY_ALL_PACKAGES 로 uid→패키지), `HealthStore`(15분 차를 24시간 보관), `Levers`(바꾸기 전 값 기억·복구), `Guardian`(틱·알림·되돌리기 수신기), 배터리 화면의 '배터리 지킴이'·'앱별 소모(최근 2시간)' 구역.
- 규칙: 밤(00~07시) + 화면 30분 넘게 꺼짐 + 충전 중 아님 → 절전·AOD 끔(옵션: 위치·동기화) → 아침 7시/충전/기상(05시 이후 화면 켬) 때 복구. 화면 꺼진 채 2시간 ≥5%/h → 절전 + 알림(범인 앱 2개, '앱 정보'·'되돌리기' 버튼), 6시간 뒤 풀고 다시 잼. 22:30 이후 하루 한 번 아침 7시 예상 잔량 < 15% 면 "N분 충전" 알림(분당 1%p 기준, 목표 25%). 사용자가 절전을 손수 끄면 12시간 쉼.
- 한계(솔직하게): 다른 앱을 강제 종료·제한하는 건 여전히 ADB(툴킷 메뉴 8 '자동 치료')로만 가능. 삼성 절전 모드가 `low_power` 설정을 따르는지는 첫 실기기에서 확인해야 한다(안 따르면 지킴이는 '사용자가 끔'으로 보고 물러나며, 알림은 그대로 동작).

## 5-2. 잔량 표시 점검·게이지 보정 (3차, 2026-10-20) — '빨리 닳는' 것의 가짜 부분을 잡기

워치9 사용자들이 겪는 "17%에서 꺼졌다 켜니 47%"는 셀이 아니라 **잔량 게이지(쿨롱 카운터+전압 추정 펌웨어)가 실제보다 낮게 세다가 하드 리셋으로 전압 기준 값에 다시 맞춰진 것**이다. 그래서 체감 소모의 일부는 **가짜 소모(표시 오차)** 이고, 이 부분은 앱·설정으로는 못 고치지만 **잡아내고(알리고) 되돌릴(강제 재시작·보정 루틴) 수는 있다.** 권한 없이 동작한다.

- `battery-core` `GaugeDrift.kt`: 쉬는 표본(화면 꺼짐·충전 안 함·전류 ≤60mA)의 전압을 OCV→잔량 근사표(4.2V 셀 / 4.35V 고전압 셀 — 완충 때 본 전압으로 고름)로 바꿔 표시 잔량과 비교. 최근 3시간 쉬는 표본 ≥3개의 **중앙값**이 드리프트(음수 = 낮게 세는 중 = 가짜 소모, 양수 = 높게 세는 중 = 갑자기 꺼질 위험). 12포인트 이상 '의심', 20포인트 이상 '확실'. 신뢰도: 표본 6개 이상 + 최근 7일 재부팅 점프·계수기 불일치 있으면 '높음'. `recoveries()`: 재부팅 점프와 사용자가 남긴 '강제 재시작' 표시(`Event.USER_NOTE`, 재시작 전에 눌렀든 뒤에 눌렀든 전후 잔량)를 모은다. `DrainAnalyzer`: `drift`·`recoveries` 필드, 'phantom'(24시간 표시 %/h − 계수기 %/h ≥ 1.5)·'drift' 태그, 판정 머리말 "잔량 표시 오차 의심 —".
- `battery-core` `Calibration.kt`: **게이지 보정 루틴** — ① 완충 뒤 1시간 유지 → ② 10% 아래까지 사용 → ③ 끊지 않고 다시 완충 + 1시간 → 끝. 게이지는 충전 종료와 거의 빈 상태에서 용량을 다시 배우므로 강제 재시작 다음으로 할 수 있는 일이다. 순수 함수 `CalibrationRule.decide`(45분 넘게 묵은 표본은 무시, 14일 기한), 단계가 바뀔 때만 행동.
- 워치 앱: 배터리 화면 **'잔량 표시 점검'** 구역(드리프트 한 줄 + 조언 + 신뢰도·표본·전압, 마지막 재시작 전후 잔량, '강제 재시작 표시 남기기' 버튼, '게이지 보정 시작/그만두기'·단계 안내·끝난 뒤 시작 때 오차 vs 지금), `Guardian.tick` 의 권한 없는 단계에서 **드리프트 알림**(확실 + 표시 ≤35% + 신뢰도 중간 이상, 12시간에 한 번: "잔량 표시가 N포인트 낮습니다 … 강제 재시작") 과 `Calibration.tick`(단계 알림 4종 + 기한 지남), 비정상 소모 알림에 "일부는 게이지 오차" 문구, summary.txt 에 [잔량 표시 점검].
- PC 툴킷: 소견 `gauge-drift`(확실=비정상 → 판정 GAUGE), `gauge-phantom`(주의), `gauge-restart-mark`; GAUGE 판정의 할 일에 보정 루틴 추가; report.html 워치 표에 '잔량 표시 점검'·'마지막 재시작 전후 잔량' 행과 요약 문단.
- 한계: OCV 표는 일반 리튬폴리머 근사치(±10%)라 큰 차이만 말한다. 쉬는 전압은 표본 시점의 순간값이라 전류가 크면 걸러진다(전류를 못 읽는 기기는 화면 꺼짐만으로 판단). 보정 루틴이 실제로 오차를 줄이는지는 끝난 뒤 같은 화면의 '시작 때 오차 → 지금' 으로 사용자가 확인한다.

## 5-3. 사용 내역·알려진 문제·조치 효과 (4차, 2026-10-21)

조사(2026-10): 갤럭시워치 과소모는 2026년 4·6·7월에 **Google Play 서비스가 멈춘 채 도는 문제**로 되풀이됐고(배터리 1위 16~99%, 캐시 지우기·재시작이 알려진 해결), 워치9 사용자도 밤사이 50% 소모를 보고했다(Samsung Members 2026-10-12).

- `battery-core` `UsageSplit`: 24시간 방전 구간을 화면 켜짐 구간(사용 통계)과 겹쳐 '대기 시간당 %'(화면이 한 번도 안 켜진 구간)와 나머지를 화면 몫으로 나누고, 정각~정각 한 시간 칸(기록 45분 이상) 중 시간당 소모가 큰 3개를 그 시간에 화면에 가장 오래 떠 있던 앱·HealthStore 15분 차로 본 뒤쪽 범인과 함께 낸다. 사용 통계가 없으면 표본의 화면 상태로 대기만 어림.
- `AppDrain`: 화면 밖(전경 25% 미만) CPU 시간당 3분 주의·10분 비정상. `KnownIssues`: Play 서비스가 1위이거나 점수 40% 이상이면서 주의 이상 → 해결 순서 3단계. 비정상이면 지킴이 알림(12시간에 한 번, 'Play 서비스 앱 정보' 버튼).
- `LeverEffects.nightMode`: 최근 14일 밤(00~07시, 충전 없이 5시간 이상) 중 지킴이 일지(`guardian_journal.jsonl`)로 밤 모드가 60% 넘게 덮은 밤과 아닌 밤의 중앙값 비교(양쪽 2밤 이상). 15% 미만 차이는 '차이 없음 — 원인은 뒤에서 도는 것'.
- 지킴이: 최근 3일 기록만 파일 끝에서 읽음(이 앱의 15분 작업을 가볍게), 화면 꺼짐 시간·최근 2시간 화면 켜짐을 사용 통계로(하루 동안 화면 기록이 없으면 믿지 않고 표본으로).
- PC 툴킷: `trim-caches`(안전, `pm trim-caches`) — Play 서비스가 짚이면 추천·자동 치료에 포함, `gms-restart`(주의, `am force-stop` → 시스템이 바로 다시 띄움) — 같은 조건. 둘 다 되돌릴 것 없는 일회성. Play 서비스는 여전히 끄기·데이터 삭제·제한 대상 아님.

## 5-4. 레버 넓히기·그래프·시계 화면·충전 습관·이 앱 자체 절약·전후 비교 (5차, 2026-10-21)

조사(2026-10): 워치9 = Wear OS 7(Android 17, API 37) · One UI 9 Watch · Snapdragon Wear Elite · 40mm 390mAh / 44mm 445mAh. 10월 20~21일에도 워치9 광탈 글(AOD·손목 올려 켜기가 켜진 채)이 이어지나 펌웨어 수정 소식은 확인되지 않음. AOSP `Settings.Global.Wearable` 의 @Readable 키(앱이 읽을 수 있음): `ambient_enabled`·`ambient_tilt_to_wake`·`ambient_touch_to_wake`·`hotword_detection_enabled`·`bedtime_mode`·`battery_saver_mode`. 설정 클래스에 없는 키(제조사 고유)도 읽을 수 있다(SettingsProvider: '정의되지 않은 키는 읽기 가능'). 시계 화면 = `WallpaperManager.getWallpaperInfo()` — QUERY_ALL_PACKAGES 로 패키지를 볼 수 있으면 돌려줌. 배터리 보호(워치8부터) = 90%에서 멈추고 85% 아래면 다시 충전.

- 지킴이 밤 모드: 손목 올림·터치로 켜기 끄기(기본 켬), 'Hey Google' 끄기(선택). `Levers.pull/putBack` — 바꾸기 전 값을 한 번만 기억, 이미 그 값이면 쓰지 않고 이름에도 넣지 않음, 되돌릴 때 이미 원래 값이면 쓰지 않음. 아침 기상은 표본의 화면 켜짐 또는 사용 통계상 15분 안에 화면을 봤으면(`GuardianRules.WAKE_SEEN_MS`, 끝 2시간 안).
- 레버 점검 `Levers.check/checkLine`: 레버마다 키·권한·지금 값 → 화면과 요약의 `[레버 점검] perm=… saver=… aod=… tilt=… touch=- …`.
- 요약 `[지표] 이름=값` (`SummaryMetrics`): idle_pph·day_pph·night_pph·screen_pph·screen_share·wakes·short_wakes·gesture_wakes·notif_wakes·notifications·drift. PC 툴킷이 조치 추천(손목 올림 끄기: gesture_wakes ≥ 80)과 전후 비교에 씀.
- 그래프 `DrainChart` (battery-core) + `DrainChartView`: 24시간 창, 40분 넘게 빈 곳에서 선을 끊음, 충전 = 꽂힌 표본부터 다음 빠진 표본까지, 화면 켜짐은 사용 통계가 있을 때만, 6시간마다 시각(지금 90분 안은 뺌), 세로는 늘 0~100%.
- 시계 화면 `KnownIssues.WATCH_FACE_HEAVY`: 지금 시계 화면이 타사(삼성·구글·WFF 렌더러 아님)이고 등급이 주의 이상이거나 CPU 시간당 3분 초과(시계 화면은 늘 '보이는' 상태라 화면 밖 CPU 표시가 안 붙어 따로 셈).
- 충전 습관 `ChargeHabits`: 최근 7일, 100%(99 이상)로 꽂혀 있던 시간 하루 2시간 이상이면 배터리 보호 권함 — 2시간 넘게 꽂은 충전이 모두 85~91%에서 1시간 넘게 머물렀으면 이미 켜진 것으로 봄. 충전 중 42℃ 이상이면 안내.
- 이 앱 자체 감사(별도 에이전트, 2026-10-21) 반영: 지도 엔진 지연 초기화(`MapApp.ensureMapSdk`), 업데이트 확인 예약 KEEP(예약 내용 버전이 바뀔 때만 UPDATE), 종료 기록은 첫 화면 때, 하루 요약 시각 먼저 기록, HealthStore 창 밖 줄은 `{"t":` 앞부분만 보고 건너뜀, 지킴이 사용 통계 6시간, 버스 정류장 대기 3분 뒤 GPS 20초(실시간 도착 2분 안이면 5초), HTTP 응답을 다 읽으면 연결 유지, 유튜브 재생 화면 `PlayerWatchdog`(2분 어둡게·45분 멈춤), 큰 업데이트 Wi-Fi 재시도 2시간(충전 중 예외), 안내 알림 `NavNotice`(50/100m 반올림, 화면 꺼짐 30초), GPS 확인 `ProbeBackoff`(4→8→16분), 저전력 화면 '지도에서 보기'는 BALANCED 30초·최소 간격 = 간격.
- PC 툴킷 `Compare`: 이 수집 바로 앞 수집(`Collect.before`)과 비교 — dumpsys 2시간 이상일 때 전체·화면 꺼짐 %/h·깨어 있던 비율·Play 서비스 몫, 워치 지표 대기·밤사이·금방 꺼진 화면. 15% 넘게, 항목별 최소 차이(0.2%/h·3%p·10번) 넘어야 변화. 사이에 적용해 되돌리지 않은 조치 id. 콘솔(collect/analyze/treat)과 보고서 '치료 전후 비교'(지금 할 일 다음). 조치 `tilt-wake-off`·`touch-wake-off`·`hotword-off`(주의), 설정 점검에 화면 켜기·AOD·절전 관련 키 전부.

## 5-5. 언제부터 더 닳았나 · 그 무렵 바뀐 것 (6차, 2026-10-21)

조사(2026-10): 워치9 사용자 글은 "업데이트 뒤 32 → 17시간"(Samsung Community 8/29), "업데이트 뒤 24시간에 50~60% → 30%"(9/25)처럼 **언제부터**가 분명하다. 삼성 운영자 답은 "업데이트 뒤 며칠(최대 1주)은 사용 습관을 다시 배우느라 더 닳을 수 있다 · 안 쓰는 건강 측정 끄기", 사용자 해결담은 강제 재시작·초기화. 2026년 4·6·7월의 광탈은 Play 서비스와 함께 왔다(Android Authority 7/24, Gadget Hacks). 그래서 **며칠 단위로 대기 소모가 꺾인 날을 찾고, 그 무렵 바뀐 것을 나란히 보여 주면** 원인을 거의 짚는다.

- 기록 `ChangeWatcher`(워치 앱): 15분·부팅 표본을 남길 때 지난번 뒤에 바뀐 것을 그 표본 note 에 `chg:` 로 — `fw=이전>이후`(`Build.VERSION.INCREMENTAL`) · `app=패키지@버전`(`PackageManager.getChangedPackages` 순번, 부팅마다 0부터 — 부팅 시각으로 가림, `+버전` = 새로 깖, `-` = 지움, 한 번에 8개 + `apps=N`) · `face=이전>이후`(`WallpaperManager`) · `set=id:이전>이후`(AOD·손목 올림·터치로 켜기·'Hey Google'·위치, 켬/끔만, 낮 7~23시에만, 지킴이가 무엇이든 쥐고 있거나 절전·취침 모드면 보지 않음 — 그동안의 변화는 다음 낮에 그 전 낮 값과 견줌). 처음 보는 값은 기억만. 지킴이 토글은 그 자리에서 `USER_NOTE` 표본으로 `cfg=night_aod:0>1`. 값은 영문·숫자·`._+-` 만, 64자. CSV 는 PC 툴킷과 같은 파일이라 툴킷도 그대로 읽는다(예전 툴킷은 note 를 무시).
- 계산 `battery-core`: `DayTrend.days`(최근 10일, 날마다 대기 %/h — 대기 기록 3시간 미만이면 비움), `DayTrend.shift`(나누는 자리마다 앞·뒤 중앙값 1.5배 이상 + 0.4%p/h 이상, 양쪽 2일 이상, 양쪽 3분의 2 이상이 1.25배 넘게 같은 쪽 — 여럿이면 로그 소모율 제곱 오차 합이 가장 작은 자리, 같으면 최근), `ChangeFinder`(앞날 0시 ~ 꺾인 첫날 끝의 변화를 늘었으면 소프트웨어 → 켠 설정 → Play 서비스·Samsung Health·시계 화면 → 새 앱·지킴이 끔 → 다른 앱, 줄었으면 끈 설정·지킴이 켬 → 시계 화면·지운 앱 → 소프트웨어 순, 글·할 일), `TrendBars`(막대 모델 — 세로 끝은 대기 기준 3.2%/h 보다 낮지 않게), `TrendReport.of`(한 묶음, `fresh` = 최근 7일 안에 늘어난 꺾임).
- 화면: '판정' 다음 '최근 며칠' — 막대(`TrendBarsView`), 꺾였으면 제목 "10/18부터 대기 소모가 늘었습니다" + "시간당 1.0% → 2.5%"(늘었으면 주의, 뒤가 4.44 넘으면 비정상), 그 무렵 바뀐 것 3개, 그 밖의 최근 7일 바뀐 것 5개. 꺾임이 없으면 날마다의 범위, 날이 모자라면 몇 날 더 필요한지. `fresh` 면 '지금 할 일'에서 비정상 알려진 문제 다음. 요약 `[최근 며칠]`·`[꺾임]`·`[바뀐 것 7일]`.
- PC 툴킷: `Facts.watchTrend`, 소견 `watch-shift`(주의, 근거 = 날마다 값·그 무렵 바뀐 것·할 일·기준), 보고서 '워치 기록'에 날마다 표(비정상 빨강)·꺾임·바뀐 것·할 일·최근 7일 바뀐 것(접힘).
- 한계: 대기는 15분 표본의 화면 상태 기준이라 표본 사이에 잠깐 본 것이 섞인다(그래서 하루가 아니라 여러 날의 흐름을 보고, 양쪽 3분의 2 일치를 요구). 바뀐 것은 워치가 알아챈 때(15분 안)이고, 이 앱이 설치되기 전·첫 표본 전의 변화는 모른다. 삼성 헬스의 측정 설정(심박 주기·수면 중 혈중 산소 등)은 다른 앱이 읽을 수 없어 '바뀐 것'에 없다.
- 독립 리뷰 반영(같은 날): ① **PC 자동 치료 안전** — `watch-shift` 소견은 `packages` 를 비우고(앱 이름은 글로만), `Finding.blames`(갈래 `trend`·참고 등급은 아님)로 범인 표·조치 추천·Play 서비스 캐시 정리 추천에서 뺀다 — 그날 업데이트됐다는 우연만으로 메뉴 8 이 그 앱을 제한하지 않게(`CatalogTest`). ② **앱 변화** — `getChangedPackages` 는 구성 요소를 켜고 끄기만 해도 순번이 바뀌므로 지난번 본 뒤에 `lastUpdateTime` 이 바뀐(깔림·업데이트) 것과 지워진 것만, 이 앱 자신은 뺌, 부팅은 `Settings.Global.BOOT_COUNT` 로(못 읽으면 어림 시각). ③ **오늘** — 끝나지 않은 오늘은 대기 12시간이 넘어야 꺾임 판단에(막대는 그림), 하루 값은 대기 6시간 이상(3시간이면 1%/h 에서 ±33% 계단 오차). ④ **잠깐 바뀐 설정** — 12시간 안에 되돌아간 설정·지킴이 설정·시계 화면은 짝으로 뺌(`ChangeNote.settled`), 절전·취침·극장 모드 동안은 보지 않음(시험 포함). ⑤ **할 일 글** — '지금 할 일' 줄(제목 두 줄·설명 세 줄)에 들어가게 짧게, 설명할 수 없는 변화(늘었는데 끈 설정·지운 앱·충전 알림 끔)는 뒤로 보내고 할 일은 설명할 수 있는 첫 변화로만, 없으면 '늘릴 만한 것은 없음'. '적기 시작' 표시(`chg:on=1`)로 그 앞은 '없음'이 아니라 '모름'. 사이에 기록 없는 날이 끼면 '10/13~10/16 사이부터'. 조사는 받침 따라(을/를). 표본 저장에 성공했을 때만 본 값을 기억, 시각 글자는 java.time(여러 스레드), 앱 이름은 분석 스레드에서 미리.
- 정확도 보강: 사용 통계가 있으면 '최근 며칠'의 대기는 화면이 한 번도 안 켜진 구간만(`ScreenUsage.readScreen` — 시스템이 보관한 가장 이른 이벤트부터 믿고, 그 하루 내내 기록이 있는 날이 4일 이상일 때만; 아니면 섞지 않고 모두 표본으로). 15분 사이에 몇 분씩 본 날(주말 등)이 대기를 부풀려 '꺾임'으로 읽히지 않게.
- 알림·타일: 하루 요약 때 `BatteryAlerts.trend` — 최근 7일 안에 늘어난 꺾임이면 꺾인 첫날마다 한 번 알림(제목 = 할 일 제목, 글 = 제목·숫자·할 일), 타일 상태 줄(알려진 문제 다음 차례)에 꺾인 첫날부터 8일 동안. 꺾임이 사라지면 타일 줄을 지움.
- 재시작 권함: 재시작한 지 7일이 넘었고 대기 소모가 주의 이상(또는 최근 늘어난 꺾임)이면 '지금 할 일'에 "재시작한 지 N일 — 한 번 재시작"(2026년 광탈 글들의 공통 해결담: 재시작 → Play 서비스 캐시 → 초기화). PC 툴킷은 `dumpsys alarm` 의 부팅 뒤 경과로 소견 `uptime-long`(대기 소모·깨어 있던 비율이 높으면 주의). 요약 `[재시작]`.

## 5-6. 전체 점검 반영 (7차, 2026-10-21) — 실제 워치에서 해가 될 수 있던 것들

독립 점검(배터리 모듈 전체를 '실제 워치9 사용자에게 해가 되는가'로 읽음)에서 나온 것과 고친 방법:

- **충전 시작이 기록되지 않음 → 짧은 충전이 '게이지 고장'으로**: `ACTION_POWER_CONNECTED/DISCONNECTED`·`BATTERY_LOW` 는 암시적 방송이라
  targetSdk 26+ 앱의 매니페스트 수신기로는 오지 않는다(예외 목록에 없음 — developer.android.com/guide/components/broadcast-exceptions).
  매니페스트엔 `BOOT_COMPLETED` 만 남기고 `ChargeWatchWorker`('충전 중' 조건 작업, 돌면 POWER_CONNECTED 한 줄·10분 뒤 다시 걸기)로 충전 시작을 잡는다.
  `Gauge` 는 잔량 상승을 그 사이 충전으로 오를 수 없는 만큼(분당 2%p 넘게)일 때만 `JUMP_UP`, 충전기 위 부팅은 꺼져 있던 동안 충전으로 안 될 만큼일 때만
  `REBOOT_JUMP` 로 센다. 워치 판정은 최근 7일의 이상만 본다.
- **알림이 밤에 화면을 깨움**: 밤(23~7시) 알림은 `battery-quiet`(IMPORTANCE_LOW) 채널로(잠자기 전 충전 알림은 그대로 울림). 하루 요약은 날짜마다
  7시 넘은 첫 기록 때(30시간 넘게 못 썼으면 바로). '언제부터 더 닳음'은 꺾인 날이 사흘 안에서 옮겨 가면 같은 꺾임, 지난 알림이 7일 안이면 안 알림,
  1.5배 더 닳으면 다시 — 밤에 찾으면 미뤄 두었다 아침 첫 기록 때. 보정 루틴은 충전기 위 99%↔100% 로 유지 시간을 다시 세지 않는다.
- **지킴이 안전장치**: `Levers.putBack` 은 쓰기에 성공했을 때만 기억을 지우고(못 쓰면 다음 기록에 다시), 권한이 없어졌는데 쥔 것이 있으면
  무엇을 직접 켤지 한 번 알리고(알림 7309) 내려놓는다. PC `undo` 는 WRITE_SECURE_SETTINGS 를 거두기 전에
  `am broadcast -n …/.battery.ShellRestoreReceiver`(android.permission.DUMP 로 막아 셸만 보냄)로 앱이 먼저 되돌리게 한다.
  절전을 원래(꺼짐)로 되돌릴 때 `low_power_sticky` 가 1이면 0으로 — 설정으로 켠 절전은 수동으로 켠 것으로 남아 충전 뒤 90% 아래에서 다시 켜진다(AOSP 'Sticky restore').
- **길 안내와 다툼**: 안내 중이거나 최근 2시간 안에 안내했으면 `GuardianRules.decide(navigating = true)` — 밤·비정상 모드를 켜지 않고 켜 둔 것은 푼다.
  안내가 시작되면 `Guardian.onNavigationStarted` 가 밤 모드로 꺼 둔 것을 바로 되돌린다. 비정상 알림의 의심 앱에서 이 앱은 뺀다.
- **이 앱의 15분 작업이 무거움**: `takeUidSnapshots` 는 마지막 동기화가 10분 넘었으면 시스템 전체 통계(무선·모뎀 포함)를 동기화한다 — 앱별 통계는
  1시간에 한 번(최근 2시간 대기 소모가 3.2%/h 넘으면 15분마다, 배터리 화면을 열 때 15분 넘게 묵었으면 하나 더). 통계 파일은 120KB 에서 자르고,
  타일은 그 파일을 읽지 않고 지킴이가 적어 둔 알려진 문제(3시간 안)만 보인다. 타일 글은 `withContext(IO)` 에서, `freshness` 없음.
- **시계 보기를 오작동으로 셈**: 짧은 화면 켜짐은 낮엔 시계 보기라 세지 않고 밤(0~6시)의 것만(`WakeStats.nightGestureWakes`, 10번부터) —
  권하는 것도 밤에만 끄기(취침 모드·지킴이). PC 의 `tilt-wake-off` 는 추천(★)하지 않는다.
- **할 일 글이 잘려 다른 뜻으로**: 480px 렌더로 잰 줄 크기 — 제목 13sp 한 줄 한글 9자·두 줄, 설명 12sp 한 줄 13자·세 줄 — 를 `TodoText`
  (띄어쓰기 줄바꿈으로 재는 보수적 어림)로 두고 모든 할 일 글(알려진 문제·잔량 표시·재시작·화면 켜짐·알림·밤 삼성 헬스·충전 습관·권한·최근 며칠)을
  그 안에 맞춘 짧은 글로. 단계 줄은 눌러 펼치기. 화면은 그리는 동안 앱 이름을 찾지 않는다(분석이 미리).
- **PC 자동 치료가 약한 근거로 제한**: 덤프 순간 화면 꺼진 채 센서를 쥔 것만으로는 범인이 아니다(`Finding.blames` 에서 sensor 제외) — 수면·운동 기록 앱이 끊기지 않게.
- **타일 글이 낱말 가운데서 갈라짐·작은 워치·큰 글꼴에서 가려짐** (타일 렌더러로 그려 찾음, 리뷰 반영): 타일은 시스템이 그려 앱 테마의
  `lineBreakWordStyle=phrase` 가 안 먹는다(렌더러가 줄바꿈 설정을 하지 않음) → 한글이 아무 글자 사이에서 줄이 바뀐다('않습/니다').
  `TileText` 가 낱말 안 글자(자소 묶음 경계 — BreakIterator) 사이에 U+2060(단어 결합자, 폭 0)을 넣어 띄어쓰기에서만 바뀌게,
  'A · B' 꼴은 A·B 를 각각 한 줄로(바뀌면 '·' 뒤에서), 읽어 주기에는 원문(가운뎃점·줄표는 쉼표로 — 이 줄들은 전엔 읽지 않았다).
  글이 쓸 수 있는 폭 = 화면 폭 ÷ 글꼴 배율(본문 글꼴은 배율을 따름)이 200dp 미만이면 예상은 '0% 내일 오전 7:40' 한 줄(`Forecast.compact`),
  소모율은 작은 글씨. 충전 중 제목 '충전 중 N%'(한 줄 제목 칸에 '배터리 100% · 충전 중'은 큰 글꼴에서 잘림), 대신 글은 짧게
  ('소모율 측정 쉼'·'빼면 다시 잽니다'·'기록 모으는 중'·'예상은 2시간 뒤부터'), 판정은 숫자와 함께만. 잔량 표시 줄은 짧은
  `GaugeDrift.tile`, 충전 중엔 하루 평균(방전 3시간 이상). `BatteryTileFitTest` 는 창에 붙여(렌더러가 그린 뒤 칸 높이에 맞춰 줄 수를
  고치는 길까지) 기기 값에 글꼴 배율·렌더러 버전(최근 1.500, 예전 1.0 — 단추 모양이 다름)을 주고 226·203·200·199·192·186dp,
  글꼴 1.15·1.24·1.3배에서 두 줄 안·'…' 없음·띄어쓰기에서만 줄바꿈·글 상자/칸 안(위아래·옆)·단추 위(2px 여유)·둥근 화면 안·
  읽어 주기 원문을 잰다. 기기 값에 글꼴 배율이 없으면(0) 렌더러가 '자세히' 단추 글 크기를 무한대로 잡아 비어 보였다(시험·렌더만).
  안드로이드 15·16 둘 다로 그린다(16 이상이면 M3 가 시스템 글꼴 토큰을 씀 — 워치9 는 One UI 버전에 따라 Wear OS 6~7 = 안드로이드 16~17,
  Robolectric 은 아직 17 이 없어 16 까지, JDK 21 에서 돌도록 테스트 JVM 에 add-opens). 한계: 시험 글꼴은 AOSP 것(삼성 글꼴과 조금 다름).

## 5-7. 2026년 9~10월 워치9 보고 반영 (8차, 2026-10-21~22, 리뷰 두 번 반영)

- **One UI Watch 홈 과소모** (삼성 커뮤니티 'Galaxy Watch9 poor battery life', 'Battery sucks' 2026-09~10: 업데이트 뒤 하루 못 감,
  배터리 1위가 'UI 9 home', 보통 재시작·진단은 효과 없고 강제 재시작으로 '훨씬 나아짐'): `KnownIssues.HOME_HOG`. 홈
  (`com.samsung.android.wearable.sysui`)은 화면을 그리는 persistent 프로세스 — 켜진 화면에선 FOREGROUND, 꺼진 화면에선 UI 를 가진
  persistent 프로세스가 BOUND_FOREGROUND_SERVICE(BatteryStats 의 FGS)로 잡힐 수 있어(AOSP OomAdjuster) `fgMs`(TOP+FOREGROUND)가
  화면 켜짐과 맞지 않는다 → `AppDrain` 은 홈에 '화면 밖 CPU' 등급·'화면 N분'을 붙이지 않고, 알려진 문제가 사용 통계의 화면 켜짐 시간
  × 0.5 와 받은 알림 × 2초(다른 앱 알림을 보여 주는 몫)를 CPU 에서 빼고 시간당 5분 초과면 주의·10분 초과면 비정상(알림)으로 본다.
  웨이크락은 '멈춘 채 쥠'(창의 절반 넘게)만. 화면이 창의 30% 넘게 켜져 있었거나 화면 시간을 모르면 짚지 않는다.
  화면·알림은 앱별 통계의 기록 구간(스냅샷 사이)들과 겹친 만큼만(`Guardian.issuesIn` — 15분 기록과 배터리 화면이 같이 씀), 최근 6시간에
  화면 기록이 없으면 하루치로 '켜진 적 없음'을 확인하고, 받은 알림은 화면 기록이 없어도 따로 센다(밤새 화면은 안 켜도 알림은 받았다).
- **창 1시간**: 알려진 문제는 모두 앱별 통계 창이 1시간 이상일 때만 — 재시작 직후·첫 스냅샷의 짧은 창에선 잠깐의 일(재시작 뒤 Play 서비스
  동기화 등)이 시간당으로 부풀려진다. 창을 모르는 PC 툴킷(충전 뒤 통계)은 그대로.
  할 일: 강제 재시작 → (업데이트 직후면 하룻밤 적응) → 홈 캐시만 지우기(그 워치의 앱 이름으로) → Samsung Members 오류 보고.
- **재시작 뒤**: 배터리 통계(HealthStats)는 부팅 때 0 이 되지 않고 충전 뒤부터 이어진다(안드로이드 10+; 강제 재시작은 저장 못 한
  몫을 잃어 누적값이 줄기도 한다). 그래서 재시작은 통계가 아니라 부팅 번호(`Settings.Global.BOOT_COUNT`, 못 읽으면 켜진 뒤 시간이
  줄었는지 — `HealthStore.Boot`)로 알아챈다: 재시작 뒤 첫 스냅샷은 기준값만(재시작을 걸친 구간을 한 줄로 세지 않는다 — 줄어든 누적값을
  '충전 뒤 비워짐'으로 보면 충전 뒤 전부가 한 구간에 잡혀 엉뚱한 Play 서비스·홈 과소모로 짚었다, 2차 리뷰에서 찾음), 줄마다 부팅 차례(`b`)를
  적어 앱별 순위·알려진 문제는 이번 부팅의 줄만, 타일 줄은 적을 때의 부팅을 같이 적어 재시작하면 바로 내린다 — 시킨 대로 재시작했는데 계속
  '강제 재시작'이라 하지 않게(재시작 뒤 기록이 1시간 쌓이면 다시 본다). 이전 버전이 쓴 줄(부팅 차례 없음)은 빼고 본다. 시계가 뒤로 가도 그
  스냅샷은 기준값만. 타일 줄은 비정상 문제 먼저, 알림 쉬는 시간(12시간)은 문제마다(예전 버전의 시각은 따로 따짐).
- **공유 uid 묶음 이름**: 알려진 문제를 보는 패키지(Play 서비스·홈)가 있으면 그 이름으로(시스템 uid 'android' 묶음은 그대로) — 워치 앱
  `HealthReader.groupKey`, PC `BatteryStats.packageOf` 같은 규칙. 지난 스냅샷에 없던 이름은 그 구간엔 기준값만(`AppDrain.delta`) — 묶음에
  앱이 늘거나 이름이 바뀌면 부팅 뒤 누적값이 한 구간 사용으로 잡혀 엉뚱한 과소모(예: Play 서비스 비정상 알림)로 짚었다.
- **'홈이 1위'의 뜻**: AOSP 의 화면 전력 나눔(ScreenPowerCalculator)은 TOP 상태 시간 기준이라 FOREGROUND 인 홈엔 거의 안 간다 — '홈이
  1위인 것은 흔하다'는 근거가 없어 쓰지 않는다. 화면 도움말·PC 보고서는 '순위만으로는 고장이라 단정할 수 없다'고만.
- **알림 화면 켜짐**: 삼성 안내(배터리가 빨리 닳을 때, 알림 설정 2026-08-01)의 '웨어러블 앱 › 워치 설정 › 알림 › 고급 설정 ›
  알림 받으면 화면 켜기' — 끄면 알림은 그대로 오고 화면만 안 켜진다. 할 일은 앱 알림을 통째로 끄라는 대신 이것으로(제목에 하루 몇 %).
  PC 설정 점검은 이 설정일 법한 키(screen_on·turn_on)도 보여 실제 키 이름을 찾는다.
- **남은 확인(실제 워치)**: 홈의 uid·shared user(하루 요약의 '[홈]' 줄 — `HomeUid`: 시스템 uid 1000 을 같이 쓰면 앱별 통계에서 홈만
  따로 셀 수 없어 '홈 과소모'를 못 짚는다고 적고, PC 보고서도 그땐 `home-uid` 소견으로 배터리 사용량 화면을 직접 보라고), 화면 켜짐/꺼짐
  (AOD 켬·끔)에서의 프로세스 상태, 하루·밤의 홈 CPU — 수집 보고서로 5분 기준을 맞춘다. 공유 uid 묶음 이름은 워치·PC 모두 'android' 가
  끼면 알파벳 순서와 상관없이 'android'.

## 6. 열린 가정 (첫 실제 수집으로 확정)

- 워치가 `BATTERY_PROPERTY_CHARGE_COUNTER`를 앱에 주는지(없으면 mAh/h·용량 추정은 "측정 불가"로 표시).
- One UI 9 Watch의 `settings` 키 이름(AOD·손목 올림·터치·음성 호출·화면 꺼짐 시간)과 선택 앱의 패키지명 — 수집 보고서의 '화면 켜기·AOD·절전 관련 키'와 워치 요약의 `[레버 점검]`으로 확인해 카탈로그·레버 갱신.
- `dumpsys batterystats` 텍스트의 정확한 섹션 문구(Wear OS 7) — 파서는 관대하게, 실제 덤프로 테스트 교체.
- `dumpsys battery`에 삼성 확장 필드가 있는지.
- 워치가 타일에 보내는 렌더러 버전·글꼴 배율 — 워치 요약의 `[타일]` 줄(`TileDeviceNote`)로 확인해 `BatteryTileFitTest` 의 어림(렌더러 1.500·글꼴 1배)을 맞춤.
