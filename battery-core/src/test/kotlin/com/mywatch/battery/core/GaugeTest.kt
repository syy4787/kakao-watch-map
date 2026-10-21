package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeTest {
    private val m = 60_000L
    private val t0 = 1_760_000_000_000L

    /** 충전기로는 오를 수 없을 만큼 짧은 사이에 오른 잔량만 점프 — 2분 만에 +5 */
    @Test
    fun levelRisingWithoutAChargerIsAJump() {
        val s = listOf(BatterySample(t0, 17), BatterySample(t0 + 2 * m, 22))
        val a = Gauge.anomalies(s, 445)
        assertEquals(listOf(AnomalyKind.JUMP_UP), a.map { it.kind })
        assertEquals(17, a[0].fromLevel); assertEquals(22, a[0].toLevel)
        assertTrue(Gauge.anomalies(listOf(BatterySample(t0, 17), BatterySample(t0 + 2 * m, 21)), 445).isEmpty())
    }

    /**
     * 15분 표본 사이에 잠깐(8분) 충전한 것은 기록되지 않는다 (충전기 꽂음 방송은 targetSdk 26+ 앱에 오지 않음) — 그 사이 충전으로
     * 오를 수 있는 만큼(분당 2%p)이면 점프가 아니다. 그걸 게이지 고장으로 읽어 서비스센터를 권하던 것 (점검에서 찾음)
     */
    @Test
    fun aRiseAChargerCouldExplainIsNotAJump() {
        assertTrue(Gauge.anomalies(listOf(BatterySample(t0, 57), BatterySample(t0 + 15 * m, 62)), 445).isEmpty())
        assertTrue(Gauge.anomalies(listOf(BatterySample(t0, 40), BatterySample(t0 + 15 * m, 69)), 445).isEmpty())
        // 15분에 +31 은 충전으로도 안 된다
        assertEquals(listOf(AnomalyKind.JUMP_UP), Gauge.anomalies(listOf(BatterySample(t0, 40), BatterySample(t0 + 15 * m, 71)), 445).map { it.kind })
    }

    @Test
    fun rebootThatRevivesTheLevelIsARebootJump() {
        val s = listOf(BatterySample(t0, 17), BatterySample(t0 + 30 * m, 47, event = Event.BOOT, plugged = false))
        val a = Gauge.anomalies(s, 445)
        assertEquals(listOf(AnomalyKind.REBOOT_JUMP), a.map { it.kind })
        // 충전기에 올려 켜진 경우: 꺼져 있던 동안 충전으로 오를 수 있는 만큼이면 점프가 아니다 (30분 +30)
        val plugged = listOf(BatterySample(t0, 17), BatterySample(t0 + 30 * m, 47, event = Event.BOOT, plugged = true))
        assertTrue(Gauge.anomalies(plugged, 445).isEmpty())
        // 꺼진 지 5분 만에 충전기 위에서 켰는데 +30 — 충전으로는 안 되는 점프
        val quick = listOf(BatterySample(t0, 17), BatterySample(t0 + 5 * m, 47, event = Event.BOOT, plugged = true))
        assertEquals(listOf(AnomalyKind.REBOOT_JUMP), Gauge.anomalies(quick, 445).map { it.kind })
        // 9포인트는 아직 아님
        assertTrue(Gauge.anomalies(listOf(BatterySample(t0, 17), BatterySample(t0 + 30 * m, 26, event = Event.BOOT)), 445).isEmpty())
    }

    @Test
    fun aCliffWhileTheScreenIsOff() {
        val s = listOf(BatterySample(t0, 40), BatterySample(t0 + 15 * m, 20))
        assertEquals(listOf(AnomalyKind.CLIFF), Gauge.anomalies(s, 445).map { it.kind })
        // 화면이 켜져 있었으면(영상 등) 급락이 아니다
        assertTrue(Gauge.anomalies(listOf(BatterySample(t0, 40), BatterySample(t0 + 15 * m, 20, interactive = true)), 445).isEmpty())
        // 20분 넘는 간격도 아니다
        assertTrue(Gauge.anomalies(listOf(BatterySample(t0, 40), BatterySample(t0 + 25 * m, 20)), 445).isEmpty())
    }

    @Test
    fun counterAndLevelDisagreeing() {
        // 표시는 20포인트 떨어졌는데 계수기는 22,250µAh(=5포인트)만 줄었다
        val s = listOf(BatterySample(t0, 60, chargeUah = 267_000), BatterySample(t0 + 60 * m, 40, chargeUah = 244_750))
        val a = Gauge.anomalies(s, 445)
        assertEquals(listOf(AnomalyKind.COUNTER_MISMATCH), a.map { it.kind })
        // 공칭 용량을 모르면 비교하지 않는다
        assertTrue(Gauge.anomalies(s, null).isEmpty())
        // 맞아떨어지면 조용하다 (20포인트 = 89,000µAh)
        val ok = listOf(BatterySample(t0, 60, chargeUah = 267_000), BatterySample(t0 + 60 * m, 40, chargeUah = 178_000))
        assertTrue(Gauge.anomalies(ok, 445).isEmpty())
    }

    @Test
    fun capacityFromAFullChargeSession() {
        val s = listOf(
            BatterySample(t0, 20, chargeUah = 89_000, plugged = true),
            BatterySample(t0 + 30 * m, 60, chargeUah = 267_000, plugged = true),
            BatterySample(t0 + 60 * m, 100, chargeUah = 445_000, plugged = true),
            BatterySample(t0 + 75 * m, 99, chargeUah = 440_000),
        )
        val c = Gauge.capacity(s, 445)!!
        assertEquals(445.0, c.estimatedMah, 1e-6)
        assertEquals(100.0, c.percentOfNominal!!, 1e-6)
        assertEquals(1, c.sessions)
    }

    @Test
    fun capacityIsTheMedianOfSessionsAndNeedsACounter() {
        val a = listOf(BatterySample(t0, 10, chargeUah = 30_000, plugged = true), BatterySample(t0 + 60 * m, 60, chargeUah = 190_000, plugged = true)) // 320 mAh
        val gap = listOf(BatterySample(t0 + 61 * m, 60, chargeUah = 190_000))
        val b = listOf(BatterySample(t0 + 120 * m, 20, chargeUah = 60_000, plugged = true), BatterySample(t0 + 180 * m, 70, chargeUah = 260_000, plugged = true)) // 400 mAh
        val gap2 = listOf(BatterySample(t0 + 181 * m, 70, chargeUah = 260_000))
        val c = listOf(BatterySample(t0 + 240 * m, 20, chargeUah = 60_000, plugged = true), BatterySample(t0 + 300 * m, 70, chargeUah = 282_500, plugged = true)) // 445 mAh
        val est = Gauge.capacity(a + gap + b + gap2 + c, 445)!!
        assertEquals(400.0, est.estimatedMah, 1e-6)
        assertEquals(3, est.sessions)
        // 40포인트 못 오른 세션은 안 쓴다
        assertNull(Gauge.capacity(listOf(BatterySample(t0, 50, chargeUah = 1, plugged = true), BatterySample(t0 + m, 80, chargeUah = 2, plugged = true)), 445))
        // 계수기 없음
        assertNull(Gauge.capacity(listOf(BatterySample(t0, 10, plugged = true), BatterySample(t0 + m, 90, plugged = true)), 445))
    }
}
