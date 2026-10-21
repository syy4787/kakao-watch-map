package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 밤사이(00~07시) 뒤에서 무엇이 배터리를 썼나 — 15분 차로. 삼성 헬스가 밤새 센서를 켜 두면 측정 설정을 알려 준다 */
class NightCulpritsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(h: Int, m: Int = 0, d: Int = 21) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** [from]~[to] 의 15분 차: 삼성 헬스 센서 2개 내내(15분에 30분), Play 서비스 조금 */
    private fun night(from: Long, to: Long, healthSensorMsPer15: Long = 30 * 60_000L): List<TimedUse> {
        val out = ArrayList<TimedUse>()
        var t = from + q
        while (t <= to) {
            out += TimedUse(t, q, listOf(
                UidUse("com.samsung.android.wear.shealth", sensorMs = healthSensorMsPer15, cpuMs = 20_000L),
                UidUse("com.google.android.gms", cpuMs = 10_000L, syncs = 1),
            ))
            t += q
        }
        return out
    }

    @Test
    fun lastNightRanksTheBackgroundUsersAndAdvisesOnHealthSensors() {
        val r = NightCulprits.lastNight(night(at(0), at(7)), at(12), zone)!!
        assertEquals(at(0), r.start)
        assertEquals(at(7), r.end)
        assertEquals("com.samsung.android.wear.shealth", r.ranks.first().use.pkg)
        assertTrue(r.advice.toString(), r.advice.single().contains("혈중 산소"))
        assertTrue(r.advice.toString(), r.advice.single().contains("시간당 120분"))
    }

    @Test
    fun aLightHealthLoadNeedsNoAdvice() {
        val r = NightCulprits.lastNight(night(at(0), at(7), healthSensorMsPer15 = 5 * 60_000L), at(12), zone)!!
        assertTrue(r.advice.isEmpty())
    }

    @Test
    fun duringTheNightItUsesTheNightSoFar() {
        val r = NightCulprits.lastNight(night(at(0), at(3)), at(3, 5), zone)!!
        assertEquals(at(0), r.start)
        assertEquals(at(3, 5), r.end)
    }

    @Test
    fun tooLittleCoverageIsNothing() {
        assertNull(NightCulprits.lastNight(night(at(0), at(1)), at(12), zone))
        assertNull(NightCulprits.lastNight(emptyList(), at(12), zone))
    }
}
