package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DrainAnalyzerTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** [start]부터 15분마다 [count]개, 시간당 [pph]% 씩 줄어드는 표본 */
    private fun drain(start: Long, count: Int, startLevel: Double, pph: Double, interactive: Boolean = false, plugged: Boolean = false) =
        (0 until count).map { i -> BatterySample(t = start + i * q, level = (startLevel - pph * i / 4).toInt().coerceIn(0, 100), interactive = interactive, plugged = plugged) }

    @Test
    fun tooLittleDataIsNotJudged() {
        val now = at(2026, 10, 20, 12)
        val a = DrainAnalyzer(445, zone).analyze(drain(now - 4 * q, 5, 80.0, 4.0), now)
        assertEquals(Grade.UNKNOWN, a.verdict.grade)
        assertTrue(a.verdict.tags.contains("insufficient"))
        assertTrue(a.verdict.headline.contains("모으는 중"))
    }

    @Test
    fun aNormalDay() {
        val now = at(2026, 10, 20, 12)
        val s = drain(now - 96 * q, 97, 100.0, 2.8)   // 24시간 2.8%/h
        val a = DrainAnalyzer(445, zone).analyze(s, now)
        assertEquals(Grade.NORMAL, a.verdict.grade)
        assertTrue(a.verdict.headline, a.verdict.headline.startsWith("정상 범위"))
        assertEquals(2.8, a.w24.percentPerHour!!, 0.15)
        assertNotNull(a.overnight)
        assertEquals("10월 20일 밤(00~07시)", a.overnightLabel)
        assertTrue(a.anomalies.isEmpty())
        assertEquals(0, a.experiments.size)
    }

    @Test
    fun heavyIdleDrainIsAbnormalAndNamesTheIdleRate() {
        val now = at(2026, 10, 20, 12)
        val s = drain(now - 40 * q, 41, 100.0, 6.0)   // 10시간, 6%/h, 화면 꺼짐
        val a = DrainAnalyzer(445, zone).analyze(s, now)
        assertEquals(Grade.ABNORMAL, a.verdict.grade)
        assertTrue(a.verdict.headline, a.verdict.headline.startsWith("대기 중 시간당 6.0%"))
        assertTrue(a.verdict.reasons.any { it.contains("24시간 평균") })
    }

    @Test
    fun gaugeJumpsTagTheVerdictEvenWhenRatesLookFine() {
        val now = at(2026, 10, 20, 12)
        val s = drain(now - 40 * q, 41, 37.0, 2.0).toMutableList()   // 10시간 동안 37% → 17%
        // 17% 에서 꺼짐 → 재부팅 후 47%
        s += BatterySample(t = now + q, level = 47, event = Event.BOOT)
        val a = DrainAnalyzer(445, zone).analyze(s, now + q)
        assertTrue(a.verdict.tags.contains("gauge"))
        assertEquals(Grade.CAUTION, a.verdict.grade)
        assertTrue(a.verdict.headline, a.verdict.headline.startsWith("잔량 표시 오류 의심"))
        assertEquals(listOf(AnomalyKind.REBOOT_JUMP), a.anomalies.map { it.kind })
    }

    @Test
    fun experimentsAreCutByTheirMarkers() {
        val now = at(2026, 10, 20, 12)
        val base = drain(now - 24 * q, 25, 90.0, 0.6).toMutableList()   // 6시간 0.6%/h 바탕
        // 그 중 2시간(now-16q .. now-8q)은 '비행기 모드' 실험 — 표본은 그대로, 표시만 넣는다 (표시 줄의 잔량은 그 시각의 실제 잔량)
        base += BatterySample(t = now - 16 * q + 1, level = 88, event = Event.EXPERIMENT_START, note = "비행기 모드")
        base += BatterySample(t = now - 8 * q - 1, level = 87, event = Event.EXPERIMENT_END, note = "비행기 모드")
        val a = DrainAnalyzer(445, zone).analyze(base, now)
        assertEquals(1, a.experiments.size)
        val e = a.experiments[0]
        assertEquals("비행기 모드", e.name)
        assertNotNull(e.end)
        assertEquals(0.5, e.rate.percentPerHour!!, 0.3)
        assertEquals(Grade.NORMAL, e.grade)
    }

    @Test
    fun anOpenExperimentIsReportedAsRunning() {
        val now = at(2026, 10, 20, 12)
        val s = drain(now - 24 * q, 25, 90.0, 3.0).toMutableList()
        s += BatterySample(t = now - 8 * q + 1, level = 78, event = Event.EXPERIMENT_START, note = "절전 모드")
        val a = DrainAnalyzer(445, zone).analyze(s, now)
        assertEquals(1, a.experiments.size)
        assertNull(a.experiments[0].end)
        assertEquals(Grade.ABNORMAL, a.experiments[0].grade)   // 절전인데 3%/h
        assertTrue(a.verdict.headline, a.verdict.headline.contains("절전 모드에서도"))
    }

    @Test
    fun lowEstimatedCapacityIsTagged() {
        val now = at(2026, 10, 20, 12)
        val s = drain(now - 40 * q, 41, 100.0, 2.0).toMutableList()
        // 충전 세션: 10% → 90% 에 계수기 200,000µAh 만 늘었다 = 250 mAh (공칭의 56%)
        s += BatterySample(t = now + q, level = 10, chargeUah = 30_000, plugged = true)
        s += BatterySample(t = now + 5 * q, level = 90, chargeUah = 230_000, plugged = true)
        val a = DrainAnalyzer(445, zone).analyze(s, now + 5 * q)
        assertTrue(a.verdict.tags.contains("capacity"))
        assertEquals(250.0, a.capacity!!.estimatedMah, 1e-6)
        assertEquals(Grade.CAUTION, a.verdict.grade)
    }
}
