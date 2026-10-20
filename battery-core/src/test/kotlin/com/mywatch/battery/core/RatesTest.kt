package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatesTest {
    private val h = 3_600_000L
    private val q = 15 * 60_000L
    private val t0 = 1_760_000_000_000L

    /** 15분 간격으로 level 을 하나씩, 전부 화면 꺼짐·충전기 없음 */
    private fun every15min(vararg levels: Int, interactive: Boolean = false) =
        levels.mapIndexed { i, l -> BatterySample(t = t0 + i * q, level = l, interactive = interactive) }

    @Test
    fun steadyDrainGivesPercentPerHour() {
        val s = every15min(100, 97, 94, 91, 88)   // 1시간에 12
        val r = Rates.rate(Rates.dischargeSegments(s))
        assertEquals(12.0, r.percentPerHour!!, 1e-9)
        assertEquals(1.0, r.hours, 1e-9)
        assertEquals(12.0, r.dropPercent, 1e-9)
        assertNull(r.mahPerHour)
    }

    @Test
    fun chargingAndRisingSamplesBreakTheSegments() {
        val s = every15min(100, 97, 94).toMutableList()
        s += BatterySample(t = t0 + 3 * q, level = 93, plugged = true)
        s += BatterySample(t = t0 + 4 * q, level = 99, plugged = true)
        s += BatterySample(t = t0 + 5 * q, level = 98)
        s += BatterySample(t = t0 + 6 * q, level = 97)
        val segs = Rates.dischargeSegments(s)
        assertEquals(3, segs.size)   // 100→97, 97→94, 98→97
    }

    @Test
    fun gapsLongerThanTwoHoursAreNotDischarge() {
        val s = listOf(BatterySample(t0, 80), BatterySample(t0 + 3 * h, 40))
        assertEquals(0, Rates.dischargeSegments(s).size)
    }

    @Test
    fun tooShortToJudge() {
        val s = every15min(100, 99)
        val r = Rates.rate(Rates.dischargeSegments(s))
        assertNull(r.percentPerHour)
        assertEquals(0.25, r.hours, 1e-9)
    }

    @Test
    fun windowClipsSegmentsProportionally() {
        val s = listOf(BatterySample(t0, 100), BatterySample(t0 + 2 * h, 90))   // 2시간에 10 = 5%/h
        val r = Rates.window(s, now = t0 + 3 * h, hours = 2)   // 창 [t0+1h, t0+3h] 와 절반 겹침
        assertEquals(1.0, r.hours, 1e-9)
        assertEquals(5.0, r.dropPercent, 1e-9)
        assertEquals(5.0, r.percentPerHour!!, 1e-9)
    }

    @Test
    fun milliampHoursComeFromTheChargeCounter() {
        val s = listOf(
            BatterySample(t0, 100, chargeUah = 445_000),
            BatterySample(t0 + h, 90, chargeUah = 400_500),   // 44.5 mAh
        )
        val r = Rates.rate(Rates.dischargeSegments(s))
        assertEquals(10.0, r.percentPerHour!!, 1e-9)
        assertEquals(44.5, r.mahPerHour!!, 1e-9)
    }

    @Test
    fun idleWindowIgnoresScreenOnSegments() {
        val on = every15min(100, 90, 80, 70, 60, interactive = true)   // 40%/h 화면 켠 채
        val off = (0 until 5).map { BatterySample(t = t0 + (4 + it) * q, level = 60 - it, interactive = false) }  // 4%/h
        val all = (on + off).distinctBy { it.t }
        val r = Rates.idleWindow(all, now = t0 + 8 * q, hours = 24)
        assertEquals(4.0, r.percentPerHour!!, 1e-9)
    }
}
