package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.nav.LocPower
import com.mywatch.kakaomap.nav.LocationPolicy
import com.mywatch.kakaomap.nav.NavState
import com.mywatch.kakaomap.nav.StallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내가 '멈춘' 채 배터리를 쓰지 않게 — 경로 위에서 더 나아가지 않거나(목적지 건물에 옆문으로 들어감·경로 위 카페)
 * 처음부터 경로에 못 올랐거나, 위치를 아예 못 받으면(위치 꺼짐·실내):
 *  · 10분 넘게 진척이 없고 멈춰 있으면 GPS 대신 Wi-Fi·기지국 위치 (몇 분마다 GPS 로 확인 — 걷기 시작하면 다시 GPS)
 *  · 1시간 넘게 진척이 없거나, 걷는 안내에서 쓸 만한 위치를 30분 넘게 못 받으면 안내를 스스로 끝냄
 *  · 목적지 60m 안에서 3분 넘게 멈춰 있으면 도착으로 보고 끝냄
 * 버스·지하철·기차를 타거나 기다리는 동안은 오래 걸려도 정상이라 세지 않는다
 */
class StallClockTest {

    private val min = 60_000L
    private val dest = Coord(36.3500, 127.3800)

    private fun walking(remaining: Int, step: Int = 2) =
        NavState(routeVersion = 1, stepIndex = step, matched = true, remainingM = remaining, toStepEndM = 40, toActionM = 40)

    @Test
    fun standingStillOnTheRouteGoesLowPowerAfterTenMinutesAndEndsAfterAnHour() {
        val c = StallClock()
        c.start(0)
        val s = walking(40)
        c.update(s, 0)
        c.onUsableFix(0)
        c.update(s, 9 * min)
        assertEquals(9 * min, c.stalledMs(9 * min))
        assertEquals(LocPower.HIGH, LocationPolicy.navigation(s, false, 0f, 0, stalledMs = c.stalledMs(9 * min)).power)
        assertEquals(LocPower.BALANCED, LocationPolicy.navigation(s, false, 0f, 0, stalledMs = c.stalledMs(11 * min)).power)
        assertEquals("걷기 시작하면 다시 GPS", LocPower.HIGH, LocationPolicy.navigation(s, false, 1.3f, 0, stalledMs = c.stalledMs(11 * min)).power)
        for (t in listOf(10L, 20L, 29L)) c.onUsableFix(t * min)   // 실내에서도 위치는 가끔 옴
        c.onUsableFix(50 * min)
        assertFalse(c.shouldGiveUp(s, 59 * min))
        assertTrue(c.shouldGiveUp(s, 61 * min))
    }

    @Test
    fun walkingAlongTheRouteIsProgress() {
        val c = StallClock()
        c.start(0)
        c.update(walking(900), 0)
        c.update(walking(860), 8 * min)   // 40m 더 감
        assertEquals(2 * min, c.stalledMs(10 * min))
        // GPS 가 몇 m 흔들리는 건 진척이 아니다
        c.update(walking(850), 15 * min)
        assertEquals(7 * min, c.stalledMs(15 * min))
        // 다음 구간으로 넘어가면 진척
        c.update(walking(850, step = 3), 16 * min)
        assertEquals(0L, c.stalledMs(16 * min))
    }

    @Test
    fun ridingOrWaitingForARideIsNeverAStall() {
        val c = StallClock()
        c.start(0)
        for (s in listOf(
            walking(5_000).copy(onRide = true),
            walking(5_000).copy(waitingBus = true),
            walking(5_000).copy(waitingLongRide = true),
            walking(5_000).copy(onRide = true, onSubway = true),
        )) {
            c.update(s, 50 * min)
            assertEquals(0L, c.stalledMs(50 * min))
            assertFalse(c.shouldGiveUp(s, 200 * min))
        }
    }

    @Test
    fun offTheRouteIsLeftToTheOffRouteClock() {
        // 벗어남·지나침·놓침은 재탐색과 LostClock(벗어난 채 30분) 몫
        val c = StallClock()
        c.start(0)
        val off = walking(900).copy(offRoute = true)
        c.update(off, 70 * min)
        assertFalse(c.shouldGiveUp(off, 70 * min))
    }

    @Test
    fun neverGettingOntoTheRouteEndsAfterAnHour() {
        val c = StallClock()
        c.start(0)
        val before = NavState(routeVersion = 1, stepIndex = 0, matched = false, remainingM = 1_200)
        c.update(before, 40 * min)
        assertFalse("40분은 아직 (집에서 나서기 전일 수 있음)", c.shouldGiveUp(before, 40 * min))
        assertTrue(c.shouldGiveUp(before, 61 * min))
    }

    @Test
    fun noUsableLocationForHalfAnHourEndsWalkingGuidance() {
        // 위치를 껐거나 실내 깊숙이 — 걷는 안내는 위치 없이 아무것도 못 한다
        val c = StallClock()
        c.start(0)
        c.onUsableFix(1 * min)
        val s = walking(500)
        c.update(s, 1 * min)
        assertFalse(c.shouldGiveUp(s, 30 * min))
        assertTrue(c.shouldGiveUp(s, 32 * min))
        // 위치가 다시 오면 처음부터
        c.onUsableFix(32 * min)
        c.update(walking(450), 32 * min)
        assertFalse(c.shouldGiveUp(walking(450), 40 * min))
    }

    @Test
    fun stoppedRightNextToTheDestinationCountsAsArrived() {
        val c = StallClock()
        c.start(0)
        val s = walking(45)
        c.update(s, 0)
        val inside = Coord(36.35035, 127.3800)   // 약 39m
        assertFalse(c.arrivedNearby(s, inside, dest, 2 * min))
        assertTrue(c.arrivedNearby(s, inside, dest, 4 * min))
        val far = Coord(36.3510, 127.3800)       // 약 111m
        assertFalse(c.arrivedNearby(s, far, dest, 10 * min))
        assertFalse("버스 안이면 아님", c.arrivedNearby(s.copy(onRide = true), inside, dest, 10 * min))
        assertFalse("위치를 모르면 아님", c.arrivedNearby(s, null, dest, 10 * min))
    }

    @Test
    fun theServiceIsWokenAtTheNextDecision() {
        // 서 있으면 위치가 거의 안 와 상태가 안 바뀐다 — 다음 판단 시각에 알람으로 깨운다
        val c = StallClock()
        c.start(0)
        c.onUsableFix(0)
        c.update(walking(120), 0)
        assertEquals(3 * min + 1_000, c.nextDeadline(0))           // 목적지 가까이면 도착인지 볼 때
        assertEquals(10 * min + 1_000, c.nextDeadline(5 * min))    // 저전력으로
        assertEquals(30 * min + 1_000, c.nextDeadline(15 * min))   // 위치를 못 받은 채 30분
        c.onUsableFix(35 * min)
        assertEquals(60 * min + 1_000, c.nextDeadline(40 * min))   // 진척 없이 1시간
        assertEquals(65 * min + 1_000, c.nextDeadline(61 * min))
        assertNull(c.nextDeadline(70 * min))
    }

    @Test
    fun locationPlanUsesWifiAndCellWhileStalledAndStill() {
        val s = walking(40)
        val plan = LocationPolicy.navigation(s, false, 0f, 0, stalledMs = 11 * min)
        assertEquals(LocPower.BALANCED, plan.power)
        assertEquals(15_000L, plan.intervalMs)
        // 걷기 시작하면 다시 GPS
        assertEquals(LocPower.HIGH, LocationPolicy.navigation(s, false, 1.3f, 0, stalledMs = 11 * min).power)
        // 버스를 기다리는 중이면 그대로 (실시간 도착을 계속 물어야 함)
        assertEquals(LocPower.HIGH, LocationPolicy.navigation(s.copy(waitingBus = true), false, 0f, 0, stalledMs = 11 * min).power)
    }
}
