package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 게이지 보정 루틴: 1단계 완충 뒤 1시간 유지 → 2단계 10% 아래까지 방전 → 3단계 다시 완충 뒤 1시간 유지 → 끝.
 * 15분 표본만 보고 단계를 넘기고, 단계가 바뀔 때만 알린다
 */
class CalibrationRuleTest {
    private val t0 = 1_760_000_000_000L
    private val min = 60_000L

    private fun full(t: Long) = BatterySample(t, 100, status = Status.FULL, plugged = true)
    private fun charging(t: Long, level: Int) = BatterySample(t, level, status = Status.CHARGING, plugged = true)
    private fun using(t: Long, level: Int) = BatterySample(t, level, status = Status.DISCHARGING, plugged = false)

    @Test
    fun startingRemembersTheDriftAndWaitsForAFullCharge() {
        val st = CalibrationRule.start(t0, driftPoints = -24)
        assertEquals(1, st.step)
        assertEquals(-24, st.driftBefore)
        val (actions, next) = CalibrationRule.decide(t0 + 15 * min, st, using(t0 + 15 * min, 60))
        assertTrue(actions.isEmpty())
        assertEquals(st, next)
        // 충전 중이지만 아직 100% 아님
        val (a2, n2) = CalibrationRule.decide(t0 + 30 * min, st, charging(t0 + 30 * min, 90))
        assertTrue(a2.isEmpty()); assertEquals(0L, n2.fullSince)
    }

    @Test
    fun holdingFullForAnHourFinishesStepOne() {
        var st = CalibrationRule.start(t0, null)
        val (a1, s1) = CalibrationRule.decide(t0 + 60 * min, st, full(t0 + 60 * min))
        assertEquals(listOf(CalibrationAction.HoldFull(1)), a1)
        assertEquals(t0 + 60 * min, s1.fullSince)
        st = s1
        val (a2, s2) = CalibrationRule.decide(t0 + 90 * min, st, full(t0 + 90 * min))
        assertTrue(a2.isEmpty()); assertEquals(1, s2.step)
        val (a3, s3) = CalibrationRule.decide(t0 + 120 * min, s2, full(t0 + 120 * min))
        assertEquals(listOf(CalibrationAction.StepDone(1)), a3)
        assertEquals(2, s3.step)
        assertEquals(0L, s3.fullSince)
    }

    @Test
    fun unpluggingEarlyRestartsTheHold() {
        val st = CalibrationRule.start(t0, null)
        val (_, s1) = CalibrationRule.decide(t0, st, full(t0))
        val (a2, s2) = CalibrationRule.decide(t0 + 20 * min, s1, using(t0 + 20 * min, 100))
        assertTrue(a2.isEmpty()); assertEquals(0L, s2.fullSince); assertEquals(1, s2.step)
        val (a3, s3) = CalibrationRule.decide(t0 + 40 * min, s2, full(t0 + 40 * min))
        assertEquals(listOf(CalibrationAction.HoldFull(1)), a3)
        assertEquals(t0 + 40 * min, s3.fullSince)
    }

    /**
     * 충전기 위에서 100% → 99%(잠깐 '충전 중') → 100% 로 오가는 것은 뺀 것이 아니다 — 1시간 유지를 처음부터 다시 세거나
     * '완충 — 1시간 더' 알림을 밤새 되풀이하지 않는다 (점검에서 찾음)
     */
    @Test
    fun aTopUpOnTheChargerDoesNotRestartTheHold() {
        val st = CalibrationRule.start(t0, null)
        val (_, s1) = CalibrationRule.decide(t0, st, full(t0))
        val (a2, s2) = CalibrationRule.decide(t0 + 15 * min, s1, charging(t0 + 15 * min, 99))
        assertTrue(a2.isEmpty()); assertEquals(t0, s2.fullSince)
        val (a3, s3) = CalibrationRule.decide(t0 + 30 * min, s2, full(t0 + 30 * min))
        assertTrue("다시 '완충' 알림 없음", a3.isEmpty()); assertEquals(t0, s3.fullSince)
        val (a4, _) = CalibrationRule.decide(t0 + 60 * min, s3, full(t0 + 60 * min))
        assertEquals(listOf(CalibrationAction.StepDone(1)), a4)
        // 충전기 위라도 한참 내려가면(95%) 완충이 아니다 — 다시 센다
        val (_, s5) = CalibrationRule.decide(t0 + 15 * min, s1, charging(t0 + 15 * min, 95))
        assertEquals(0L, s5.fullSince)
    }

    @Test
    fun drainingBelowTenPercentFinishesStepTwo() {
        val st = CalibrationRule.start(t0, null).copy(step = 2)
        val (a1, s1) = CalibrationRule.decide(t0, st, using(t0, 12))
        assertTrue(a1.isEmpty()); assertEquals(2, s1.step)
        // 10% 위에서 충전기에 올려도 단계는 그대로 (다시 빼서 쓰면 이어진다)
        val (a2, s2) = CalibrationRule.decide(t0 + 15 * min, s1, charging(t0 + 15 * min, 30))
        assertTrue(a2.isEmpty()); assertEquals(2, s2.step)
        val (a3, s3) = CalibrationRule.decide(t0 + 30 * min, s2, using(t0 + 30 * min, 9))
        assertEquals(listOf(CalibrationAction.StepDone(2)), a3)
        assertEquals(3, s3.step)
    }

    @Test
    fun theSecondFullHoldCompletesTheRoutine() {
        val st = CalibrationRule.start(t0, -24).copy(step = 3)
        val (a1, s1) = CalibrationRule.decide(t0, st, full(t0))
        assertEquals(listOf(CalibrationAction.HoldFull(3)), a1)
        val (a2, s2) = CalibrationRule.decide(t0 + 61 * min, s1, full(t0 + 61 * min))
        assertEquals(listOf(CalibrationAction.Done(-24)), a2)
        assertEquals(CalibrationRule.STEP_DONE, s2.step)
        assertEquals(t0 + 61 * min, s2.finishedAt)
        // 끝난 뒤엔 아무 일도 없다
        val (a3, s3) = CalibrationRule.decide(t0 + 120 * min, s2, using(t0 + 120 * min, 95))
        assertTrue(a3.isEmpty()); assertEquals(s2, s3)
    }

    @Test
    fun expiresAfterTwoWeeksAndIgnoresStaleSamples() {
        val st = CalibrationRule.start(t0, null)
        val late = t0 + CalibrationRule.MAX_MS + min
        val (a, s) = CalibrationRule.decide(late, st, using(late, 50))
        assertEquals(listOf(CalibrationAction.Expired), a)
        assertEquals(0, s.step)
        // 마지막 표본이 45분 넘게 묵었으면 판단하지 않는다
        val (a2, s2) = CalibrationRule.decide(t0 + 120 * min, st, full(t0 + 60 * min))
        assertTrue(a2.isEmpty()); assertEquals(st, s2)
        assertNull(CalibrationRule.stepLabel(0))
        assertEquals("1단계 — 충전기에 올려 100%가 된 뒤 1시간 더 두세요", CalibrationRule.stepLabel(1))
    }
}
