package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.RouteProgress
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.nav.NavEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 길 안내 판단(구간 전환·이탈·도착·하차 알림)과 GPS 흔들림에 대한 내성 검증 */
class NavEngineTest {

    // 위도 37° 에서 경도 0.001° ≈ 89m, 위도 0.0001° ≈ 11m
    private val a = Coord(37.0, 127.000)
    private val b = Coord(37.0, 127.005)
    private val c = Coord(37.0, 127.010)
    private val walk = listOf(GuideStep("출발", listOf(a, b)), GuideStep("우회전", listOf(b, c)))

    @Test
    fun walkingGuidanceFollowsSteps() {
        val e = NavEngine(walk, c, routeVersion = 1)
        assertEquals("처음엔 첫 구간 문구", "출발", e.state.guide)

        assertTrue(e.update(Coord(37.0, 127.001), 5f).isEmpty())
        assertEquals(0, e.state.stepIndex)
        assertTrue(e.state.matched)
        assertTrue(e.state.guide!!.contains("후 우회전"))

        // 구간이 바뀌는 것만으로는 진동하지 않는다 (회전은 갈림길 '전에' 알림)
        assertTrue(e.update(Coord(37.0, 127.006), 5f).isEmpty())
        assertEquals(1, e.state.stepIndex)
        assertTrue(e.state.guide!!.contains("후 도착"))
        assertTrue(e.state.remainingM!! in 300..400)
    }

    @Test
    fun standingAtAJunctionWithGpsJitterBuzzesOnlyOnce() {
        val e = NavEngine(walk, c, 1)
        e.update(Coord(37.0, 127.003), 5f)
        var buzzes = 0
        var flips = 0
        var prev = e.state.stepIndex
        // 갈림길(B) 근처에 3분 서 있음: 경도 ±3.5m, 위도 ±2m 흔들림
        for (i in 0 until 90) {
            val p = Coord(37.0 + 0.00002 * cos(i * 1.7), 127.005 + 0.00004 * sin(i * 2.3))
            buzzes += e.update(p, 5f).size
            if (e.state.stepIndex != prev) flips++
            prev = e.state.stepIndex
        }
        assertTrue("진동 $buzzes 번", buzzes <= 1)
        assertTrue("구간 표시가 왔다 갔다 함: $flips 번", flips <= 1)
    }

    @Test
    fun walkingBackForRealReturnsToTheEarlierStep() {
        val e = NavEngine(walk, c, 1)
        e.update(Coord(37.0, 127.004), 5f)
        e.update(Coord(37.0, 127.006), 5f)
        assertEquals(1, e.state.stepIndex)
        // 경로를 거슬러 약 180m 되돌아감 → 두 번 확인되면 앞 구간 안내
        e.update(Coord(37.0, 127.003), 5f)
        assertEquals("한 번은 보류", 1, e.state.stepIndex)
        e.update(Coord(37.0, 127.0029), 5f)
        assertEquals(0, e.state.stepIndex)
        // 다시 갈림길을 지나면 다시 뒤 구간
        e.update(Coord(37.0, 127.006), 5f)
        assertEquals(1, e.state.stepIndex)
    }

    @Test
    fun offRouteNeedsTwoFixesAndAlertsOnce() {
        val e = NavEngine(walk, c, 1)
        e.update(Coord(37.0, 127.006), 5f)
        // 약 110m 북쪽으로 벗어남: 한 번은 GPS 튐일 수 있어 보류
        assertTrue(e.update(Coord(37.001, 127.007), 5f).isEmpty())
        assertFalse(e.state.offRoute)
        assertEquals(listOf(NavEvent.OFF_ROUTE), e.update(Coord(37.001, 127.0075), 5f))
        assertTrue(e.state.offRoute)
        assertTrue(e.state.guide!!.startsWith("경로를 벗어났어요"))
        assertTrue("이미 알린 이탈은 다시 울리지 않음", e.update(Coord(37.001, 127.0078), 5f).isEmpty())
        // 경로로 돌아오면 정상 안내
        assertTrue(e.update(Coord(37.0, 127.008), 5f).isEmpty())
        assertFalse(e.state.offRoute)
        assertEquals(1, e.state.stepIndex)
        assertTrue(e.state.guide!!.contains("후 도착"))
    }

    @Test
    fun poorAccuracyWidensOffRouteThreshold() {
        val e = NavEngine(walk, c, 1)
        e.update(Coord(37.0, 127.006), 5f)
        // 경로에서 약 67m — 정확도 40m 면 아직 경로 위로 본다
        val off = Coord(37.0006, 127.007)
        e.update(off, 40f)
        assertTrue(e.update(off, 40f).isEmpty())
        assertFalse(e.state.offRoute)
    }

    @Test
    fun veryInaccurateFixesAreIgnored() {
        val e = NavEngine(walk, c, 1)
        assertTrue(e.update(Coord(37.0, 127.006), 300f).isEmpty())
        assertEquals("정확도 300m 위치는 버림", -1, e.state.stepIndex)
        // 정확도를 모르는 위치(NaN)로는 도착을 판단하지 않는다
        e.update(Coord(37.0, 127.009), 5f)
        repeat(3) { e.update(Coord(37.0, 127.00998), Float.NaN) }
        assertFalse(e.state.arrived)
    }

    @Test
    fun arrivalIsConfirmedByTwoFixesAndFiresOnce() {
        val e = NavEngine(walk, c, 1)
        e.update(Coord(37.0, 127.006), 5f)
        assertTrue(e.update(Coord(37.0, 127.00995), 5f).isEmpty())
        assertFalse(e.state.arrived)
        assertEquals(listOf(NavEvent.ARRIVED), e.update(Coord(37.0, 127.0099), 5f))
        assertTrue(e.state.arrived)
        assertEquals(NavEngine.ARRIVED_TEXT, e.state.guide)
        assertTrue(e.update(Coord(37.0, 127.0099), 5f).isEmpty())
    }

    @Test
    fun beingFarBeyondTheRouteEndIsNotArrival() {
        val e = NavEngine(walk, c, 1)
        // 끝점에서 북쪽으로 약 220m — 경로상 가장 가까운 점은 끝점이지만 도착은 아님
        repeat(3) { e.update(Coord(37.002, 127.0105), 5f) }
        assertFalse(e.state.arrived)
    }

    @Test
    fun destinationAcrossTheRoadIsNotArrivalAtTheStart() {
        // 동쪽 200m → 북쪽 30m 건넘 → 서쪽 200m: 목적지는 출발점 길 건너편(30m)
        val p0 = Coord(37.0, 127.000)
        val p1 = Coord(37.0, 127.00225)
        val p2 = Coord(37.00027, 127.00225)
        val dest = Coord(37.00027, 127.000)
        val route = listOf(GuideStep("출발", listOf(p0, p1)), GuideStep("횡단보도", listOf(p1, p2)), GuideStep("좌회전", listOf(p2, dest)))
        val e = NavEngine(route, dest, 1)
        repeat(3) { assertTrue(e.update(Coord(37.0, 127.0001), 10f).none { it == NavEvent.ARRIVED }) }
        assertFalse("건너편이라 아직 도착 아님", e.state.arrived)
        e.update(Coord(37.00027, 127.0003), 10f)
        e.update(Coord(37.00027, 127.0001), 10f)
        assertTrue(e.state.arrived)
    }

    @Test
    fun startingAwayFromTheRouteDoesNotCryOffRoute() {
        val e = NavEngine(walk, c, 1)
        // 경로에서 약 100m 떨어진 건물 안에서 출발: 이탈 진동 없이 '경로 쪽으로'
        repeat(4) { assertTrue(e.update(Coord(37.0009, 127.002), 10f).isEmpty()) }
        assertFalse(e.state.offRoute)
        assertTrue(e.state.guide!!.startsWith("경로 쪽으로 이동하세요"))
        // 200m 넘게 떨어져 있으면 세 번째에 이탈로 본다 (재탐색 필요)
        assertTrue(e.update(Coord(37.0025, 127.002), 10f).isEmpty())
        assertTrue(e.update(Coord(37.0025, 127.002), 10f).isEmpty())
        assertEquals(listOf(NavEvent.OFF_ROUTE), e.update(Coord(37.0025, 127.002), 10f))
    }

    /** 정류장까지 동쪽으로 걸은 뒤, 버스가 바로 옆 길(약 11m 북쪽)로 되돌아 서쪽으로 가는 경로 */
    private val stop = Coord(37.0, 127.003)
    private val busEnd = Coord(37.0001, 126.990)
    private val dest = Coord(37.0005, 126.990)
    private val transit = listOf(
        GuideStep("도보 4분 · 266m", listOf(a, stop)),
        GuideStep("102번 버스 · A정류장 → B정류장", listOf(Coord(37.0001, 127.003), busEnd), ride = true, alight = "B정류장"),
        GuideStep("도보 1분 · 44m", listOf(busEnd, dest)),
    )

    @Test
    fun doesNotJumpToTheReturningBusWhileWalking() {
        val p = RouteProgress.locate(Coord(37.0, 127.001), transit)!!
        assertEquals("걷는 중엔 옆 버스 노선이 더 가까워도 도보 구간", 0, p.stepIndex)
        // 이미 버스 구간까지 왔다면 지나온 도보 구간은 보지 않는다
        val later = RouteProgress.locate(Coord(37.0001, 127.001), transit, fromSeg = 1)!!
        assertEquals(1, later.stepIndex)
    }

    @Test
    fun alightAlertFiresOnceNearTheStop() {
        val e = NavEngine(transit, dest, 1)
        e.update(Coord(37.0, 127.001), 5f)
        assertEquals(0, e.state.stepIndex)
        // 버스를 타고 서쪽으로: 탄 뒤 첫 위치는 '탔는지' 확인 대기
        e.update(Coord(37.0001, 126.996), 5f)
        assertEquals(0, e.state.stepIndex)
        // 두 번째 → 버스 구간. 내릴 곳까지 약 440m → 문구는 '곧 하차', 진동은 두 번 확인 후
        e.update(Coord(37.0001, 126.995), 5f)
        assertEquals(1, e.state.stepIndex)
        assertTrue(e.state.guide!!.startsWith("곧 하차 · B정류장"))
        assertEquals(listOf(NavEvent.ALIGHT_SOON), e.update(Coord(37.0001, 126.993), 5f))
        assertTrue("하차 알림은 한 번만", e.update(Coord(37.0001, 126.992), 5f).isEmpty())
        // 내려서 마지막 도보 구간으로
        e.update(Coord(37.00015, 126.990), 5f)
        assertEquals(2, e.state.stepIndex)
    }

    @Test
    fun waitingAtTheStopDoesNotUseUpAShortRideAlert() {
        // 한 정거장(약 440m)짜리 짧은 버스 구간
        val s = Coord(37.0, 127.003)
        val rideEnd = Coord(37.0001, 127.008)
        val d = Coord(37.0005, 127.008)
        val route = listOf(
            GuideStep("도보", listOf(a, s)),
            GuideStep("5번 버스", listOf(Coord(37.0001, 127.003), rideEnd), ride = true, alight = "C정류장"),
            GuideStep("도보", listOf(rideEnd, d)),
        )
        val e = NavEngine(route, d, 1)
        e.update(Coord(37.0, 127.002), 5f)
        val events = ArrayList<NavEvent>()
        // 정류장에서 5분 기다림 (GPS 흔들림)
        for (i in 0 until 150) {
            events += e.update(Coord(37.00005 + 0.00003 * cos(i * 1.3), 127.003 + 0.00004 * sin(i * 0.7)), 5f)
        }
        assertFalse("기다리는 동안 하차 알림 없음", NavEvent.ALIGHT_SOON in events)
        assertTrue("승차 알림은 많아야 한 번", events.count { it == NavEvent.BOARD_SOON } <= 1)
        assertEquals("기다리는 동안 버스 구간으로 넘어가지 않음", 0, e.state.stepIndex)
        // 타고 가다 내릴 곳 가까이에서 알림
        val ride = listOf(127.004, 127.005, 127.006, 127.0065, 127.007).flatMap { e.update(Coord(37.0001, it), 5f) }
        assertEquals(1, ride.count { it == NavEvent.ALIGHT_SOON })
    }

    @Test
    fun noOffRouteWhileRiding() {
        val e = NavEngine(transit, dest, 1)
        e.update(Coord(37.0001, 126.999), 5f)
        // 버스 구간에서 노선 좌표와 80m 떨어져도(성근 좌표·지하 GPS) 이탈로 보지 않는다
        e.update(Coord(37.0008, 126.998), 5f)
        assertTrue(e.update(Coord(37.0008, 126.9979), 5f).none { it == NavEvent.OFF_ROUTE })
        assertFalse(e.state.offRoute)
    }

    @Test
    fun reroutingTextStaysUntilDone() {
        val e = NavEngine(walk, c, 1)
        e.update(Coord(37.0, 127.002), 5f)
        e.setRerouting(true)
        e.update(Coord(37.0, 127.003), 5f)
        assertEquals(NavEngine.REROUTING_TEXT, e.state.guide)
        e.setRerouting(false)
        e.update(Coord(37.0, 127.0035), 5f)
        assertTrue(e.state.guide!!.contains("후 우회전"))
    }
}
