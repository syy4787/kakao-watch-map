package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Action
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.nav.NavEvent
import com.mywatch.kakaomap.ui.NavOverlayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

/**
 * 가짜 GPS 로 길안내 전체를 재생하는 시뮬레이션.
 * 실제 걷는 속도·위치 간격·GPS 잡음(σ 3~5m)·빌딩 반사로 튄 위치(40~60m)·정류장에서 기다리기·
 * 회전을 놓치기·정류장 지나치기·도착 뒤 떠나기를 재현해서 진동(이벤트)과 안내 문구를 확인한다.
 */
class GuidanceSimulationTest {

    // ── 가짜 GPS ──
    private data class Fix(val c: Coord, val t: Long, val acc: Float = 5f)

    private fun lerp(a: Coord, b: Coord, f: Double) = Coord(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)

    /** 경로 위 s 미터 지점 */
    private fun pointAt(path: List<Coord>, s: Double): Coord {
        var left = s
        for (i in 0 until path.size - 1) {
            val len = Geo.distanceM(path[i], path[i + 1])
            if (left <= len) return lerp(path[i], path[i + 1], if (len == 0.0) 0.0 else left / len)
            left -= len
        }
        return path.last()
    }

    private fun length(path: List<Coord>) = (0 until path.size - 1).sumOf { Geo.distanceM(path[it], path[it + 1]) }

    private fun noisy(c: Coord, sigma: Double, rnd: Random): Coord {
        val dn = rnd.nextGaussian() * sigma
        val de = rnd.nextGaussian() * sigma
        return Coord(c.lat + dn / 111_320.0, c.lng + de / (111_320.0 * cos(Math.toRadians(c.lat))))
    }

    private fun Random.nextGaussian(): Double {
        // Box–Muller
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** path 를 speed(m/s)로 dt 간격 따라가는 위치들 (시작 시각 t0) */
    private fun walk(path: List<Coord>, speed: Double, dtMs: Long, sigma: Double, rnd: Random, t0: Long = 0, from: Double = 0.0, to: Double = length(path)): List<Fix> {
        val out = ArrayList<Fix>()
        var s = from
        var t = t0
        while (s <= to) {
            out += Fix(noisy(pointAt(path, s), sigma, rnd), t)
            s += speed * dtMs / 1000.0
            t += dtMs
        }
        return out
    }

    private class Run(val engine: NavEngine) {
        val events = ArrayList<Pair<NavEvent, Int>>()     // 이벤트와 그때의 위치 번호
        val steps = ArrayList<Int>()
        fun feed(fixes: List<Fix>, onEach: (Int, Fix) -> Unit = { _, _ -> }) {
            fixes.forEachIndexed { i, f ->
                engine.update(f.c, f.acc, f.t).forEach { events += it to i }
                steps += engine.state.stepIndex
                onEach(i, f)
            }
        }
        fun count(e: NavEvent) = events.count { it.first == e }
    }

    // ── 경로: 동쪽 200m 걷고 역전약국 앞에서 좌회전 150m (위도 37° 기준) ──
    private val a = Coord(37.0, 127.0)
    private val b = Coord(37.0, 127.0 + 200 / 88_900.0)
    private val c = Coord(37.0 + 150 / 111_130.0, b.lng)
    private val lRoute = listOf(
        GuideStep("역전약국까지 200m 이동", listOf(a, b), turnGuidance = true),
        GuideStep("역전약국 앞에서 좌회전 후 150m 이동(대전로)", listOf(b, c), turnGuidance = true),
    )
    private val lPath = listOf(a, b, c)

    @Test
    fun leftTurnIsAnnouncedOnceBeforeTheCorner() {
        val run = Run(NavEngine(lRoute, c, 1))
        val fixes = walk(lPath, 1.4, 1_000, 3.0, Random(1))
        var sawAhead = false
        var sawNow = false
        var sawAfter = false
        run.feed(fixes) { i, _ ->
            val s = i * 1.4
            val (main, sub) = NavOverlayText.bannerLines(run.engine.state.guide ?: "")
            if (s in 60.0..120.0) {
                assertTrue(main, Regex("""\d+m 후 좌회전""").matches(main))
                assertEquals("역전약국 앞 · 대전로", sub)
                assertEquals(Action.LEFT, run.engine.state.action)
                // 지도 배너: 왼쪽 아이콘 아래 거리, 오른쪽엔 짧은 할 일 + 장소 하나
                val st = run.engine.state
                assertEquals("좌회전", st.bannerTitle)
                assertEquals("역전약국 앞", st.bannerSub)
                assertTrue(st.bannerDistM!! in 70..150)
                sawAhead = true
            }
            if (s in 193.0..199.0 && main == "지금 좌회전") {
                assertEquals("지금 좌회전", run.engine.state.bannerTitle)
                assertEquals(null, run.engine.state.bannerDistM)
                sawNow = true
            }
            if (s in 260.0..300.0) {
                assertTrue(main, Regex("""\d+m 후 도착""").matches(main))
                sawAfter = true
            }
        }
        assertTrue(sawAhead && sawNow && sawAfter)
        assertEquals("좌회전 진동은 한 번", 1, run.count(NavEvent.TURN_SOON_LEFT))
        val at = run.events.first { it.first == NavEvent.TURN_SOON_LEFT }.second * 1.4
        assertTrue("모퉁이 15~45m 앞에서 알림 (실제 ${200 - at}m 앞)", (200 - at) in 15.0..45.0)
        assertEquals(0, run.count(NavEvent.OFF_ROUTE))
        assertEquals(1, run.count(NavEvent.ARRIVED))
    }

    @Test
    fun straightBoundariesAreSilentAndCrosswalkIsAnnounced() {
        // 동 150m → (교량 진입) 동 100m → 횡단보도 남쪽 20m → 왼쪽길로 동 120m
        val p0 = Coord(37.0, 127.0)
        val p1 = Coord(37.0, 127.0 + 150 / 88_900.0)
        val p2 = Coord(37.0, 127.0 + 250 / 88_900.0)
        val p3 = Coord(37.0 - 20 / 111_130.0, p2.lng)
        val p4 = Coord(p3.lat, p2.lng + 120 / 88_900.0)
        val route = listOf(
            GuideStep("150m 이동", listOf(p0, p1), turnGuidance = true),
            GuideStep("교량 진입 후 100m 이동", listOf(p1, p2), turnGuidance = true),
            GuideStep("햇살과일 앞에서 횡단보도 이용", listOf(p2, p3), turnGuidance = true),
            GuideStep("왼쪽길로 120m 이동", listOf(p3, p4), turnGuidance = true),
        )
        val run = Run(NavEngine(route, p4, 1))
        run.feed(walk(listOf(p0, p1, p2, p3, p4), 1.3, 1_000, 3.0, Random(7))) { i, _ ->
            val s = i * 1.3
            if (s in 40.0..60.0) {
                val (main, sub) = NavOverlayText.bannerLines(run.engine.state.guide!!)
                assertTrue("직진 구간은 건너뛰고 다음 할 일까지 거리: $main", Regex("""(1[89]\d|2[01]\d)m 후 횡단보도 건너기""").matches(main))
                assertEquals("햇살과일 앞", sub)
            }
        }
        assertEquals(1, run.count(NavEvent.ACTION_SOON))
        assertEquals("남쪽→동쪽은 왼쪽", 1, run.count(NavEvent.TURN_SOON_LEFT))
        assertEquals(0, run.count(NavEvent.TURN_SOON_RIGHT))
        val cross = run.events.indexOfFirst { it.first == NavEvent.ACTION_SOON }
        val left = run.events.indexOfFirst { it.first == NavEvent.TURN_SOON_LEFT }
        assertTrue("횡단보도 → 좌회전 순서", cross < left)
        assertEquals(0, run.count(NavEvent.OFF_ROUTE))
    }

    // ── U자 경로: 동 300m → 북 60m → 서 300m (다리 사이 60m) ──
    private val u0 = Coord(37.0, 127.0)
    private val u1 = Coord(37.0, 127.0 + 300 / 88_900.0)
    private val u2 = Coord(37.0 + 60 / 111_130.0, u1.lng)
    private val u3 = Coord(u2.lat, 127.0)
    private val uRoute = listOf(
        GuideStep("300m 이동", listOf(u0, u1), turnGuidance = true),
        GuideStep("좌회전 후 60m 이동", listOf(u1, u2), turnGuidance = true),
        GuideStep("좌회전 후 300m 이동", listOf(u2, u3), turnGuidance = true),
    )

    @Test
    fun aSingleGpsOutlierDoesNotJumpAheadOrStrandTheGuidance() {
        val run = Run(NavEngine(uRoute, u3, 1))
        val rnd = Random(3)
        val fixes = walk(listOf(u0, u1, u2, u3), 1.4, 1_000, 3.0, rnd).toMutableList()
        // 60초째(첫 다리 84m 지점)에 빌딩 반사로 55m 북쪽(셋째 다리 근처)에 찍힌 위치 한 번
        val i = 60
        fixes[i] = fixes[i].copy(c = Coord(fixes[i].c.lat + 55 / 111_130.0, fixes[i].c.lng), acc = 20f)
        run.feed(fixes)
        assertEquals("튄 위치 때문에 셋째 구간으로 건너뛰지 않음", 0, run.steps[i])
        assertTrue("튄 위치에 진동 없음", run.events.none { it.second == i })
        assertEquals("이탈 오판 없음", 0, run.count(NavEvent.OFF_ROUTE))
        assertEquals("두 번의 좌회전", 2, run.count(NavEvent.TURN_SOON_LEFT))
        assertEquals(1, run.count(NavEvent.ARRIVED))
        assertTrue("구간 번호는 뒤로 가지 않음", run.steps.zipWithNext().all { (x, y) -> y >= x })
    }

    @Test
    fun multipathBurstsDoNotCauseFalseAlerts() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            val fixes = walk(lPath, 1.4, 1_000, 4.0, rnd).map { f ->
                if (rnd.nextDouble() < 0.05) {                       // 5% 는 40~60m 튄 위치
                    val d = 40 + rnd.nextDouble() * 20
                    val ang = rnd.nextDouble() * 2 * Math.PI
                    f.copy(c = Coord(f.c.lat + d * kotlin.math.sin(ang) / 111_130.0, f.c.lng + d * kotlin.math.cos(ang) / 88_900.0), acc = 25f)
                } else f
            }
            val run = Run(NavEngine(lRoute, c, 1))
            run.feed(fixes)
            assertEquals("seed $seed: 이탈 오판", 0, run.count(NavEvent.OFF_ROUTE))
            assertEquals("seed $seed: 좌회전 알림", 1, run.count(NavEvent.TURN_SOON_LEFT))
            assertEquals("seed $seed: 도착", 1, run.count(NavEvent.ARRIVED))
        }
    }

    @Test
    fun missedTurnIsCaughtWithinAboutThirtyMetres() {
        val run = Run(NavEngine(lRoute, c, 1))
        // 모퉁이에서 꺾지 않고 동쪽으로 60m 더 감
        val beyond = Coord(37.0, b.lng + 60 / 88_900.0)
        val fixes = walk(listOf(a, beyond), 1.4, 1_000, 3.0, Random(5))
        run.feed(fixes)
        val off = run.events.firstOrNull { it.first == NavEvent.OFF_ROUTE }
        assertTrue("이탈 감지", off != null)
        val past = off!!.second * 1.4 - 200
        assertTrue("모퉁이를 ${past}m 지나서 감지 (45m 이내여야)", past in 10.0..45.0)
    }

    // ── 대중교통: 동쪽 250m 걸어 정류장 → 버스로 옆 길(11m 북쪽) 서쪽 1.2km → 내려서 40m ──
    private val s0 = Coord(37.0, 127.0)
    private val stop = Coord(37.0, 127.0 + 250 / 88_900.0)
    private val busStart = Coord(37.0001, stop.lng)
    private val busEnd = Coord(37.0001, stop.lng - 1_200 / 88_900.0)
    private val dest = Coord(37.0001 + 40 / 111_130.0, busEnd.lng)
    private val transit = listOf(
        GuideStep("시청역까지 도보", listOf(s0, stop), approx = true),
        GuideStep("617번 버스 · 시청역 → 은하수네거리", listOf(busStart, busEnd), ride = true, alight = "은하수네거리", line = "617번", board = "시청역"),
        GuideStep("도보 1분 · 40m", listOf(busEnd, dest)),
    )

    @Test
    fun waitingAtTheStopNeverLooksLikeRidingAndAlertsComeInOrder() {
        val run = Run(NavEngine(transit, dest, 1))
        val rnd = Random(11)
        // 정류장까지 걷기 (1초 간격)
        var fixes = walk(listOf(s0, stop), 1.3, 1_000, 3.0, rnd)
        var t = fixes.last().t
        // 정류장에서 5분 기다림: 5초 간격, σ 5m 흔들림
        val wait = (1..60).map { k -> Fix(noisy(stop, 5.0, rnd), t + k * 5_000L) }
        t = wait.last().t
        // 버스: 8m/s, 5초 간격으로 내릴 곳까지
        val ride = walk(listOf(busStart, busEnd), 8.0, 5_000, 5.0, rnd, t0 = t + 5_000)
        run.feed(fixes)
        assertEquals("정류장 30m 앞에서 승차 알림", 1, run.count(NavEvent.BOARD_SOON))
        run.feed(wait)
        assertTrue("기다리는 동안엔 버스에 탄 걸로 보지 않음", run.steps.takeLast(wait.size).all { it == 0 })
        val (main, sub) = NavOverlayText.bannerLines(run.engine.state.guide!!)
        assertEquals("617번 승차", main)
        assertEquals("시청역", sub)
        assertEquals("617번 승차", run.engine.state.bannerTitle)
        assertEquals("시청역", run.engine.state.bannerSub)
        assertEquals(null, run.engine.state.bannerDistM)
        assertEquals(0, run.count(NavEvent.ALIGHT_SOON))
        run.feed(ride)
        assertEquals("탄 뒤엔 버스 구간", 1, run.engine.state.stepIndex)
        assertEquals("곧 하차", run.engine.state.bannerTitle)
        assertEquals("은하수네거리", run.engine.state.bannerSub)
        assertEquals("하차 알림 한 번", 1, run.count(NavEvent.ALIGHT_SOON))
        assertEquals(0, run.count(NavEvent.OFF_ROUTE))
    }

    @Test
    fun ridingBannerShowsLineAndWhereToGetOff() {
        val run = Run(NavEngine(transit, dest, 1, destinationName = "은하수 아파트"))
        val rnd = Random(4)
        run.feed(walk(listOf(busStart, busEnd), 8.0, 5_000, 3.0, rnd, from = 100.0, to = 400.0))
        val st = run.engine.state
        assertEquals(1, st.stepIndex)
        assertEquals("617번 타는 중", st.bannerTitle)
        assertEquals("은하수네거리 하차", st.bannerSub)
        assertTrue(st.bannerDistM!! in 750..950)
    }

    @Test
    fun ridingPastTheStopIsReportedOnce() {
        val run = Run(NavEngine(transit, dest, 1))
        val rnd = Random(21)
        val beyond = Coord(37.0001, busEnd.lng - 400 / 88_900.0)    // 내릴 곳을 400m 지나침
        run.feed(walk(listOf(busStart, beyond), 8.0, 5_000, 4.0, rnd, from = 100.0))
        assertEquals(1, run.count(NavEvent.PASSED_STOP))
        assertTrue(run.engine.state.guide!!.startsWith("정류장을 지났어요"))
        assertEquals("정류장 지남", run.engine.state.bannerTitle)
        assertTrue(run.engine.state.passedStop)
    }

    @Test
    fun guidanceFinishesAfterArrivalWhenWalkingAwayOrAfterFiveMinutes() {
        val run = Run(NavEngine(lRoute, c, 1))
        run.feed(walk(lPath, 1.4, 1_000, 3.0, Random(2)))
        assertTrue(run.engine.state.arrived)
        assertFalse(run.engine.state.finished)
        val t = 400_000L
        // 목적지 근처에서 1분 머묾 → 아직 끝내지 않음
        run.engine.update(Coord(c.lat + 20 / 111_130.0, c.lng), 5f, t)
        assertFalse(run.engine.state.finished)
        // 350m 떠나면 끝 (두 번 연속 확인)
        run.engine.update(Coord(c.lat + 350 / 111_130.0, c.lng), 5f, t + 60_000)
        run.engine.update(Coord(c.lat + 355 / 111_130.0, c.lng), 5f, t + 61_000)
        assertTrue(run.engine.state.finished)

        val stay = Run(NavEngine(lRoute, c, 2))
        stay.feed(walk(lPath, 1.4, 1_000, 3.0, Random(2)))
        stay.engine.update(c, 5f, 900_000L)        // 도착하고 5분 넘게 지남
        assertTrue(stay.engine.state.finished)
    }

    // ── 검토에서 나온 경계 상황 ──

    @Test
    fun aBadFixOnALaterBusLineDoesNotFreezeTheTrip() {
        // 걷기 300m → 1번 버스(동쪽 1km) → 30m 걷기 → 3번 버스(첫 도보 40m 북쪽 길을 서쪽으로) → 걷기
        val w0a = Coord(37.0, 127.0)
        val s1 = Coord(37.0, 127.0 + 300 / 88_900.0)
        val b1a = Coord(37.0001, s1.lng)
        val e1 = Coord(37.0001, s1.lng + 1_000 / 88_900.0)
        val s3 = Coord(37.0001 + 30 / 111_130.0, e1.lng)
        val north = 37.0 + 40 / 111_130.0
        val b3a = Coord(north, e1.lng)
        val e3 = Coord(north, 126.995)
        val d = Coord(north + 30 / 111_130.0, 126.995)
        val route = listOf(
            GuideStep("정류장까지 도보", listOf(w0a, s1), approx = true),
            GuideStep("1번 버스", listOf(b1a, e1), ride = true, alight = "E1", line = "1번", board = "S1"),
            GuideStep("도보 1분", listOf(e1, s3)),
            GuideStep("3번 버스", listOf(b3a, e3), ride = true, alight = "E3", line = "3번", board = "S3"),
            GuideStep("도보", listOf(e3, d)),
        )
        val run = Run(NavEngine(route, d, 1))
        val rnd = Random(8)
        val path = listOf(w0a, s1)
        run.feed(walk(path, 1.3, 1_000, 3.0, rnd, to = 150.0))
        // 90초 GPS 끊김 뒤 첫 위치가 40m 북쪽(3번 버스 길)으로 튐
        val t = 115_000L + 90_000L
        val trueNow = pointAt(path, 150 + 1.3 * 90)
        run.feed(listOf(Fix(Coord(trueNow.lat + 38 / 111_130.0, trueNow.lng), t, 12f)))
        assertEquals("튄 위치로 3번 버스 쪽으로 건너뛰지 않음", 0, run.engine.state.stepIndex)
        // 이어서 정상 위치로 정류장까지
        run.feed(walk(path, 1.3, 1_000, 3.0, rnd, t0 = t + 1_000, from = 150 + 1.3 * 91))
        assertEquals("1번 승차", run.engine.state.bannerTitle)
        assertEquals(1, run.count(NavEvent.BOARD_SOON))
    }

    @Test
    fun theVeryFirstFixLandingOnALaterBusLineIsNotTrusted() {
        // 안내 시작 직후 첫 위치가 40m 북쪽 '3번 버스' 길 위로 튐 (비교할 이전 위치가 없음)
        val s1 = Coord(37.0, 127.0 + 300 / 88_900.0)
        val e1 = Coord(37.0001, s1.lng + 1_000 / 88_900.0)
        val s3 = Coord(37.0001 + 30 / 111_130.0, e1.lng)
        val north = 37.0 + 40 / 111_130.0
        val e3 = Coord(north, 126.995)
        val d = Coord(north + 30 / 111_130.0, 126.995)
        val route = listOf(
            GuideStep("정류장까지 도보", listOf(Coord(37.0, 127.0), s1), approx = true),
            GuideStep("1번 버스", listOf(Coord(37.0001, s1.lng), e1), ride = true, alight = "E1", line = "1번", board = "S1"),
            GuideStep("도보 1분", listOf(e1, s3)),
            GuideStep("3번 버스", listOf(Coord(north, e1.lng), e3), ride = true, alight = "E3", line = "3번", board = "S3"),
            GuideStep("도보", listOf(e3, d)),
        )
        val run = Run(NavEngine(route, d, 1))
        run.feed(listOf(Fix(Coord(north, 127.0 + 100 / 88_900.0), 0, 12f)))
        assertFalse("첫 위치 하나로 3번 버스 승차 안내를 하지 않음", run.engine.state.bannerTitle == "3번 승차")
        assertEquals(0, run.count(NavEvent.BOARD_SOON))
        run.feed(walk(listOf(Coord(37.0, 127.0), s1), 1.3, 1_000, 3.0, Random(9), t0 = 1_000, from = 100.0, to = 200.0))
        assertEquals(0, run.engine.state.stepIndex)
        assertTrue(run.engine.state.bannerTitle!!.startsWith("1번") || run.engine.state.bannerTitle == "1번 승차")
    }

    @Test
    fun aBusWaitingAtALightAfterPassingTheStopKeepsThePassedState() {
        val run = Run(NavEngine(transit, dest, 1))
        val rnd = Random(21)
        val beyond = Coord(37.0001, busEnd.lng - 600 / 88_900.0)
        run.feed(walk(listOf(busStart, beyond), 8.0, 5_000, 3.0, rnd, from = 100.0, to = 1_600.0))
        assertTrue(run.engine.state.passedStop)
        // 600m 지난 곳에서 신호 대기 (거의 안 움직임)
        val t = run.engine.state.let { 1_000_000L }
        run.feed((1..6).map { k -> Fix(noisy(beyond, 2.0, rnd), t + k * 5_000L) })
        assertTrue("지나친 상태 유지", run.engine.state.passedStop)
        assertEquals("내려서 걷는 걸로 바꾸지 않음", 1, run.engine.state.stepIndex)
        assertEquals(1, run.count(NavEvent.PASSED_STOP))
        assertEquals("이탈 진동 없음 (이미 '지남'으로 알림)", 0, run.count(NavEvent.OFF_ROUTE))
        assertTrue(run.engine.state.guide!!.startsWith("정류장을 지났어요"))
    }

    // ── 지하철: 역까지 걸어가 지하로 들어간 뒤엔 기지국 위치(정확도 150~300m)만 들어온다 ──
    private val st0 = Coord(37.0, 127.0)
    private val station = Coord(37.0, 127.0 + 200 / 88_900.0)
    private val subEnd = Coord(37.0, station.lng + 3_000 / 88_900.0)
    private val subDest = Coord(37.0 + 50 / 111_130.0, subEnd.lng)
    private val subway = listOf(
        GuideStep("역까지 도보", listOf(st0, station), approx = true),
        GuideStep("1호선", listOf(station, subEnd), ride = true, subway = true, alight = "정부청사", line = "1호선", board = "대전역"),
        GuideStep("도보", listOf(subEnd, subDest)),
    )

    private fun coarse(c: Coord, rnd: Random, acc: Float) = Fix(noisy(c, acc * 0.5, rnd), 0, acc)

    @Test
    fun undergroundSubwayRideWorksWithCellTowerFixes() {
        val run = Run(NavEngine(subway, subDest, 1))
        val rnd = Random(31)
        run.feed(walk(listOf(st0, station), 1.3, 1_000, 3.0, rnd))
        var t = 200_000L
        // 승강장에서 3분 기다림: 기지국 위치만
        val wait = (1..18).map { k -> coarse(station, rnd, 200f).copy(t = t + k * 10_000L) }
        run.feed(wait)
        assertEquals(0, run.engine.state.stepIndex)
        assertTrue("기다리는 동안에도 지하철 저전력 위치", run.engine.state.onSubway)
        t = wait.last().t
        // 지하철로 3km (10초 간격, 15m/s, 정확도 150~300m)
        val ride = (1..20).map { k ->
            val s = (k * 150.0).coerceAtMost(3_000.0)
            coarse(pointAt(listOf(station, subEnd), s), rnd, (150 + rnd.nextInt(150)).toFloat()).copy(t = t + k * 10_000L)
        }
        run.feed(ride)
        assertEquals("지하철에 탄 것으로 인식", 1, run.engine.state.stepIndex)
        assertEquals("하차 알림", 1, run.count(NavEvent.ALIGHT_SOON))
        assertEquals("정류장 지나침 오판 없음", 0, run.count(NavEvent.PASSED_STOP))
    }

    @Test
    fun coarseFixesBesideTheLineNearTheEndAreNotAPassedStop() {
        val run = Run(NavEngine(subway, subDest, 1))
        val rnd = Random(41)
        run.feed(walk(listOf(station, subEnd), 15.0, 10_000, 5.0, rnd, from = 100.0, to = 2_400.0))
        assertEquals(1, run.engine.state.stepIndex)
        // 역 바로 앞까지 와서, 노선에서 170~200m 옆으로 찍힌 기지국 위치 두 번 (정확도 300m, 열차는 계속 움직임)
        val t = run.events.size.let { 2_400_000L / 15 }
        val a1 = pointAt(listOf(station, subEnd), 2_972.0)
        val a2 = pointAt(listOf(station, subEnd), 2_999.0)
        run.feed(listOf(
            Fix(Coord(a1.lat + 180 / 111_130.0, a1.lng), t + 5_000, 300f),
            Fix(Coord(a2.lat + 190 / 111_130.0, a2.lng), t + 10_000, 300f),
        ))
        assertEquals(0, run.count(NavEvent.PASSED_STOP))
    }

    @Test
    fun finishingAfterArrivalNeedsTwoFarFixes() {
        val run = Run(NavEngine(lRoute, c, 1))
        run.feed(walk(lPath, 1.4, 1_000, 3.0, Random(2)))
        assertTrue(run.engine.state.arrived)
        run.engine.update(Coord(c.lat + 400 / 111_130.0, c.lng), 20f, 300_000L)   // 튄 위치 한 번
        assertFalse(run.engine.state.finished)
        run.engine.update(Coord(c.lat + 20 / 111_130.0, c.lng), 5f, 301_000L)
        run.engine.update(Coord(c.lat + 350 / 111_130.0, c.lng), 5f, 360_000L)
        run.engine.update(Coord(c.lat + 360 / 111_130.0, c.lng), 5f, 361_000L)
        assertTrue(run.engine.state.finished)
    }
}
