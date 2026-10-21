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
        val before = Facts(collectedAt = t0, watchMetrics = mapOf("idle_pph" to 2.4, "gesture_wakes" to 160.0, "night_pph" to 1.8))
        val after = Facts(collectedAt = t0 + 30 * h, watchMetrics = mapOf("idle_pph" to 1.1, "gesture_wakes" to 150.0, "night_pph" to 0.8))
        val c = Compare.of(before, after, emptyList())!!
        val rows = c.rows.associateBy { it.label }
        assertEquals(Change.BETTER, rows.getValue(Compare.IDLE).change)
        assertEquals(Change.SAME, rows.getValue(Compare.GESTURE).change)
        assertEquals(Change.BETTER, rows.getValue(Compare.NIGHT).change)
        assertTrue(c.headline, c.headline.contains("2.4%") && c.headline.contains("1.1%"))
    }
}
