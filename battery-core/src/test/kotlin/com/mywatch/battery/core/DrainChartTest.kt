package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 배터리 화면의 24시간 그래프 모델: 선(끊긴 곳은 나눔)·충전·화면 켜짐·가장 많이 닳은 시간 */
class DrainChartTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private val h = 3_600_000L
    private fun at(hh: Int, mm: Int = 0) = LocalDateTime.of(2026, 10, 21, hh, mm).atZone(zone).toInstant().toEpochMilli()
    private val now = at(22)

    @Test
    fun pointsMapToTheWindowAndGapsSplitTheLine() {
        // 24시간 전보다 앞선 표본은 버리고, 12:00~14:00 사이 기록이 없으면 선을 끊는다
        val s = ArrayList<BatterySample>()
        s += BatterySample(t = now - 30 * h, level = 100)                         // 창 밖
        for (i in 0..8) s += BatterySample(t = at(10) + i * q, level = 90 - i)    // 10:00~12:00
        for (i in 0..8) s += BatterySample(t = at(14) + i * q, level = 70 - i)    // 14:00~16:00
        val m = DrainChart.build(s, now, zone)
        assertEquals(now - 24 * h, m.from)
        assertEquals(now, m.to)
        assertEquals(2, m.lines.size)
        val first = m.lines[0].first()
        assertEquals((at(10) - m.from).toDouble() / (24 * h), first.x, 1e-9)
        assertEquals(90, first.level)
        assertEquals(DrainChart.x(at(14) + 8 * q, m), m.lines[1].last().x, 1e-9)
        assertEquals(62, m.lines[1].last().level)
    }

    @Test
    fun chargingSpansComeFromPluggedSamples() {
        val s = ArrayList<BatterySample>()
        for (i in 0..4) s += BatterySample(t = at(18) + i * q, level = 40 - i)
        for (i in 0..3) s += BatterySample(t = at(19, 15) + i * q, level = 50 + i * 10, plugged = true, status = Status.CHARGING)
        for (i in 0..4) s += BatterySample(t = at(20, 15) + i * q, level = 90 - i)
        val m = DrainChart.build(s, now, zone)
        assertEquals(1, m.charging.size)
        val c = m.charging[0]
        assertEquals(DrainChart.x(at(19, 15), m), c.from, 1e-9)
        assertEquals(DrainChart.x(at(20, 15), m), c.to, 1e-9)     // 다음(빼낸) 표본까지
        assertEquals(1, m.lines.size)                              // 충전도 한 선으로 이어진다
    }

    @Test
    fun screenSpansAndHotHoursAreClippedToTheWindow() {
        val s = (0..96).map { i -> BatterySample(t = now - 24 * h + i * q, level = 100 - i * 3 / 4) }
        val screen = listOf(Span(now - 25 * h, now - 23 * h), Span(at(20), at(20, 10)))
        val hot = listOf(at(19) to at(20))
        val m = DrainChart.build(s, now, zone, screen = screen, hot = hot)
        assertEquals(2, m.screenOn.size)
        assertEquals(0.0, m.screenOn[0].from, 1e-9)                                  // 창 앞쪽은 잘림
        assertEquals(DrainChart.x(now - 23 * h, m), m.screenOn[0].to, 1e-9)
        assertEquals(1, m.hot.size)
        assertEquals(DrainChart.x(at(19), m), m.hot[0].from, 1e-9)
        assertTrue(m.screenExact)
    }

    @Test
    fun descriptionSaysWhatTheGraphShows() {
        val s = ArrayList<BatterySample>()
        for (i in 0..40) s += BatterySample(t = at(8) + i * q, level = 100 - i)
        for (i in 0..3) s += BatterySample(t = at(18, 15) + i * q, level = 70 + i * 8, plugged = true)
        s += BatterySample(t = at(19, 15), level = 95)
        val m = DrainChart.build(s, now, zone, hot = listOf(at(12) to at(13)))
        assertTrue(m.description, m.description.contains("100%") && m.description.contains("충전 1번"))
        assertTrue(m.description, m.description.contains("오후 12시"))
        assertEquals(60, m.minLevel)
        assertEquals(100, m.maxLevel)
    }

    /** 가로축 글자: 6시간마다 시각 (0시·6시·12시·18시) — 지금 바로 옆(1시간 반 안)은 '지금'과 겹치니 뺀다 */
    @Test
    fun clockTicksEverySixHours() {
        val s = (0..96).map { i -> BatterySample(t = now - 24 * h + i * q, level = 100 - i / 2) }
        val m = DrainChart.build(s, now, zone)
        assertEquals(listOf("0시", "6시", "12시", "18시"), m.ticks.map { it.label })
        assertEquals(DrainChart.x(at(12), m), m.ticks[2].x, 1e-9)
        val late = DrainChart.build(s.map { it.copy(t = it.t + 5 * 60 * 60_000L) }, at(19, 0) + 0, zone)
        assertTrue(late.ticks.map { it.label }.toString(), "18시" !in late.ticks.map { it.label })
    }

    @Test
    fun tooFewSamplesGiveNoChart() {
        assertNull(DrainChart.buildOrNull(emptyList(), now, zone))
        assertNull(DrainChart.buildOrNull(listOf(BatterySample(t = now - h, level = 50)), now, zone))
    }
}
