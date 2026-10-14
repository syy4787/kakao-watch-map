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
        for (s in listOf(
            walking(5_000).copy(onRide = true),
            walking(5_000).copy(waitingBus = true),
            walking(5_000).copy(waitingLongRide = true),
            walking(5_000).copy(onRide = true, onSubway = true),
        )) {
            val c = StallClock()
            c.start(0)
            c.update(s, 50 * min)
            assertEquals(0L, c.stalledMs(50 * min))
            assertFalse(c.shouldGiveUp(s, 100 * min))
            if (s.onRide) assertFalse("타는 중엔 몇 시간이어도", c.shouldGiveUp(s, 300 * min))
        }
    }

    /** 지하철 승강장(지하)에서 기다림: 타는 중도 버스 기다림도 아닌 상태 — 지하라 쓸 만한 위치가 거의 안 온다 */
    private val platform = NavState(
        routeVersion = 1, stepIndex = 0, matched = true, remainingM = 4_000, toStepEndM = 0, toActionM = 0,
        onSubway = true, boardingStep = 1,
    )

    /**
     * 리뷰에서 찾음: 지하철을 기다리는 동안은 진척 없는 시간으로 셌다 — 지하에선 쓸 만한 위치(50m 이내)가 안 오고 탄 것도
     * 늦게 확인돼서, 30분 뒤 '위치를 못 받아' 안내를 끝냈다 (지하철을 타고 가는 중에)
     */
    @Test
    fun waitingForTheSubwayUndergroundIsNotAStall() {
        val c = StallClock()
        c.start(0)
        c.update(platform, 0)
        c.update(platform, 45 * min)
        assertEquals(0L, c.stalledMs(45 * min))
        assertFalse(c.shouldGiveUp(platform, 45 * min))
        assertFalse(c.arrivedNearby(platform, dest, dest, 45 * min))
    }

    /**
     * 리뷰에서 찾음: 시내 버스 정류장에서 기다리다 위치가 정류장 바로 앞(걸어온 길 끝)에 찍히면 '기다림'이 아닌 '멈춤'으로 셌다 —
     * 10분 뒤 저전력 위치(10m 움직여야 옴)로 내려가 실시간 도착을 더 묻지 못했다 (위치가 올 때 함께 묻는다)
     */
    @Test
    fun standingWithinThirtyMetresOfTheStopToBoardIsWaiting() {
        val c = StallClock()
        c.start(0)
        val atStop = walking(4_000, step = 0).copy(toStepEndM = 12, toActionM = 12, boardingStep = 1)
        c.update(atStop, 0)
        c.update(atStop, 20 * min)
        assertEquals(0L, c.stalledMs(20 * min))
        val plan = LocationPolicy.navigation(atStop, false, 0f, 0, stalledMs = 20 * min)
        assertEquals(LocPower.HIGH, plan.power)
        // 정류장에서 먼 곳(걸어가는 길 위)에 멈춰 있는 건 그대로 멈춤
        val c2 = StallClock()
        c2.start(0)
        val before = walking(4_000, step = 0).copy(toStepEndM = 200, toActionM = 200, boardingStep = 1)
        c2.update(before, 0)
        c2.update(before, 20 * min)
        assertEquals(20 * min, c2.stalledMs(20 * min))
    }

    @Test
    fun waitingForARideIsGivenUpOnlyAfterTwoHours() {
        // 정류장 옆 집에서 '안내 시작'만 누르고 잊어버린 경우 — GPS 를 5초마다 끝없이 쓰지 않게 (버스 간격이 길어도 2시간이면 충분)
        val c = StallClock()
        c.start(0)
        val waiting = walking(4_000, step = 0).copy(toStepEndM = 0, toActionM = 0, boardingStep = 1, waitingBus = true)
        c.update(waiting, 0)
        c.update(waiting, 100 * min)
        assertFalse(c.shouldGiveUp(waiting, 100 * min))
        assertEquals("위치가 안 와도 그때 깨워서 본다", 120 * min + 1_000, c.nextDeadline(100 * min))
        assertTrue(c.shouldGiveUp(waiting, 121 * min))
        assertFalse("알림은 '위치를 못 받음'이 아니라 '나아가지 않음' 문구", c.noFix(waiting, 121 * min))
        // 타면 처음부터
        c.update(waiting.copy(onRide = true, waitingBus = false, boardingStep = null), 122 * min)
        assertFalse(c.shouldGiveUp(waiting.copy(onRide = true, waitingBus = false, boardingStep = null), 300 * min))
    }

    private val atBusStop = walking(4_000, step = 0).copy(toStepEndM = 0, toActionM = 0, boardingStep = 1, waitingBus = true)
    /** 정류장에서 걸어온 길 쪽으로 45m — GPS 가 튀었거나 정류장을 떠남 */
    private val offTheStop = walking(4_000, step = 0).copy(toStepEndM = 45, toActionM = 45, boardingStep = 1)

    /** 재검토에서 찾음: 정류장에서 GPS 가 한 번 튀어 '기다림'이 잠깐 꺼지면 2시간을 처음부터 셌다 (잊고 둔 안내가 끝나지 않음) */
    @Test
    fun aStrayFixAtTheStopDoesNotRestartTheTwoHourWait() {
        val c = StallClock()
        c.start(0)
        for (t in 0L..60L step 10) c.update(atBusStop, t * min)
        c.update(offTheStop, 60 * min + 30_000)
        for (t in 61L..120L step 10) c.update(atBusStop, t * min)
        c.update(atBusStop, 121 * min)
        assertTrue(c.shouldGiveUp(atBusStop, 121 * min))
    }

    @Test
    fun walkingAwayFromTheStopForAWhileStartsTheWaitAfresh() {
        val c = StallClock()
        c.start(0)
        for (t in 0L..20L step 10) c.update(atBusStop, t * min)
        for (t in 30L..36L step 3) c.update(offTheStop, t * min)   // 6분 넘게 정류장을 떠나 있음
        for (t in 60L..120L step 10) c.update(atBusStop, t * min)  // 다시 와서 기다림
        c.update(atBusStop, 121 * min)
        assertFalse("다시 기다리기 시작한 때부터", c.shouldGiveUp(atBusStop, 121 * min))
        for (t in 130L..180L step 10) c.update(atBusStop, t * min)
        c.update(atBusStop, 181 * min)
        assertTrue(c.shouldGiveUp(atBusStop, 181 * min))
    }

    /** 재검토에서 찾음: 기차역에 2시간 넘게 일찍 와서 기다리면 타기 전에 안내가 끝났다 — 시간표가 있으면 놓침은 엔진이 알아챈다 */
    @Test
    fun waitingForATimetabledTrainIsNotCutAtTwoHours() {
        val c = StallClock()
        c.start(0)
        val station = walking(40_000, step = 0).copy(
            toStepEndM = 0, toActionM = 0, boardingStep = 1, waitingLongRide = true, longRideAtMs = 1_760_000_000_000L,
        )
        for (t in 0L..150L step 10) c.update(station, t * min)
        assertFalse(c.shouldGiveUp(station, 150 * min))
        assertNull(c.nextDeadline(150 * min))
    }

    @Test
    fun waitingForTheSubwayUsesCellAndWifiInsteadOfGps() {
        // 승강장(지하)에선 GPS 가 위성을 찾느라 전기만 쓴다 — 타는 중처럼 기지국·Wi-Fi 위치 10초 (코드 설명엔 그렇게 적혀 있었지만
        // 실제로는 타는 중에만 그랬고 기다리는 동안엔 GPS 1초였다)
        for (speed in listOf(null, 0f, 1.2f)) {
            val plan = LocationPolicy.navigation(platform, false, speed, 0)
            assertEquals("속도 $speed", LocPower.BALANCED, plan.power)
            assertEquals(10_000L, plan.intervalMs)
        }
    }

    /**
     * 리뷰에서 찾음: 다시 찾은 경로는 구간 0 부터 세고 남은 거리도 보통 더 긴데, 지난 경로의 구간·최소 남은 거리가 기준으로 남아
     * 새 경로를 따라 걸어도 진척으로 치지 않았다 → 10분 뒤 저전력, 목적지 근처면 3분 뒤 '도착'으로 안내를 일찍 끝냄
     */
    @Test
    fun aNewRouteAfterARerouteStartsItsOwnBaseline() {
        val c = StallClock()
        c.start(0)
        c.update(walking(300, step = 4), 0)
        c.update(walking(300, step = 4).copy(offRoute = true), 1 * min)
        // 다시 찾은 새 경로 (구간 0, 남은 900m)를 따라 걸음
        fun rerouted(remaining: Int) = walking(remaining, step = 0).copy(routeVersion = 2)
        c.update(rerouted(900), 2 * min)
        c.update(rerouted(860), 5 * min)
        c.update(rerouted(820), 8 * min)
        assertEquals("새 경로를 따라 걷는 건 진척", 0L, c.stalledMs(8 * min))
        // 새 경로에서 멈춰 있으면 그때부터 센다
        c.update(rerouted(815), 18 * min)
        assertEquals(10 * min, c.stalledMs(18 * min))
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
        assertTrue("알림은 '위치를 못 받음' 문구", c.noFix(s, 32 * min))
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
