# 워치9 배터리 닥터 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 갤럭시 워치9 배터리 광탈의 원인을 숫자로 밝히고, 루트 없이 가능한 소프트웨어 차단과 증거 패키지를 한 번에 제공하는 도구(워치 앱 모듈 + Windows PC 툴킷)를 만든다.

**Architecture:** 순수 Kotlin 라이브러리 `battery-core`(표본·CSV·소모율·게이지 이상·용량 추정·판정)를 워치 앱(`app`의 `battery` 패키지: 15분 기록·화면·알림)과 PC 툴킷(`battery-doctor`: ADB 수집·dumpsys 파서·분석·HTML 보고서·조치/되돌리기·증거)이 함께 쓴다. 워치 CSV는 ADB로 PC에 끌어와 dumpsys와 합쳐 판정한다.

**Tech Stack:** Kotlin 2.4(JVM 17 타깃), Gradle(AGP 9.1, 기존 저장소), WorkManager, Robolectric/JUnit4, Windows 배치 실행기, Playwright(검증용 스크린샷).

**Spec:** `docs/superpowers/specs/2026-10-19-battery-doctor-design.md`

## Global Constraints

- 루트 없음. 네트워크 사용 없음(adb 외 프로세스 실행 금지). 모든 기록은 워치·PC 안.
- 공개 저장소: 견본 덤프는 가짜 데이터, 개인 정보(계정·집·좌표·일련번호) 금지.
- 조치는 전부 `journal.jsonl`에 이전 값과 함께 기록하고 되돌릴 수 있어야 한다. '강함' 등급은 기본 꺼짐·재확인.
- 핵심 시스템 패키지 거부 목록(비활성화 금지): `com.google.android.gms`, `com.google.android.gsf`, `com.android.vending`, `com.samsung.android.wear.shealth`, `com.samsung.android.wearable.sysui`, `com.android.systemui`, `com.android.settings`, `com.samsung.android.watch.watchface*`, `com.samsung.android.wear.*wearable*`, `com.google.android.wearable.*`, `com.android.phone`, `com.android.bluetooth`, `com.samsung.android.app.watchmanager*`, `com.mywatch.kakaomap`.
- 임계값(%/h): 대기 ≤3.2 정상 / ≤4.44 주의 / >4.44 비정상; 24h 평균 ≤3.3 / <5.0 / ≥5.0; 밤사이(00~07시) 대기와 같음; 실험(비행기·절전·시계만) ≤1.0 / ≤2.0 / >2.0(경험칙). 판정 보류: 표본 2시간 미만. (구현: `battery-core`의 `Thresholds`)
- 강제 재시작 문구: "홈 + 뒤로 가기 버튼을 꺼질 때까지(7초 이상), 효과 없으면 30초".
- 한국어 UI·보고서. 코드 주석은 저장소 관례대로 한국어.
- 테스트 실행: 워치 앱 `bash <scratchpad>/rt.sh <Test...>`; JVM 모듈 `./gradlew --no-daemon -q :battery-core:test :battery-doctor:test`.

---

## 파일 구조

```
battery-core/
  build.gradle.kts
  src/main/kotlin/com/mywatch/battery/core/
    BatterySample.kt      표본·Status·Event
    SampleCsv.kt          CSV 직렬화·관대한 파서
    WatchModels.kt        모델명 → 공칭 용량
    Rates.kt              Rate, 방전 구간 추출, 창 소모율
    Gauge.kt              GaugeAnomaly 탐지, CapacityEstimate
    Thresholds.kt         Grade, 기준표
    DrainAnalyzer.kt      Analysis, Verdict, Experiment
    AlertRule.kt          워치 알림 규칙(2시간·5%/h·화면 꺼짐 75%)
  src/test/kotlin/com/mywatch/battery/core/  …Test.kt
app/src/main/java/com/mywatch/kakaomap/battery/
    BatteryProbe.kt       현재 표본 읽기
    BatteryLog.kt         CSV 파일(덧붙이기·30일 정리·summary.txt)
    BatterySampleWorker.kt / BatteryEventsReceiver.kt / BatteryAlerts.kt
    BatteryActivity.kt    화면
app/src/main/res/layout/activity_battery.xml, drawable/ic_battery.xml, values/strings.xml(추가)
battery-doctor/
  build.gradle.kts, BatteryDoctor.bat, README.txt
  src/main/kotlin/doctor/
    Main.kt               인자/메뉴
    Adb.kt                AdbRunner(실행·타임아웃), RealAdb, 기기 선택
    Config.kt             data/config.properties
    Connect.kt            페어링·연결 안내
    Collect.kt            수집 목록 → 스냅샷 폴더
    parse/ BatteryDump.kt BatteryStatsText.kt BatteryStatsCheckin.kt AlarmDump.kt SensorDump.kt LocationDump.kt DeviceIdleDump.kt Packages.kt SettingsDump.kt PowerDump.kt
    Analyzer.kt           Findings, Verdict(판정 트리)
    HtmlReport.kt         report.html(+SVG)
    fix/ Catalog.kt Fixer.kt Journal.kt
    Evidence.kt           증거 폴더·신고문·마스킹
  src/test/kotlin/doctor/ …Test.kt, src/test/resources/samples/*.txt(가짜 덤프)
  tools/fake-adb.sh       e2e용 가짜 adb
```

---

## 1부. battery-core

### Task 1: 모듈 뼈대와 표본·CSV

**Files:** `settings.gradle.kts`(include 추가), `battery-core/build.gradle.kts`, `BatterySample.kt`, `SampleCsv.kt`, `WatchModels.kt`, 테스트 `SampleCsvTest.kt`, `WatchModelsTest.kt`

**Interfaces (Produces):**
```kotlin
package com.mywatch.battery.core
enum class Status { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }
enum class Event { PERIODIC, POWER_CONNECTED, POWER_DISCONNECTED, BOOT, APP_OPEN, LOW, EXPERIMENT_START, EXPERIMENT_END }
data class BatterySample(val t: Long, val level: Int, val chargeUah: Int? = null, val voltageMv: Int? = null,
    val tempDeciC: Int? = null, val currentUa: Int? = null, val status: Status = Status.UNKNOWN,
    val plugged: Boolean = false, val interactive: Boolean = false, val event: Event = Event.PERIODIC, val note: String = "")
object SampleCsv { const val MAGIC = "# battery-log v1"; const val HEADER = "t,level,chargeUah,voltageMv,tempDeciC,currentUa,status,plugged,interactive,event,note"
    fun line(s: BatterySample): String; fun parseLine(line: String): BatterySample?; fun parse(text: String): List<BatterySample> /* t 오름차순, 같은 t 중복 제거 */ }
object WatchModels { fun nominalMah(model: String?): Int? /* SM-L350/L355→445, SM-L340/L345→390, 그 외 null */ ; fun sizeLabel(model: String?): String? /* "44mm"/"40mm" */ }
```

- [ ] `settings.gradle.kts`에 `include(":battery-core")`, `battery-core/build.gradle.kts`: `plugins { id("org.jetbrains.kotlin.jvm") }`, `kotlin { jvmToolchain(17) }`, `dependencies { testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2") }`, `tasks.test { useJUnit() }`.
- [ ] 실패하는 테스트: `SampleCsvTest` — 왕복(line→parseLine), 빈 값(null) 왕복, 깨진 줄(`12345,ab`)·헤더·MAGIC은 null, `parse`가 정렬·중복 제거, note의 쉼표는 `;`로 치환되어 저장.
- [ ] 실패하는 테스트: `WatchModelsTest` — 4개 모델·소문자·null.
- [ ] 구현, `./gradlew -q :battery-core:test` 통과.
- [ ] 커밋 `battery-core: 표본·CSV·모델 용량`.

### Task 2: 방전 구간과 창 소모율

**Files:** `Rates.kt`, `RatesTest.kt`

**Interfaces:**
```kotlin
data class Rate(val percentPerHour: Double?, val mahPerHour: Double?, val hours: Double, val dropPercent: Int, val dropUah: Int?)
data class Segment(val from: BatterySample, val to: BatterySample) { val hours: Double; val dropPercent: Int; val dropUah: Int?; val idle: Boolean /* 양 끝 interactive=false */ }
object Rates {
    fun dischargeSegments(samples: List<BatterySample>): List<Segment>   // 연속 표본 쌍 중 둘 다 plugged=false, to.level <= from.level, 간격 ≤ 2시간(그 이상은 기록 공백으로 끊음)
    fun rate(segments: List<Segment>): Rate                                 // Σdrop / Σhours ; hours < 0.5 이면 percentPerHour=null
    fun window(samples: List<BatterySample>, now: Long, hours: Int): Rate  // now-hours 이후 구간(경계 걸친 구간은 시간 비례로 자름)
    fun idleWindow(samples: List<BatterySample>, now: Long, hours: Int): Rate // idle 구간만
    fun between(samples: List<BatterySample>, start: Long, end: Long): Rate
}
```

- [ ] 실패하는 테스트: 15분 간격 100→97→94(=12%/h); 충전 표본 끼면 끊김; 2시간 넘는 공백은 끊김; 창 경계 비례 자르기; chargeUah 있으면 mAh/h(ΔµAh/1000/h); 0.5시간 미만이면 null; idle 창은 interactive 표본 제외.
- [ ] 구현·통과·커밋 `battery-core: 방전 구간·창 소모율`.

### Task 3: 게이지 이상·용량 추정

**Files:** `Gauge.kt`, `GaugeTest.kt`

**Interfaces:**
```kotlin
enum class AnomalyKind { JUMP_UP, REBOOT_JUMP, CLIFF, COUNTER_MISMATCH }
data class GaugeAnomaly(val kind: AnomalyKind, val t: Long, val fromLevel: Int, val toLevel: Int, val detail: String)
data class CapacityEstimate(val estimatedMah: Double, val nominalMah: Int?, val sessions: Int) { val percentOfNominal: Double? }
object Gauge {
    fun anomalies(samples: List<BatterySample>, nominalMah: Int?): List<GaugeAnomaly>
    fun capacity(samples: List<BatterySample>, nominalMah: Int?): CapacityEstimate?   // 충전 중 Δlevel ≥ 40 인 세션들의 ΔµAh/Δlevel×100 중앙값
}
```
규칙: JUMP_UP = 연속 표본 둘 다 plugged=false 이고 Δlevel ≥ +5; REBOOT_JUMP = to.event==BOOT 이고 Δlevel ≥ +10(plugged 무관); CLIFF = 간격 ≤ 20분, Δlevel ≤ −15, to.interactive=false; COUNTER_MISMATCH = 방전 구간에서 |ΔµAh/(nominal×10) − 표시Δ%| ≥ 10 (nominal 있을 때만).

- [ ] 실패하는 테스트: 네 종류 각각 1건씩 잡힘·경계값 미만은 안 잡힘; 용량: 20→100% 충전에 356,000µAh 증가 → 445mAh 추정(=100%); 세션 2개 중앙값; 미지원(chargeUah null)이면 null.
- [ ] 구현·통과·커밋 `battery-core: 게이지 이상·용량 추정`.

### Task 4: 기준표·판정·알림 규칙

**Files:** `Thresholds.kt`, `DrainAnalyzer.kt`, `AlertRule.kt`, 테스트 3개

**Interfaces:**
```kotlin
enum class Grade { NORMAL, CAUTION, ABNORMAL, UNKNOWN }
object Thresholds { fun idle(pph: Double?): Grade; fun mixed24h(pph: Double?): Grade; fun overnight(pph: Double?): Grade; fun experiment(pph: Double?): Grade
    const val SOURCE_IDLE = "Android vitals 워치페이스 기준: 4.44%/h 초과 과소모, 3.2%/h 미만 목표" }
data class Experiment(val name: String, val start: Long, val end: Long?, val rate: Rate, val grade: Grade)
data class Verdict(val grade: Grade, val headline: String, val reasons: List<String>, val tags: Set<String>) // tags: "gauge", "capacity", "insufficient"
data class Analysis(val samples: Int, val spanHours: Double, val w1: Rate, val w8: Rate, val w24: Rate, val idle24: Rate,
    val overnight: Rate?, val overnightLabel: String?, val anomalies: List<GaugeAnomaly>, val capacity: CapacityEstimate?,
    val experiments: List<Experiment>, val verdict: Verdict)
class DrainAnalyzer(private val nominalMah: Int?, private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    fun analyze(samples: List<BatterySample>, now: Long = System.currentTimeMillis()): Analysis }
object AlertRule { data class Alert(val pph: Double, val hours: Double)
    fun check(samples: List<BatterySample>, now: Long, lastAlertAt: Long?): Alert? /* 최근 2시간 전부 방전, 표본≥6, 화면꺼짐 비율≥0.75, ≥5.0%/h, 마지막 알림 6시간 경과 */ }
```
판정 순서: spanHours<2 → UNKNOWN "기록 모으는 중"; anomalies 있음 → grade = max(기준 grade, CAUTION) + tag gauge + 이유 "잔량 점프 N회"; idle24·w24·overnight·experiments 중 최악 grade; capacity<80% → tag capacity. headline 예: "대기 중 시간당 5.8% — 비정상(기준 4.4%)".

- [ ] 실패하는 테스트: 기준표 경계값; 판정 6가지 시나리오; AlertRule 조건 4개(시간 부족·화면 켜짐 많음·5% 미만·6시간 내 재알림 금지).
- [ ] 구현·통과·커밋 `battery-core: 기준표·판정·알림 규칙`.

---

## 2부. 워치 앱 모듈

### Task 5: 기록 — Probe, Log, Worker, Receiver, 예약

**Files:** `app/build.gradle.kts`(`implementation(project(":battery-core"))`), `battery/BatteryProbe.kt`, `BatteryLog.kt`, `BatterySampleWorker.kt`, `BatteryEventsReceiver.kt`, `AndroidManifest.xml`(수신기·`RECEIVE_BOOT_COMPLETED`), `MapApp.kt`(예약 호출), 테스트 `BatteryLogTest`, `BatterySampleWorkerTest`, `BatteryEventsReceiverTest`

**Interfaces:**
```kotlin
object BatteryProbe { fun read(context: Context, event: Event = Event.PERIODIC, note: String = ""): BatterySample }
class BatteryLog(context: Context) { val file: File /* externalFilesDir/battery/log.csv 또는 filesDir */
    fun append(sample: BatterySample); fun readAll(): List<BatterySample>; fun pruneOlderThan(days: Int = 30); fun writeSummary(text: String): File }
class BatterySampleWorker : Worker { companion object { const val UNIQUE = "battery-sample"; fun schedule(context: Context); fun recordNow(context: Context, event: Event, note: String = "") } }
```

- [ ] 실패하는 테스트 `BatteryLogTest`(Robolectric): append 두 줄 → readAll 2개·파일 첫 줄 MAGIC·둘째 줄 HEADER; 깨진 줄 섞여도 읽힘; pruneOlderThan(30)이 31일 전 줄 제거.
- [ ] 실패하는 테스트 `BatterySampleWorkerTest`: `MapApp` 생성 후 WorkManager에 UNIQUE 주기 작업 존재(`WorkManagerTestInitHelper`); `TestListenableWorkerBuilder`로 실행 → 로그 1줄, ShadowBatteryManager 값(level 57, charge counter 250000)이 그대로.
- [ ] 실패하는 테스트 `BatteryEventsReceiverTest`: `ACTION_POWER_CONNECTED` 브로드캐스트 → 즉시 작업 1건(또는 동기 기록) → event=POWER_CONNECTED 줄.
- [ ] 구현. Probe는 `Int.MIN_VALUE`·0을 미지원으로 보고 null, `currentUa` 부호는 그대로 저장. Worker 실행 끝에 `BatteryAlerts.check(context)`(Task 7, 지금은 빈 함수) 호출. 매일 1회 `pruneOlderThan`.
- [ ] `rt.sh BatteryLogTest BatterySampleWorkerTest BatteryEventsReceiverTest` 통과, 전체 `rt.sh`도 통과(기존 `MapApp` 관련 테스트 영향 확인).
- [ ] 커밋 `워치: 배터리 15분 기록(표본·CSV·이벤트)`.

### Task 6: 배터리 화면과 첫 화면 버튼

**Files:** `battery/BatteryActivity.kt`, `res/layout/activity_battery.xml`(또는 기존 `activity_list` 재사용 + Rows), `res/drawable/ic_battery.xml`, `res/layout/activity_hub.xml`, `HubActivity.kt`, `strings.xml`, `AndroidManifest.xml`, 테스트 `BatteryActivityTest`, `HubTest` 수정

- [ ] 실패하는 테스트 `HubTest`: `open_battery` 클릭 → 다음 Activity가 `BatteryActivity`(새 작업 플래그 없음).
- [ ] 실패하는 테스트 `BatteryActivityTest`: 로그에 24시간치(시간당 6% 방전, 화면 꺼짐) 넣고 열면 "비정상" 글과 "6.0%" 가 보인다; 표본 없으면 "기록 모으는 중"; '실험 시작' → 모드 고르기 → 로그에 EXPERIMENT_START(note=모드); '실험 끝' → EXPERIMENT_END; '요약 새로 쓰기' → summary.txt 생성.
- [ ] 레이아웃: 첫 화면 아래 줄을 가로 2칸(`hub_update` | `open_battery`, 각 minWidth 76dp, 글자 13sp). 배터리 화면은 `WearList` 세로 목록, 섹션 순서는 스펙 2장. 색: 정상 `#4CAF50`, 주의 `#FFC107`, 비정상 `#F44336`(기존 색 리소스 있으면 재사용).
- [ ] 라이브 전류: `onResume`에서 2초마다 `BatteryProbe.read`로 '지금' 칸만 갱신, `onPause`에서 중단.
- [ ] 도움말 섹션 문구(스펙 2장 9번).
- [ ] 테스트 통과, 커밋 `워치: 배터리 화면·첫 화면 버튼`.

### Task 7: 알림

**Files:** `battery/BatteryAlerts.kt`, 테스트 `BatteryAlertTest`

- [ ] 실패하는 테스트: 2시간 동안 15분 간격 100→88(6%/h), 전부 interactive=false → 알림 1개(채널 `battery-watch`, 제목에 "시간당 6.0%"); 바로 다시 check → 알림 추가 없음; 7시간 뒤 → 다시 1개.
- [ ] 구현: `AlertRule.check`, 마지막 알림 시각은 SharedPreferences. 알림 탭 → `BatteryActivity`.
- [ ] 통과, 커밋 `워치: 비정상 소모 알림`.

---

## 3부. PC 툴킷 battery-doctor

### Task 8: 모듈 뼈대, AdbRunner, 설정, 연결

**Files:** `settings.gradle.kts`(include), `battery-doctor/build.gradle.kts`, `Main.kt`, `Adb.kt`, `Config.kt`, `Connect.kt`, 테스트 `AdbTest`, `ConnectTest`

**Interfaces:**
```kotlin
interface AdbRunner { fun run(args: List<String>, timeoutSec: Int = 120): Result /* data class Result(val code: Int, val out: String, val err: String) */
    fun pull(remote: String, local: java.io.File): Boolean }
class RealAdb(val adbPath: String, val serial: String?) : AdbRunner   // ProcessBuilder, UTF-8, 타임아웃 시 destroy
class Config(dir: File) { var lastAddress: String?; var adbPath: String?; var purchaseDate: String?; fun save() }
object Connect { fun run(adb: AdbRunner, cfg: Config, io: Console): Boolean /* 안내 → pair? → connect → devices → getprop 모델/빌드 출력 */ }
interface Console { fun println(s: String); fun readLine(prompt: String): String? }
```

- [ ] `battery-doctor/build.gradle.kts`: kotlin jvm, `application { mainClass = "doctor.MainKt" }`, `dependencies { implementation(project(":battery-core")) ; testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2") }`, `tasks.register<Jar>("fatJar")`(Main-Class 매니페스트, runtimeClasspath 포함, 이름 `battery-doctor.jar`), `tasks.register<Zip>("distZip")`(`BatteryDoctor.bat`, `README.txt`, fatJar → `build/dist/battery-doctor.zip`).
- [ ] 테스트: `RealAdb`가 인자를 그대로 전달하는지(가짜 실행 파일 = 셸 스크립트 `echo "$@"`), 타임아웃이 동작하는지(`sleep 5`, timeout 1). `Connect`가 `devices` 출력에 주소가 없으면 `connect`를 호출하고 `pair`는 사용자가 코드를 입력했을 때만 호출(가짜 AdbRunner로 호출 기록 검증).
- [ ] 구현·통과·커밋 `battery-doctor: 뼈대·ADB 실행·연결`.

### Task 9: 수집

**Files:** `Collect.kt`, 테스트 `CollectTest`

**Interfaces:**
```kotlin
data class Snapshot(val dir: File) { fun text(name: String): String? ; val time: Long }
object Collect { val COMMANDS: List<Pair<String, List<String>>> /* 이름 → shell 인자, 스펙 3장 목록 */
    fun run(adb: AdbRunner, root: File, bugreport: Boolean, log: (String) -> Unit): Snapshot /* root/snapshots/yyyyMMdd-HHmmss/, 실패는 <이름>.error.txt */ }
```
워치 CSV: `adb shell ls /sdcard/Android/data/com.mywatch.kakaomap/files/battery/log.csv` 성공 시 `pull` → `watch_log.csv`.

- [ ] 테스트: 가짜 AdbRunner로 모든 명령이 호출되고 파일이 생김; 한 명령 실패해도 계속; CSV 없으면 건너뜀.
- [ ] 구현·통과·커밋 `battery-doctor: 수집`.

### Task 10: 파서

**Files:** `parse/*.kt`, `src/test/resources/samples/*.txt`(가짜 덤프: `battery.txt`, `batterystats.txt`, `batterystats_checkin.txt`, `alarm.txt`, `sensorservice.txt`, `deviceidle.txt`, `packages_u.txt`, `settings_global.txt`, `power.txt`), 테스트 파서별

**Interfaces (핵심 데이터):**
```kotlin
data class BatteryNow(val level: Int?, val scale: Int?, val voltageMv: Int?, val tempDeciC: Int?, val health: String?, val status: String?, val chargeCounterUah: Int?, val samsung: Map<String, String> /* mSavedBattery* */)
data class WakeLock(val uid: Int?, val pkg: String?, val tag: String, val totalMs: Long, val count: Int)
data class PowerItem(val label: String /* uid 또는 Screen/Idle… */, val pkg: String?, val mah: Double, val percent: Double? )
data class BatteryStats(val onBatteryMs: Long?, val screenOnMs: Long?, val screenOffMs: Long?, val screenOffAwakeMs: Long?, val dischargeMah: Pair<Int,Int>? /*lower,upper*/,
    val dischargeScreenOn: Int?, val dischargeScreenOff: Int?, val capacityMah: Int?, val computedDrainMah: Double?, val actualDrainMah: Pair<Double,Double>?,
    val powerItems: List<PowerItem>, val partialWakeLocks: List<WakeLock>, val kernelWakeLocks: List<WakeLock>, val wakeupReasons: List<WakeLock>,
    val radioActiveMs: Long?, val wifiScanCount: Int?, val gpsMs: Long?, val uidPackages: Map<Int, List<String>>)
data class AlarmStat(val pkg: String, val wakeups: Int, val totalMs: Long, val count: Int)
data class SensorUse(val sensor: String, val pkg: String, val rateHz: Double?, val active: Boolean)
data class IdleState(val state: String?, val whitelist: List<String>)
data class PackageInfo(val name: String, val uid: Int?, val enabled: Boolean, val thirdParty: Boolean)
```
텍스트 batterystats에서 찾을 줄(관대한 정규식, 없으면 null): `Time on battery: 5h 12m 3s 123ms (98.1%) realtime`, `Time on battery screen off: …`, `Screen on: … (12.3%) 42x`, `Screen off: … Awake: …`(없으면 `Awake: …`), `Discharge: 312 mAh`/`Amount discharged (lower bound): 60`, `Estimated power use (mAh):`, `Capacity: 445, Computed drain: 310, actual drain: 280-290`, `Uid u0a123: 45.2 ( cpu=… wake=… )`, `Screen: 80.1`, `All kernel wake locks:`/`All partial wake locks:`/`All wakeup reasons:` 아래 `Kernel Wake lock NAME: 1h 2m (13 times) realtime` 또는 `Wake lock u0a123 TAG: 10m 2s (33 times) realtime`. 앱별 블록 `u0a123:` 아래 `Wake lock TAG: … partial (N times)`. checkin: `9,UID,l,wl,TAG,PARTIAL_MS,PARTIAL_COUNT,…`, `9,0,i,uid,UID,PKG`, `9,UID,l,pwi,LABEL,MAH,…`, `9,0,l,kwl,NAME,MS,COUNT`, `9,0,l,wr,NAME,MS,COUNT`, `9,0,l,bt,…`(batteryRealtime/uptime/screenOn…), `9,0,l,dc,…`.

- [ ] 견본 덤프 작성(AOSP 포맷을 따르되 가짜 패키지·값; 각 섹션 2~5줄).
- [ ] 파서별 실패하는 테스트(값 추출, 섹션 없을 때 null, uid→패키지 매핑, 삼성 필드 보존).
- [ ] 구현·통과·커밋 `battery-doctor: dumpsys 파서`.

### Task 11: 분석·판정

**Files:** `Analyzer.kt`, 테스트 `AnalyzerTest`

**Interfaces:**
```kotlin
enum class Severity { INFO, CAUTION, ABNORMAL }
data class Finding(val id: String, val severity: Severity, val title: String, val evidence: List<String>, val packages: List<String> = emptyList(), val source: String? = null)
enum class Cause { INSUFFICIENT, GAUGE, SOFTWARE, USAGE, HARDWARE, NORMAL }
data class DoctorVerdict(val cause: Cause, val confidence: String /* 높음/중간/낮음 */, val headline: String, val why: List<String>, val todo: List<String>, val culprits: List<String>)
data class Facts(val model: String?, val build: String?, val nominalMah: Int?, val now: BatteryNow?, val stats: BatteryStats?, val alarms: List<AlarmStat>, val sensors: List<SensorUse>,
    val idle: IdleState?, val packages: List<PackageInfo>, val settings: Map<String, Map<String,String>>, val heldWakeLocks: List<String>, val watch: Analysis? /* battery-core */)
object Analyzer { fun facts(snapshot: Snapshot): Facts; fun findings(f: Facts): List<Finding>; fun verdict(f: Facts, findings: List<Finding>): DoctorVerdict }
```
규칙은 스펙 3장 "분석·판정". 신뢰도: 근거 소견 2개 이상 ABNORMAL이면 높음, 1개면 중간, 워치 CSV 없이 dumpsys만이면 한 단계 낮춤.

- [ ] 실패하는 테스트: 판정 가지 6개 각각(사실 조립 → 기대 Cause), 깨어있던 비율 계산, wakelock 시간당 환산, GMS 15% 패턴, 설정 소견(키 없으면 소견 없음).
- [ ] 구현·통과·커밋 `battery-doctor: 분석·판정`.

### Task 12: HTML 보고서

**Files:** `HtmlReport.kt`, 테스트 `HtmlReportTest`

- [ ] 실패하는 테스트: 결론 카드에 판정·신뢰도, 할 일 3개, 소견이 심각도 순, 범인 표에 패키지, 워치 그래프 `<svg>`가 표본 있을 때만, `<`·`&`가 이스케이프, 기준 출처 문구 포함.
- [ ] 구현(문자열 템플릿, 인라인 CSS, 모바일 폭 대응, 접힘 `<details>` 원자료). `analyze` 뒤 `java.awt.Desktop`로 열기(실패 시 경로 출력).
- [ ] 통과·커밋 `battery-doctor: HTML 보고서`.

### Task 13: 조치 카탈로그·적용·되돌리기

**Files:** `fix/Catalog.kt`, `fix/Fixer.kt`, `fix/Journal.kt`, 테스트 3개

**Interfaces:**
```kotlin
enum class Tier { SAFE, CAUTION, STRONG }
data class Lever(val id: String, val title: String, val detail: String, val tier: Tier, val packages: List<String> = emptyList(),
    val readState: (AdbRunner) -> String?, val apply: List<List<String>>, val undo: (before: String?) -> List<List<String>>?, val recommended: Boolean = false)
object Catalog { fun levers(f: Facts, findings: List<Finding>): List<Lever> /* 조건에 맞는 것만, 추천 표시 */ ; val DENY: List<Regex> }
data class JournalEntry(val time: Long, val id: String, val before: String?, val applied: List<List<String>>, val undo: List<List<String>>?, val undone: Boolean)
class Journal(file: File) { fun append(e: JournalEntry); fun entries(): List<JournalEntry>; fun markUndone(index: Int) }
object Fixer { fun apply(adb: AdbRunner, lever: Lever, journal: Journal): Boolean; fun undo(adb: AdbRunner, journal: Journal, index: Int?): Int }
```

- [ ] 실패하는 테스트: 거부 목록 패키지는 절대 Lever로 나오지 않음; 키가 settings에 없으면 설정 Lever 없음; 소견의 범인 패키지에 `bg-restrict:<pkg>` 추천; STRONG은 recommended=false 고정; Journal 왕복·되돌리기 역순 명령; `pm clear`는 undo=null(기록만).
- [ ] 구현·통과·커밋 `battery-doctor: 조치 카탈로그·되돌리기`.

### Task 14: 증거 패키지·신고문·마스킹

**Files:** `Evidence.kt`, 테스트 `EvidenceTest`

- [ ] 실패하는 테스트: 마스킹(`a.b@gmail.com`→`[email]`, `010-1234-5678`→`[phone]`, `36.1234,127.5678`→`[coord]`, `ro.serialno`·`SSID` 값), `share/`에 `location.txt` 없음, 신고문 3종에 모델·펌웨어·측정치가 채워지고 `--purchase`로 경과일 계산(10일/1개월 기준 문장 분기), zip 생성.
- [ ] 구현(문구는 스펙 3장 '증거 패키지'; 소비자분쟁해결기준 인용은 03 연구 노트의 원문).
- [ ] 통과·커밋 `battery-doctor: 증거 패키지`.

### Task 15: CLI 메뉴, 실행기, README, 배포물

**Files:** `Main.kt`, `BatteryDoctor.bat`, `README.txt`, 테스트 `MainTest`(인자 파싱)

- [ ] 인자 없음 → 메뉴(1~6, 0 종료), 인자 있으면 하위 명령. `-Dbd.adb` 없으면 PATH의 adb.
- [ ] `BatteryDoctor.bat`(스펙 3장 실행기; `setlocal EnableDelayedExpansion`, UTF-8 BOM 없이 ASCII만 — 한글 메시지는 Java 쪽에서), `README.txt`(한국어: 준비물, 무선 디버깅 켜기 5번 탭, 메뉴 설명, share 폴더만 공유).
- [ ] `./gradlew :battery-doctor:distZip` → zip 안 3파일 확인(테스트 또는 빌드 검증 스크립트).
- [ ] 커밋 `battery-doctor: 메뉴·실행기·배포물`.

---

## 4부. 통합

### Task 16: CI

- [ ] `release.yml` 테스트·빌드 명령 교체: `./gradlew --no-daemon :app:testDebugUnitTest :battery-core:test :battery-doctor:test :app:assembleRelease :battery-doctor:distZip`; `Prepare release files`에서 `cp battery-doctor/build/dist/battery-doctor.zip .`; `FILES`에 `battery-doctor.zip`; 릴리스 노트에 "PC 툴킷 battery-doctor.zip(Windows)" 한 줄.
- [ ] `pages.yml`·`paths-ignore` 영향 확인(새 모듈 경로는 무시 목록에 없음).
- [ ] 커밋 `CI: 배터리 닥터 모듈 테스트·zip 첨부`.

### Task 17: e2e(가짜 adb)와 보고서 화면 확인

- [ ] `battery-doctor/tools/fake-adb.sh`: 인자에 따라 `samples/*.txt` 출력(`shell dumpsys battery` 등), `pull`은 견본 CSV 복사, `settings put`·`pm`·`cmd appops`는 상태 파일에 기록해 `settings get`에 반영.
- [ ] 실제 fatJar로 `collect → analyze → fix --apply wifi-scan-off,bg-restrict:com.example.drain → undo --all` 실행, 스냅샷·report.html·journal 확인, 되돌린 뒤 상태 파일이 원래 값.
- [ ] Playwright(Chromium)로 report.html 스크린샷(폭 400·1200) 확인.
- [ ] 전체 테스트(`rt.sh` 전체 + JVM 모듈) 통과.

### Task 18: 문서·배포

- [ ] README에 '배터리' 화면과 battery-doctor 절 추가(사용법·공유 폴더 주의).
- [ ] 커밋·`main` 푸시 → 릴리스 v128 확인(APK·zip 첨부, 워치 자동 업데이트).
- [ ] zip을 사용자에게 파일로도 전달, 첫 수집(share 폴더) 요청.
