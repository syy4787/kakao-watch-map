package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 충전 습관: 충전기에 100%로 오래 머무는지(배터리 보호 90% 권함 — 워치8부터 설정 > 배터리 > 배터리 보호), 충전 중 온도, 하루 충전 횟수.
 * 배터리 수명(용량이 줄어드는 속도)에 관한 것 — 하루 소모와는 따로
 */
class ChargeHabitsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** [days]일 동안 매일 밤 22시~07시 충전(빨리 100% 가 되어 그대로 [fullTo]%), 낮엔 15분마다 1%씩 */
    private fun nights(days: Int, fullTo: Int = 100, tempDeciC: Int = 330): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        for (d in 0 until days) {
            var level = 95
            var t = at(10 + d, 7)
            while (t < at(10 + d, 22)) { out += BatterySample(t, level, tempDeciC = 300); level = (level - 1).coerceAtLeast(20); t += q }
            while (t < at(11 + d, 7)) {
                level = minOf(fullTo, level + 10)
                out += BatterySample(t, level, plugged = true, status = if (level >= 100) Status.FULL else Status.CHARGING, tempDeciC = tempDeciC)
                t += q
            }
        }
        return out
    }

    @Test
    fun hoursAtFullOnTheChargerAskForBatteryProtection() {
        val s = nights(7)
        val h = ChargeHabits.analyze(s, at(17, 7))!!
        assertEquals(7, h.sessions)
        assertTrue(h.hoursAtFullPerDay.toString(), h.hoursAtFullPerDay in 6.0..9.0)
        assertFalse(h.protectLikely)
        assertTrue(h.advice.joinToString(), h.advice.any { it.contains("배터리 보호") })
    }

    @Test
    fun aWatchThatStopsAtNinetyAlreadyHasProtectionOn() {
        val h = ChargeHabits.analyze(nights(5, fullTo = 90), at(15, 7))!!
        assertTrue(h.protectLikely)
        assertEquals(0.0, h.hoursAtFullPerDay, 0.01)
        assertTrue(h.advice.none { it.contains("배터리 보호를 켜") })
    }

    @Test
    fun hotChargingIsCalledOut() {
        val h = ChargeHabits.analyze(nights(3, tempDeciC = 445), at(13, 7))!!
        assertEquals(44.5, h.maxChargeTempC!!, 0.01)
        assertTrue(h.advice.joinToString(), h.advice.any { it.contains("44.5℃") })
    }

    @Test
    fun withoutAnyChargingThereIsNothingToSay() {
        val s = (0..40).map { BatterySample(at(10, 7) + it * q, 90 - it / 2) }
        assertNull(ChargeHabits.analyze(s, at(10, 18)))
    }
}
