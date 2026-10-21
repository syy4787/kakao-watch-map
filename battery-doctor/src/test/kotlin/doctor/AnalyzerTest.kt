package doctor

import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Event
import doctor.parse.AlarmStat
import doctor.parse.BatteryNow
import doctor.parse.BatteryStats
import doctor.parse.PackageInfo
import doctor.parse.PowerItem
import doctor.parse.SensorUse
import doctor.parse.WakeLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId

class AnalyzerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 19, 22)

    /** 15분 간격, 시간당 pph 로 줄어드는 24시간치 표본(화면 꺼짐) */
    private fun watch(pph: Double, hours: Int = 36, interactive: Boolean = false): List<BatterySample> {
        val start = now - hours * 3_600_000L
        return (0..hours * 4).map { i -> BatterySample(t = start + i * q, level = (100 - pph * i / 4).toInt().coerceIn(0, 100), interactive = interactive) }
    }

    private fun analysis(samples: List<BatterySample>) = DrainAnalyzer(445, zone).analyze(samples, now)

    private val h8 = 8 * 3_600_000L

    private fun quietStats(onBatteryMs: Long = h8, screenOnMs: Long = 20 * 60_000L, awakeMs: Long = 10 * 60_000L) = BatteryStats(
        onBatteryMs = onBatteryMs, screenOnMs = screenOnMs, screenOffMs = onBatteryMs - screenOnMs, screenOffAwakeMs = awakeMs,
        dischargePercent = 24 to 25, capacityMah = 445, computedDrainMah = 100.0, actualDrainMah = 95.0 to 100.0,
        powerItems = listOf(PowerItem("Screen", null, 30.0, 30.0), PowerItem("Uid u0a45", "com.google.android.gms", 8.0, 8.0)),
    )

    private val packages = listOf(
        PackageInfo("com.example.drain", 10123, true, true),
        PackageInfo("com.google.android.gms", 10045, true, false),
        PackageInfo("com.samsung.android.wear.shealth", 10087, true, false),
    )

    private fun facts(
        stats: BatteryStats? = quietStats(), watchSamples: List<BatterySample>? = watch(2.5),
        alarms: List<AlarmStat> = emptyList(), sensors: List<SensorUse> = emptyList(),
        settings: Map<String, Map<String, String>> = emptyMap(), nowBattery: BatteryNow? = null,
    ) = Facts(
        model = "SM-L350", build = "AZH4", nominalMah = 445, now = nowBattery, stats = stats,
        alarms = alarms, alarmElapsedMs = h8, sensors = sensors, packages = packages, settings = settings,
        screenOffAtDump = true, watch = watchSamples?.let { analysis(it) }, watchSamples = watchSamples ?: emptyList(), collectedAt = now,
    )

    private fun verdict(f: Facts) = Analyzer.verdict(f, Analyzer.findings(f))

    /** 10/12~10/18 날마다 0~20시 화면 꺼진 채 [rates]%/h, 20~24시 충전 — [notes] 는 (시각, 바뀐 것) */
    private fun days(rates: List<Double>, vararg notes: Pair<Long, com.mywatch.battery.core.ChangeNote.Item>): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        rates.forEachIndexed { i, r ->
            val start = at(2026, 10, 12 + i, 0)
            var t = start
            while (t <= start + 20 * 3_600_000L) {
                val note = notes.firstOrNull { it.first == t }?.let { com.mywatch.battery.core.ChangeNote.encode(listOf(it.second)) } ?: ""
                out += BatterySample(t = t, level = (100 - r * (t - start) / 3_600_000.0).toInt(), note = note); t += q
            }
            t = start + 20 * 3_600_000L + q
            while (t < start + 24 * 3_600_000L) { out += BatterySample(t = t, level = 100, plugged = true); t += q }
        }
        return out
    }

    /** 워치 기록으로 본 '언제부터': 최근 7일 안에 대기 소모가 늘어 꺾였으면 소견 — 그 무렵 바뀐 것과 할 일 */
    @Test
    fun aRecentRiseInIdleDrainIsAFindingWithWhatChangedThen() {
        val fw = com.mywatch.battery.core.ChangeNote.Item(com.mywatch.battery.core.ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1")
        val samples = days(listOf(1.0, 1.0, 1.1, 1.0, 2.6, 2.5, 2.4), at(2026, 10, 16, 3) to fw)
        val f = facts(watchSamples = samples).copy(watchTrend = com.mywatch.battery.core.TrendReport.of(samples, now, zone))
        val shift = Analyzer.findings(f).single { it.id == "watch-shift" }
        assertEquals(Severity.CAUTION, shift.severity)
        assertTrue(shift.title, shift.title.contains("10/16부터 대기 소모가 시간당 1.0% → 2.5%로 늘었습니다"))
        assertTrue(shift.evidence.joinToString(), shift.evidence.any { it.contains("워치 소프트웨어 업데이트 (L350XXU1AZI1)") })
        assertTrue(shift.evidence.joinToString(), shift.evidence.any { it.contains("1주") })
        // 그 무렵 업데이트된 앱은 글로만 — 범인(자동 치료 대상)으로 삼지 않는다
        assertTrue(shift.packages.isEmpty())
        // 바뀐 것을 적기 전(이 기능 전의 기록)이고 그 무렵 표시도 없으면 '없음'이 아니라 '모름'
        val old = days(listOf(1.0, 1.0, 1.1, 1.0, 2.6, 2.5, 2.4))
        val unknown = Analyzer.findings(facts(watchSamples = old).copy(watchTrend = com.mywatch.battery.core.TrendReport.of(old, now, zone))).single { it.id == "watch-shift" }
        assertTrue(unknown.evidence.joinToString(), unknown.evidence.any { it.contains("적기 전이라") })
        assertTrue(unknown.packages.isEmpty())
        // 적기 시작한 뒤라면 '없음'
        val tracked = days(listOf(1.0, 1.0, 1.1, 1.0, 2.6, 2.5, 2.4), at(2026, 10, 12, 1) to com.mywatch.battery.core.ChangeNote.Item(com.mywatch.battery.core.ChangeKind.TRACKING, "on"))
        val none = Analyzer.findings(facts(watchSamples = tracked).copy(watchTrend = com.mywatch.battery.core.TrendReport.of(tracked, now, zone))).single { it.id == "watch-shift" }
        assertTrue(none.evidence.joinToString(), none.evidence.any { it.contains("변화(소프트웨어·앱·시계 화면·주요 설정)는 없음") })
        // 꺾임이 없으면 소견도 없다
        val flat = days(listOf(1.0, 1.1, 1.0, 1.0, 1.1, 1.0, 1.0))
        val g = facts(watchSamples = flat).copy(watchTrend = com.mywatch.battery.core.TrendReport.of(flat, now, zone))
        assertTrue(Analyzer.findings(g).none { it.id == "watch-shift" })
    }

    /** 재시작한 지 7일이 넘었으면 소견 — 대기 소모가 높으면 주의(재시작부터), 아니면 참고 */
    @Test
    fun aLongUptimeIsAFindingAndACautionWhenIdleDrainIsHigh() {
        val day = 24 * 3_600_000L
        val high = Analyzer.findings(facts(watchSamples = watch(4.0, hours = 24)).copy(alarmElapsedMs = 9 * day + 3_600_000L)).single { it.id == "uptime-long" }
        assertEquals(Severity.CAUTION, high.severity)
        assertTrue(high.title, high.title.contains("9일"))
        assertTrue(high.evidence.joinToString(), high.evidence.any { it.contains("강제 재시작") })
        val calm = Analyzer.findings(facts(watchSamples = watch(1.0)).copy(alarmElapsedMs = 9 * day)).single { it.id == "uptime-long" }
        assertEquals(Severity.INFO, calm.severity)
        assertTrue(Analyzer.findings(facts().copy(alarmElapsedMs = 3 * day)).none { it.id == "uptime-long" })
    }

    // ── 판정 가지 6개 ──

    @Test
    fun insufficientWhenNeitherSourceHasTwoHours() {
        val v = verdict(facts(stats = quietStats(onBatteryMs = 3_600_000L), watchSamples = watch(2.5, hours = 1)))
        assertEquals(Cause.INSUFFICIENT, v.cause)
        assertEquals(3, v.todo.size)
        assertTrue(v.todo.joinToString().contains("측정 시작"))
    }

    @Test
    fun gaugeWinsWhenWatchSeesARebootJump() {
        val s = watch(2.5).toMutableList()
        val i = s.size / 2
        s[i] = s[i].copy(level = s[i - 1].level + 30, event = Event.BOOT)
        val f = facts(watchSamples = s)
        val fs = Analyzer.findings(f)
        assertTrue(fs.any { it.id == "gauge-watch" && it.severity == Severity.ABNORMAL })
        val v = Analyzer.verdict(f, fs)
        assertEquals(Cause.GAUGE, v.cause)
        assertTrue(v.todo[0].contains("7초"))
        assertTrue(v.todo.joinToString().contains("증거 패키지"))
    }

    /** 3주 전 한 번의 부팅 점프는 증거로만 — 지금 판정을 '게이지 고장(서비스센터)'으로 만들지 않는다 (워치 판정과 같은 7일, 리뷰에서 찾음) */
    @Test
    fun anOldRebootJumpIsEvidenceNotAGaugeVerdict() {
        val old = now - 21 * 24 * 3_600_000L
        val s = listOf(BatterySample(t = old, level = 17), BatterySample(t = old + q, level = 47, event = Event.BOOT)) + watch(2.5)
        val f = facts(watchSamples = s)
        val fs = Analyzer.findings(f)
        val g = fs.first { it.id == "gauge-watch" }
        assertEquals(Severity.CAUTION, g.severity)
        assertTrue(Analyzer.verdict(f, fs).cause != Cause.GAUGE)
    }

    @Test
    fun gaugeFromChargeCounterMismatch() {
        // 196800 µAh 가 90% 라면 218 mAh = 공칭의 49% → 잔량 표시 불일치
        val f = facts(nowBattery = BatteryNow(90, 100, 3900, 300, "2", "3", 196800, emptyMap()))
        val fs = Analyzer.findings(f)
        val g = fs.first { it.id == "gauge-counter" }
        assertEquals(Severity.ABNORMAL, g.severity)
        assertEquals(Cause.GAUGE, Analyzer.verdict(f, fs).cause)
        // 정상 범위(92%)면 소견 없음
        assertFalse(Analyzer.findings(facts(nowBattery = BatteryNow(48, 100, 3812, 298, "2", "3", 196800, emptyMap()))).any { it.id == "gauge-counter" })
    }

    /**
     * 15분 표본 사이 8분 충전(샤워하며)은 기록되지 않는다 — 57% → 62% 가 '게이지 고장'이 되어 진짜 범인(웨이크락)을 가리고
     * 서비스센터를 권하던 것 (점검에서 찾음). 충전으로 오를 수 있었던 만큼은 잔량 표시 이상이 아니다
     */
    @Test
    fun anUnloggedTopUpDoesNotHideTheSoftwareCulprit() {
        val stats = quietStats().copy(partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "*job*/x", 70 * 60_000L, 300)))
        val s = watch(5.5, hours = 16).toMutableList()
        val i = s.size / 2
        for (j in i until s.size) s[j] = s[j].copy(level = (s[j].level + 8).coerceAtMost(100))   // 8분 충전 ≈ +8
        val f = facts(stats = stats, watchSamples = s)
        val fs = Analyzer.findings(f)
        assertFalse(fs.joinToString { it.id }, fs.any { it.id == "gauge-watch" })
        assertEquals(Cause.SOFTWARE, Analyzer.verdict(f, fs).cause)
    }

    @Test
    fun softwareWhenAWakeLockIsAbnormal() {
        val stats = quietStats().copy(partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "*job*/x", 70 * 60_000L, 300)))
        val f = facts(stats = stats, watchSamples = watch(5.5, hours = 16))
        val fs = Analyzer.findings(f)
        val w = fs.first { it.id == "wakelock:com.example.drain" }
        assertEquals(Severity.ABNORMAL, w.severity)
        assertTrue(w.evidence.joinToString().contains("초/시간"))
        assertTrue(w.source!!.contains("Android vitals"))
        val v = Analyzer.verdict(f, fs)
        assertEquals(Cause.SOFTWARE, v.cause)
        assertEquals(listOf("com.example.drain"), v.culprits)
        assertTrue(v.todo.joinToString().contains("reset") || v.todo.joinToString().contains("측정 시작"))
    }

    @Test
    fun usageWhenScreenDominatesAndIdleIsNormal() {
        val stats = quietStats(screenOnMs = (2.5 * 3_600_000L).toLong())   // 8시간 중 2.5시간 = 31%
        val f = facts(stats = stats, watchSamples = watch(2.5))
        val fs = Analyzer.findings(f)
        assertTrue(fs.any { it.id == "usage-screen" })
        assertEquals(Cause.USAGE, Analyzer.verdict(f, fs).cause)
    }

    @Test
    fun hardwareWhenEverythingIsQuietButDrainIsHigh() {
        val s = watch(6.0, hours = 14).toMutableList()   // 14시간 × 6% = 84포인트(0% 에 닿지 않게)
        // 비행기 모드 실험 구간(6시간)도 같은 소모율
        val startIdx = s.size - 6 * 4 - 1
        s[startIdx] = s[startIdx].copy(event = Event.EXPERIMENT_START, note = "비행기 모드")
        s[s.size - 1] = s[s.size - 1].copy(event = Event.EXPERIMENT_END, note = "비행기 모드")
        val f = facts(stats = quietStats().copy(dischargePercent = 48 to 49), watchSamples = s)
        val fs = Analyzer.findings(f)
        val v = Analyzer.verdict(f, fs)
        assertEquals(Cause.HARDWARE, v.cause)
        assertTrue(v.todo.joinToString().contains("서비스센터"))
        assertTrue(v.why.joinToString().contains("비행기 모드"))
    }

    /** 쉬는 전압이 3.95V(고전압 셀 약 44%)인데 표시 잔량이 20% 안팎이면 게이지 드리프트 — 잔량 표시 문제로 판정하고 강제 재시작·보정 루틴을 권한다 */
    @Test
    fun gaugeDriftFromRestingVoltageIsAGaugeVerdict() {
        val s = (0..24).map { i -> BatterySample(t = now - (24 - i) * q, level = 25 - i / 4, voltageMv = 3950, currentUa = -15_000, interactive = false) }
        val f = facts(watchSamples = s)
        val fs = Analyzer.findings(f)
        val d = fs.first { it.id == "gauge-drift" }
        assertEquals(Severity.ABNORMAL, d.severity)
        assertTrue(d.title, d.title.contains("낮게 세는 중"))
        assertTrue(d.evidence.joinToString(), d.evidence.joinToString().contains("3.95V"))
        val v = Analyzer.verdict(f, fs)
        assertEquals(Cause.GAUGE, v.cause)
        assertTrue(v.todo.joinToString(), v.todo.joinToString().contains("게이지 보정"))
        // 표시와 전압이 맞으면 소견 없음
        val ok = s.map { it.copy(level = it.level + 22) }
        assertFalse(Analyzer.findings(facts(watchSamples = ok)).any { it.id == "gauge-drift" })
    }

    @Test
    fun aHotIdleWatchIsASoftwareCaution() {
        val s = watch(2.5).mapIndexed { i, x -> if (i >= 130) x.copy(tempDeciC = 430) else x.copy(tempDeciC = 290) }
        val fs = Analyzer.findings(facts(watchSamples = s))
        val h = fs.first { it.id == "watch-hot" }
        assertEquals(Severity.CAUTION, h.severity)
        assertEquals("awake", h.category)
        assertFalse(Analyzer.findings(facts(watchSamples = watch(2.5))).any { it.id == "watch-hot" })
    }

    @Test
    fun phantomDrainFromTheChargeCounterIsAGaugeFinding() {
        // 표시는 시간당 6% 씩 떨어지는데 전하량은 시간당 13 mAh(≈2.9%) 만 줄어든다 → 가짜 소모
        val s = (0..16).map { i -> BatterySample(t = now - (16 - i) * q, level = (90 - 6.0 * i / 4).toInt(), chargeUah = 400_000 - i * 3_250, voltageMv = 4000, currentUa = -15_000) }
        val fs = Analyzer.findings(facts(watchSamples = s))
        val p = fs.first { it.id == "gauge-phantom" }
        assertEquals(Severity.CAUTION, p.severity)
        assertTrue(p.title, p.title.contains("가짜 소모"))
    }

    @Test
    fun normalWhenNothingStandsOut() {
        val v = verdict(facts())
        assertEquals(Cause.NORMAL, v.cause)
        assertEquals("높음", v.confidence)
        assertTrue(v.headline.contains("정상"))
    }

    // ── 개별 규칙 ──

    @Test
    fun awakeRatioThresholds() {
        val caution = Analyzer.findings(facts(stats = quietStats(awakeMs = (0.15 * (h8 - 20 * 60_000L)).toLong()))).first { it.id == "awake-ratio" }
        assertEquals(Severity.CAUTION, caution.severity)
        assertTrue(caution.evidence.joinToString().contains("15"))
        assertTrue(caution.source!!.contains("경험칙"))
        val abnormal = Analyzer.findings(facts(stats = quietStats(awakeMs = (0.25 * (h8 - 20 * 60_000L)).toLong()))).first { it.id == "awake-ratio" }
        assertEquals(Severity.ABNORMAL, abnormal.severity)
        val ok = Analyzer.findings(facts(stats = quietStats(awakeMs = (0.05 * (h8 - 20 * 60_000L)).toLong()))).first { it.id == "awake-ratio" }
        assertEquals(Severity.INFO, ok.severity)
    }

    @Test
    fun wakeLockPerHourConversion() {
        // 8시간에 4분 = 30초/시간 → 주의(18.5초/시간 초과), 비정상 아님
        val stats = quietStats().copy(partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "tag", 4 * 60_000L, 20)))
        val w = Analyzer.findings(facts(stats = stats)).first { it.id == "wakelock:com.example.drain" }
        assertEquals(Severity.CAUTION, w.severity)
        assertTrue(w.evidence.joinToString().contains("30.0초/시간"))
        // 합계 1시간 초과 → 비정상
        val stuck = quietStats().copy(partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "tag", 61 * 60_000L, 1)))
        assertEquals(Severity.ABNORMAL, Analyzer.findings(facts(stats = stuck)).first { it.id == "wakelock:com.example.drain" }.severity)
        // 작은 것은 소견 없음
        val tiny = quietStats().copy(partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "tag", 60_000L, 1)))
        assertFalse(Analyzer.findings(facts(stats = tiny)).any { it.id.startsWith("wakelock:") })
    }

    @Test
    fun alarmThresholdsPerHour() {
        val many = listOf(AlarmStat("com.example.drain", 500, 1000, 600))   // 8시간에 500회 = 62.5/h
        val fs = Analyzer.findings(facts(alarms = many))
        assertEquals(Severity.ABNORMAL, fs.first { it.id == "alarm:com.example.drain" }.severity)
        val some = listOf(AlarmStat("com.example.drain", 200, 1000, 300))   // 25/h
        assertEquals(Severity.CAUTION, Analyzer.findings(facts(alarms = some)).first { it.id == "alarm:com.example.drain" }.severity)
        val few = listOf(AlarmStat("com.example.drain", 20, 1000, 30))
        assertFalse(Analyzer.findings(facts(alarms = few)).any { it.id.startsWith("alarm:") })
    }

    @Test
    fun sensorsHeldByThirdPartyAppsWhileScreenOff() {
        val sensors = listOf(
            SensorUse("LSM6DSO Accelerometer", "com.example.drain", 50.0, true, "android.sensor.accelerometer", 10123),
            SensorUse("Samsung HR Sensor", "com.samsung.android.wear.shealth", 1.0, true, "android.sensor.heart_rate", 10087),
        )
        val fs = Analyzer.findings(facts(sensors = sensors))
        val s = fs.first { it.id == "sensor:com.example.drain" }
        assertEquals(Severity.CAUTION, s.severity)
        assertTrue(s.evidence.joinToString().contains("50"))
        assertFalse(fs.any { it.id == "sensor:com.samsung.android.wear.shealth" })   // 시스템(헬스)은 정상
    }

    @Test
    fun gmsShareOverFifteenPercentIsThe2026Pattern() {
        val stats = quietStats().copy(powerItems = listOf(PowerItem("Uid u0a45", "com.google.android.gms", 20.0, 20.0), PowerItem("Screen", null, 30.0, 30.0)))
        val g = Analyzer.findings(facts(stats = stats)).first { it.id == "gms-share" }
        assertEquals(Severity.ABNORMAL, g.severity)
        assertTrue(g.title.contains("2026"))
        assertTrue(g.packages.contains("com.google.android.gms"))
        assertFalse(Analyzer.findings(facts()).any { it.id == "gms-share" })   // 8% 는 소견 없음
    }

    /**
     * 추정 전력 순위에 One UI Watch 홈이 위에 있으면 그게 흔한 일이라는 설명을 붙인다 — 화면 전력은 앞에 떠 있던 앱의 시간 비율로
     * 나뉘어 시계 화면을 띄운 홈에 잡힌다 (2026년 9~10월 워치9 사용자들이 '홈이 1위'를 원인으로 오해). 범인으로 짚지는 않는다
     */
    @Test
    fun theWatchHomeNearTheTopOfPowerUseIsExplainedNotBlamed() {
        val home = "com.samsung.android.wearable.sysui"
        val stats = quietStats().copy(powerItems = listOf(PowerItem("Uid 1000", home, 40.0, 40.0), PowerItem("Screen", null, 30.0, 30.0), PowerItem("Uid u0a45", "com.google.android.gms", 8.0, 8.0)))
        val fs = Analyzer.findings(facts(stats = stats))
        val top = fs.first { it.id == "power-top" }
        // 1위라는 것만으로 고장이라 단정하지 않는다 — '흔하다'고 단정하지도 않는다 (리뷰에서 찾음: 근거 없는 안심)
        assertTrue(top.evidence.toString(), top.evidence.any { it.contains("One UI Watch 홈") && it.contains("단정할 수 없") && !it.contains("흔") })
        // 할 일은 단계마다 다르니(강제 재시작 → 홈 캐시 → 오류 보고) 한 단계를 박아 두지 않고 워치의 '지금 할 일'을 가리킨다 (시나리오 점검)
        assertTrue(top.evidence.toString(), top.evidence.any { it.contains("'지금 할 일'") && !it.contains("→ 강제 재시작") })
        assertFalse(fs.filter { it.blames }.flatMap { it.packages }.contains(home))
        // 홈이 위쪽이 아니면 설명도 없다
        assertFalse(Analyzer.findings(facts()).first { it.id == "power-top" }.evidence.any { it.contains("One UI Watch 홈") })
    }

    @Test
    fun settingsFindingsOnlyWhenKeyExists() {
        val risky = mapOf("global" to mapOf("wifi_scan_always_enabled" to "1", "adaptive_battery_management_enabled" to "0", "ble_scan_always_enabled" to "0"))
        val fs = Analyzer.findings(facts(settings = risky))
        assertTrue(fs.any { it.id == "setting:wifi_scan_always_enabled" })
        assertTrue(fs.any { it.id == "setting:adaptive_battery_management_enabled" })
        assertFalse(fs.any { it.id == "setting:ble_scan_always_enabled" })   // 값이 안전
        assertFalse(fs.any { it.id == "setting:app_standby_enabled" })       // 키 없음
        assertFalse(Analyzer.findings(facts()).any { it.id.startsWith("setting:") })
    }

    @Test
    fun capacityFindingsFromWatchAndSamsungAsoc() {
        val f = facts(nowBattery = BatteryNow(48, 100, 3812, 298, "2", "3", null, mapOf("mSavedBatteryAsoc" to "72")))
        val a = Analyzer.findings(f).first { it.id == "capacity-asoc" }
        assertEquals(Severity.CAUTION, a.severity)
        assertTrue(a.evidence.joinToString().contains("72"))
        assertFalse(Analyzer.findings(facts(nowBattery = BatteryNow(48, 100, 3812, 298, "2", "3", null, mapOf("mSavedBatteryAsoc" to "96")))).any { it.id == "capacity-asoc" })
    }

    @Test
    fun confidenceDropsWithoutWatchLog() {
        val two = quietStats().copy(
            partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "a", 70 * 60_000L, 300), WakeLock(10087, "com.samsung.android.wear.shealth", "b", 65 * 60_000L, 10)),
        )
        assertEquals("높음", verdict(facts(stats = two, watchSamples = watch(5.5, hours = 16))).confidence)
        assertEquals("중간", verdict(facts(stats = two, watchSamples = null)).confidence)
        val one = quietStats().copy(partialWakeLocks = listOf(WakeLock(10123, "com.example.drain", "a", 70 * 60_000L, 300)))
        assertEquals("중간", verdict(facts(stats = one, watchSamples = watch(5.5, hours = 16))).confidence)
        assertEquals("낮음", verdict(facts(stats = one, watchSamples = null)).confidence)
    }

    @Test
    fun culpritsSkipCoreSystemPackagesButKeepPlayServices() {
        val stats = quietStats().copy(partialWakeLocks = listOf(
            WakeLock(10087, "com.samsung.android.wear.shealth", "a", 70 * 60_000L, 300),
            WakeLock(10045, "com.google.android.gms", "b", 65 * 60_000L, 10),
            WakeLock(10123, "com.example.drain", "c", 62 * 60_000L, 10),
        ))
        val v = verdict(facts(stats = stats))
        assertEquals(Cause.SOFTWARE, v.cause)
        assertEquals(listOf("com.google.android.gms", "com.example.drain"), v.culprits)
        assertFalse(v.todo[0].contains("shealth"))
        assertTrue(v.todo[0].contains("Play 서비스"))
        assertTrue(v.todo[0].contains("com.example.drain"))
    }

    @Test
    fun factsFromTheSampleSnapshot() {
        val snap = sampleSnapshot(tmp.root)
        val f = Analyzer.facts(snap, zone)
        assertEquals("SM-L350", f.model)
        assertEquals("AZH4.260818.001", f.build)
        assertEquals("L350XXU1AZH4", f.incremental)
        assertEquals(445, f.nominalMah)
        assertEquals(48, f.now!!.level)
        assertEquals(29523123L, f.stats!!.onBatteryMs)
        assertEquals(5, f.alarms.size)
        assertEquals(30612000L, f.alarmElapsedMs)
        assertEquals(4, f.sensors.size)
        assertEquals("IDLE", f.idle!!.state)
        assertEquals(21, f.packages.size)
        assertEquals("1", f.settings["global"]!!["wifi_scan_always_enabled"])
        assertEquals("1", f.settings["secure"]!!["ambient_enabled"])
        assertEquals(2, f.power!!.heldWakeLocks.size)
        assertTrue(f.screenOffAtDump)
        assertEquals(3, f.locations.size)
        assertEquals("26.39.35 (240400-123456789)", f.gmsVersion)
        assertNotNull(f.watch)
        assertTrue(f.watchSamples.size > 100)
        assertEquals(1792414865000L, f.collectedAt)
        assertTrue(f.missing.isEmpty())

        val fs = Analyzer.findings(f)
        val v = Analyzer.verdict(f, fs)
        assertEquals(Cause.SOFTWARE, v.cause)
        assertTrue(v.culprits.contains("com.example.drain"))
        assertTrue(fs.any { it.id == "awake-ratio" && it.severity == Severity.CAUTION })
        assertTrue(fs.any { it.id == "wakelock:com.example.drain" && it.severity == Severity.ABNORMAL })
        assertTrue(fs.any { it.id == "alarm:com.example.drain" })
        assertTrue(fs.any { it.id == "sensor:com.example.drain" })
        assertTrue(fs.any { it.id == "setting:wifi_scan_always_enabled" })
        assertTrue(fs.any { it.id == "watch-night" })
        assertTrue(fs.any { it.id == "power-top" })
        assertTrue(fs.any { it.id == "kernel-top" })
        // 심각도 순 정렬
        val sev = fs.map { it.severity.ordinal }
        assertEquals(sev.sortedDescending(), sev)
    }

    /** 워치 앱 요약의 '[지표]' 줄을 숫자로 읽는다 (없으면 빈 지도) */
    @Test
    fun watchMetricsComeFromTheWatchSummary() {
        val snap = sampleSnapshot(tmp.root)
        assertEquals(emptyMap<String, Double>(), Analyzer.facts(snap, zone).watchMetrics)
        java.io.File(snap.dir, "watch_summary.txt").writeText("갤럭시워치 배터리 기록 요약\n[지표] idle_pph=1.8 gesture_wakes=120\n", Charsets.UTF_8)
        val f = Analyzer.facts(snap, zone)
        assertEquals(1.8, f.watchMetrics["idle_pph"]!!, 1e-9)
        assertEquals(120.0, f.watchMetrics["gesture_wakes"]!!, 1e-9)
    }

    @Test
    fun missingFilesAreReportedNotFatal() {
        val snap = sampleSnapshot(tmp.root, except = setOf("batterystats", "batterystats_checkin", "watch_log.csv", "battery"))
        val f = Analyzer.facts(snap, zone)
        assertNull(f.stats)
        assertNull(f.now)
        assertNull(f.watch)
        assertTrue(f.missing.containsAll(listOf("batterystats", "batterystats_checkin", "battery")))
        val fs = Analyzer.findings(f)
        assertTrue(fs.any { it.id == "collect-failed" })
        assertEquals(Cause.INSUFFICIENT, Analyzer.verdict(f, fs).cause)
    }

    /**
     * 워치 앱이 방금 쓴 요약의 알려진 문제(최근 2시간 앱별 통계로 짚음)를 PC 도 쓴다 — 충전 뒤 통계(이 보고서)에선 Play 서비스 몫이 15%
     * 아래여도 자동 치료가 캐시 정리·Play 서비스 다시 시작을 고르게. 3시간 넘게 묵은 요약은 쓰지 않는다
     */
    @Test
    fun aFreshWatchFindingFlagsPlayServicesForTheTreatment() {
        val now = 1792414865000L
        val gms = PackageInfo(Analyzer.GMS, 10045, true, false)
        val f = Facts(collectedAt = now, packages = listOf(gms),
            watchMetrics = mapOf("at" to (now - 10 * 60_000L).toDouble(), "issue_gms" to 2.0, "app_window_min" to 120.0))
        val fs = Analyzer.findings(f)
        val w = fs.single { it.id == "watch-gms" }
        assertEquals(Severity.ABNORMAL, w.severity)
        assertTrue(w.blames && Analyzer.GMS in w.packages)
        val levers = doctor.fix.Catalog.levers(f, fs)
        assertTrue(levers.single { it.id == "trim-caches" }.recommended)
        assertTrue(levers.any { it.id == "gms-restart" })
        val plan = Treat.decide(levers, strong = false)
        assertTrue(plan.apply.map { it.id }.toString(), plan.apply.any { it.id == "trim-caches" })
        // 묵은 요약(3시간 넘음)·창이 짧은 요약은 쓰지 않는다
        val stale = f.copy(watchMetrics = f.watchMetrics + ("at" to (now - 4 * 3_600_000L).toDouble()))
        assertTrue(Analyzer.findings(stale).none { it.id == "watch-gms" })
        // 수집 중에 새로 쓴 요약은 수집 시각(먼저 읽은 워치 시계)보다 조금 늦다 — 30분까지만 '방금 쓴 것'
        val justWritten = f.copy(watchMetrics = f.watchMetrics + ("at" to (now + 20 * 60_000L).toDouble()))
        assertTrue(Analyzer.findings(justWritten).any { it.id == "watch-gms" })
        val future = f.copy(watchMetrics = f.watchMetrics + ("at" to (now + 31 * 60_000L).toDouble()))
        assertTrue(Analyzer.findings(future).none { it.id == "watch-gms" })
        val short = f.copy(watchMetrics = f.watchMetrics + ("app_window_min" to 30.0))
        assertTrue(Analyzer.findings(short).none { it.id == "watch-gms" })
    }

    /** 워치가 One UI Watch 홈 과소모를 짚었으면: 홈을 범인으로 제한하지는 않고(시스템 앱) 모든 앱 캐시 정리(홈 캐시 포함)를 추천 */
    @Test
    fun aFreshWatchHomeFindingRecommendsTheCacheTrimWithoutBlamingTheHome() {
        val now = 1792414865000L
        val f = Facts(collectedAt = now, packages = listOf(PackageInfo(Analyzer.HOME, 10123, true, false)),
            watchMetrics = mapOf("at" to (now - 5 * 60_000L).toDouble(), "issue_home" to 2.0, "back_home" to 1.0, "app_window_min" to 60.0))
        val fs = Analyzer.findings(f)
        val w = fs.single { it.id == "watch-home" }
        assertEquals(Severity.ABNORMAL, w.severity)
        assertFalse(w.blames && w.packages.isNotEmpty())
        assertTrue(w.title, w.title.contains("재시작 뒤에도"))
        assertTrue(w.evidence.toString(), w.evidence.any { it.contains("강제 재시작") })
        val levers = doctor.fix.Catalog.levers(f, fs)
        assertTrue(levers.single { it.id == "trim-caches" }.recommended)
        assertTrue(levers.none { Analyzer.HOME in it.packages && it.id != "trim-caches" })
        // 처음 짚었을 땐 강제 재시작이 먼저 — 캐시 정리는 재시작 뒤에도 또 나타났을 때만 추천 (워치의 순서와 같게)
        val firstTime = f.copy(watchMetrics = f.watchMetrics + ("back_home" to 0.0))
        val firstFs = Analyzer.findings(firstTime)
        assertFalse(firstFs.single { it.id == "watch-home" }.title.contains("재시작 뒤에도"))
        assertFalse(doctor.fix.Catalog.levers(firstTime, firstFs).single { it.id == "trim-caches" }.recommended)
        // Play 서비스만 다시 나타났으면 홈 제목엔 붙이지 않는다 (문제마다)
        val gmsBack = f.copy(watchMetrics = f.watchMetrics + mapOf("back_home" to 0.0, "back_gms" to 1.0, "issue_gms" to 1.0))
        assertFalse(Analyzer.findings(gmsBack).single { it.id == "watch-home" }.title.contains("재시작 뒤에도"))
        assertTrue(Analyzer.findings(gmsBack).single { it.id == "watch-gms" }.title.contains("재시작 뒤에도"))
        // 판정은 '하드웨어 의심'이 아니라 소프트웨어 — 앱·알람·센서가 조용해 보여도 워치가 홈을 짚었다
        val vf = facts().copy(watchMetrics = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "issue_home" to 2.0, "app_window_min" to 60.0))
        val v = Analyzer.verdict(vf, Analyzer.findings(vf) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        assertEquals(Cause.SOFTWARE, v.cause)
        assertTrue(v.todo.toString(), v.todo.first().contains("One UI Watch 홈"))
        // 처음 짚었으면 강제 재시작부터, 재시작 뒤에도 또 나타났으면 캐시 정리부터 (소견·워치와 같은 순서)
        assertTrue(v.todo.first(), v.todo.first().contains("강제 재시작 →"))
        val vb = facts().copy(watchMetrics = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "issue_home" to 2.0, "back_home" to 1.0, "app_window_min" to 60.0))
        val vBack = Analyzer.verdict(vb, Analyzer.findings(vb) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        assertTrue(vBack.todo.first(), vBack.todo.first().contains("재시작 뒤에도 또 나타남") && vBack.todo.first().indexOf("캐시 정리") < vBack.todo.first().indexOf("강제 재시작"))
        // 다른 할 일(다시 재서 견주기)이 밀려나지 않는다
        assertTrue(v.todo.toString(), v.todo.any { it.contains("24시간 뒤 메뉴 2") })
    }

    /**
     * 워치가 '다음 단계 뒤에도 계속'(2단계)이라 적었으면 PC 도 같은 말로 — 같은 '캐시 정리 → 재시작'을 되풀이하지 않고 오류 보고·워치
     * 소프트웨어 업데이트·Play 스토어 업데이트로 (워치 앱 할 일과 같게 · 시나리오 점검). 예전 워치 앱은 1까지만 적는다
     */
    @Test
    fun aWatchIssueThatOutlastsTheNextStepPointsToReportsOnThePcToo() {
        val now = 1792414865000L
        val f = Facts(collectedAt = now, packages = listOf(PackageInfo(Analyzer.HOME, 10123, true, false), PackageInfo(Analyzer.GMS, 10045, true, false)),
            watchMetrics = mapOf("at" to (now - 5 * 60_000L).toDouble(), "issue_home" to 2.0, "back_home" to 2.0, "issue_gms" to 2.0, "back_gms" to 2.0, "app_window_min" to 120.0))
        val fs = Analyzer.findings(f)
        val home = fs.single { it.id == "watch-home" }
        assertTrue(home.title, home.title.endsWith("다음 단계 뒤에도 계속"))
        assertTrue(home.evidence.toString(), home.evidence.any { it.contains("Samsung Members") } && home.evidence.any { it.contains("워치 소프트웨어 업데이트") })
        val gms = fs.single { it.id == "watch-gms" }
        assertTrue(gms.title, gms.title.endsWith("다음 단계 뒤에도 계속"))
        assertTrue(gms.evidence.toString(), gms.evidence.any { it.contains("모두 업데이트") })
        // 워치는 재시작만 안다 — 홈 캐시를 지웠다고 단정하지 않는다 (리뷰)
        assertTrue(home.evidence.toString(), home.evidence.none { it.contains("홈 캐시 삭제 뒤에도 계속됩니다") })
        val vf = facts().copy(watchMetrics = f.watchMetrics)
        val v = Analyzer.verdict(vf, Analyzer.findings(vf) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        assertTrue(v.todo.first(), v.todo.first().contains("다음 단계 뒤에도 계속") && v.todo.first().contains("Samsung Members"))
        // Play 서비스도 처음 순서(캐시 삭제 → 강제 재시작)를 되풀이하지 않고 업데이트·오류 보내기로 (리뷰)
        val gmsStep = v.todo.first { it.contains("Play 서비스는") }
        assertTrue(gmsStep, gmsStep.contains("모두 업데이트") && !gmsStep.contains("캐시 삭제 → 강제 재시작"))
    }

    /**
     * 재시작(·치료) 직후 수집: 워치는 아직 다시 보는 중이라(창 20분) 문제 칸이 없고, 이 보고서의 충전 뒤 통계엔 재시작 전 소모가 남아
     * 소모율만 비정상 — 워치가 2.5시간 전에 홈 과소모를 봤다면 하드웨어로 단정하지 않는다. 다시 재서 견주라고 (시나리오 점검)
     */
    @Test
    fun aCollectionRightAfterARestartIsNotReadAsHardware() {
        val wm = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "app_window_min" to 20.0, "seen_home_h" to 2.5)
        val vf = facts().copy(watchMetrics = wm)
        val fs = Analyzer.findings(vf)
        val e = fs.single { it.id == "watch-home-earlier" }
        assertEquals(Severity.CAUTION, e.severity)
        // 'N시간 전'은 수집 때부터 (요약은 5분 전에 씀 — 리뷰)
        assertTrue(e.title, e.title.contains("2.6시간 전") && e.title.contains("One UI Watch 홈 과소모"))
        // 재시작·치료를 했다고 단정하지 않는다 (워치는 모른다 — 리뷰)
        assertTrue(e.evidence.toString(), e.evidence.none { it.contains("재시작·치료 뒤라") || it.contains("재시작·치료 뒤 1시간") })
        assertTrue(e.evidence.toString(), e.evidence.any { it.contains("다시 보는 중") } && e.evidence.any { it.contains("하드웨어로 단정하기 전에") })
        assertFalse("범인으로 짚지 않는다", e.blames && e.packages.isNotEmpty())
        val v = Analyzer.verdict(vf, fs + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        assertEquals(Cause.SOFTWARE, v.cause)
        // 할 일 맨 앞은 다시 재서 견주기 — 일반적인 '추천 조치 적용'이 아니라 (리뷰)
        assertTrue(v.todo.first(), v.todo.first().contains("워치가") && v.todo.first().contains("다시 수집"))
        // 그 전엔(이 소견 없이) 하드웨어 의심이었다
        val bare = facts().copy(watchMetrics = wm - "seen_home_h")
        assertEquals(Cause.HARDWARE, Analyzer.verdict(bare, Analyzer.findings(bare) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain")).cause)
        // 창이 1시간 넘게 쌓였는데도 안 보이면 '조용하다'고
        val quiet = Analyzer.findings(facts().copy(watchMetrics = wm + ("app_window_min" to 90.0) + ("issue_home" to 0.0))).single { it.id == "watch-home-earlier" }
        assertTrue(quiet.evidence.toString(), quiet.evidence.any { it.contains("지금은 조용") })
        // 묵은 요약(3시간 넘음)은 쓰지 않는다
        assertTrue(Analyzer.findings(facts().copy(watchMetrics = wm + ("at" to (this.now - 4 * 3_600_000L).toDouble()))).none { it.id == "watch-home-earlier" })
    }

    /**
     * 워치가 본 때가 이 보고서의 측정 창보다 앞이면(그 뒤 충전해 dumpsys 가 비워졌고 워치 기록 24시간보다도 앞) 지금 소모율과 상관없다 —
     * 참고로만 두고 판정은 그대로 (리뷰)
     */
    @Test
    fun anEarlierSightingOutsideTheMeasuredWindowIsOnlyANote() {
        val wm = mapOf("at" to (this.now - 60 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 0.0, "seen_home_h" to 25.0)
        val vf = facts().copy(watchMetrics = wm)                       // dumpsys 8시간 · 워치 기록 24시간
        val e = Analyzer.findings(vf).single { it.id == "watch-home-earlier" }
        assertEquals(Severity.INFO, e.severity)
        assertTrue(e.title, e.title.contains("26시간 전"))
        assertTrue(e.evidence.toString(), e.evidence.none { it.contains("하드웨어로 단정하기 전에") })
        val v = Analyzer.verdict(vf, Analyzer.findings(vf) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        assertEquals(Cause.HARDWARE, v.cause)
    }

    /**
     * 워치 기록으로 본 충전이 공식(44mm 90분)보다 한참 느림 — 충전 중 뜨거웠으면 그렇다고 (워치9 사용자 보고와 같은 모습). 범인 앱은
     * 짚지 않는다 (자동 치료가 아무 앱도 제한하지 않게)
     */
    @Test
    fun aSlowChargeOnTheWatchIsAFinding() {
        val q = 15 * 60_000L
        val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
        var t = now - 3 * 24 * 3_600_000L
        repeat(3) {
            val dayStart = t
            while (t < dayStart + 12 * 3_600_000L) { samples += com.mywatch.battery.core.BatterySample(t, 95 - ((t - dayStart) / q).toInt() * 80 / 48); t += q }
            var lv = 15.0
            while (lv < 100.0) { samples += com.mywatch.battery.core.BatterySample(t, lv.toInt(), plugged = true, tempDeciC = 440); t += q; lv += 0.6 * 15 }
            samples += com.mywatch.battery.core.BatterySample(t, 100, plugged = true, tempDeciC = 400)
            t = dayStart + 24 * 3_600_000L
        }
        val fs = Analyzer.findings(facts().copy(model = "SM-L350", watchSamples = samples, zone = zone))
        val f = fs.single { it.id == "watch-charge-slow" }
        assertEquals(Severity.CAUTION, f.severity)
        assertTrue(f.title, f.title.contains("충전이 공식보다 느림 — 완충 어림 2시간 47분 넘게 (삼성 공식 90분)"))
        assertTrue(f.evidence.toString(), f.evidence.any { it.contains("44℃") && it.contains("뜨거워지면 느려진다") })
        assertTrue(f.packages.isEmpty())
        // 모르는 모델이면(공식 시간을 모름) 말하지 않는다
        assertTrue(Analyzer.findings(facts().copy(model = "SM-R960", watchSamples = samples, zone = zone)).none { it.id == "watch-charge-slow" })
    }

    /** 워치가 그 문제를 처음 짚은 때(요약의 first_*_at)를 소견에 — 증상이 얼마나 이어졌는지 (신고문에도) */
    @Test
    fun theFirstSightingTimeIsInTheWatchFinding() {
        val wm = mapOf("at" to (now - 5 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 2.0, "back_home" to 1.0,
            "issue_gms" to 2.0, "first_home_at" to (now - 26 * 3_600_000L).toDouble(), "first_gms_at" to (now + 3_600_000L).toDouble())
        val fs = Analyzer.findings(facts().copy(watchMetrics = wm, zone = zone))
        val home = fs.single { it.id == "watch-home" }
        assertTrue(home.evidence.toString(), home.evidence.any { it.contains("처음 짚은 때: 10월 18일 20:00 (26시간 전)") })
        // 수집보다 늦으면(시계가 뒤로 감) 모르는 것으로 — 적지 않는다
        assertTrue(fs.single { it.id == "watch-gms" }.evidence.none { it.contains("처음 짚은 때") })
        assertEquals("1시간 안", Analyzer.ago(59 * 60_000L))
        assertEquals("47시간 전", Analyzer.ago(47 * 3_600_000L))
        assertEquals("2일 전", Analyzer.ago(48 * 3_600_000L))
        assertEquals("3일 전", Analyzer.ago(72 * 3_600_000L + 1))
        // 수집하며 워치가 요약을 새로 쓸 때 처음 짚었으면 처음 짚은 때가 수집 시각(워치 date)보다 조금 늦다 — 요약을 쓴 때까지는 그대로 (리뷰)
        val during = mapOf("at" to (now + 3 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 1.0,
            "first_home_at" to (now + 2 * 60_000L).toDouble())
        val h2 = Analyzer.findings(facts().copy(watchMetrics = during, zone = zone)).single { it.id == "watch-home" }
        assertTrue(h2.evidence.toString(), h2.evidence.any { it.contains("처음 짚은 때: 10월 19일 22:02 (1시간 안)") })
        // 지금은 안 보이는 문제(재시작·치료 뒤)에도 처음 짚은 때를
        val earlier = mapOf("at" to (now - 5 * 60_000L).toDouble(), "seen_home_h" to 2.0, "first_home_at" to (now - 30 * 3_600_000L).toDouble())
        val e = Analyzer.findings(facts().copy(watchMetrics = earlier, zone = zone)).single { it.id == "watch-home-earlier" }
        assertTrue(e.evidence.toString(), e.evidence.any { it.contains("처음 짚은 때: 10월 18일 16:00 (30시간 전)") })
    }

    /** 워치 기록이 하루가 안 되면 '워치 기록 24시간'이 아니라 그 길이까지만 이 보고서가 잰 구간 (리뷰) */
    @Test
    fun aShortWatchLogLimitsTheMeasuredWindow() {
        val wm = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 0.0, "seen_home_h" to 10.0)
        val short = facts(watchSamples = watch(2.5, hours = 6)).copy(watchMetrics = wm)       // dumpsys 8시간 · 워치 기록 6시간
        assertEquals(Severity.INFO, Analyzer.findings(short).single { it.id == "watch-home-earlier" }.severity)
        assertEquals(Severity.CAUTION, Analyzer.findings(facts().copy(watchMetrics = wm)).single { it.id == "watch-home-earlier" }.severity)
    }

    /** 요약에 앱별 기록 창이 없으면(재시작·치료 직후 비었거나 앱별 통계 권한이 없음) '0분치'라 하지 않는다 (리뷰) */
    @Test
    fun aMissingAppWindowIsNotZeroMinutes() {
        val e = Analyzer.findings(facts().copy(watchMetrics = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "seen_home_h" to 2.5)))
            .single { it.id == "watch-home-earlier" }
        assertTrue(e.evidence.toString(), e.evidence.none { it.contains("0분치") } && e.evidence.any { it.contains("앱별 기록이 없어") })
    }

    /** 지금 짚은 범인이 있으면 다시 재서 견주기는 그 할 일 뒤에 (리뷰: 시험이 없던 갈래) */
    @Test
    fun theEarlierStepFollowsTheCulpritSteps() {
        val vf = facts().copy(watchMetrics = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "app_window_min" to 20.0, "seen_home_h" to 2.5))
        val drain = Finding("wakelock:com.example.drain", Severity.ABNORMAL, "com.example.drain 웨이크락", emptyList(), packages = listOf("com.example.drain"), category = "wakelock")
        val v = Analyzer.verdict(vf, Analyzer.findings(vf) + drain)
        assertTrue(v.todo.first(), v.todo.first().startsWith("메뉴 4(차단 조치)"))
        assertTrue(v.todo.last(), v.todo.last().contains("워치가") && v.todo.last().contains("다시 수집"))
    }

    /**
     * 워치 요약의 Play 서비스 단계는 방금 쓴 요약일 때만 — 묵은 요약(3시간 넘음)의 '다음 단계 뒤에도 계속'으로, 이 보고서의 dumpsys 만
     * Play 서비스를 짚은 지금 할 일을 바꾸지 않는다 (리뷰)
     */
    @Test
    fun aStaleWatchStageDoesNotChangeThePlayServicesSteps() {
        val vf = facts().copy(watchMetrics = mapOf("at" to (this.now - 4 * 3_600_000L).toDouble(), "issue_gms" to 2.0, "back_gms" to 2.0, "app_window_min" to 120.0))
        val fs = Analyzer.findings(vf)
        assertTrue("묵은 요약은 소견으로 쓰지 않는다", fs.none { it.id == "watch-gms" })
        val dumpsysGms = Finding("gms-share", Severity.ABNORMAL, "Play 서비스가 추정 전력의 40%", emptyList(), packages = listOf(Analyzer.GMS), category = "gms")
        val step = Analyzer.verdict(vf, fs + dumpsysGms).todo.first { it.contains("Play 서비스는") }
        assertTrue(step, step.contains("캐시 삭제 → 강제 재시작") && !step.contains("재시작·치료 뒤에도 계속"))
    }

    /**
     * 오류 보내기는 워치 앱과 같은 경로·같은 말로 — 삼성전자서비스 안내(98474, 2024: 폰의 Galaxy Wearable 앱 › 대시보드 › ⋮ › 설정 › 문의하기 ›
     * 오류 보내기 · Samsung Members › 도움 받기 › 질문/오류 보내기, 증상 2~3분 안)와 삼성 호주 안내(시스템 로그 데이터 보내기 체크).
     * 'Samsung Members 앱으로 오류 보고'처럼 한쪽 길만 말하지 않는다 (리뷰)
     */
    @Test
    fun errorReportsUseTheSamePathEverywhere() {
        val f = Facts(collectedAt = now, packages = listOf(PackageInfo(Analyzer.HOME, 10123, true, false), PackageInfo(Analyzer.GMS, 10045, true, false)),
            watchMetrics = mapOf("at" to (now - 5 * 60_000L).toDouble(), "issue_home" to 2.0, "back_home" to 2.0, "issue_gms" to 2.0, "back_gms" to 2.0, "app_window_min" to 120.0))
        val fs = Analyzer.findings(f)
        val vf = facts().copy(watchMetrics = f.watchMetrics)
        val v = Analyzer.verdict(vf, Analyzer.findings(vf) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        val hw = Analyzer.verdict(facts(), Analyzer.findings(facts()) + Finding("rate-24h", Severity.ABNORMAL, "24시간 시간당 6.0%", emptyList(), category = "drain"))
        assertEquals(Cause.HARDWARE, hw.cause)
        val texts = fs.filter { it.id == "watch-home" || it.id == "watch-gms" }.flatMap { it.evidence } + v.todo + hw.todo
        val reports = texts.filter { it.contains("오류 보") }
        assertTrue(texts.toString(), reports.size >= 4)
        for (t in reports) assertTrue(t, (t.contains(com.mywatch.battery.core.KnownIssues.REPORT) || t.contains("오류 보내기(위 홈 줄과 같은 길)")) && !t.contains("오류 보고"))
        // 홈·Play 서비스가 함께 마지막 단계여도 긴 경로는 할 일에 한 번만, 경로 뒤에 다른 할 일을 잇지 않는다 (리뷰)
        val todo = v.todo.joinToString("\n")
        assertEquals(todo, 1, todo.split(com.mywatch.battery.core.KnownIssues.REPORT).size - 1)
        assertTrue(todo, todo.lines().none { it.contains(".txt → ") })
    }

    /**
     * Play 서비스는 워치 앱과 같은 순서(캐시 삭제 → 강제 재시작)로. 어시스턴트 업데이트 제거는 2023년 워치6 사례(Play 서비스로 보였지만
     * 어시스턴트였음)의 해결책이라 그렇게 적는다 — '2026년 사례'가 아니다 (출처 확인 · 시나리오 점검)
     */
    @Test
    fun playServicesStepsMatchTheWatchOrderAndTheAssistantFixIsDated() {
        val vf = facts().copy(watchMetrics = mapOf("at" to (this.now - 5 * 60_000L).toDouble(), "issue_gms" to 2.0, "app_window_min" to 120.0))
        val v = Analyzer.verdict(vf, Analyzer.findings(vf))
        val step = v.todo.first { it.contains("Play 서비스는") }
        assertTrue(step, step.contains("캐시 삭제 → 강제 재시작") && !step.contains("재시작 → 캐시"))
        assertTrue(step, step.contains("2023년") && !step.contains("2026년 사례"))
        // 그 사례의 원인을 단정하지 않는다 — 보고된 것과 풀린 방법만 (리뷰)
        assertTrue(step, !step.contains("어시스턴트였음"))
    }

    /**
     * One UI Watch 홈이 시스템 uid(1000)를 같이 쓰면 앱별 통계에서 홈만 따로 셀 수 없다 — 보고서가 그렇다고 알려 준다
     * (워치 앱의 '홈 과소모'도 못 짚음 → 워치 배터리 사용량 화면에서 직접 보라고). 따로 쓰면 조용히
     */
    @Test
    fun aHomeOnTheSystemUidIsReported() {
        val home = Analyzer.HOME
        val shared = Analyzer.findings(Facts(packages = listOf(PackageInfo("android", 1000, true, false), PackageInfo(home, 1000, true, false))))
            .firstOrNull { it.id == "home-uid" }
        assertNotNull(shared)
        assertEquals(Severity.INFO, shared!!.severity)
        assertTrue(shared.title, shared.title.contains("시스템과 같은 uid"))
        assertTrue(shared.evidence.toString(), shared.evidence.any { it.contains("배터리 사용량") })
        assertTrue(Analyzer.findings(Facts(packages = listOf(PackageInfo(home, 10123, true, false)))).none { it.id == "home-uid" })
    }

    /** uid 하나에 패키지가 여럿이면 알려진 문제를 보는 패키지(홈·Play 서비스)를 대표로 — 시스템 uid('android')는 그대로 (리뷰에서 찾음) */
    @Test
    fun aSharedUidIsNamedAfterTheKnownPackage() {
        val home = "com.samsung.android.wearable.sysui"
        val s = BatteryStats(uidPackages = mapOf(10020 to listOf("com.android.systemui", home), 1000 to listOf("android", home), 1001 to listOf(home, "android")))
        assertEquals(home, s.packageOf(10020))
        assertEquals("android", s.packageOf(1000))
        // 목록 순서와 상관없이 (덤프마다 순서가 다를 수 있다)
        assertEquals("android", s.packageOf(1001))
    }
}
