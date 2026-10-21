package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 지금 페이스면 언제 15%·0% 가 되나 — 최근 2시간(모자라면 8시간) 소모율로 */
class ProjectionTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 21, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun drain(end: Long, count: Int, start: Double, pph: Double, plugged: Boolean = false) =
        (0 until count).map { i -> BatterySample(end - (count - 1 - i) * q, (start - pph * i / 4).toInt(), plugged = plugged) }

    @Test
    fun paceUsesTheLastTwoHours() {
        // 8시간 전부터 시간당 2%, 마지막 2시간은 시간당 6%
        val slow = drain(at(10), 25, 90.0, 2.0)
        val fast = drain(at(12), 9, slow.last().level.toDouble(), 6.0)
        val p = Projection.pace(slow + fast.drop(1), at(12))!!
        assertEquals(6.0, p, 0.6)
    }

    @Test
    fun timeToFifteenAndZero() {
        val now = at(12)
        val samples = drain(now, 9, 63.0, 4.0)      // 2시간 시간당 4% → 지금 55%
        val f = Projection.forecast(samples, now)!!
        assertEquals(55, f.level)
        assertEquals(4.0, f.pph, 0.6)
        // 55 → 15 는 40% ÷ 4%/h = 10시간 → 22시 무렵
        assertEquals(at(22).toDouble(), f.at15!!.toDouble(), 75 * 60_000.0)
        assertTrue(f.at0!! > f.at15!!)
        assertTrue(f.label(zone), f.label(zone).contains("오후 10:"))
    }

    @Test
    fun chargingOrTooLittleDataHasNoForecast() {
        assertNull(Projection.forecast(drain(at(12), 9, 50.0, 4.0, plugged = true), at(12)))
        assertNull(Projection.forecast(drain(at(12), 2, 50.0, 4.0), at(12)))
    }

    @Test
    fun alreadyBelowFifteenShowsOnlyZero() {
        val f = Projection.forecast(drain(at(12), 9, 18.0, 4.0), at(12))!!
        assertNull(f.at15)
        assertTrue(f.label(zone), f.label(zone).contains("0%"))
    }
    /** 자정·새벽은 '오전 12:03'(헷갈림) 대신 '밤 12:03'·'새벽 3:24' */
    @Test
    fun midnightAndSmallHoursReadNaturally() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        fun t(h: Int, m: Int) = java.time.LocalDateTime.of(2026, 10, 22, h, m).atZone(zone).toInstant().toEpochMilli()
        val now = java.time.LocalDateTime.of(2026, 10, 21, 20, 0).atZone(zone).toInstant().toEpochMilli()
        // 다음 날 00:03 은 말로는 '오늘 밤 12시 3분'
        assertEquals("오늘 밤 12:03", Forecast.clock(t(0, 3), zone, now))
        assertEquals("내일 새벽 3:24", Forecast.clock(t(3, 24), zone, now))
        assertEquals("내일 오전 7:05", Forecast.clock(t(7, 5), zone, now))
        assertEquals("내일 오후 1:00", Forecast.clock(t(13, 0), zone, now))
    }

    /** 타일용 짧은 글: 소모율 없이 언제 15%·0% — 같은 날이면 날짜는 한 번 (작은 글씨 두 줄에 0% 시각이 잘리지 않게) */
    @Test
    fun theTileLineIsShort() {
        fun t(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        val now = at(22)
        assertEquals("내일 15% 새벽 3:24 · 0% 오전 7:40", Forecast(40, 4.0, t(22, 3, 24), t(22, 7, 40)).short(zone, now))
        assertEquals("15% 오후 10:05 · 0% 내일 새벽 2:10", Forecast(20, 4.0, t(21, 22, 5), t(22, 2, 10)).short(zone, at(21, 50)))
        assertEquals("0% 오후 11:30", Forecast(10, 4.0, null, t(21, 23, 30)).short(zone, now))
        assertTrue(TodoText.width(Forecast(40, 4.0, t(22, 3, 24), t(22, 7, 40)).short(zone, now)) <= 22)
    }

    /** 작은 워치 타일용 한 줄: 0% 되는 때만 (그 날짜도 붙여) — 두 줄이면 아래 상태 줄이 칸 밖으로 밀렸다 (맞춤 시험에서 찾음) */
    @Test
    fun theSmallTileLineIsOnlyWhenItRunsOut() {
        fun t(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        val now = at(22)
        assertEquals("0% 내일 오전 7:40", Forecast(40, 4.0, t(22, 3, 24), t(22, 7, 40)).compact(zone, now))
        assertEquals("0% 오후 11:30", Forecast(10, 4.0, null, t(21, 23, 30)).compact(zone, now))
        assertTrue(TodoText.width(Forecast(40, 4.0, t(22, 3, 24), t(23, 7, 40)).compact(zone, now)) <= 11)
    }
}
