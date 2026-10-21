package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 날마다 대기(화면 꺼짐·충전 안 함) 소모를 놓고 '언제부터' 꺾였는지 찾는다 — 워치9 사용자 글은 "업데이트 뒤 32 → 17시간"처럼
 * 꺾인 날이 분명하다. 하루하루의 흔들림·하루 튄 날은 꺾임이 아니다
 */
class DayTrendTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** 10월 [first]일부터 날마다: 0시 100% → 20시까지 화면 꺼진 채 [rates]%/h → 20~24시 충전 */
    private fun days(rates: List<Double?>, first: Int = 10): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        rates.forEachIndexed { i, r ->
            val d = first + i
            var t = at(d, 0)
            if (r == null) {
                // 거의 하루 내내 충전기 위 (대기 기록 2시간뿐)
                while (t < at(d, 2)) { out += BatterySample(t, 100 - ((t - at(d, 0)) / 3_600_000.0).toInt()); t += q }
                while (t < at(d + 1, 0)) { out += BatterySample(t, 100, plugged = true, status = Status.FULL); t += q }
                return@forEachIndexed
            }
            while (t <= at(d, 20)) {
                val h = (t - at(d, 0)) / 3_600_000.0
                out += BatterySample(t, (100 - r * h).toInt())
                t += q
            }
            while (t < at(d + 1, 0)) { out += BatterySample(t, 100, plugged = true, status = Status.CHARGING); t += q }
        }
        return out
    }

    private fun shiftOf(rates: List<Double?>, first: Int = 10): Shift? {
        val now = at(first + rates.size, 0) - 1
        return DayTrend.shift(DayTrend.days(days(rates, first), now, zone))
    }

    @Test
    fun eachDayGetsItsIdleRate() {
        val d = DayTrend.days(days(listOf(1.0, 2.0, 1.5)), at(13, 0) - 1, zone, days = 3)
        assertEquals(listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)), d.map { it.date })
        assertEquals(1.0, d[0].pph!!, 0.1)
        assertEquals(2.0, d[1].pph!!, 0.1)
        assertEquals(1.5, d[2].pph!!, 0.1)
    }

    @Test
    fun aSustainedRiseIsFoundWithTheDayItStarted() {
        val s = shiftOf(listOf(1.0, 1.1, 0.9, 1.0, 1.0, 2.4, 2.5, 2.3))!!
        assertTrue(s.worse)
        assertEquals(LocalDate.of(2026, 10, 14), s.lastBefore)
        assertEquals(LocalDate.of(2026, 10, 15), s.firstAfter)
        assertEquals(1.0, s.before, 0.1)
        assertEquals(2.4, s.after, 0.1)
        assertEquals(5, s.beforeDays)
        assertEquals(3, s.afterDays)
    }

    @Test
    fun aDropAfterAFixIsFoundToo() {
        val s = shiftOf(listOf(2.4, 2.5, 2.3, 2.4, 1.0, 1.1, 0.9))!!
        assertFalse(s.worse)
        assertEquals(LocalDate.of(2026, 10, 14), s.firstAfter)
    }

    @Test
    fun dayToDayNoiseIsNotAShift() {
        assertNull(shiftOf(listOf(1.0, 1.4, 0.9, 1.3, 1.0, 1.5, 1.1, 1.2)))
    }

    @Test
    fun oneBadDayIsNotAShift() {
        assertNull(shiftOf(listOf(1.0, 1.0, 1.1, 3.0, 1.0, 1.0, 1.1)))
        assertNull("마지막 하루만 나쁨 — 이틀은 봐야", shiftOf(listOf(1.0, 1.0, 1.1, 1.0, 3.0)))
    }

    @Test
    fun aRelativeJumpNeedsARealDifferenceToo() {
        // 두 배지만 시간당 0.3%p — 대기 기준(3.2%)에 견주면 작은 차이
        assertNull(shiftOf(listOf(0.3, 0.3, 0.3, 0.6, 0.6, 0.6)))
    }

    @Test
    fun daysWithLittleIdleTimeAreLeftOutButKept() {
        val samples = days(listOf(1.0, 1.0, null, 1.0, 2.4, 2.5, 2.4))
        val d = DayTrend.days(samples, at(17, 0) - 1, zone, days = 7)
        assertNull("충전기 위에서 보낸 날은 비움", d[2].pph)
        assertEquals(7, d.size)
        val s = DayTrend.shift(d)!!
        assertEquals(LocalDate.of(2026, 10, 13), s.lastBefore)
        assertEquals(LocalDate.of(2026, 10, 14), s.firstAfter)
        assertEquals(3, s.beforeDays)
    }

    @Test
    fun todayCountsOnceItHasEnoughIdleHours() {
        val samples = days(listOf(1.0, 1.0, 1.0, 2.4, 2.4)) + days(listOf(2.4), first = 15).filter { it.t <= at(15, 10) }
        val d = DayTrend.days(samples, at(15, 10), zone, days = 6)
        assertEquals(LocalDate.of(2026, 10, 15), d.last().date)
        assertEquals(2.4, d.last().pph!!, 0.15)
        assertNull("오늘 2시간뿐이면 비움", DayTrend.days(samples, at(15, 2), zone, days = 6).last().pph)
    }

    @Test
    fun withTwoChangesTheBiggerOneWins() {
        // 1.0 → 1.6 (작게) → 3.0 (크게): 3.0 쪽 경계
        val s = shiftOf(listOf(1.0, 1.0, 1.0, 1.6, 1.6, 1.6, 3.0, 3.0, 3.0))
        assertNotNull(s)
        assertEquals(LocalDate.of(2026, 10, 16), s!!.firstAfter)
    }
}
