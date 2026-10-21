package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 조치가 정말 효과가 있었는지: 지킴이의 밤 모드(절전·AOD 끔)가 켜졌던 밤과 아닌 밤의 밤사이(00~07시) 소모를 비교한다.
 * 밤이 양쪽 다 2번 이상 있어야 말한다.
 */
class LeverEffectTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** [days] 날짜마다 밤 00~07시를 시간당 [pph] 로 (저녁 22시에 90% 에서 시작) */
    private fun nights(days: Map<Int, Double>): List<BatterySample> = days.flatMap { (d, pph) ->
        val start = at(d - 1, 22)
        (0..40).map { k ->
            val t = start + k * q
            val hoursIntoNight = ((t - at(d, 0)) / 3_600_000.0).coerceIn(0.0, 7.0)
            val before = if (t < at(d, 0)) (t - start) / 3_600_000.0 * 2.0 else 4.0
            BatterySample(t, (90 - before - hoursIntoNight * pph).toInt())
        }
    }

    @Test
    fun nightModeNightsAreComparedWithPlainNights() {
        val samples = nights(mapOf(14 to 3.0, 15 to 2.8, 16 to 1.0, 17 to 1.2, 18 to 1.1))
        val sessions = listOf(16, 17, 18).map { d -> Span(at(d, 0, 30), at(d, 7, 0)) }
        val e = LeverEffects.nightMode(samples, sessions, at(19, 12), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)), e.with.map { it.date })
        assertEquals(2, e.without.size)
        assertEquals(1.1, e.withMedian!!, 0.25)
        assertEquals(2.9, e.withoutMedian!!, 0.3)
        val cut = e.reductionPercent!!
        assertTrue("$cut", cut in 55.0..70.0)
        assertTrue(e.headline!!, e.headline!!.contains("줄"))
    }

    @Test
    fun oneNightEachIsNotEnough() {
        val samples = nights(mapOf(14 to 3.0, 16 to 1.0))
        val e = LeverEffects.nightMode(samples, listOf(Span(at(16, 0, 30), at(16, 7))), at(19, 12), zone)
        assertNull(e.reductionPercent)
        assertNull(e.headline)
        assertNotNull(e.withMedian)
    }

    @Test
    fun aNightWithTooLittleRecordIsSkipped() {
        // 밤 중 3시간만 기록(나머지는 충전) — 5시간 미만이라 비교에서 뺀다
        val samples = nights(mapOf(14 to 3.0)).map { if (it.t > at(14, 3)) it.copy(plugged = true) else it }
        val e = LeverEffects.nightMode(samples, emptyList(), at(19, 12), zone)
        assertTrue(e.without.isEmpty())
    }

    @Test
    fun nightModeThatDidNotHelpIsSaidPlainly() {
        val samples = nights(mapOf(13 to 2.0, 14 to 2.1, 15 to 2.0, 16 to 2.1))
        val sessions = listOf(15, 16).map { d -> Span(at(d, 0, 30), at(d, 7, 0)) }
        val e = LeverEffects.nightMode(samples, sessions, at(19, 12), zone)
        assertTrue(e.headline!!, e.headline!!.contains("차이 없음"))
    }
}
