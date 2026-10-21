package doctor

import doctor.fix.JournalEntry
import doctor.parse.BatteryStats
import doctor.parse.PowerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 치료 전후 비교: 지난 수집과 이번 수집의 소모율·화면 꺼짐 소모·깨어 있던 비율·Play 서비스 몫 — 사이에 적용한 조치와 함께 */
class CompareTest {
    private val h = 3_600_000L
    private val t0 = 1_792_000_000_000L

    private fun stats(hours: Double, drop: Int, offHours: Double, offDrop: Int, awake: Double, gms: Double, total: Double) = BatteryStats(
        onBatteryMs = (hours * h).toLong(), screenOffMs = (offHours * h).toLong(), screenOffAwakeMs = (offHours * h * awake).toLong(),
        dischargePercent = drop to drop + 1, dischargeScreenOff = offDrop,
        powerItems = listOf(PowerItem("Uid u0a12", "com.google.android.gms", gms, null), PowerItem("Screen", null, total - gms, null)),
    )

    @Test
    fun treatmentThatWorkedIsShownRowByRowWithTheActionsInBetween() {
        val before = Facts(stats = stats(10.0, 40, 9.0, 27, 0.30, 60.0, 120.0), collectedAt = t0)
        val after = Facts(stats = stats(10.0, 15, 9.0, 9, 0.05, 5.0, 60.0), collectedAt = t0 + 30 * h)
        val journal = listOf(
            JournalEntry(t0 - h, "old-thing", null, emptyList(), null, false),
            JournalEntry(t0 + h, "trim-caches", null, emptyList(), null, false),
            JournalEntry(t0 + h + 1, "gms-restart", null, emptyList(), null, false),
            JournalEntry(t0 + 2 * h, "bg-restrict:com.example.drain", "default", emptyList(), emptyList(), undone = true),
        )
        val c = Compare.of(before, after, journal)!!
        assertEquals(listOf("trim-caches", "gms-restart"), c.actions)
        val rows = c.rows.associateBy { it.label }
        assertEquals(Change.BETTER, rows.getValue(Compare.TOTAL).change)
        assertEquals(Change.BETTER, rows.getValue(Compare.SCREEN_OFF).change)
        assertEquals("시간당 3.0%", rows.getValue(Compare.SCREEN_OFF).before)
        assertEquals("시간당 1.0%", rows.getValue(Compare.SCREEN_OFF).after)
        assertEquals(Change.BETTER, rows.getValue(Compare.AWAKE).change)
        assertEquals(Change.BETTER, rows.getValue(Compare.GMS).change)
        assertTrue(c.headline, c.headline.contains("좋아졌") && c.headline.contains("3.0%") && c.headline.contains("1.0%"))
    }

    /**
     * 사이에 한 조치는 PC 시계로 고른다 — 조치 기록은 PC 시계인데 수집 시각은 워치 시계라, 워치가 몇 분 앞서면 자동 치료(수집 몇 초 뒤
     * 조치)가 '수집 전'으로 보여 빠졌다 (시나리오 점검)
     */
    @Test
    fun actionsAreMatchedOnThePcClock() {
        val before = Facts(stats = stats(10.0, 40, 9.0, 27, 0.30, 60.0, 120.0), collectedAt = t0 + 3 * 60_000L)   // 워치 시계 3분 앞섬
        val after = Facts(stats = stats(10.0, 15, 9.0, 9, 0.05, 5.0, 60.0), collectedAt = t0 + 30 * h + 3 * 60_000L)
        val journal = listOf(JournalEntry(t0 + 30_000L, "trim-caches", null, emptyList(), null, false))
        assertEquals(listOf("trim-caches"), Compare.of(before, after, journal, actionsBetween = (t0 + 1)..(t0 + 30 * h))!!.actions)
        assertTrue(Compare.of(before, after, journal)!!.actions.isEmpty())   // 워치 시계로만 보면 빠진다
    }

    @Test
    fun aSimilarResultSaysTheCauseMayBeElsewhere() {
        val before = Facts(stats = stats(10.0, 30, 9.0, 18, 0.10, 6.0, 60.0), collectedAt = t0)
        val after = Facts(stats = stats(10.0, 29, 9.0, 17, 0.10, 6.0, 60.0), collectedAt = t0 + 30 * h)
        val c = Compare.of(before, after, emptyList())!!
        assertTrue(c.rows.all { it.change == Change.SAME })
        assertTrue(c.headline, c.headline.contains("차이가 크지 않"))
    }

    @Test
    fun worseIsSaidPlainly() {
        val before = Facts(stats = stats(10.0, 15, 9.0, 9, 0.05, 5.0, 60.0), collectedAt = t0)
        val after = Facts(stats = stats(10.0, 40, 9.0, 27, 0.30, 60.0, 120.0), collectedAt = t0 + 30 * h)
        val c = Compare.of(before, after, emptyList())!!
        assertEquals(Change.WORSE, c.rows.first { it.label == Compare.SCREEN_OFF }.change)
        assertTrue(c.headline, c.headline.contains("나빠졌"))
    }

    @Test
    fun tooShortOrMissingMeasurementsAreNotCompared() {
        val before = Facts(stats = stats(1.0, 5, 0.8, 3, 0.1, 1.0, 10.0), collectedAt = t0)
        val after = Facts(stats = stats(10.0, 15, 9.0, 9, 0.05, 5.0, 60.0), collectedAt = t0 + 30 * h)
        assertNull("한 시간치로는 비교하지 않는다", Compare.of(before, after, emptyList()))
        assertNull(Compare.of(Facts(collectedAt = t0), Facts(collectedAt = t0 + h), emptyList()))
    }

    @Test
    fun watchAppMetricsAreComparedToo() {
        val before = Facts(collectedAt = t0, watchMetrics = mapOf("at" to (t0 - h).toDouble(), "idle_pph" to 2.4, "gesture_wakes" to 160.0, "night_pph" to 1.8))
        // 지난 수집 뒤 하루가 넘어 쓴 요약 (하루치 숫자가 치료 전 시간을 품지 않게)
        val after = Facts(collectedAt = t0 + 30 * h, watchMetrics = mapOf("at" to (t0 + 26 * h).toDouble(), "idle_pph" to 1.1, "gesture_wakes" to 150.0, "night_pph" to 0.8))
        val c = Compare.of(before, after, emptyList())!!
        val rows = c.rows.associateBy { it.label }
        assertEquals(Change.BETTER, rows.getValue(Compare.IDLE).change)
        assertEquals(Change.SAME, rows.getValue(Compare.GESTURE).change)
        assertEquals(Change.BETTER, rows.getValue(Compare.NIGHT).change)
        assertTrue(c.headline, c.headline.contains("2.4%") && c.headline.contains("1.1%"))
    }
    /**
     * 워치 앱 요약은 하루에 한 번만 새로 쓰인다 — 두 수집에 같은 요약이 들어 있거나(같은 'at') 이번 요약이 지난 수집보다 먼저 쓰였으면
     * 비교하지 않는다. 한쪽에만 지표가 있으면(앱 업데이트 직후) 다른 출처와 섞지 않는다 (리뷰에서 찾음)
     */
    @Test
    fun aStaleOrOneSidedWatchSummaryIsNotCompared() {
        val before = Facts(collectedAt = t0, watchMetrics = mapOf("at" to (t0 - 2 * h).toDouble(), "idle_pph" to 2.4))
        val same = Facts(collectedAt = t0 + 12 * h, watchMetrics = mapOf("at" to (t0 - 2 * h).toDouble(), "idle_pph" to 2.4))
        assertNull(Compare.of(before, same, emptyList()))
        val fresh = Facts(collectedAt = t0 + 28 * h, watchMetrics = mapOf("at" to (t0 + 26 * h).toDouble(), "idle_pph" to 1.1))
        assertEquals(Change.BETTER, Compare.of(before, fresh, emptyList())!!.rows.single { it.label == Compare.IDLE }.change)
        assertNull("한쪽에만 지표", Compare.of(before.copy(watchMetrics = emptyMap()), fresh, emptyList()))
        // 수집 때마다 요약을 새로 쓰게 됐다(워치 앱 v2.43) — 지난 수집 뒤 하루가 안 돼 쓴 요약의 하루치 숫자는 치료 전 시간을 품어 견주지 않는다
        val tooSoon = Facts(collectedAt = t0 + 12 * h, watchMetrics = mapOf("at" to (t0 + 12 * h).toDouble(), "idle_pph" to 1.1))
        assertNull("하루가 안 됨", Compare.of(before, tooSoon, emptyList()))
    }

    /** 수집 → 몇 분 뒤 자동 치료(다시 수집): 충전 없이 이어진 dumpsys 창이 겹치므로 비교하지 않는다 */
    @Test
    fun overlappingDumpsysWindowsAreNotCompared() {
        val before = Facts(stats = stats(10.0, 40, 9.0, 27, 0.30, 60.0, 120.0), collectedAt = t0)
        val after = Facts(stats = stats(10.2, 40, 9.2, 27, 0.30, 60.0, 120.0), collectedAt = t0 + 10 * 60_000L)
        assertNull(Compare.of(before, after, emptyList()))
    }

    /** 워치 기록(CSV)으로 비교할 땐 '이번' 쪽은 지난 수집 뒤의 기록만 — 앞의 하루와 섞이지 않게 */
    @Test
    fun watchLogRowsUseOnlyTheTimeAfterThePreviousCollection() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val q = 15 * 60_000L
        val beforeSamples = (0..96).map { i -> com.mywatch.battery.core.BatterySample(t = t0 - 24 * h + i * q, level = 90 - i * 3 / 4) }   // 시간당 3%
        val afterSamples = (1..48).map { i -> com.mywatch.battery.core.BatterySample(t = t0 + i * q, level = 90 - i / 4) }                   // 시간당 1%
        val analyzer = com.mywatch.battery.core.DrainAnalyzer(445, zone)
        val before = Facts(collectedAt = t0, nominalMah = 445, watchSamples = beforeSamples, watch = analyzer.analyze(beforeSamples, t0))
        val all = beforeSamples + afterSamples
        val after = Facts(collectedAt = t0 + 12 * h, nominalMah = 445, watchSamples = all, watch = analyzer.analyze(all, t0 + 12 * h))
        val idle = Compare.of(before, after, emptyList(), zone)!!.rows.single { it.label == Compare.IDLE }
        assertEquals(Change.BETTER, idle.change)
        assertTrue(idle.after, idle.after.contains("1.0%"))
    }
}
