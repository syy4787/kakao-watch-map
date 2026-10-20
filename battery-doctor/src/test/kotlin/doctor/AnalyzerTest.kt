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
}
