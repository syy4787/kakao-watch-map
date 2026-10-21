package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 재시작 효과: 시킨 대로 강제 재시작한 뒤 대기(화면 꺼짐·충전 안 함) 소모가 줄었는지 — 재시작 전 12시간과 재시작 30분 뒤부터를 견준다.
 * 재시작을 걸친 구간·부팅 표본에서 시작하는 구간(게이지가 잔량을 다시 잼)은 어느 쪽에도 넣지 않는다
 */
class RestartEffectsTest {
    private val h = 3_600_000L
    private val q = 15 * 60_000L
    private val t0 = 1_792_000_000_000L

    /** [from]~[to] 15분마다 화면 꺼진 채 [pph]%/h */
    private fun idle(from: Long, to: Long, startLevel: Double, pph: Double): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        var t = from
        while (t <= to) {
            out += BatterySample(t = t, level = (startLevel - pph * (t - from) / h).toInt())
            t += q
        }
        return out
    }

    private fun boot(t: Long, level: Int) = BatterySample(t = t, level = level, event = Event.BOOT)

    @Test
    fun aRestartThatHelpedShowsTheDrop() {
        val b = t0 + 12 * h
        val samples = idle(t0, b - q, 90.0, 4.0) + boot(b, 42) + idle(b + q, b + 3 * h + 30 * 60_000L, 42.0, 1.0)
        val e = RestartEffects.last(samples, b + 3 * h + 30 * 60_000L)!!
        assertEquals(b, e.bootAt)
        assertEquals(4.0, e.before.percentPerHour!!, 0.3)
        assertEquals(1.0, e.after.percentPerHour!!, 0.3)
        assertEquals(RestartEffect.Verdict.BETTER, e.verdict)
        assertEquals("재시작 뒤 대기 시간당 1.0% (그 전 4.0%) — 줄었습니다", e.line)
    }

    @Test
    fun aRestartThatMadeItWorseSaysSo() {
        val b = t0 + 12 * h
        val samples = idle(t0, b - q, 90.0, 1.0) + boot(b, 78) + idle(b + q, b + 6 * h, 78.0, 4.0)
        val e = RestartEffects.last(samples, b + 6 * h)!!
        assertEquals(RestartEffect.Verdict.WORSE, e.verdict)
        assertTrue(e.line, e.line.endsWith("— 늘었습니다"))
    }

    /** '그 뒤'는 재시작 뒤 12시간까지만 — 며칠 뒤 다시 나빠진 것까지 평균해 '줄었습니다'로 남지 않게 (리뷰) */
    @Test
    fun theAfterWindowIsTwelveHours() {
        val b = t0 + 12 * h
        val after = idle(b + q, b + 12 * h + 30 * 60_000L, 60.0, 1.0)
        val later = idle(b + 12 * h + 45 * 60_000L, b + 16 * h, 47.0, 4.0)
        val e = RestartEffects.last(idle(t0, b - q, 98.0, 4.0) + boot(b, 60) + after + later, b + 16 * h)!!
        assertEquals(1.0, e.after.percentPerHour!!, 0.2)
        assertEquals(RestartEffect.Verdict.BETTER, e.verdict)
        // 이틀이 지나면 보이지 않는다
        assertNull(RestartEffects.last(idle(t0, b - q, 98.0, 4.0) + boot(b, 60) + after, b + 49 * h))
    }

    @Test
    fun aRestartThatDidNotHelpSaysSo() {
        val b = t0 + 12 * h
        val samples = idle(t0, b - q, 90.0, 4.0) + boot(b, 42) + idle(b + q, b + 4 * h, 42.0, 4.0)
        val e = RestartEffects.last(samples, b + 4 * h)!!
        assertEquals(RestartEffect.Verdict.SAME, e.verdict)
        assertEquals("재시작 뒤 대기 시간당 4.0% (그 전 4.0%) — 비슷합니다", e.line)
    }

    @Test
    fun tooEarlyAfterTheRestartWaits() {
        val b = t0 + 12 * h
        val samples = idle(t0, b - q, 90.0, 4.0) + boot(b, 42) + idle(b + q, b + 90 * 60_000L, 42.0, 1.0)
        val e = RestartEffects.last(samples, b + 90 * 60_000L)!!
        assertNull(e.verdict)
        assertTrue(e.line, e.line.startsWith("재시작 뒤 대기 기록이 2시간 쌓이면"))
    }

    /** 재시작 순간의 잔량 점프(게이지가 다시 잼)는 소모로 세지 않는다 — 50%에서 꺼졌다 30%로 켜져도 */
    @Test
    fun theJumpAcrossTheRestartIsNotCounted() {
        val b = t0 + 12 * h
        val samples = idle(t0, b - q, 98.0, 4.0) + boot(b, 30) + idle(b + q, b + 4 * h, 30.0, 4.0)
        val e = RestartEffects.last(samples, b + 4 * h)!!
        assertEquals(4.0, e.before.percentPerHour!!, 0.3)
        assertEquals(4.0, e.after.percentPerHour!!, 0.4)
    }

    /** 재시작 전 대기 기록이 모자라면(밤새 꺼져 있었음·충전 중이었음) 견줄 수 없어 아예 보이지 않는다 (리뷰) */
    @Test
    fun withoutEnoughBeforeThereIsNothingToShow() {
        val b = t0 + 12 * h
        val samples = idle(b - h, b - q, 50.0, 4.0) + boot(b, 46) + idle(b + q, b + 5 * h, 46.0, 1.0)
        assertNull(RestartEffects.last(samples, b + 5 * h))
    }

    /**
     * 밀린 15분 기록이 부팅 기록보다 먼저 남으면(부팅 기록은 한 번짜리 작업이라 늦을 수 있다) 재시작을 걸친 구간이 부팅 기록 앞에서 끝난다 —
     * 실제 부팅 때(켜진 뒤 시간으로 본 것)를 함께 주면 그 구간도 빼고, 모르면 부팅 기록 20분 안에서 끝나는 구간을 뺀다 (리뷰)
     */
    @Test
    fun aLateBootRowDoesNotLeakTheJumpIntoBefore() {
        val b = t0 + 12 * h
        val before = idle(t0, b - q, 90.0, 1.0)          // 실제로 시간당 1%
        val periodic = BatterySample(t = b + 2 * 60_000L, level = 70)   // 재시작 뒤 밀린 기록 (게이지가 8포인트 낮게 다시 잼)
        val bootRow = boot(b + 5 * 60_000L, 70)
        val after = idle(b + 5 * 60_000L + q, b + 8 * h, 70.0, 1.0)
        val samples = before + periodic + bootRow + after
        for (hint in listOf(b, null)) {
            val e = RestartEffects.last(samples, b + 8 * h, bootHint = hint)!!
            assertEquals("hint=$hint", 1.0, e.before.percentPerHour!!, 0.25)
            assertEquals("hint=$hint", RestartEffect.Verdict.SAME, e.verdict)
        }
    }

    /** 한 번 더 강제 재시작한 경우: '그 전'은 앞 재시작 30분 뒤부터 (앞 부팅 직후의 동기화를 '그 전'에 넣지 않게) */
    @Test
    fun anEarlierRestartBoundsTheBeforeWindow() {
        val b1 = t0 + 6 * h
        val b2 = t0 + 12 * h
        val samples = idle(t0, b1 - q, 99.0, 2.0) + boot(b1, 87) +
            listOf(BatterySample(t = b1 + q, level = 80)) +                         // 앞 부팅 직후 동기화로 7포인트
            idle(b1 + 30 * 60_000L, b2 - q, 80.0, 2.0) + boot(b2, 69) + idle(b2 + q, b2 + 6 * h, 69.0, 2.0)
        val e = RestartEffects.last(samples, b2 + 6 * h)!!
        assertEquals(2.0, e.before.percentPerHour!!, 0.3)
        assertEquals(RestartEffect.Verdict.SAME, e.verdict)
    }

    /** 잔량이 1% 단위라 짧은 창의 계단 오차를 '늘었습니다'로 읽지 않는다 — 양쪽 시간당 0.6%면 2시간째에도 '비슷합니다' (리뷰) */
    @Test
    fun gaugeStepsAreNotReadAsAChange() {
        val b = t0 + 12 * h
        for (phase in 0 until 4) {
            val shift = phase * 15 * 60_000L / 4
            val samples = idle(t0 + shift, b - q, 90.4, 0.6) + boot(b, 83) + idle(b + q + shift, b + 30 * 60_000L + 2 * h + shift, 83.9, 0.6)
            val e = RestartEffects.last(samples, b + 30 * 60_000L + 2 * h + shift)!!
            assertTrue("phase $phase: ${e.verdict} ${e.after}", e.verdict == null || e.verdict == RestartEffect.Verdict.SAME)
        }
    }

    /** 충전 중·화면 켜진 구간은 대기가 아니다 */
    @Test
    fun chargingAndScreenOnAreNotIdle() {
        val b = t0 + 12 * h
        val charging = (1..4).map { BatterySample(t = b + it * q, level = 40 + it * 5, plugged = true) }
        val screen = (1..4).map { BatterySample(t = b + 2 * h + it * q, level = 60 - it * 3, interactive = true) }
        val samples = idle(t0, b - q, 90.0, 4.0) + boot(b, 40) + charging + screen + idle(b + 3 * h + q, b + 7 * h, 48.0, 1.0)
        val e = RestartEffects.last(samples, b + 7 * h)!!
        assertEquals(1.0, e.after.percentPerHour!!, 0.3)
    }

    @Test
    fun noRecentRestartNoEffect() {
        assertNull(RestartEffects.last(idle(t0, t0 + 10 * h, 90.0, 2.0), t0 + 10 * h))
        val old = boot(t0, 80)
        val samples = listOf(old) + idle(t0 + q, t0 + 8 * 24 * h, 80.0, 0.3)
        assertNull("일주일 넘은 재시작", RestartEffects.last(samples, t0 + 8 * 24 * h))
    }
}
