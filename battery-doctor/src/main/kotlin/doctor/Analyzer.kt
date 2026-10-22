package doctor

import com.mywatch.battery.core.AnomalyKind
import com.mywatch.battery.core.ChangeFinder
import com.mywatch.battery.core.ChargeHabits
import com.mywatch.battery.core.ChargeSpeed
import com.mywatch.battery.core.DayTrend
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Gauge
import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.SampleCsv
import com.mywatch.battery.core.Thresholds
import com.mywatch.battery.core.TrendReport
import com.mywatch.battery.core.WatchModels
import doctor.fix.Catalog
import doctor.parse.AlarmDump
import doctor.parse.BatteryDump
import doctor.parse.BatteryStatsCheckin
import doctor.parse.BatteryStatsMerge
import doctor.parse.BatteryStatsText
import doctor.parse.DeviceIdleDump
import doctor.parse.Durations
import doctor.parse.GmsPackage
import doctor.parse.LocationDump
import doctor.parse.Packages
import doctor.parse.PowerDump
import doctor.parse.SensorDump
import doctor.parse.SettingsDump
import doctor.parse.WakeLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 사실(facts) → 소견(findings) → 판정(verdict) */
object Analyzer {
    const val GMS = "com.google.android.gms"
    /** 오류 보내기 — 워치 앱과 같은 길·같은 말([com.mywatch.battery.core.KnownIssues.REPORT])에 붙여 넣을 글(메뉴 6 증거 패키지)까지 */
    const val REPORT = "${com.mywatch.battery.core.KnownIssues.REPORT} — 붙여 넣을 글은 메뉴 6(증거 패키지)의 ${Evidence.LETTER_MEMBERS}"
    /** One UI Watch 홈 (시스템 UI) */
    const val HOME = "com.samsung.android.wearable.sysui"
    /** 시스템 uid (android.os.Process.SYSTEM_UID) */
    const val SYSTEM_UID = 1000
    /** 워치 앱 요약의 알려진 문제를 쓸 만큼 새로운가 (수집 때와의 차이) */
    const val WATCH_FRESH_MS = 3L * 3_600_000L
    /** 수집 중 새로 쓴 요약은 수집 시각(워치 date — 먼저 읽음)보다 이만큼까지 늦을 수 있다 */
    const val WATCH_AHEAD_MS = 30L * 60_000L

    private const val DAY_MS = 24 * 3_600_000L
    /** '언제부터' 소견의 갈래 — 범인 앱을 정하지 않는다 ([Finding.blames]) */
    const val TREND = "trend"

    fun facts(snapshot: Snapshot, zone: ZoneId = ZoneId.systemDefault()): Facts {
        val getprop = snapshot.text("getprop") ?: ""
        val model = Collect.propOf(getprop, "ro.product.model") ?: snapshot.meta.getProperty("model")
        val nominal = WatchModels.nominalMah(model)
        val stats = BatteryStatsMerge.merge(BatteryStatsText.parse(snapshot.text("batterystats")), BatteryStatsCheckin.parse(snapshot.text("batterystats_checkin")))
        val alarm = AlarmDump.parse(snapshot.text("alarm"))
        val power = PowerDump.parse(snapshot.text("power"))
        val samples = snapshot.watchCsv?.let { runCatching { SampleCsv.parse(it.readText(Charsets.UTF_8)) }.getOrNull() } ?: emptyList()
        val watch = if (samples.isNotEmpty()) runCatching { DrainAnalyzer(nominal, zone).analyze(samples, snapshot.time) }.getOrNull() else null
        return Facts(
            model = model,
            build = Collect.propOf(getprop, "ro.build.display.id") ?: snapshot.meta.getProperty("build"),
            incremental = Collect.propOf(getprop, "ro.build.version.incremental"),
            nominalMah = nominal,
            now = BatteryDump.parse(snapshot.text("battery")),
            stats = stats,
            alarms = alarm.stats,
            alarmElapsedMs = alarm.elapsedMs,
            sensors = SensorDump.parse(snapshot.text("sensorservice")),
            idle = DeviceIdleDump.parse(snapshot.text("deviceidle"), snapshot.text("doze_whitelist")),
            packages = Packages.parse(snapshot.text("packages_u"), snapshot.text("packages_e"), snapshot.text("packages_d"), snapshot.text("packages_3")),
            settings = mapOf("global" to SettingsDump.parse(snapshot.text("settings_global")), "secure" to SettingsDump.parse(snapshot.text("settings_secure")), "system" to SettingsDump.parse(snapshot.text("settings_system"))),
            heldWakeLocks = power?.heldWakeLocks?.map { it.display } ?: emptyList(),
            power = power,
            screenOffAtDump = power?.interactive?.not() ?: true,
            locations = LocationDump.parse(snapshot.text("location")),
            gmsVersion = GmsPackage.versionName(snapshot.text("gms_package")),
            watch = watch,
            watchSamples = samples,
            watchSummary = snapshot.watchSummary,
            watchMetrics = com.mywatch.battery.core.SummaryMetrics.parse(snapshot.watchSummary),
            watchTrend = if (samples.isNotEmpty()) runCatching { TrendReport.of(samples, snapshot.time, zone) }.getOrNull() else null,
            zone = zone,
            collectedAt = snapshot.time,
            missing = snapshot.failed(),
            snapshotDir = snapshot.dir.path,
        )
    }

    // ───────────────────────── 소견 ─────────────────────────

    fun findings(f: Facts): List<Finding> {
        val out = ArrayList<Finding>()
        val s = f.stats
        val hours = s?.onBatteryHours

        // 1. 화면 꺼짐 중 깨어 있던 비율
        s?.screenOffAwakeRatio?.let { r ->
            val sev = when { r > Rules.AWAKE_ABNORMAL -> Severity.ABNORMAL; r > Rules.AWAKE_CAUTION -> Severity.CAUTION; else -> Severity.INFO }
            out += Finding(
                "awake-ratio", sev, "화면 꺼진 동안 깨어 있던 비율 ${pct(r * 100)}% — ${sev.label}",
                listOf("화면 꺼짐 ${Durations.human(s.screenOffMs)} 중 CPU 깨어 있음 ${Durations.human(s.screenOffAwakeMs)} (${pct(r * 100)}%)", "기준: 10% 초과 주의, 20% 초과 비정상"),
                source = Rules.SOURCE_AWAKE, category = "awake",
            )
        }

        // 2. 앱 partial wakelock (패키지별 합계)
        if (s != null && hours != null && hours > 0) {
            val byPkg = LinkedHashMap<String, MutableList<WakeLock>>()
            for (w in s.partialWakeLocks) {
                val name = w.pkg ?: f.packageByUid(w.uid) ?: w.uid?.let { "uid $it" } ?: "?"
                byPkg.getOrPut(name) { ArrayList() } += w
            }
            for ((pkg, list) in byPkg) {
                val total = list.sumOf { it.totalMs }
                val count = list.sumOf { it.count }
                val perHour = total / 1000.0 / hours
                val sev = when {
                    total > Rules.WAKELOCK_ABNORMAL_TOTAL_MS || perHour > Rules.WAKELOCK_ABNORMAL_S_PER_H -> Severity.ABNORMAL
                    perHour > Rules.WAKELOCK_CAUTION_S_PER_H -> Severity.CAUTION
                    else -> null
                } ?: continue
                val top = list.sortedByDescending { it.totalMs }.take(3).joinToString(", ") { "${it.tag} ${Durations.human(it.totalMs)}" }
                out += Finding(
                    "wakelock:$pkg", sev, "$pkg 의 웨이크락 ${Durations.human(total)} — ${sev.label}",
                    listOf("합계 ${Durations.human(total)}, ${count}회 — ${fmt1(perHour)}초/시간 (배터리 사용 ${fmt1(hours)}시간 기준)", "태그: $top", "기준: 18.5초/시간 초과 주의, 합계 1시간 초과 또는 300초/시간 초과 비정상"),
                    packages = listOf(pkg), source = Rules.SOURCE_WAKELOCK, category = "wakelock",
                )
            }
            // 커널 웨이크락·깨움 이유 상위 5개(참고)
            if (s.kernelWakeLocks.isNotEmpty()) {
                out += Finding("kernel-top", Severity.INFO, "커널 웨이크락 상위 ${minOf(5, s.kernelWakeLocks.size)}개",
                    s.kernelWakeLocks.sortedByDescending { it.totalMs }.take(5).map { "${it.tag}: ${Durations.human(it.totalMs)} (${it.count}회) — ${kernelHint(it.tag)}" },
                    source = "커널 웨이크락은 하드웨어·드라이버 단의 깨어 있음. PowerManagerService.WakeLocks 는 앱 웨이크락의 합계", category = "info")
            }
            if (s.wakeupReasons.isNotEmpty()) {
                out += Finding("reasons-top", Severity.INFO, "깨움 이유(wakeup reasons) 상위 ${minOf(5, s.wakeupReasons.size)}개",
                    s.wakeupReasons.sortedByDescending { it.count }.take(5).map { "${it.tag}: ${it.count}회, ${Durations.human(it.totalMs)}" },
                    source = "qcom,smd-rpm / glink·modem 계열은 모뎀·무선, alarmtimer 는 알람 타이머", category = "info")
            }
        }

        // 3. 알람
        val alarmHours = (f.alarmElapsedMs ?: s?.onBatteryMs)?.takeIf { it > 0 }?.let { it / 3_600_000.0 }
        if (alarmHours != null && alarmHours > 0) {
            for (a in f.alarms) {
                val perHour = a.wakeups / alarmHours
                val sev = when { perHour > Rules.ALARM_ABNORMAL_PER_H -> Severity.ABNORMAL; perHour > Rules.ALARM_CAUTION_PER_H -> Severity.CAUTION; else -> null } ?: continue
                out += Finding("alarm:${a.pkg}", sev, "${a.pkg} 의 깨움 알람 시간당 ${fmt1(perHour)}회 — ${sev.label}",
                    listOf("${alarmHours.let { fmt1(it) }}시간 동안 wakeup ${a.wakeups}회, 알람 ${a.count}회, 실행 ${Durations.human(a.totalMs)}", "기준: 시간당 20회 초과 주의, 60회 초과 비정상"),
                    packages = listOf(a.pkg), source = Rules.SOURCE_ALARM, category = "alarm")
            }
        }

        // 4. 센서 — 화면 꺼진 상태에서 시스템 외 앱의 가속도·심박 연속 등록
        if (f.screenOffAtDump) {
            val byPkg = f.sensors.filter { it.active && (it.isMotion || it.isHeartRate) && f.isThirdParty(it.pkg) }.groupBy { it.pkg }
            for ((pkg, uses) in byPkg) {
                out += Finding("sensor:$pkg", Severity.CAUTION, "$pkg 이(가) 화면 꺼진 상태에서 센서를 계속 등록 중 — 수면·운동 기록 앱이 아니면 의심",
                    uses.map { "${it.sensor}${it.rateHz?.let { hz -> " ${fmt1(hz)}Hz" } ?: ""}" } + listOf("덤프 시점 화면 상태: 꺼짐"),
                    packages = listOf(pkg), source = Rules.SOURCE_SENSOR, category = "sensor")
            }
        }

        // 5. 무선·GPS·화면 (사용 패턴)
        if (s != null && hours != null && hours > 0) {
            s.screenOnMs?.let { on ->
                val share = on.toDouble() / (s.onBatteryMs ?: 1)
                if (share > Rules.SCREEN_SHARE) {
                    out += Finding("usage-screen", Severity.CAUTION, "화면 켜짐 ${pct(share * 100)}% — 사용량이 소모를 지배",
                        listOf("배터리 사용 ${fmt1(hours)}시간 중 화면 켜짐 ${Durations.human(on)}", s.dischargeScreenOn?.let { "화면 켜짐 중 소모 ${it}포인트 / 꺼짐 중 ${s.dischargeScreenOff ?: "?"}포인트" } ?: "", "기준: 화면 켜짐 15% 초과"),
                        source = Rules.SOURCE_USAGE, category = "usage")
                }
            }
            s.gpsMs?.takeIf { it > Rules.GPS_LONG_MS }?.let {
                out += Finding("usage-gps", Severity.CAUTION, "GPS ${Durations.human(it)} — 운동·길안내 소모", listOf("GPS 사용 ${Durations.human(it)} (배터리 사용 ${fmt1(hours)}시간 중)", "운동 중 GPS 는 정상적으로 큰 소모 — 대기 측정에서는 뺀다"), source = Rules.SOURCE_USAGE, category = "usage")
            }
            s.radioActiveMs?.let { r ->
                val share = r.toDouble() / (s.onBatteryMs ?: 1)
                if (share > Rules.RADIO_SHARE) out += Finding("usage-radio", Severity.CAUTION, "셀룰러 라디오 활성 ${pct(share * 100)}% — 무선 소모", listOf("라디오 활성 ${Durations.human(r)}", "LTE 모델은 설정 > 연결 > 모바일 네트워크 '자동'(휴대폰 연결 중엔 LTE 끔) 확인"), source = Rules.SOURCE_USAGE, category = "usage")
            }
            s.wifiScanCount?.takeIf { it > 0 }?.let { n ->
                if (n / hours > 20) out += Finding("usage-wifi-scan", Severity.CAUTION, "Wi-Fi 스캔 ${n}회(시간당 ${fmt1(n / hours)}회)", listOf("백그라운드 Wi-Fi 스캔이 잦음 — wifi_scan_always_enabled 와 위치 서비스 확인"), source = Rules.SOURCE_USAGE, category = "usage")
            }
        }

        // 워치 앱이 방금 쓴 요약의 알려진 문제 — 최근 2시간 앱별 통계(화면 꺼진 동안 CPU·웨이크락)로 짚은 것이라 이 보고서의 충전 뒤
        // 통계와 따로 잰 증거. 3시간 넘게 묵었거나 판단할 만큼(55분) 기록이 없었으면 쓰지 않는다. 수집 시각(워치 date)은 요약을 새로
        // 쓰기 전에 읽으므로 새 요약은 그보다 조금 늦다 — 30분까지는 '방금 쓴 것'으로 (리뷰에서 찾음: 늘 미래라 버려졌다)
        val wm = f.watchMetrics
        // 워치가 이번 일을 처음 짚은 때(요약의 first_*_at) — 증상이 얼마나 이어졌는지 (신고문의 '언제부터'). 모르면 적지 않는다 ([firstSeenAt])
        fun firstSeen(key: String): List<String> {
            val t = firstSeenAt(f, key) ?: return emptyList()
            val ref = f.collectedAt ?: t
            return listOf("처음 짚은 때: ${time(t, f.zone)} (${ago((ref - t).coerceAtLeast(0L))})")
        }
        val watchFresh = (wm["app_window_min"] ?: 0.0) >= 55.0 &&
            wm["at"]?.let { at -> f.collectedAt?.let { c -> c - at.toLong() in -WATCH_AHEAD_MS..WATCH_FRESH_MS } } == true
        if (watchFresh) {
            // 워치가 적은 단계: 0 처음 · 1 재시작 뒤에도 또 · 2 다음 단계 뒤에도 계속 (예전 워치 앱은 1까지만)
            fun stage(key: String) = (wm[key] ?: 0.0).let { if (it >= 2.0) 2 else if (it >= 1.0) 1 else 0 }
            fun back(key: String) = when (stage(key)) { 2 -> " — 다음 단계 뒤에도 계속"; 1 -> " — 재시작 뒤에도 또 나타남"; else -> "" }
            val minutes = wm["app_window_min"]!!.toInt()
            wm["issue_gms"]?.takeIf { it >= 1.0 }?.let { g ->
                out += Finding("watch-gms", if (g >= 2.0) Severity.ABNORMAL else Severity.CAUTION, "워치 앱: 최근 Google Play 서비스 과소모${back("back_gms")}",
                    listOf("워치 앱 지킴이가 최근 ${minutes}분 앱별 통계(화면 꺼진 동안의 CPU·웨이크락)로 짚었습니다 — 이 보고서의 충전 뒤 통계와 따로 잰 것") +
                        firstSeen("first_gms_at") +
                        listOf("자동 치료(메뉴 8)가 모든 앱 캐시 정리와 Play 서비스 다시 시작을 합니다(데이터·로그인은 그대로)") +
                        // 캐시·재시작·치료를 다 보인 뒤에도 — 모두에게 듣는 해결은 없다 (워치 앱 할 일과 같게)
                        if (stage("back_gms") >= 2) listOf("치료·재시작 뒤에도 계속되면: 충전기에 올려 둔 채 워치 Play 스토어 › 내 앱 › 모두 업데이트(도움이 됐다는 보고), " +
                            "고쳐질 때까지 밤엔 워치 앱 지킴이 밤 모드, 과소모가 이어지는 동안 $REPORT") else emptyList(),
                    packages = listOf(GMS), source = "워치 앱 지킴이(앱별 건강 통계, 최근 2시간)", category = "gms")
            }
            wm["issue_home"]?.takeIf { it >= 1.0 }?.let { g ->
                // 홈은 시스템 앱 — 범인으로 제한하지 않는다(DENY). 재시작 뒤에도 또 나타났으면 캐시 정리를 추천(워치의 다음 단계와 같게)
                out += Finding("watch-home", if (g >= 2.0) Severity.ABNORMAL else Severity.CAUTION, "워치 앱: One UI Watch 홈 과소모${back("back_home")}",
                    listOf("워치 앱 지킴이가 최근 ${minutes}분 앱별 통계로 짚었습니다(화면 켜짐·받은 알림 몫을 빼고 남은 CPU)") +
                        firstSeen("first_home_at") +
                        when (stage("back_home")) {
                            2 -> listOf("다음 단계(홈 캐시 삭제 → 강제 재시작)를 보인 뒤 또 재시작했는데도 계속됩니다(홈 캐시를 아직 안 지웠으면 그것부터) — " +
                                "폰의 Galaxy Wearable 앱 › 워치 설정 › 워치 소프트웨어 업데이트 확인, 고쳐질 때까지 밤엔 워치 앱 지킴이 밤 모드, " +
                                "과소모가 이어지는 동안 $REPORT")
                            1 -> listOf("강제 재시작 뒤에도 또 나타났습니다 — 자동 치료(메뉴 8)의 '모든 앱 캐시 정리'가 홈 캐시도 지웁니다. 그 뒤 한 번 더 강제 재시작")
                            else -> listOf("홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작 — 보통 재시작으론 안 풀리고 강제 재시작으로 나아졌다는 보고",
                                "그래도 또 나타나면 자동 치료(메뉴 8)의 '모든 앱 캐시 정리'가 홈 캐시도 지웁니다 — 그 뒤 한 번 더 강제 재시작")
                        },
                    source = "워치 앱 지킴이(앱별 건강 통계, 최근 2시간)", category = "home")
            }
        }

        // 워치가 이틀 안에 짚었는데 지금은 안 보이는 것(요약의 seen_*_h) — 재시작·치료 뒤 다시 보는 중이거나 들은 것. 이 보고서의 충전 뒤
        // 통계엔 그 전 소모가 남아 소모율이 높게 나올 수 있어 하드웨어로 단정하지 않게 '주의'로 (범인으로 짚지는 않는다)
        val summaryAt = wm["at"]?.toLong()
        val summaryFresh = summaryAt != null && f.collectedAt?.let { c -> c - summaryAt in -WATCH_AHEAD_MS..WATCH_FRESH_MS } == true
        if (summaryFresh) {
            val window = wm["app_window_min"]?.toInt()
            // 그때가 이 보고서가 잰 창(충전 뒤 dumpsys · 워치 기록 24시간까지 — 기록이 그보다 짧으면 그 길이) 안이어야 지금 소모율에 섞여
            // 있다 — 그래야 판정을 바꾼다 (리뷰)
            val statsHours = s?.onBatteryHours ?: 0.0
            val watchHours = if (f.hasWatchLog) minOf(24.0, f.watch?.spanHours ?: 0.0) else 0.0
            fun earlier(key: String, firstKey: String, id: String, name: String, category: String) {
                // 'N시간 전'은 수집 때부터 (요약은 수집 몇 분~3시간 전에 쓰였을 수 있다)
                val hours = (wm[key] ?: return) + ((f.collectedAt ?: summaryAt!!) - summaryAt!!).coerceAtLeast(0L) / 3_600_000.0
                if (out.any { it.id == id }) return
                val inWindow = hours <= maxOf(statsHours, watchHours)
                val now = when {
                    window == null -> "지금은 앱별 기록이 없어(재시작·PC 치료 직후이거나 앱별 통계 권한이 없음) 지금 상태는 아직 모릅니다"
                    window >= 55 -> "최근 ${window}분 앱별 기록에선 지금은 조용합니다"
                    else -> "지금은 앱별 기록이 ${window}분치라 아직 다시 보는 중입니다(1시간쯤 쌓이면 봄 — 재시작·PC 치료 뒤엔 기록을 새로 모음)"
                }
                out += Finding("$id-earlier", if (inWindow) Severity.CAUTION else Severity.INFO, "워치 앱: ${fmt1(hours).removeSuffix(".0")}시간 전 $name — 지금은 안 보임",
                    listOf("워치 앱 지킴이가 최근 48시간 안에 짚었습니다 — $now") + firstSeen(firstKey) +
                        if (inWindow) listOf("이 보고서가 잰 구간(충전 뒤 통계·워치 기록 최근 24시간)에 그때의 소모가 섞여 소모율이 높게 나올 수 있습니다 — 하드웨어로 단정하기 전에 메뉴 3(측정 시작)으로 초기화하고 하루 뒤 다시 수집(메뉴 2)해 견주세요")
                        else listOf("그때는 이 보고서가 잰 구간보다 앞이라 지금 소모율과는 따로입니다 — 참고로만"),
                    source = "워치 앱 지킴이(최근 48시간)", category = category)
            }
            earlier("seen_gms_h", "first_gms_at", "watch-gms", "Google Play 서비스 과소모", "gms")
            earlier("seen_home_h", "first_home_at", "watch-home", "One UI Watch 홈 과소모", "home")
        }

        // One UI Watch 홈이 시스템 uid(1000)를 같이 쓰면 앱별 통계에서 홈만 따로 셀 수 없다 — 이 보고서의 순위도, 워치 앱의
        // '홈 과소모'도 홈을 짚지 못하니 그렇다고 알린다 (따로 쓰면 조용히)
        f.packages.firstOrNull { it.name == HOME }?.uid?.takeIf { it == SYSTEM_UID }?.let {
            out += Finding("home-uid", Severity.INFO, "One UI Watch 홈이 시스템과 같은 uid(1000) — 홈만 따로 셀 수 없음",
                listOf("앱별 통계는 uid 단위라 홈의 소모가 'android'(시스템)로 합쳐 보입니다 — 이 보고서의 순위와 워치 앱 지킴이의 'One UI Watch 홈 과소모'가 홈을 짚지 못합니다",
                    "홈이 의심되면 워치의 설정 › 배터리 › 배터리 사용량에서 'One UI Watch 홈' 항목을 직접 보세요"),
                category = "info")
        }

        // 6. 추정 전력 순위 + Play 서비스 패턴 + 계산/실측 차이
        if (s != null && s.powerItems.isNotEmpty()) {
            val top = s.powerItems.sortedByDescending { it.mah }.take(10)
            // One UI Watch 홈이 위쪽이면: 순위만으로는 고장이라 단정할 수 없다는 설명 (흔하다고 단정하지도 않는다 — 워치9 보고에선 그게
            // 증상이었다). 화면 꺼진 동안의 CPU 는 워치 앱 지킴이가 화면 켜짐 몫을 빼고 본다
            val homeNote = top.take(3).firstOrNull { it.pkg == HOME }?.let {
                "One UI Watch 홈이 위쪽 — 순위만으로는 고장이라 단정할 수 없습니다. 화면이 꺼진 채 CPU 를 많이 쓰는지는 워치 앱 지킴이가 " +
                    "화면 켜짐 몫을 빼고 봅니다(알려진 문제 'One UI Watch 홈 과소모' — 할 일은 단계마다 달라 워치 배터리 화면 '지금 할 일'에)"
            }
            out += Finding("power-top", Severity.INFO, "추정 전력 사용 상위 ${top.size}개",
                top.mapIndexed { i, p -> "${i + 1}. ${p.display}: ${fmt1(p.mah)} mAh${p.percent?.let { " (${pct(it)}%)" } ?: ""}" } + listOfNotNull(homeNote),
                packages = top.mapNotNull { it.pkg }, source = "dumpsys batterystats 'Estimated power use' — 시스템이 모델로 추정한 값(실측 아님)", category = "info")
            val total = s.computedDrainMah ?: s.powerItems.sumOf { it.mah }.takeIf { it > 0 }
            val gms = s.powerItems.filter { it.pkg == GMS }.sumOf { it.mah }
            val gmsPct = if (total != null && total > 0) gms / total * 100 else null
            if (gmsPct != null && gmsPct > Rules.GMS_SHARE_PERCENT) {
                out += Finding("gms-share", Severity.ABNORMAL, "Google Play 서비스가 추정 전력의 ${pct(gmsPct)}% — 2026년 Play 서비스 과소모 패턴",
                    listOf("${fmt1(gms)} mAh / 전체 ${fmt1(total!!)} mAh", f.gmsVersion?.let { "Play 서비스 버전 $it" } ?: "", "기준: 15% 초과"),
                    packages = listOf(GMS), source = Rules.SOURCE_GMS, category = "gms")
            }
            val computed = s.computedDrainMah
            val actual = s.actualDrainMah
            if (computed != null && actual != null) {
                val mid = (actual.first + actual.second) / 2
                if (mid > 0 && kotlin.math.abs(computed - mid) / mid > Rules.DRAIN_GAP_RATIO) {
                    out += Finding("drain-gap", Severity.INFO, "추정 전력 합계(${fmt1(computed)} mAh)와 실제 소모(${fmt1(actual.first)}~${fmt1(actual.second)} mAh)가 ${pct(kotlin.math.abs(computed - mid) / mid * 100)}% 어긋남",
                        listOf("추정 모델이 못 잡는 소모(무선·게이지 오차)가 있다는 뜻 — 범인 순위는 참고만"), category = "info")
                }
            }
        }

        // 7. 게이지
        f.now?.impliedCapacityMah?.let { implied ->
            val nominal = f.nominalMah
            if (nominal != null) {
                val ratio = implied / nominal
                if (ratio < Rules.GAUGE_LOW_RATIO || ratio > Rules.GAUGE_HIGH_RATIO) {
                    out += Finding("gauge-counter", Severity.ABNORMAL, "전하량 계수기로 역산한 용량 ${fmt1(implied)} mAh = 공칭의 ${pct(ratio * 100)}% — 잔량 표시 불일치",
                        listOf("Charge counter ${f.now.chargeCounterUah} µAh, 표시 잔량 ${f.now.level}%", "기준: 공칭(${nominal} mAh)의 60% 미만 또는 140% 초과"),
                        source = Rules.SOURCE_GAUGE, category = "gauge")
                } else {
                    out += Finding("gauge-counter-ok", Severity.INFO, "전하량 계수기로 역산한 용량 ${fmt1(implied)} mAh (공칭의 ${pct(ratio * 100)}%)", listOf("Charge counter ${f.now.chargeCounterUah} µAh ÷ 잔량 ${f.now.level}%"), source = Rules.SOURCE_GAUGE, category = "info")
                }
            }
        }
        f.watch?.anomalies?.takeIf { it.isNotEmpty() }?.let { list ->
            // 판정(비정상 → 게이지 고장)엔 최근 7일 것만 — 워치 판정과 같게. 그 앞의 것은 증거 줄로만
            val since = (f.collectedAt ?: list.maxOf { it.t }) - com.mywatch.battery.core.DrainAnalyzer.RECENT_ANOMALY_MS
            val hard = list.filter { it.kind != AnomalyKind.CLIFF && it.t >= since }
            val sev = if (hard.isNotEmpty()) Severity.ABNORMAL else Severity.CAUTION
            val kinds = list.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.first().label} ${it.value.size}회" }
            out += Finding("gauge-watch", sev, "워치 기록의 잔량 표시 이상 ${list.size}회 ($kinds) — ${sev.label}",
                list.takeLast(8).map { "${time(it.t, f.zone)} ${it.label}: ${it.detail}" } + listOf("기준: 충전 없이 +5포인트(그 사이 충전으로도 안 될 만큼 — 분당 2포인트 넘게), 부팅 뒤 +10포인트(충전기 위에서 켜졌으면 꺼져 있던 동안 충전으로도 안 될 만큼), 20분 안에 −15포인트, 계수기와 10포인트 차이"),
                source = "battery-core Gauge 규칙 — 잔량 점프·급락은 셀이 아니라 게이지(잔량 추정 펌웨어)의 문제일 때가 많음(2026년 워치8 '재부팅 한 번에 30% 손실' 사례)", category = "gauge")
        }

        // 7-1. 잔량 게이지 드리프트 — 쉬는 전압으로 어림잡은 잔량과 표시 잔량의 차이 (워치9 '17%에서 꺼졌다 켜니 47%' 의 정체)
        f.watch?.drift?.takeIf { it.suspicious }?.let { d ->
            val sev = if (d.certain) Severity.ABNORMAL else Severity.CAUTION
            out += Finding("gauge-drift", sev, "워치 기록: ${d.headline} (신뢰도 ${d.confidence})",
                listOfNotNull(
                    "최근 3시간 쉬는 표본 ${d.restSamples}개, 쉬는 전압 중앙값 ${String.format(Locale.US, "%.2f", d.restMv / 1000.0)}V → 전압-잔량 표로 약 ${d.voltageSoc}%, 표시 ${d.reported}%",
                    d.advice,
                    "기준: ${GaugeDriftRule.SUSPECT_POINTS}포인트 이상 의심, ${GaugeDriftRule.CERTAIN_POINTS}포인트 이상 확실 (전압 기반 잔량은 ±10% 쯤 틀릴 수 있어 큰 차이만 봄)",
                ),
                source = Rules.SOURCE_DRIFT, category = "gauge")
        }
        f.watch?.recoveries?.firstOrNull { it.userMarked }?.let { r ->
            out += Finding("gauge-restart-mark", if (r.gained >= Gauge.REBOOT_JUMP_MIN) Severity.CAUTION else Severity.INFO,
                "사용자가 표시한 강제 재시작: 잔량 ${r.before}% → ${r.after}% (${if (r.gained >= 0) "+" else ""}${r.gained})",
                listOf("${time(r.t, f.zone)} 워치 배터리 화면 '강제 재시작 표시 남기기'", "재시작으로 잔량이 뛰면 그만큼은 게이지 드리프트였다는 뜻"), source = Rules.SOURCE_DRIFT, category = "gauge")
        }
        f.watch?.takeIf { it.hotIdleCount >= DrainAnalyzer.HOT_MIN_SAMPLES }?.let { w ->
            out += Finding("watch-hot", Severity.CAUTION, "워치 기록: 충전 안 하고 화면 꺼진 채 ${DrainAnalyzer.HOT_DECI_C / 10}℃ 이상이 ${w.hotIdleCount}회(15분 표본, 최근 24시간) — 백그라운드 과열",
                listOf("뜨거운 워치는 CPU·무선이 쉬지 않는다는 뜻 — 웨이크락·알람·센서 소견과 같이 보세요", "기준: 1시간어치(4개) 이상 (경험칙)"), source = Rules.SOURCE_AWAKE, category = "awake")
        }
        f.watch?.takeIf { "phantom" in it.verdict.tags }?.let { w ->
            out += Finding("gauge-phantom", Severity.CAUTION, "워치 기록: 표시 소모가 전하량 계수기 소모보다 큼 — 가짜 소모(게이지 오차)",
                w.verdict.reasons.filter { it.contains("가짜 소모") } + listOf("기준: 24시간 평균 표시 소모(%/h)가 계수기 소모(mAh/h ÷ 공칭 용량)보다 1.5포인트 이상 크면"),
                source = Rules.SOURCE_DRIFT, category = "gauge")
        }

        // 7-1. 언제부터 — 최근 7일 안에 대기 소모가 늘어 꺾였으면 그 무렵 바뀐 것과 할 일 (워치 앱이 표본에 남긴 '바뀐 것' 표시).
        // 그 무렵 업데이트된 앱은 우연일 수 있으므로 packages 에 넣지 않는다 — 자동 치료·범인 표가 그 앱을 범인으로 삼지 않게 (글로만)
        f.watchTrend?.takeIf { it.fresh }?.let { t ->
            val s0 = t.shift ?: return@let
            val (todo, how) = t.advice { null } ?: return@let
            out += Finding("watch-shift", Severity.CAUTION, "워치 기록: ${ChangeFinder.headline(s0)}",
                listOf("날마다 대기 시간당 %: " + t.shownDays.joinToString(" · ") { d -> "${ChangeFinder.day(d.date)} ${d.pph?.let { fmt1(it) } ?: "-"}" }) +
                    when {
                        t.suspects.isNotEmpty() -> t.suspects.take(5).map { "그 무렵: ${time(it.t, f.zone)} ${ChangeFinder.label(it) { null }}" }
                        !t.known -> listOf("그때는 워치가 아직 바뀐 것을 적기 전이라 그 무렵 변화는 모름")
                        else -> listOf("그 무렵 워치 앱이 아는 변화(소프트웨어·앱·시계 화면·주요 설정)는 없음")
                    } +
                    listOf("할 일: $todo — $how", "기준: 날마다 대기 %/h 중앙값이 ${DayTrend.RATIO}배·${DayTrend.MIN_DIFF}%p 넘게, 양쪽 ${DayTrend.MIN_SIDE_DAYS}일 이상이 같은 쪽"),
                category = TREND)
        }

        // 7-2. 오래 켜 둔 워치 — 대기 소모가 높으면 재시작부터 (2026년 광탈 글들의 공통 해결담: 재시작 → 캐시 → 초기화)
        f.alarmElapsedMs?.takeIf { it >= Rules.UPTIME_DAYS * DAY_MS }?.let { up ->
            val idleGrade = Thresholds.idle(f.watch?.idle24?.percentPerHour)
            val awakeHigh = (s?.screenOffAwakeRatio ?: 0.0) > Rules.AWAKE_CAUTION
            val high = idleGrade == Grade.CAUTION || idleGrade == Grade.ABNORMAL || awakeHigh
            out += Finding("uptime-long", if (high) Severity.CAUTION else Severity.INFO, "워치를 재시작한 지 ${up / DAY_MS}일" + (if (high) " — 대기 소모가 높으니 재시작부터" else ""),
                listOf("부팅 뒤 ${Durations.human(up)} (dumpsys alarm 의 경과 시간)", Rules.FORCED_RESTART + " — 오래 켜 둔 워치는 재시작으로 멈춘 서비스가 풀리는 일이 많습니다"),
                category = "info")
        }

        // 7-3. 충전 속도 — 워치9 충전이 공식(고속 충전 40mm 80분·44mm 90분)보다 한참 느림(충전 중 뜨거워지며 느려질 수 있다는 보고가 많다 —
        // 2026년 10월 기준 삼성은 고침을 알리지 않음). 범인 앱은 짚지 않는다
        chargeSpeed(f)?.let { (ch, sp) ->
            out += Finding("watch-charge-slow", Severity.CAUTION, "워치 기록: 충전이 공식보다 느림 — 완충 어림 ${ChargeHabits.duration(sp.minutesPerFull)} 넘게 (삼성 공식 고속 충전 ${sp.specMinutes}분)",
                listOfNotNull(
                    // 잰 충전 수는 그 주의 충전 수와 따로 — 잠깐 충전·충전기 위에서 쓴 충전 등은 재지 않는다 (리뷰)
                    "최근 7일 충전 ${ch.sessions}번 중 ${sp.measured}번을 잼 — 처음 오른 ${ChargeSpeed.FROM}~${ChargeSpeed.TO}% 구간 빠르기로 어림한 0→100% 시간의 가운데 값 (그 뒤는 원래 느려 실제 완충은 더 걸림)",
                    sp.maxTempC?.let { "느린 충전의 그 구간 최고 ${fmt1(it).removeSuffix(".0")}℃" + if (sp.hot) " — 충전 중 뜨거워지면 느려질 수 있다는 워치9 사용자 보고와 같은 모습" else "" },
                    ch.advice.firstOrNull { it.startsWith("충전이 공식보다 느립니다") },
                    "기준: 공식 0→100% 시간의 ${ChargeSpeed.SLOW_RATIO}배 이상, 잰 충전 ${ChargeSpeed.MIN_SESSIONS}번 이상의 절반 넘게(둘이면 둘 다)",
                ),
                source = "삼성 발표 충전 시간(40mm 80분·44mm 90분 — Android Authority 2026-08-12) · 워치9 느린 충전 보고(Notebookcheck 2026-08-12, Sammy Fans 2026-10-13)",
                category = "charge")
        }

        // 8. 용량
        f.watch?.capacity?.let { c ->
            val p = c.percentOfNominal
            val sev = if (p != null && p < Rules.CAPACITY_LOW_PERCENT) Severity.CAUTION else Severity.INFO
            out += Finding("capacity-watch", sev, "충전 곡선으로 추정한 용량 ${fmt1(c.estimatedMah)} mAh${p?.let { " (공칭의 ${pct(it)}%)" } ?: ""}${if (sev == Severity.CAUTION) " — 용량 저하 의심" else ""}",
                listOf("근거 충전 세션 ${c.sessions}개", "기준: 공칭의 80% 미만이면 용량 저하 의심"), source = Rules.SOURCE_CAPACITY, category = "capacity")
        }
        f.now?.asoc?.let { a ->
            val sev = if (a < Rules.CAPACITY_LOW_PERCENT) Severity.CAUTION else Severity.INFO
            out += Finding(if (sev == Severity.CAUTION) "capacity-asoc" else "capacity-asoc-ok", sev, "삼성 기록 배터리 건강도(mSavedBatteryAsoc) ${a}%${if (sev == Severity.CAUTION) " — 용량 저하 의심" else ""}",
                listOf("dumpsys battery 의 삼성 확장 필드 그대로: " + f.now.samsung.entries.joinToString(", ") { "${it.key}=${it.value}" }, "기준: 80% 미만"), source = Rules.SOURCE_CAPACITY, category = "capacity")
        }

        // 9. 위험 설정 — 키가 있을 때만
        val global = f.settings["global"] ?: emptyMap()
        fun risky(key: String, bad: String, title: String, lever: String) {
            val v = global[key] ?: return
            if (v.trim() == bad) out += Finding("setting:$key", Severity.CAUTION, "$title ($key=$v)", listOf("조치 '$lever' 로 바꿀 수 있습니다(메뉴 4)"), source = "AOSP 설정 키 — 값이 있을 때만 판단", category = "settings")
        }
        risky("wifi_scan_always_enabled", "1", "Wi-Fi 꺼도 스캔 허용이 켜져 있음", "wifi-scan-off")
        risky("ble_scan_always_enabled", "1", "블루투스 꺼도 BLE 스캔 허용이 켜져 있음", "ble-scan-off")
        risky("adaptive_battery_management_enabled", "0", "적응형 배터리가 꺼져 있음", "adaptive-battery-on")
        risky("app_standby_enabled", "0", "앱 대기(App Standby)가 꺼져 있음", "app-standby-on")

        // 10. 소모율 — dumpsys 와 워치 기록
        s?.percentPerHour?.let { pph ->
            val g = Thresholds.mixed24h(pph)
            out += Finding("stats-rate", sevOf(g), "dumpsys 기준 시간당 ${fmt1(pph)}% — ${g.label}",
                listOf("마지막 충전 뒤 ${fmt1(hours ?: 0.0)}시간 동안 ${s.dischargePercent?.first}~${s.dischargePercent?.second}포인트 소모${s.dischargeMah?.let { ", $it mAh" } ?: ""}", "기준: ${Thresholds.MIXED_NORMAL}%/h 이하 정상, ${Thresholds.MIXED_ABNORMAL}%/h 이상 비정상"),
                source = Thresholds.SOURCE_MIXED, category = "drain")
        }
        f.watch?.let { w ->
            rateFinding(out, "watch-idle", "워치 기록: 대기(화면 꺼짐) 소모", w.idle24, Thresholds.idle(w.idle24.percentPerHour), "${Thresholds.IDLE_NORMAL}%/h 이하 정상, ${Thresholds.IDLE_ABNORMAL}%/h 초과 비정상", Thresholds.SOURCE_IDLE)
            rateFinding(out, "watch-24h", "워치 기록: 24시간 평균 소모", w.w24, Thresholds.mixed24h(w.w24.percentPerHour), "${Thresholds.MIXED_NORMAL}%/h 이하 정상, ${Thresholds.MIXED_ABNORMAL}%/h 이상 비정상", Thresholds.SOURCE_MIXED)
            w.overnight?.let { rateFinding(out, "watch-night", "워치 기록: ${w.overnightLabel ?: "밤사이"} 소모", it, Thresholds.overnight(it.percentPerHour), "${Thresholds.NIGHT_NORMAL}%/h 이하 정상, ${Thresholds.NIGHT_ABNORMAL}%/h 초과 비정상", Thresholds.SOURCE_NIGHT) }
            for (e in w.experiments) {
                val pph = e.rate.percentPerHour ?: continue
                val state = if (e.end == null) "진행 중" else "끝"
                out += Finding("watch-experiment:${e.name}", sevOf(e.grade), "실험 '${e.name}'($state) 시간당 ${fmt1(pph)}% — ${e.grade.label}(경험칙 ${Thresholds.EXPERIMENT_ABNORMAL}%/h)",
                    listOf("${time(e.start, f.zone)} ~ ${e.end?.let { time(it, f.zone) } ?: "진행 중"}, ${fmt1(e.rate.hours)}시간, ${fmt1(e.rate.dropPercent)}포인트", "기준: ${Thresholds.EXPERIMENT_NORMAL}%/h 이하 정상, ${Thresholds.EXPERIMENT_ABNORMAL}%/h 초과 비정상"),
                    source = Thresholds.SOURCE_EXPERIMENT, category = "drain")
            }
            if (w.samples > 0) {
                val recent = f.watchSamples.filter { it.t >= (f.collectedAt ?: Long.MAX_VALUE) - 24 * 3_600_000L }
                if (recent.size >= 8) {
                    val share = recent.count { it.interactive }.toDouble() / recent.size
                    if (share > Rules.SCREEN_SHARE) out += Finding("usage-watch-interactive", Severity.CAUTION, "워치 기록: 최근 24시간 표본의 ${pct(share * 100)}%가 화면 켜짐", listOf("15분 표본 ${recent.size}개 중 ${recent.count { it.interactive }}개"), source = Rules.SOURCE_USAGE, category = "usage")
                }
            }
        }

        // 11. 지금 쥔 웨이크락, 수집 실패
        if (f.heldWakeLocks.isNotEmpty()) {
            out += Finding("held-wakelock", Severity.INFO, "덤프 시점에 쥐고 있던 웨이크락 ${f.heldWakeLocks.size}개", f.heldWakeLocks, packages = f.power?.heldWakeLocks?.mapNotNull { it.pkg } ?: emptyList(), category = "info")
        }
        if (f.missing.isNotEmpty()) {
            out += Finding("collect-failed", Severity.INFO, "수집 실패 ${f.missing.size}개 항목", listOf("빠진 항목: ${f.missing.joinToString(", ")}", "해당 항목의 소견은 비어 있습니다 — 다시 수집하면 채워집니다"), category = "info")
        }

        return out.map { it.copy(evidence = it.evidence.filter { e -> e.isNotBlank() }) }
            .sortedByDescending { it.severity.ordinal }
    }

    private fun rateFinding(out: MutableList<Finding>, id: String, title: String, r: Rate, g: Grade, basis: String, source: String) {
        val pph = r.percentPerHour ?: return
        val mah = r.mahPerHour?.let { ", 실측 ${fmt1(it)} mAh/h" } ?: ""
        out += Finding(id, sevOf(g), "$title 시간당 ${fmt1(pph)}% — ${g.label}", listOf("${fmt1(r.hours)}시간 동안 ${fmt1(r.dropPercent)}포인트$mah", "기준: $basis"), source = source, category = "drain")
    }

    private fun sevOf(g: Grade) = when (g) { Grade.ABNORMAL -> Severity.ABNORMAL; Grade.CAUTION -> Severity.CAUTION; else -> Severity.INFO }

    private fun kernelHint(tag: String): String = when {
        tag.startsWith("PowerManagerService.WakeLocks") -> "앱 웨이크락의 합(어느 앱인지는 위 웨이크락 소견)"
        tag.startsWith("PowerManagerService.Display") -> "화면 켜짐"
        tag.contains("radio", true) || tag.contains("qcom_rx", true) || tag.contains("wlan", true) || tag.contains("ipa", true) || tag.contains("modem", true) -> "무선(모뎀·Wi-Fi) 수신"
        tag.contains("alarm", true) -> "알람 타이머"
        tag.contains("bluetooth", true) || tag.contains("bt_", true) -> "블루투스"
        tag.startsWith("event") -> "입력·센서 이벤트"
        tag.contains("sensor", true) || tag.contains("ssc", true) -> "센서 허브"
        else -> "드라이버"
    }

    // ───────────────────────── 판정 ─────────────────────────

    fun verdict(f: Facts, findings: List<Finding>): DoctorVerdict {
        val statsHours = f.stats?.onBatteryHours ?: 0.0
        val watchHours = f.watch?.spanHours ?: 0.0
        val hasWatch = f.hasWatchLog
        fun ofCat(vararg cats: String, min: Severity) = findings.filter { it.category in cats && it.severity.ordinal >= min.ordinal }
        // home = 워치 앱이 짚은 One UI Watch 홈 과소모 — 범인 앱은 없어도(시스템 앱) 하드웨어 의심으로 넘기지 않게
        val softwareCats = arrayOf("awake", "wakelock", "alarm", "sensor", "gms", "home")
        val homeFlagged = findings.any { it.id == "watch-home" }
        // 워치가 적은 홈 과소모 단계: 0 처음 · 1 재시작 뒤에도 또 · 2 다음 단계 뒤에도 계속
        val homeStage = if (!homeFlagged) 0 else (f.watchMetrics["back_home"] ?: 0.0).let { if (it >= 2.0) 2 else if (it >= 1.0) 1 else 0 }
        val drainAbnormal = ofCat("drain", min = Severity.ABNORMAL)
        val rates = findings.filter { it.category == "drain" }.map { it.title }

        if (statsHours < 2.0 && watchHours < 2.0) {
            val have = buildList {
                if (statsHours > 0) add("dumpsys 통계 ${fmt1(statsHours)}시간")
                if (watchHours > 0) add("워치 기록 ${fmt1(watchHours)}시간")
                if (isEmpty()) add("쓸 수 있는 통계가 없습니다" + (if (f.missing.isNotEmpty()) " (수집 실패: ${f.missing.joinToString(", ")})" else ""))
            }
            return DoctorVerdict(Cause.INSUFFICIENT, "낮음", "측정이 더 필요합니다 — 2시간 넘게 충전기 없이 쓴 기록이 있어야 판정합니다", have,
                listOf("메뉴 3(측정 시작)으로 통계를 초기화해 깨끗한 측정 구간을 시작하세요 (개발자 옵션의 '자동 Wi-Fi 끄기'는 다시 끄세요)",
                    "충전기 없이 평소처럼 8시간 이상(가능하면 밤사이 포함) 쓰세요. 워치 앱의 '배터리' 화면을 한 번 열어 두면 15분마다 기록이 쌓입니다",
                    "충전기에 올리기 전에 Wi-Fi 를 켜고 메뉴 2(수집·분석)를 다시 실행하세요"), emptyList())
        }

        val gauge = ofCat("gauge", min = Severity.ABNORMAL)
        if (gauge.isNotEmpty()) {
            return DoctorVerdict(Cause.GAUGE, confidence(gauge.size, hasWatch), "잔량 표시(게이지)·펌웨어 문제 의심 — ${gauge.first().title}",
                gauge.map { it.title } + rates,
                listOf(Rules.FORCED_RESTART + " — 잔량 점프는 재시작 뒤 사라지는 경우가 많습니다",
                    "워치 배터리 화면 '잔량 표시 점검' → '게이지 보정 시작'(완충 + 1시간 → 10% 아래까지 사용 → 다시 완충 + 1시간)으로 게이지가 용량을 다시 배우게 하세요. 끝나면 같은 화면에서 오차가 줄었는지 보입니다",
                    "메뉴 6(증거 패키지)으로 지금 상태를 보관하세요 — 잔량 점프·급락 기록(워치 CSV)이 핵심 증거입니다",
                    "서비스센터(1588-3366)에 '잔량 표시 오류·과소모'로 접수하고 배터리 효율 측정값을 서면으로 요구하세요 (02_서비스센터_접수메모.txt 참고). 잔량이 튀거나 급락하면 바로 $REPORT"),
                gauge.flatMap { it.packages }.distinct())
        }

        val swAbnormal = ofCat(*softwareCats, min = Severity.ABNORMAL)
        val swCaution = findings.filter { it.category in softwareCats && it.severity == Severity.CAUTION }
        // 핵심 시스템 패키지(거부 목록)는 범인으로 지목하지 않는다 — Play 서비스만 2026년 패턴 안내를 위해 남긴다
        val culprits = (swAbnormal + swCaution).filter { it.blames }.flatMap { it.packages }.distinct().filter { it == GMS || !Catalog.isDenied(it) }
        // 워치가 이틀 안에 짚었는데 지금은 안 보이는 것(이 보고서가 잰 구간 안) — 할 일 맨 앞에 다시 재서 견주기를
        val earlierSeen = findings.filter { it.id.endsWith("-earlier") && it.severity == Severity.CAUTION }
        if (swAbnormal.isNotEmpty()) {
            return DoctorVerdict(Cause.SOFTWARE, confidence(swAbnormal.size, hasWatch),
                "소프트웨어(서비스) 과소모 — " + (culprits.takeIf { it.isNotEmpty() }?.let { "범인: ${it.take(3).joinToString(", ")}" } ?: swAbnormal.first().title),
                swAbnormal.map { it.title } + swCaution.map { it.title } + rates, softwareTodo(culprits, homeFlagged, homeStage, gmsStage(f, findings), earlierSeen), culprits)
        }

        val idleAbnormal = f.watch?.let { Thresholds.idle(it.idle24.percentPerHour) == Grade.ABNORMAL || Thresholds.overnight(it.overnight?.percentPerHour) == Grade.ABNORMAL } ?: false
        val awakeAbnormal = findings.any { it.id == "awake-ratio" && it.severity == Severity.ABNORMAL }
        val usage = ofCat("usage", min = Severity.CAUTION)
        if (usage.isNotEmpty() && !idleAbnormal && !awakeAbnormal) {
            return DoctorVerdict(Cause.USAGE, confidence(usage.size, hasWatch), "사용 패턴(화면·GPS·무선)이 소모의 대부분 — 대기 소모는 정상 범위",
                usage.map { it.title } + rates,
                listOf("화면 켜짐·GPS·무선이 소모를 지배합니다 — 설정 > 디스플레이에서 AOD·'손목 올려 켜기'·화면 자동 꺼짐 시간을 줄여 보세요 (AOD 끄면 공식 30→40시간)",
                    "운동 중 GPS·LTE 는 정상적으로 큰 소모입니다. 비교하려면 운동 없는 하루를 따로 재세요(메뉴 3 → 평소대로 → 메뉴 2)",
                    "메뉴 4의 '안전' 등급 설정 조치(Wi-Fi/BLE 스캔 끄기 등)를 적용하고 24시간 뒤 다시 수집해 비교하세요"),
                emptyList())
        }

        if (drainAbnormal.isNotEmpty()) {
            if (swCaution.isNotEmpty()) {
                return DoctorVerdict(Cause.SOFTWARE, "낮음", "소프트웨어 과소모 가능성 — 뚜렷한 범인은 없지만 주의 소견이 있고 소모율이 비정상",
                    swCaution.map { it.title } + rates, softwareTodo(culprits, homeFlagged, homeStage, gmsStage(f, findings), earlierSeen), culprits)
            }
            return DoctorVerdict(Cause.HARDWARE, confidence(drainAbnormal.size, hasWatch),
                "하드웨어(배터리) 의심 — 앱·알람·센서가 조용한데도 ${drainAbnormal.first().title}",
                drainAbnormal.map { it.title } + findings.filter { it.category == "capacity" }.map { it.title } + listOf("웨이크락·알람·센서·설정 소견에 비정상 없음"),
                listOf("워치 배터리 화면의 '실험 시작'으로 비행기 모드 또는 시계만 사용 모드를 6시간 이상 재서, 무선·앱을 꺼도 소모율이 그대로인지 확인하세요 (경험칙: 2%/h 초과면 이상)",
                    "소모가 이어지는 동안 $REPORT",
                    "서비스센터(1588-3366)에 '배터리 과소모'로 접수하고 수리내역서·배터리 효율 측정값을 받으세요. 같은 하자로 2회 수리 뒤 재발하면 교환·환급 요구 근거가 됩니다(03_소비자분쟁해결기준_요구서.txt)"),
                emptyList())
        }

        val base = if (hasWatch && watchHours >= 24) "높음" else "중간"
        val caution = findings.filter { it.severity == Severity.CAUTION }
        return DoctorVerdict(Cause.NORMAL, if (hasWatch) base else stepDown(base),
            "정상 범위 — 이 모델의 한계(44mm 공식 최대 30~40시간 ≈ 시간당 2.5~3.3%) 안입니다" + (if (caution.isNotEmpty()) " (주의 소견 ${caution.size}개는 아래 참고)" else ""),
            rates + caution.map { it.title },
            listOf("비정상 소견이 없습니다. 지금 소모율은 이 모델의 정상 범위이며, 40mm 도 같은 퍼센트 기준입니다",
                "더 늘리려면 가장 큰 항목부터: AOD 끄기(공식 +33%), 수면 중 혈중 산소·피부 온도 끄기, 심박 측정 '휴식 중 10분마다', 손목 올려 말하기·제스처 끄기",
                "며칠 더 기록을 쌓은 뒤(메뉴 2) 밤사이 소모와 실험 구간을 비교하세요. 새 기기·업데이트 직후 1주일은 학습 때문에 소모가 큽니다"),
            emptyList())
    }

    /**
     * 워치가 적은 Play 서비스 과소모 단계 (0 처음 · 1 재시작 뒤에도 또 · 2 다음 단계 뒤에도 계속) — 방금 쓴 요약으로 워치가 지금 짚었을 때만
     * ('watch-gms' 소견이 있을 때 — 묵은 요약의 단계로 dumpsys 만 짚은 할 일을 바꾸지 않게 · 리뷰)
     */
    private fun gmsStage(f: Facts, findings: List<Finding>): Int = if (findings.none { it.id == "watch-gms" }) 0
        else (f.watchMetrics["back_gms"] ?: 0.0).let { if (it >= 2.0) 2 else if (it >= 1.0) 1 else 0 }

    private fun softwareTodo(culprits: List<String>, home: Boolean = false, homeStage: Int = 0, gmsStage: Int = 0, earlier: List<Finding> = emptyList()): List<String> {
        val actionable = culprits.filterNot { Catalog.isDenied(it) }
        // 워치가 전에 짚었는데 지금은 안 보이는 것만 있으면(재시작·치료 직후 수집 등) 먼저 다시 재서 견주기 (리뷰)
        val earlierStep = earlier.takeIf { it.isNotEmpty() }?.let { e ->
            "워치가 ${e.joinToString(", ") { it.title.removePrefix("워치 앱: ").substringBefore(" — ") }}를 봤고 지금은 안 보입니다 — 메뉴 3(측정 시작)으로 통계를 비우고 " +
                "하루 뒤 메뉴 2로 다시 수집해 견주세요(그 전 소모가 이 통계에 섞여 있어서)"
        }
        // 워치가 One UI Watch 홈 과소모를 짚었으면 그 해결 순서를 맨 앞에 (홈은 시스템 앱이라 차단 조치 대상이 아니다) —
        // 재시작 뒤에도 또 나타났으면 캐시 정리부터, 그 뒤에도 계속이면 오류 보고·업데이트 (소견·워치와 같은 순서)
        val homeStep = when (homeStage) {
            2 -> "One UI Watch 홈 과소모(다음 단계 뒤에도 계속): 홈 캐시 삭제·강제 재시작을 아직 안 했으면 그것부터 → 폰의 Galaxy Wearable 앱 › " +
                "워치 설정 › 워치 소프트웨어 업데이트 확인 → 고쳐질 때까지 밤엔 워치 앱 지킴이 밤 모드 → 과소모가 이어지는 동안 $REPORT"
            1 -> "One UI Watch 홈 과소모(재시작 뒤에도 또 나타남): 메뉴 8(자동 치료)의 '모든 앱 캐시 정리'(홈 캐시 포함) → " +
                "한 번 더 강제 재시작(홈 + 뒤로 7초 이상) → 계속되면 과소모가 이어지는 동안 $REPORT"
            else -> "One UI Watch 홈 과소모: 홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작 → 그래도 또 나타나면 메뉴 8(자동 치료)의 " +
                "'모든 앱 캐시 정리'(홈 캐시 포함) 뒤 한 번 더 강제 재시작 → 계속되면 과소모가 이어지는 동안 $REPORT"
        }
        val steps = listOfNotNull(homeStep.takeIf { home },
        "메뉴 4(차단 조치)에서 추천 표시된 항목을 적용하세요" + (if (actionable.isNotEmpty()) " — ${actionable.take(2).joinToString(", ")} 의 백그라운드 제한·대기 버킷 restricted·Doze 예외 해제(안 쓰는 앱이면 비활성화)" else "") +
            // 워치 앱과 같은 순서(캐시 삭제 → 강제 재시작) — 워치가 다음 단계 뒤에도 계속이라 적었으면 업데이트·오류 보내기로. 어시스턴트 업데이트
            // 제거는 2023년 워치6 사용자 사례(Play 서비스 과소모로 보였는데 이것으로 풀렸다는 글 — Samsung Community). 원인은 단정하지 않는다
            (if (!culprits.contains(GMS)) "" else if (gmsStage >= 2) ". Play 서비스는 재시작·치료 뒤에도 계속 — 충전 중 워치 Play 스토어 › 내 앱 › 모두 업데이트 → " +
                "고쳐질 때까지 밤엔 워치 앱 지킴이 밤 모드 → 과소모가 이어지는 동안 " + (if (home) "오류 보내기(위 홈 줄과 같은 길)" else REPORT)
            else ". Play 서비스는 캐시 삭제 → 강제 재시작(워치 앱과 같은 순서), 안 되면 Play 스토어 › 앱 관리에서 Google 어시스턴트 " +
                "업데이트 제거 뒤 다시 업데이트(2023년 워치6 사용자 사례 — Play 서비스 과소모로 보였는데 이것으로 풀렸다는 글)"),
        "조치 뒤 메뉴 3(측정 시작, dumpsys batterystats --reset)으로 통계를 비우고 깨끗하게 다시 재세요. ${Rules.FORCED_RESTART}",
        "24시간 뒤 메뉴 2(수집·분석)로 다시 수집해 웨이크락·소모율이 줄었는지 비교하세요. 그대로면 메뉴 5로 되돌리고 다음 후보를 시도",
        )
        // 지금 짚은 범인·홈이 없으면 다시 재서 견주기가 맨 앞, 있으면 그 할 일 뒤에
        return when {
            earlierStep == null -> steps
            culprits.isEmpty() && !home -> listOf(earlierStep) + steps
            else -> steps + earlierStep
        }
    }

    private fun confidence(supporting: Int, hasWatch: Boolean): String {
        val base = when { supporting >= 2 -> "높음"; supporting == 1 -> "중간"; else -> "낮음" }
        return if (hasWatch) base else stepDown(base)
    }

    private fun stepDown(c: String) = when (c) { "높음" -> "중간"; else -> "낮음" }

    fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)
    /**
     * 요약이 적은 처음 짚은 때(epoch ms) — 모르면 null: 이 버전 전 워치 앱에서 시작된 일(값 없음), 수집 시각과 요약을 쓴 때 가운데 늦은
     * 것보다도 늦은 때(시계가 뒤로 감). 수집하며 워치가 요약을 새로 쓸 때 처음 짚었으면 수집 시각(워치 date — 요약을 쓰라고 부르기 전에
     * 읽음)보다 조금 늦을 수 있다 (리뷰)
     */
    fun firstSeenAt(f: Facts, key: String): Long? {
        val t = f.watchMetrics[key]?.toLong()?.takeIf { it > 0 } ?: return null
        val bound = maxOf(f.collectedAt ?: 0L, f.watchMetrics["at"]?.toLong() ?: 0L)
        return t.takeIf { bound > 0 && it <= bound }
    }

    /** 워치 기록의 충전 속도가 공식보다 한참 느리면 그 충전 습관과 속도 (신고문도 같은 값을 쓴다) */
    fun chargeSpeed(f: Facts): Pair<ChargeHabits, ChargeSpeed>? {
        val now = f.collectedAt ?: f.watchSamples.maxOfOrNull { it.t } ?: return null
        val ch = ChargeHabits.analyze(f.watchSamples, now, model = f.model) ?: return null
        val sp = ch.speed?.takeIf { it.slow } ?: return null
        return ch to sp
    }

    /** 지난 시간을 사람 말로 — 1시간 안(또는 [minutes] 면 N분 전) · N시간 전(이틀까지) · N일 전 */
    fun ago(ms: Long, minutes: Boolean = false): String {
        val h = ms / 3_600_000L
        return when {
            h < 1 && minutes -> "${ms / 60_000L}분 전"
            h < 1 -> "1시간 안"
            h < 48 -> "${h}시간 전"
            else -> "${h / 24}일 전"
        }
    }
    fun pct(x: Double): String = String.format(Locale.US, "%.1f", x)
    private val timeFmt = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    fun time(t: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(t).atZone(zone).format(timeFmt)
}
