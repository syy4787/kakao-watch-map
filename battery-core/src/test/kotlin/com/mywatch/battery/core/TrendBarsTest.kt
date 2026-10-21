package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** '최근 며칠' 막대 그래프의 내용: 날마다 대기 %/h·등급·그날 바뀐 것 수, 꺾인 자리, 화면 낭독기용 설명 */
class TrendBarsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun day(d: Int, pph: Double?) =
        DayRate(LocalDate.of(2026, 10, d), if (pph == null) Rate.NONE else Rate(pph, null, 20.0, pph * 20, null))

    private val days = listOf(day(14, 1.0), day(15, 1.1), day(16, null), day(17, 1.0), day(18, 2.4), day(19, 4.6), day(20, 2.5))
    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun eachDayBecomesABarWithItsGradeAndChanges() {
        val changes = listOf(
            Change(at(18, 3), ChangeKind.FIRMWARE, "fw", "A", "B"),
            Change(at(18, 10), ChangeKind.APP, ChangeFinder.GMS, null, "26.40.31"),
            Change(at(20, 9), ChangeKind.SETTING, "aod", "0", "1"),
            Change(at(1, 9), ChangeKind.SETTING, "aod", "1", "0"),   // 그래프 밖
        )
        val m = TrendBars.build(days, null, changes, zone, today)!!
        assertEquals(listOf("14", "15", "16", "17", "18", "19", "20"), m.bars.map { it.label })
        assertEquals(listOf(1.0, 1.1, null, 1.0, 2.4, 4.6, 2.5), m.bars.map { it.pph })
        assertEquals(Grade.UNKNOWN, m.bars[2].grade)
        assertEquals(Grade.NORMAL, m.bars[4].grade)
        assertEquals(Grade.ABNORMAL, m.bars[5].grade)
        assertEquals(listOf(0, 0, 0, 0, 2, 0, 1), m.bars.map { it.changes })
        assertTrue(m.bars.last().today)
        // 세로 끝은 대기 기준(3.2%)보다 낮지 않게 — 작은 값이 크게 보이지 않도록
        assertTrue(m.top >= 4.6)
        assertEquals(Thresholds.IDLE_NORMAL, TrendBars.build(days.take(2), null, emptyList(), zone, today)!!.top / 1.1, 0.01)
    }

    @Test
    fun theShiftIsDrawnAfterTheLastDayBefore() {
        val shift = Shift(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18), 1.0, 2.5, 3, 3)
        val m = TrendBars.build(days, shift, emptyList(), zone, today)!!
        assertEquals(3, m.splitAfter)
        assertEquals(true, m.worse)
        assertTrue(m.description, m.description.contains("10/18부터 늘어남"))
        assertTrue(m.description, m.description.contains("10/14 1.0") && m.description.contains("오늘 2.5"))
    }

    @Test
    fun nothingToDrawWithoutAnyDay() {
        assertNull(TrendBars.build(listOf(day(14, null), day(15, null)), null, emptyList(), zone, today))
    }

    /** '며칠 더 필요' 글의 날 수는 꺾임 판단에 쓰는 날로 — 끝나지 않은 오늘의 아침 8시간은 막대엔 그려도 세지 않는다 */
    @Test
    fun usableDaysLeaveOutAPartialMorning() {
        val morning = DayRate(today, Rate(2.0, null, 8.0, 16.0, null), partial = true)
        val r = TrendReport(days.take(2) + morning, null, emptyList(), emptyList(), null, fresh = false)
        assertEquals(2, r.validDays)
        assertEquals(3, r.shownDays.count { it.pph != null })
    }
}
