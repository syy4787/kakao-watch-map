package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 배터리 사용 내역: 하루 동안 줄어든 잔량을 '화면 켜진 동안'과 '화면 꺼진 동안'으로 나누고,
 * 가장 많이 닳은 시간대를 그때 화면에 떠 있던 앱·뒤에서 돈 앱과 함께 보여 준다.
 */
class UsageSplitTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(h: Int, m: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(23, 0)

    /** 화면 켜진 구간: 08:00~08:30, 12:00~12:20, 19:00~19:40 (합 1.5시간) */
    private val screen = listOf(Span(at(8), at(8, 30)), Span(at(12), at(12, 20)), Span(at(19), at(19, 40)))

    /** 24시간 15분 표본 — 화면 꺼진 동안 시간당 2%, 켜진 동안 30% */
    private fun day(start: Double = 100.0, charging: Span? = null): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        var level = start
        var t = now - 24 * 3_600_000L
        while (t <= now) {
            val plugged = charging != null && t >= charging.start && t < charging.end
            out += BatterySample(t, level.toInt(), plugged = plugged, interactive = screen.any { t >= it.start && t < it.end })
            // 다음 15분 동안 1분씩 쌓기
            for (k in 0 until 15) {
                val m = t + k * 60_000L
                level -= if (plugged) 0.0 else if (screen.any { m >= it.start && m < it.end }) 0.5 else 2.0 / 60
            }
            t += q
        }
        return out
    }

    @Test
    fun splitsTheDayIntoScreenAndIdle() {
        val b = UsageSplit.analyze(day(), screen, emptyList(), emptyList(), now, zone)!!
        assertTrue(b.exactScreen)
        assertEquals(1.5, b.screenOnHours!!, 0.01)
        assertEquals(22.5, b.screenOffHours!!, 0.3)
        assertEquals(2.0, b.idlePph!!, 0.35)
        assertEquals(30.0, b.screenPph!!, 4.0)
        // 화면 1.5시간 × 30% ≈ 45, 대기 22.5시간 × 2% ≈ 45 → 반반
        assertEquals(0.5, b.screenShare!!, 0.08)
        assertTrue(b.headline, b.headline.contains("화면"))
    }

    @Test
    fun steepestHoursComeWithTheAppOnScreenAndTheBackgroundCulprit() {
        val apps = listOf(AppSpan("com.google.android.youtube", at(19), at(19, 40)), AppSpan("com.samsung.health", at(8), at(8, 30)))
        val usage = listOf(15, 30, 45, 60).map { m ->
            TimedUse(at(19) + m * 60_000L, q, listOf(UidUse("com.google.android.gms", cpuMs = 3 * 60_000L), UidUse("com.quiet", cpuMs = 1_000)))
        }
        val b = UsageSplit.analyze(day(), screen, apps, usage, now, zone)!!
        val top = b.steepest.first()
        assertEquals(at(19), top.start)
        assertEquals(at(20), top.end)
        assertEquals(40 * 60_000L, top.screenOnMs)
        assertEquals("com.google.android.youtube", top.topApp)
        assertEquals("com.google.android.gms", top.culprit!!.use.pkg)
        assertTrue(top.pph > 15)
        assertTrue(b.steepest.size <= 3)
        // 그다음은 아침(30분 화면)
        assertEquals(at(8), b.steepest[1].start)
    }

    @Test
    fun withoutUsageStatsOnlyTheHoursAreShown() {
        val b = UsageSplit.analyze(day(), null, emptyList(), emptyList(), now, zone)!!
        assertTrue(!b.exactScreen)
        assertNull(b.screenOnHours)
        assertNull(b.screenShare)
        assertNotNull(b.idlePph)            // 표본의 화면 상태로 본 대기 구간
        assertEquals(at(19), b.steepest.first().start)
        assertNull(b.steepest.first().screenOnMs)
    }

    @Test
    fun chargingHoursAreLeftOut() {
        val b = UsageSplit.analyze(day(charging = Span(at(19), at(21))), screen, emptyList(), emptyList(), now, zone)!!
        assertTrue(b.steepest.none { it.start == at(19) || it.start == at(20) })
        assertTrue(b.coveredHours < 22.5)
    }

    @Test
    fun tooLittleDataSaysNothing() {
        val few = listOf(BatterySample(now - q, 50), BatterySample(now, 50))
        assertNull(UsageSplit.analyze(few, screen, emptyList(), emptyList(), now, zone))
    }
}
