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
