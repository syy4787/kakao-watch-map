package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeDriftTest {
    private val q = 15 * 60_000L
    private val now = 1_760_000_000_000L

    @Test
    fun ocvTablesInterpolateAndClamp() {
        assertEquals(100, GaugeDriftRule.socOf(4400, GaugeDriftRule.OCV_435))
        assertEquals(0, GaugeDriftRule.socOf(3400, GaugeDriftRule.OCV_435))
        assertEquals(52, GaugeDriftRule.socOf(4000, GaugeDriftRule.OCV_435))
        assertEquals(48, GaugeDriftRule.socOf(3975, GaugeDriftRule.OCV_435))   // 4000(52)~3950(44) 가운데
        assertEquals(72, GaugeDriftRule.socOf(4000, GaugeDriftRule.OCV_42))
        // 완충 전압으로 셀 종류 고르기
        assertSame(GaugeDriftRule.OCV_42, GaugeDriftRule.table(4195))
        assertSame(GaugeDriftRule.OCV_435, GaugeDriftRule.table(4340))
        assertSame(GaugeDriftRule.OCV_435, GaugeDriftRule.table(null))
    }

    private fun assertSame(a: Any, b: Any) = assertTrue(a === b)

    /** 쉬는 표본 8개: 표시 잔량은 [levels], 전압은 [mv] */
    private fun resting(levels: List<Int>, mv: Int, current: Int? = -20_000) =
        levels.mapIndexed { i, l -> BatterySample(t = now - (levels.size - 1 - i) * q, level = l, voltageMv = mv, currentUa = current, interactive = false) }

    @Test
    fun gaugeReadingFarBelowTheVoltageIsFlaggedAsDrift() {
        // 3.95V 쉬는 전압(고전압 셀 기준 약 44%)인데 표시는 20% 안팎 → 24포인트 낮게 세는 중
        val s = resting(listOf(22, 22, 21, 21, 20, 20, 19, 19), 3950)
        val d = GaugeDriftRule.estimate(s, now)!!
        assertTrue(d.driftPoints.toString(), d.driftPoints <= -20)
        assertTrue(d.certain)
        assertTrue(d.headline, d.headline.contains("낮게 세는 중"))
        assertNotNull(d.advice)
        assertEquals("중간", d.confidence)   // 표본 8개, 다른 증거 없음
        assertTrue(GaugeDriftRule.shouldNotify(d, now, null))
        assertFalse(GaugeDriftRule.shouldNotify(d, now, now - 3_600_000L))   // 12시간 쿨다운
    }

    @Test
    fun agreementIsNotDrift() {
        val s = resting(listOf(45, 45, 44, 44, 44, 43, 43, 43), 3950)   // 44% 쯤 — 맞음
        val d = GaugeDriftRule.estimate(s, now)!!
        assertTrue(d.driftPoints.toString(), kotlin.math.abs(d.driftPoints) < GaugeDriftRule.SUSPECT_POINTS)
        assertFalse(d.suspicious)
        assertNull(d.advice)
        assertFalse(GaugeDriftRule.shouldNotify(d, now, null))
    }

    @Test
    fun screenOnOrChargingOrHighCurrentSamplesAreNotRest() {
        val busy = resting(listOf(20, 20, 20, 20), 3950, current = -250_000)   // 영상 재생 중 전류
        assertNull(GaugeDriftRule.estimate(busy, now))
        val plugged = resting(listOf(20, 20, 20, 20), 3950).map { it.copy(plugged = true) }
        assertNull(GaugeDriftRule.estimate(plugged, now))
        val on = resting(listOf(20, 20, 20, 20), 3950).map { it.copy(interactive = true) }
        assertNull(GaugeDriftRule.estimate(on, now))
    }

    @Test
    fun aRebootJumpRaisesConfidenceAndIsListedAsRecovery() {
        val s = resting(listOf(22, 22, 21, 21, 20, 20, 19, 17), 3950).toMutableList()
        s += BatterySample(t = now + q, level = 47, voltageMv = 3950, event = Event.BOOT)
        val anomalies = Gauge.anomalies(s, 445)
        val d = GaugeDriftRule.estimate(s, now + q, anomalies)!!
        assertEquals("높음", d.confidence)
        val r = GaugeDriftRule.recoveries(s, anomalies).single()
        assertEquals(17, r.before); assertEquals(47, r.after); assertEquals(30, r.gained); assertFalse(r.userMarked)
    }

    @Test
    fun aUserMarkedForcedRestartCountsAsRecovery() {
        val s = listOf(
            BatterySample(now - 2 * q, 18, voltageMv = 3940),
            BatterySample(now - q, 17, voltageMv = 3940),
            BatterySample(now, 17, voltageMv = 3940, event = Event.USER_NOTE, note = GaugeDriftRule.NOTE_FORCED_RESTART),
            BatterySample(now + 5 * 60_000L, 46, voltageMv = 3940, event = Event.PERIODIC),
        )
        val r = GaugeDriftRule.recoveries(s, Gauge.anomalies(s, 445)).single()
        assertTrue(r.userMarked)
        assertEquals(17, r.before); assertEquals(46, r.after)
    }

    @Test
    fun aMarkerPressedAfterTheRestartUsesTheSampleBeforeTheBoot() {
        // 17% 에서 강제 재시작 → 부팅 표본 24% (+7, 재부팅 점프 기준 10 미만이라 이상으로는 안 잡힘) → 앱을 열어 표시
        val s = listOf(
            BatterySample(now - 2 * q, 18, voltageMv = 3940),
            BatterySample(now - q, 17, voltageMv = 3940),
            BatterySample(now - 5 * 60_000L, 24, voltageMv = 3940, event = Event.BOOT),
            BatterySample(now, 24, voltageMv = 3940, event = Event.USER_NOTE, note = GaugeDriftRule.NOTE_FORCED_RESTART),
        )
        val anomalies = Gauge.anomalies(s, 445)
        assertTrue(anomalies.toString(), anomalies.none { it.kind == AnomalyKind.REBOOT_JUMP })
        val r = GaugeDriftRule.recoveries(s, anomalies).single()
        assertTrue(r.userMarked)
        assertEquals(17, r.before); assertEquals(24, r.after)
    }

    /** 완충 전압을 아직 못 봤으면 두 표 가운데 차이가 작은 쪽 — 4.2V 셀이 4.0V 에서 72% 를 보여도 헛소리하지 않는다 */
    @Test
    fun withoutAFullChargeTheMoreCharitableTableWins() {
        val s = resting(listOf(72, 72, 72, 71, 71, 71), 4000)
        val d = GaugeDriftRule.estimate(s, now)!!
        assertFalse(d.headline, d.suspicious)
        // 완충 때 4.34V 를 봤으면 고전압 셀이 확실 → 같은 표본이 '높게 세는 중'(+20)
        val withFull = listOf(BatterySample(now - 24 * 3_600_000L, 100, voltageMv = 4340, status = Status.FULL, plugged = true)) + s
        val d2 = GaugeDriftRule.estimate(withFull, now)!!
        assertTrue(d2.headline, d2.driftPoints >= GaugeDriftRule.SUSPECT_POINTS)
        assertTrue(d2.headline, d2.headline.contains("높게 세는 중"))
    }

    @Test
    fun threeRestSamplesAreTooFewToNotify() {
        val s = resting(listOf(20, 20, 19), 3950)
        val d = GaugeDriftRule.estimate(s, now)!!
        assertEquals("낮음", d.confidence)
        assertTrue(d.certain)
        assertFalse(GaugeDriftRule.shouldNotify(d, now, null))
    }

    @Test
    fun theAnalyzerCarriesDriftIntoTheVerdict() {
        // 하루치 정상 소모지만 게이지가 낮게 세는 중
        val samples = (0..96).map { i -> BatterySample(t = now - (96 - i) * q, level = (60 - 2.5 * i / 4).toInt(), voltageMv = 3950, currentUa = -15_000) }
        val a = DrainAnalyzer(445).analyze(samples, now)
        assertNotNull(a.drift)
        assertTrue(a.verdict.tags.contains("drift"))
        assertTrue(a.verdict.headline, a.verdict.headline.startsWith("잔량 표시 오차 의심"))
        assertTrue(a.verdict.reasons.any { it.contains("낮게 세는 중") })
    }

    /** 화면 꺼진 채 40℃ 넘는 표본이 1시간어치(4개) 넘으면 백그라운드 과열 — 가짜가 아니라 진짜 소모라는 신호 */
    @Test
    fun aHotWatchWithTheScreenOffIsFlagged() {
        val cool = (0..96).map { i -> BatterySample(t = now - (96 - i) * q, level = (90 - 2.5 * i / 4).toInt(), tempDeciC = 280) }
        assertFalse(DrainAnalyzer(445).analyze(cool, now).verdict.tags.contains("hot"))
        val hot = cool.mapIndexed { i, s -> if (i >= 90) s.copy(tempDeciC = 425) else s }
        val a = DrainAnalyzer(445).analyze(hot, now)
        assertEquals(7, a.hotIdleCount)
        assertTrue(a.verdict.tags.contains("hot"))
        assertTrue(a.verdict.reasons.toString(), a.verdict.reasons.any { it.contains("40℃") })
        assertEquals(Grade.CAUTION, a.verdict.grade)
        // 충전 중이거나 화면 켠 채 뜨거운 건 세지 않는다
        val charging = cool.mapIndexed { i, s -> if (i >= 90) s.copy(tempDeciC = 425, plugged = true) else s }
        assertEquals(0, DrainAnalyzer(445).analyze(charging, now).hotIdleCount)
    }

    @Test
    fun phantomDrainIsCalledOutWhenTheCounterDisagrees() {
        // 표시는 시간당 6% 씩 떨어지는데 전하량은 시간당 13 mAh(≈2.9%) 만 줄어든다
        val samples = (0..16).map { i ->
            BatterySample(t = now - (16 - i) * q, level = (90 - 6.0 * i / 4).toInt(), chargeUah = 400_000 - i * 3_250, voltageMv = 4000, currentUa = -15_000)
        }
        val a = DrainAnalyzer(445).analyze(samples, now)
        assertTrue(a.verdict.reasons.toString(), a.verdict.reasons.any { it.contains("가짜 소모") })
        assertTrue(a.verdict.tags.contains("phantom"))
    }
}
