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

    /**
     * 사용 통계(화면 켜짐 구간)가 있으면 화면이 한 번도 안 켜진 구간만 대기로 — 15분 사이에 몇 분씩 본 날(주말)이 대기 소모를
     * 부풀려 '꺾임'으로 읽히지 않게. 시스템이 며칠만 보관하므로 [ScreenCover.from] 앞의 날은 비운다
     */
    @Test
    fun exactScreenSpansKeepBusyDaysFromLookingLikeAShift() {
        // 날마다 0~10시: 대기는 15분에 1%(시간당 4%), 그런데 마지막 사흘은 매시 정각 지나 5분씩 화면을 보며 그 15분에 3%를 더 씀
        val samples = ArrayList<BatterySample>()
        val screen = ArrayList<Span>()
        for (i in 0 until 7) {
            val d = 10 + i
            val busy = i >= 4
            var t = at(d, 0)
            var level = 100
            while (t <= at(d, 10)) {
                samples += BatterySample(t, level)
                val onTheHour = ((t - at(d, 0)) / 60_000L) % 60 == 0L
                level -= 1 + (if (busy && onTheHour) 3 else 0)
                if (busy && onTheHour) screen += Span(t + 2 * 60_000L, t + 7 * 60_000L)
                t += q
            }
            while (t < at(d + 1, 0)) { samples += BatterySample(t, 100, plugged = true, status = Status.CHARGING); t += q }
        }
        val now = at(17, 0) - 1
        // 표본만으로는 바쁜 날이 대기 7%/h 로 보여 꺾임
        val sampled = DayTrend.days(samples, now, zone, days = 7)
        assertEquals(7.0, sampled[5].pph!!, 0.1)
        assertNotNull(DayTrend.shift(sampled))
        // 화면 구간을 주면 바쁜 날도 대기 4%/h — 꺾임 없음
        val exact = DayTrend.days(samples, now, zone, days = 7, screen = ScreenCover(screen, at(10, 0)))
        assertTrue(exact.all { it.exact })
        assertEquals(4.0, exact[5].pph!!, 0.1)
        assertEquals(4.0, exact[0].pph!!, 0.1)
        assertNull(DayTrend.shift(exact))
    }

    @Test
    fun daysBeforeTheScreenRecordStartsUseTheSamplesForEveryDay() {
        val samples = days(listOf(1.0, 1.0, 1.0, 2.5, 2.5, 2.5))
        val now = at(16, 0) - 1
        // 화면 기록이 14일부터뿐 (사흘) — 정확한 날이 4일이 안 되면 섞지 않고 모두 표본으로
        val d = DayTrend.days(samples, now, zone, days = 6, screen = ScreenCover(emptyList(), at(13, 0)))
        assertTrue(d.none { it.exact })
        assertNotNull(DayTrend.shift(d))
        // 넷째 날부터 기록이 있으면 그 앞날은 비우고 정확한 날만
        val e = DayTrend.days(samples, now, zone, days = 6, screen = ScreenCover(emptyList(), at(12, 0)))
        assertEquals(listOf(false, false, true, true, true, true), e.map { it.exact })
        assertNull(e[0].pph)
        assertEquals(2.5, e[5].pph!!, 0.1)
    }

    /**
     * 어제 하루 나빴고 오늘은 아직 아침 — 끝나지 않은 오늘(대기 12시간 미만)은 꺾임 판단에 넣지 않는다
     * (넣으면 하루 중에도 꺾임이 생겼다 사라지며 '지금 할 일'이 깜빡인다 — 리뷰에서 찾음)
     */
    @Test
    fun aBadYesterdayAndAPartialTodayAreNotAShift() {
        val samples = days(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.5, 2.5))   // 10/10~10/19, 오늘 = 10/19
        val morning = DayTrend.days(samples, at(19, 8), zone)
        assertEquals(2.5, morning.last().pph!!, 0.1)   // 막대는 그린다
        assertNull(morning.last().forShift)
        assertNull("오늘 아침 8시간뿐 — 어제 하루만 나쁨", DayTrend.shift(morning))
        // 오늘 대기가 12시간을 넘으면 넣는다 → 이틀째 나쁨 = 꺾임
        val evening = DayTrend.days(samples, at(19, 20), zone)
        assertEquals(LocalDate.of(2026, 10, 18), DayTrend.shift(evening)!!.firstAfter)
    }

    @Test
    fun withTwoChangesTheBiggerOneWins() {
        // 1.0 → 1.6 (작게) → 3.0 (크게): 3.0 쪽 경계
        val s = shiftOf(listOf(1.0, 1.0, 1.0, 1.6, 1.6, 1.6, 3.0, 3.0, 3.0))
        assertNotNull(s)
        assertEquals(LocalDate.of(2026, 10, 16), s!!.firstAfter)
    }
}
