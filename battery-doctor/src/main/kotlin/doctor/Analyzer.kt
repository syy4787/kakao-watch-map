package doctor

import com.mywatch.battery.core.AnomalyKind
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Gauge
import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.SampleCsv
import com.mywatch.battery.core.Thresholds
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
                out += Finding("sensor:$pkg", Severity.CAUTION, "$pkg 이(가) 화면 꺼진 상태에서 센서를 계속 등록 중 — 주의",
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

        // 6. 추정 전력 순위 + Play 서비스 패턴 + 계산/실측 차이
        if (s != null && s.powerItems.isNotEmpty()) {
            val top = s.powerItems.sortedByDescending { it.mah }.take(10)
            out += Finding("power-top", Severity.INFO, "추정 전력 사용 상위 ${top.size}개",
                top.mapIndexed { i, p -> "${i + 1}. ${p.display}: ${fmt1(p.mah)} mAh${p.percent?.let { " (${pct(it)}%)" } ?: ""}" },
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
            val hard = list.filter { it.kind != AnomalyKind.CLIFF }
            val sev = if (hard.isNotEmpty()) Severity.ABNORMAL else Severity.CAUTION
            val kinds = list.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.first().label} ${it.value.size}회" }
            out += Finding("gauge-watch", sev, "워치 기록의 잔량 표시 이상 ${list.size}회 ($kinds) — ${sev.label}",
                list.takeLast(8).map { "${time(it.t)} ${it.label}: ${it.detail}" } + listOf("기준: 충전 없이 +5포인트, 부팅 뒤 +10포인트, 20분 안에 −15포인트, 계수기와 10포인트 차이"),
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
                listOf("${time(r.t)} 워치 배터리 화면 '강제 재시작 표시 남기기'", "재시작으로 잔량이 뛰면 그만큼은 게이지 드리프트였다는 뜻"), source = Rules.SOURCE_DRIFT, category = "gauge")
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
                    listOf("${time(e.start)} ~ ${e.end?.let { time(it) } ?: "진행 중"}, ${fmt1(e.rate.hours)}시간, ${fmt1(e.rate.dropPercent)}포인트", "기준: ${Thresholds.EXPERIMENT_NORMAL}%/h 이하 정상, ${Thresholds.EXPERIMENT_ABNORMAL}%/h 초과 비정상"),
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
        val softwareCats = arrayOf("awake", "wakelock", "alarm", "sensor", "gms")
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
                    "서비스센터(1588-3366)에 '잔량 표시 오류·과소모'로 접수하고 배터리 효율 측정값을 서면으로 요구하세요 (02_서비스센터_접수메모.txt 참고). 삼성멤버스 '오류 보내기'는 증상 직후 5분 안에"),
                gauge.flatMap { it.packages }.distinct())
        }

        val swAbnormal = ofCat(*softwareCats, min = Severity.ABNORMAL)
        val swCaution = findings.filter { it.category in softwareCats && it.severity == Severity.CAUTION }
        // 핵심 시스템 패키지(거부 목록)는 범인으로 지목하지 않는다 — Play 서비스만 2026년 패턴 안내를 위해 남긴다
        val culprits = (swAbnormal + swCaution).flatMap { it.packages }.distinct().filter { it == GMS || !Catalog.isDenied(it) }
        if (swAbnormal.isNotEmpty()) {
            return DoctorVerdict(Cause.SOFTWARE, confidence(swAbnormal.size, hasWatch),
                "소프트웨어(서비스) 과소모 — " + (culprits.takeIf { it.isNotEmpty() }?.let { "범인: ${it.take(3).joinToString(", ")}" } ?: swAbnormal.first().title),
                swAbnormal.map { it.title } + swCaution.map { it.title } + rates, softwareTodo(culprits), culprits)
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
                    swCaution.map { it.title } + rates, softwareTodo(culprits), culprits)
            }
            return DoctorVerdict(Cause.HARDWARE, confidence(drainAbnormal.size, hasWatch),
                "하드웨어(배터리) 의심 — 앱·알람·센서가 조용한데도 ${drainAbnormal.first().title}",
                drainAbnormal.map { it.title } + findings.filter { it.category == "capacity" }.map { it.title } + listOf("웨이크락·알람·센서·설정 소견에 비정상 없음"),
                listOf("워치 배터리 화면의 '실험 시작'으로 비행기 모드 또는 시계만 사용 모드를 6시간 이상 재서, 무선·앱을 꺼도 소모율이 그대로인지 확인하세요 (경험칙: 2%/h 초과면 이상)",
                    "메뉴 6(증거 패키지)을 만들고 삼성멤버스 '오류 보내기'(Galaxy Wearable > ⋮ > 설정 > 문의하기 > 오류 보내기, 시스템 로그 포함, 증상 직후 5분 안)로 제출하세요",
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

    private fun softwareTodo(culprits: List<String>): List<String> {
        val actionable = culprits.filterNot { Catalog.isDenied(it) }
        return listOf(
        "메뉴 4(차단 조치)에서 추천 표시된 항목을 적용하세요" + (if (actionable.isNotEmpty()) " — ${actionable.take(2).joinToString(", ")} 의 백그라운드 제한·대기 버킷 restricted·Doze 예외 해제(안 쓰는 앱이면 비활성화)" else "") +
            (if (culprits.contains(GMS)) ". Play 서비스는 워치 재시작 → 캐시 삭제, 안 되면 Play 스토어 > 앱 관리에서 Google 앱(어시스턴트) 업데이트 제거 후 재설치(2026년 사례의 해결책)" else ""),
        "조치 뒤 메뉴 3(측정 시작, dumpsys batterystats --reset)으로 통계를 비우고 깨끗하게 다시 재세요. ${Rules.FORCED_RESTART}",
        "24시간 뒤 메뉴 2(수집·분석)로 다시 수집해 웨이크락·소모율이 줄었는지 비교하세요. 그대로면 메뉴 5로 되돌리고 다음 후보를 시도",
        )
    }

    private fun confidence(supporting: Int, hasWatch: Boolean): String {
        val base = when { supporting >= 2 -> "높음"; supporting == 1 -> "중간"; else -> "낮음" }
        return if (hasWatch) base else stepDown(base)
    }

    private fun stepDown(c: String) = when (c) { "높음" -> "중간"; else -> "낮음" }

    fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)
    fun pct(x: Double): String = String.format(Locale.US, "%.1f", x)
    private val timeFmt = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    fun time(t: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(t).atZone(zone).format(timeFmt)
}
