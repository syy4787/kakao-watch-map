package com.mywatch.kakaomap

import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.PathStep
import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitAccess
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TransitWalks
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.WalkPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카카오맵처럼 '정류장까지 걷는 길'을 실제 길로: 카카오 대중교통 응답에 없는 출발지→첫 정류장, 마지막 정류장→도착지 도보를
 * 직선 대신 카카오 도보 길찾기 경로로 바꾼다 (지도에 그 길이 그려지고 남은 거리도 길 따라 잼)
 */
class TransitWalksTest {

    private val home = Coord(36.3700, 127.4300)          // 출발 (아파트 단지 안)
    private val stop = Coord(36.3712, 127.4318)           // 첫 정류장
    private val lastStop = Coord(36.3500, 127.3850)
    private val dest = Coord(36.3490, 127.3840)

    private val bus = TransitStep(
        TransitStepType.BUS, "간선 102 (송촌동 > 시청)", 6000, 1500,
        stops = listOf("송촌동", "중간", "시청"), vehicles = listOf(Vehicle("간선", "102")),
        points = listOf(stop, Coord(36.36, 127.41), lastStop),
    )
    private val route = TransitAccess.withAccessWalks(
        TransitRoute("BUS", 7000, 2400, 0, 1500, listOf(bus)), home, dest, "시청",
    )

    /** 길 따라 꺾이는 걷는 길: 단지 안 도로 → 큰길 → 정류장 근처 (끝이 정류장에서 12m 떨어짐) */
    private fun walkToStop() = SimpleRoute(
        TravelMode.WALK, 260, 210,
        listOf(
            PathStep("출발", 120, 100, Coord(36.37005, 127.43002), listOf(Coord(36.37005, 127.43002), Coord(36.3708, 127.4302))),
            PathStep("우회전", 140, 110, Coord(36.3708, 127.4302), listOf(Coord(36.3708, 127.4302), Coord(36.3708, 127.4302), Coord(36.37115, 127.43168))),
        ),
    )

    @Test
    fun accessWalksAreTheOnesToReplace() {
        assertEquals(TransitStepType.WALK, route.steps.first().type)
        assertEquals(listOf(0, 2), TransitWalks.pending(route))
        // 카카오가 준 환승 도보(이미 길을 따라감)는 그대로
        val transfer = route.copy(steps = route.steps + TransitStep(TransitStepType.WALK, "환승", 200, 180, emptyList(), emptyList(), listOf(home, dest)))
        assertEquals(listOf(0, 2), TransitWalks.pending(transfer))
        // 아주 짧은 직선(40m 미만)은 부르지 않는다
        val near = TransitAccess.withAccessWalks(TransitRoute("BUS", 7000, 2400, 0, null, listOf(bus)), Coord(36.37105, 127.4317), dest, "시청")
        assertEquals(listOf(2), TransitWalks.pending(near))
    }

    @Test
    fun theRealPathIsJoinedToTheStartAndTheStop() {
        val r = TransitWalks.apply(route, 0, walkToStop())
        val walk = r.steps[0]
        assertTrue(walk.pathFound)
        assertTrue("정류장까지 도보 안내 문구·이탈 판단(느슨함)은 그대로", walk.approx)
        // 출발점과 정류장 좌표에서 정확히 시작·끝 (다음 버스 구간과 이어짐)
        assertEquals(home, walk.points.first())
        assertEquals(stop, walk.points.last())
        // 겹친 점은 하나로
        assertEquals(walk.points.size, walk.points.zipWithNext().count { (a, b) -> a != b } + 1)
        // 걷는 거리·시간은 실제 길 + 이어 붙인 몫 (정류장 12m, 출발점 6m)
        val joined = Geo.distanceM(home, Coord(36.37005, 127.43002)) + Geo.distanceM(Coord(36.37115, 127.43168), stop)
        assertEquals(260 + joined.toInt(), walk.distanceM, 2.0)
        assertTrue(walk.timeSec >= 210)
        // 나머지는 그대로
        assertSame(route.steps[1], r.steps[1])
        assertSame(route.steps[2], r.steps[2])
        assertEquals(route.totalTimeSec, r.totalTimeSec)
        assertEquals(listOf(2), TransitWalks.pending(r))
    }

    @Test
    fun badAnswersLeaveTheStraightLine() {
        val empty = SimpleRoute(TravelMode.WALK, 0, 0, emptyList())
        assertSame(route, TransitWalks.apply(route, 0, empty))
        assertSame("버스 구간은 바꾸지 않음", route, TransitWalks.apply(route, 1, walkToStop()))
        assertSame(route, TransitWalks.apply(route, 9, walkToStop()))
        // 엉뚱한 곳의 길(출발점·정류장에서 300m 넘게 떨어짐)은 쓰지 않는다
        val far = SimpleRoute(TravelMode.WALK, 500, 400, listOf(PathStep("x", 500, 400, dest, listOf(dest, lastStop))))
        assertSame(route, TransitWalks.apply(route, 0, far))
    }

    @Test
    fun enrichFetchesBothLegsAndKeepsWhatFails(): Unit = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<Coord, Coord>>())
        val r = WalkPaths.enrich(route) { from, to ->
            calls += from to to
            if (from == home) walkToStop() else throw java.io.IOException("끊김")
        }
        assertEquals(setOf(home to stop, lastStop to dest), calls.toSet())
        assertTrue(r.steps[0].pathFound)
        assertFalse("못 받은 구간은 직선 그대로", r.steps[2].pathFound)
        assertEquals(listOf(lastStop, dest), r.steps[2].points)
    }

    @Test
    fun enrichNeverHoldsUpTheStartForLong(): Unit = runBlocking {
        val started = System.nanoTime()
        val r = WalkPaths.enrich(route, timeoutMs = 300) { _, _ -> Thread.sleep(3_000); walkToStop() }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("느린 응답을 기다리지 않는다 ($tookMs ms)", tookMs < 1_500)
        assertSame(route, r)
        // 바꿀 구간이 없으면 부르지도 않는다
        val done = TransitWalks.apply(TransitWalks.apply(route, 0, walkToStop()), 2, walkToStop().copy(steps = listOf(
            PathStep("출발", 150, 120, lastStop, listOf(lastStop, dest)),
        )))
        assertSame(done, WalkPaths.enrich(done) { _, _ -> error("부르면 안 됨") })
    }

    /** 기차 경로: 역까지 직선 도보(어림 5분) → KTX → 목적지까지 직선 도보(어림 3분). 나가야 할 시각·도착 시각이 정해져 있다 */
    private val depAt = 1_800_000_000_000L
    private val arrAt = depAt + 60 * 60_000L
    private val train = TransitRoute(
        "TRAIN", 160_600, 4_500, 0, 23_700,
        listOf(
            TransitStep(TransitStepType.WALK, "대전역까지 도보", 400, 300, emptyList(), emptyList(), listOf(home, stop), approx = true),
            TransitStep(
                TransitStepType.TRAIN, "KTX 105 · 대전 → 서울", 160_000, 3_600, listOf("대전", "서울"), listOf(Vehicle("KTX", "105")),
                listOf(stop, lastStop), departAtMs = depAt, arriveAtMs = arrAt,
            ),
            TransitStep(TransitStepType.WALK, "도착지까지 도보", 200, 180, emptyList(), emptyList(), listOf(lastStop, dest), approx = true),
        ),
        arriveAtMs = arrAt + 180_000, leaveByMs = depAt - (300 + 600) * 1000L,
    )

    @Test
    fun scheduledRoutesLeaveAndArriveByTheRealWalk() {
        // 역까지 실제 길이 어림과 다르면 '늦어도 이때 출발'이 그만큼 당겨지거나 늦춰진다 (열차 시각은 그대로)
        val a = TransitWalks.apply(train, 0, walkToStop())
        val toStation = a.steps[0].timeSec - 300
        assertEquals(train.leaveByMs!! - toStation * 1000L, a.leaveByMs)
        assertEquals(train.arriveAtMs, a.arriveAtMs)
        assertEquals(train.totalTimeSec, a.totalTimeSec)
        assertEquals(train.totalDistanceM + a.steps[0].distanceM - 400, a.totalDistanceM)
        // 내려서 목적지까지 실제 길이 더 길면 도착 시각·남은 시간이 그만큼 늦어진다 (나가야 할 시각은 그대로)
        val longWay = SimpleRoute(TravelMode.WALK, 330, 300, listOf(PathStep("출발", 330, 300, lastStop, listOf(lastStop, Coord(36.3497, 127.3843), dest))))
        val b = TransitWalks.apply(a, 2, longWay)
        val toDest = b.steps[2].timeSec - 180
        assertEquals(120, toDest)
        assertEquals(a.arriveAtMs!! + toDest * 1000L, b.arriveAtMs)
        assertEquals(a.totalTimeSec + toDest, b.totalTimeSec)
        assertEquals(a.leaveByMs, b.leaveByMs)
        // 시내 경로는 카카오가 준 소요 시간·거리 그대로 (카카오맵 목록과 같은 숫자), 시각은 없음
        val city = TransitWalks.apply(route, 0, walkToStop())
        assertEquals(route.totalDistanceM, city.totalDistanceM)
        assertEquals(null, city.leaveByMs)
        assertEquals(null, city.arriveAtMs)
    }

    @Test
    fun noRouteAnswersAreNotLoggedAndOtherFailuresOnlyOncePerCall(): Unit = runBlocking {
        val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
        // 길이 없다는 답(아주 가까움 등)은 오류가 아님 — 오류 기록(최근 30줄)을 밀어내지 않게
        WalkPaths.enrich(route, log = { _, e -> logged += e.message.orEmpty() }) { _, _ -> throw RouteStatusException("104", "출발지와 도착지가 너무 가까워요") }
        assertEquals(emptyList<String>(), logged)
        // 끊김은 한 번 여는 데 한 줄만
        WalkPaths.enrich(route, log = { _, e -> logged += e.message.orEmpty() }) { _, _ -> throw ApiException("네트워크 연결을 확인하세요") }
        assertEquals(listOf("네트워크 연결을 확인하세요"), logged)
    }

    @Test
    fun enrichedRoutesAreKeptBesideTheSearchResultsNotInThem() {
        val other = route.copy(totalTimeSec = 3_000)
        val third = route.copy(totalTimeSec = 3_600)
        val list = listOf(route, other)
        try {
            RouteRepository.transitRoutes = list
            val walked = TransitWalks.apply(route, 0, walkToStop())
            RouteRepository.rememberWalked(route, walked)
            // 목록은 그대로 — 길찾기 화면은 '같은 검색 결과인지'를 목록 객체로 가려낸다(실시간 도착·시간표 찾기)
            assertSame(list, RouteRepository.transitRoutes)
            assertSame(walked, RouteRepository.walked(route))
            assertSame(other, RouteRepository.walked(other))
            // 같은 경로가 든 새 목록(기차·버스 결과를 붙임·지난 편 빼기)에서도 기억, 새로 찾으면 잊는다
            RouteRepository.transitRoutes = list + third
            assertSame(walked, RouteRepository.walked(route))
            RouteRepository.transitRoutes = listOf(third)
            assertSame(route, RouteRepository.walked(route))
            // 지난 검색의 경로(늦게 온 응답)는 기억하지 않는다
            RouteRepository.rememberWalked(route, walked)
            assertSame(route, RouteRepository.walked(route))
        } finally {
            RouteRepository.transitRoutes = null
        }
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)
}
