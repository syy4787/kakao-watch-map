package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.MiniMapProjection
import com.mywatch.kakaomap.model.TransitAccess
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.nav.LocPower
import com.mywatch.kakaomap.nav.LocationPolicy
import com.mywatch.kakaomap.nav.LostClock
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.nav.NavEvent
import com.mywatch.kakaomap.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 배터리 우회법(상황별 위치 간격·미니 지도)과 대중교통 접근 도보 보정 검증 */
class BatteryAndAccessTest {

    private fun nav(
        matched: Boolean = true, toNext: Int? = 1_000, ride: Boolean = false,
        off: Boolean = false, arrived: Boolean = false,
    ) = NavState(routeVersion = 1, stepIndex = 0, matched = matched, toStepEndM = toNext, onRide = ride, offRoute = off, arrived = arrived)

    @Test
    fun locationIntervalFollowsTheSituation() {
        // 할 일(회전·횡단보도) 바로 앞·이탈·출발 직후는 1초
        assertEquals(1_000L, LocationPolicy.navigation(nav(toNext = 50), false, 1.3f, 0).intervalMs)
        assertEquals(1_000L, LocationPolicy.navigation(nav(off = true), false, 1.3f, 0).intervalMs)
        assertEquals(1_000L, LocationPolicy.navigation(nav(matched = false), false, 1.3f, 0).intervalMs)
        // 실내 출발 등으로 1분 넘게 경로에 못 오르면 1초를 계속 쓰지 않는다
        assertEquals(3_000L, LocationPolicy.navigation(nav(matched = false), false, 1.3f, 0, sinceStartMs = 120_000).intervalMs)
        // 할 일 접근 2초, 긴 직진 4초 + 모아 받기
        assertEquals(2_000L, LocationPolicy.navigation(nav(toNext = 200), false, 1.3f, 0).intervalMs)
        val straight = LocationPolicy.navigation(nav(toNext = 900), false, 1.3f, 0)
        assertEquals(4_000L, straight.intervalMs)
        assertTrue(straight.maxDelayMs > straight.intervalMs)
        // 신호 대기·정류장 대기 등 멈춤은 갈림길 바로 앞이어도 5초 (움직이면 바로 다시 촘촘히)
        assertEquals(5_000L, LocationPolicy.navigation(nav(toNext = 900), false, 0f, 0).intervalMs)
        assertEquals(5_000L, LocationPolicy.navigation(nav(toNext = 20), false, 0f, 0).intervalMs)
        // 버스: 내릴 곳이 아주 멀면 15초, 멀면 10초, 가까우면 3초
        assertEquals(15_000L, LocationPolicy.navigation(nav(toNext = 5_000, ride = true), false, 8f, 0).intervalMs)
        assertEquals(10_000L, LocationPolicy.navigation(nav(toNext = 2_000, ride = true), false, 8f, 0).intervalMs)
        assertEquals(3_000L, LocationPolicy.navigation(nav(toNext = 900, ride = true), false, 8f, 0).intervalMs)
        // 지하철(지하)은 GPS 대신 기지국·Wi-Fi 위치
        assertEquals(LocPower.BALANCED, LocationPolicy.navigation(nav(toNext = 3_000, ride = true).copy(onSubway = true), false, 10f, 0).power)
        // 자전거는 더 촘촘히
        assertEquals(2_000L, LocationPolicy.navigation(nav(toNext = 900), true, 5f, 0).intervalMs)
        assertEquals(1_000L, LocationPolicy.navigation(nav(toNext = 250), true, 5f, 0).intervalMs)
        // 다음 할 일까지 거리(직진 구간 합침)가 있으면 그것을 기준으로
        assertEquals(4_000L, LocationPolicy.navigation(nav(toNext = 50).copy(toActionM = 900), false, 1.3f, 0).intervalMs)
        // 배터리 15% 이하: 간격을 넓힌다 (회전 앞도 2초)
        assertEquals(2_000L, LocationPolicy.navigation(nav(toNext = 50), false, 1.3f, 0, lowBattery = true).intervalMs)
        assertEquals(8_000L, LocationPolicy.navigation(nav(toNext = 900), false, 1.3f, 0, lowBattery = true).intervalMs)
        // 저전력 화면에 '지도에서 보기' 경로만 띄워 둠(안내 아님): 아무도 안 보니 GPS 대신 Wi-Fi·기지국 30초
        assertEquals(LocPower.BALANCED, LocationPolicy.AMBIENT_VIEW_ONLY.power)
        assertEquals(30_000L, LocationPolicy.AMBIENT_VIEW_ONLY.intervalMs)
    }

    @Test
    fun leftTheRouteIndoorsDoesNotKeepGpsAtOneSecond() {
        val min = 60_000L
        // 경로를 벗어나 걷는 중: 재탐색을 위해 1초
        assertEquals(1_000L, LocationPolicy.navigation(nav(off = true), false, 1.3f, 0, sinceLostMs = 30_000).intervalMs)
        // 벗어난 채로 멈춰 있음(카페에 들어감 등): 5초 — 움직이면 바로 1초로
        assertEquals(5_000L, LocationPolicy.navigation(nav(off = true), false, 0f, 0, sinceLostMs = 30_000).intervalMs)
        assertEquals(5_000L, LocationPolicy.navigation(nav(matched = false), false, 0f, 0, sinceLostMs = 30_000).intervalMs)
        // 10분 넘게 경로 밖에 멈춰 있음: GPS 끄고 Wi-Fi·기지국 위치 15초
        val parked = LocationPolicy.navigation(nav(off = true), false, 0f, 0, sinceLostMs = 11 * min)
        assertEquals(LocPower.BALANCED, parked.power)
        assertEquals(15_000L, parked.intervalMs)
        assertEquals(LocPower.BALANCED, LocationPolicy.navigation(nav(matched = false), false, 0f, 0, sinceStartMs = 11 * min, sinceLostMs = 11 * min).power)
        // 10분 넘게 벗어난 채 움직이는 중: GPS 3초 (밖에 나오면 재탐색할 수 있게)
        val wandering = LocationPolicy.navigation(nav(off = true), false, 1.3f, 0, sinceLostMs = 11 * min)
        assertEquals(LocPower.HIGH, wandering.power)
        assertEquals(3_000L, wandering.intervalMs)
        // 재탐색 중에는 언제나 1초
        assertEquals(1_000L, LocationPolicy.navigation(nav(off = true).copy(rerouting = true), false, 0f, 0, sinceLostMs = 11 * min).intervalMs)
    }

    @Test
    fun navigationEndsItselfAfterHalfAnHourOffTheRoute() {
        val min = 60_000L
        val clock = LostClock()
        var t = 1_000L
        fun at(minutes: Long, s: NavState): Boolean {
            t = 1_000L + minutes * min
            clock.update(s, t)
            return clock.shouldGiveUp(s, t)
        }
        // 출발 전 실내에서 오래 기다림(한 번도 경로에 오르지 않음): 위치 아끼기는 하지만 끝내지는 않는다
        assertTrue(!at(0, nav(matched = false)))
        assertTrue(!at(40, nav(matched = false)))
        assertEquals(40 * min, clock.sinceLost(t))
        // 40분 뒤 처음 이탈 → 그때부터 30분을 센다 (곧바로 끝내지 않음)
        assertTrue(!at(41, nav(off = true)))
        // 재탐색해서 새 경로에 아직 못 올라도 계속 셈
        assertTrue(!at(60, nav(matched = false)))
        assertTrue(at(72, nav(matched = false)))

        // 경로로 돌아오면 다시 처음부터
        val back = LostClock()
        back.update(nav(off = true), 0)
        back.update(nav(), 20 * min)
        back.update(nav(off = true), 25 * min)
        assertTrue(!back.shouldGiveUp(nav(off = true), 50 * min))
        assertTrue(back.shouldGiveUp(nav(off = true), 56 * min))
        assertEquals(0L, LostClock().also { it.update(nav(), 5 * min) }.sinceLost(10 * min))

        // 버스·지하철 타는 중, 도착 뒤에는 끝내지 않는다
        val ride = LostClock()
        ride.update(nav(off = true), 0)
        assertTrue(!ride.shouldGiveUp(nav(ride = true).copy(passedStop = true), 60 * min))
        assertTrue(!ride.shouldGiveUp(nav(arrived = true), 60 * min))
    }

    @Test
    fun afterArrivalLocationCostsAlmostNothing() {
        val justArrived = LocationPolicy.navigation(nav(arrived = true), false, 0f, 30_000)
        assertEquals(LocPower.BALANCED, justArrived.power)
        val later = LocationPolicy.navigation(nav(arrived = true), false, 0f, LocationPolicy.AFTER_ARRIVAL_ACTIVE_MS + 1)
        assertEquals(LocPower.PASSIVE, later.power)
        // 화면을 볼 땐 1초
        assertEquals(1_000L, LocationPolicy.INTERACTIVE.intervalMs)
    }

    @Test
    fun miniMapProjectionIsNorthUpAndScaled() {
        val center = Coord(36.35, 127.43)
        val proj = MiniMapProjection.fit(center, radiusM = 300.0, radiusPx = 200f, cx = 240f, cy = 240f)
        assertEquals(1.5, proj.metersPerPx, 1e-9)
        val north = Coord(36.35 + 300 / 110_574.0, 127.43)      // 300m 북쪽
        assertEquals(240f, proj.x(north), 0.5f)
        assertEquals(40f, proj.y(north), 0.5f)                    // 화면 위쪽 끝
        val east = Coord(36.35, 127.43 + 0.001)
        val eastM = Geo.distanceM(center, east)
        assertEquals(240f + (eastM / 1.5).toFloat(), proj.x(east), 1f)
        assertEquals(240f, proj.y(east), 0.5f)
        assertEquals(200f, proj.distancePx(north), 0.5f)
    }

    // 실제 카카오 응답처럼: 단계에는 버스만 있고, 정류장까지·도착지까지 도보는 총합에만 들어 있음
    private val origin = Coord(36.33433799, 127.39688087)
    private val dest = Coord(36.30229415, 127.38464451)
    private val busOnly = TransitRoute(
        type = "BUS", totalDistanceM = 7331, totalTimeSec = 2353, transfers = 0, fare = 1500,
        steps = listOf(
            TransitStep(
                type = TransitStepType.BUS, guidance = "일반 314 (목련아파트 > 대전역)", distanceM = 6597, timeSec = 1630,
                stops = listOf("목련아파트", "청솔아파트", "대전역"), vehicles = listOf(Vehicle("일반", "314")),
                points = listOf(Coord(36.33340000, 127.39410000), Coord(36.31500000, 127.39000000), Coord(36.30410000, 127.38340000)),
            ),
        ),
    )

    @Test
    fun accessWalksAreAddedLikeKakaoMap() {
        val r = TransitAccess.withAccessWalks(busOnly, origin, dest, "대전역")
        assertEquals(3, r.steps.size)
        val lead = r.steps.first()
        val tail = r.steps.last()
        assertTrue(lead.approx && tail.approx)
        assertEquals(TransitStepType.WALK, lead.type)
        assertEquals("목련아파트까지 도보", lead.guidance)
        assertEquals("대전역까지 도보", tail.guidance)
        // 빠진 몫(723초·734m)을 둘이 나눠 가져서 단계 합 = 총합
        assertTrue(kotlin.math.abs(busOnly.totalTimeSec - r.steps.sumOf { it.timeSec }) <= 2)
        assertTrue(kotlin.math.abs(busOnly.totalDistanceM - r.steps.sumOf { it.distanceM }) <= 2)
        assertEquals(lead.timeSec + tail.timeSec, r.walkTimeSec)
        // 첫 버스·내릴 곳 정보는 그대로
        assertEquals("목련아파트", r.firstRide?.boardingStop)
    }

    @Test
    fun routesThatAlreadyHaveWalksAreLeftAlone() {
        val withWalk = busOnly.copy(
            steps = listOf(
                TransitStep(TransitStepType.WALK, "정류장까지", 300, 240, emptyList(), emptyList(), listOf(origin, Coord(36.33340000, 127.39410000))),
            ) + busOnly.steps + listOf(
                TransitStep(TransitStepType.WALK, "도착지까지", 300, 240, emptyList(), emptyList(), listOf(Coord(36.30410000, 127.38340000), dest)),
            ),
        )
        assertSame(withWalk, TransitAccess.withAccessWalks(withWalk, origin, dest, "대전역"))
    }

    @Test
    fun walkingToTheStopIsNotOffRoute() {
        // 집에서 정류장까지 직선 도보(실제 길과 다름) → 100m 벗어나도 이탈 진동 없음
        val r = TransitAccess.withAccessWalks(busOnly, origin, dest, "대전역")
        val steps = r.steps.map { GuideStep(it.guidance, it.points, ride = it.type == TransitStepType.BUS, alight = it.alightingStop, approx = it.approx) }
        val e = NavEngine(steps, dest, 1)
        val beside = Coord(36.33480000, 127.39580000)    // 직선에서 100m 쯤 옆 골목
        repeat(4) { assertFalse(NavEvent.OFF_ROUTE in e.update(beside, 8f)) }
        assertFalse(e.state.offRoute)
        assertEquals(0, e.state.stepIndex)
        assertTrue(e.state.guide!!.contains("314"))      // "…m 후 314번 버스 …"
    }

    @Test
    fun guidanceContinuesAfterArrivalWithDistance() {
        val a = Coord(37.0, 127.000)
        val c = Coord(37.0, 127.010)
        val e = NavEngine(listOf(GuideStep("출발", listOf(a, c))), c, 1)
        e.update(Coord(37.0, 127.009), 5f)
        e.update(Coord(37.0, 127.0099), 5f)
        assertTrue(e.update(Coord(37.0, 127.00995), 5f).isEmpty() || e.state.arrived)
        assertTrue(e.state.arrived)
        // 도착 뒤 조금 걸어가면 '목적지까지 직선거리'로 계속 안내 (진동은 없음)
        assertTrue(e.update(Coord(37.0004, 127.010), 6f).isEmpty())
        assertTrue(e.state.guide!!.startsWith(NavEngine.NEAR_TEXT))
        assertTrue(e.state.remainingM!! in 40..50)
    }
}
