package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.KakaoParsers
import com.mywatch.kakaomap.model.LiveBus
import com.mywatch.kakaomap.model.TransitAccess
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.nav.LocPower
import com.mywatch.kakaomap.nav.LocationPolicy
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.nav.NavEvent
import com.mywatch.kakaomap.nav.NavState
import com.mywatch.kakaomap.net.LiveBusTracker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

/**
 * 시내 버스 타고 내리기 — 가짜 GPS 로 재생해서 카카오맵처럼 안내하는지 확인한다.
 * 위치만 보면 버스 길과 걸을 길이 겹칠 때(내려서 같은 길로 걷기, 걸어온 길로 되짚어 가는 버스, 같은 정류장에서 갈아타기)
 * 헷갈리므로 속도(8초 넘는 구간 평균)와 내릴 곳에서 머문 시간도 본다.
 */
class BusRideTest {

    // ── 가짜 GPS ──
    private data class Fix(val c: Coord, val t: Long, val acc: Float = 5f)

    private fun lerp(a: Coord, b: Coord, f: Double) = Coord(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)

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

    private fun Random.gauss(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private fun noisy(c: Coord, sigma: Double, rnd: Random) =
        Coord(c.lat + rnd.gauss() * sigma / 111_320.0, c.lng + rnd.gauss() * sigma / (111_320.0 * cos(Math.toRadians(c.lat))))

    /** path 를 speed(m/s)로 dt 간격 따라가는 위치들 */
    private fun move(
        path: List<Coord>, speed: Double, dtMs: Long, sigma: Double, rnd: Random, t0: Long,
        from: Double = 0.0, to: Double = length(path),
    ): List<Fix> {
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

    private fun stay(at: Coord, seconds: Int, dtMs: Long, sigma: Double, rnd: Random, t0: Long): List<Fix> =
        (1..(seconds * 1000 / dtMs).toInt()).map { k -> Fix(noisy(at, sigma, rnd), t0 + k * dtMs) }

    private class Run(val engine: NavEngine) {
        val events = ArrayList<Pair<NavEvent, String>>()
        val titles = ArrayList<String?>()
        fun feed(label: String, fixes: List<Fix>) {
            fixes.forEach { f ->
                engine.update(f.c, f.acc, f.t).forEach { events += it to label }
                titles += engine.state.bannerTitle
            }
        }
        fun count(e: NavEvent) = events.count { it.first == e }
        fun count(e: NavEvent, label: String) = events.count { it.first == e && it.second == label }
    }

    // ── 동쪽으로 2km 가는 5번 버스 (정류장 500·1000·1500·2000m, 2000m 가 내릴 곳) ──
    private fun east(m: Double, north: Double = 0.0) = Coord(37.0 + north / 111_130.0, 127.0 + m / 88_900.0)

    private fun busRoute(egressTo: Coord) = listOf(
        GuideStep("정류장까지 도보", listOf(east(-200.0, -60.0), east(0.0)), approx = true),
        GuideStep("5번 버스", listOf(east(0.0), east(2_000.0)), ride = true, alight = "E", line = "5번", board = "S", timeSec = 300),
        GuideStep("목적지까지 도보", listOf(east(2_000.0), egressTo), approx = true),
    )

    private fun ridingEngine(route: List<GuideStep>, dest: Coord): Pair<NavEngine, Long> {
        val e = NavEngine(route, dest, 1, destinationName = "목적지")
        e.setStops(1, listOf(500.0, 1_000.0, 1_500.0, 2_000.0))
        var t = 0L
        var s = 100.0
        while (s < 1_990.0) {      // 버스: 8m/s, 3초 간격
            e.update(east(s), 5f, t)
            s += 24.0
            t += 3_000
        }
        return e to t
    }

    @Test
    fun missingTheStopWhileTheBusFollowsTheWalkIsReportedNotArrived() {
        // 내릴 곳 다음 도보가 버스 길을 따라 150m 앞(목적지가 길가) — 내리지 않고 버스가 계속 가면
        // 예전엔 도보 구간으로 넘어가 목적지 앞을 지날 때 '도착' 진동 + 안내 종료였다
        for (dest in listOf(east(2_150.0), east(2_150.0, 40.0))) {
            val (e, t0) = ridingEngine(busRoute(dest), dest)
            val events = ArrayList<NavEvent>()
            var t = t0
            var s = 1_990.0
            while (s <= 3_000.0) {
                events += e.update(east(s), 5f, t)
                s += 24.0
                t += 3_000
            }
            assertEquals("정류장 지남 한 번", 1, events.count { it == NavEvent.PASSED_STOP })
            assertFalse("차 속도로 지나가는 목적지는 도착이 아님", NavEvent.ARRIVED in events)
            assertTrue(e.state.passedStop)
            assertEquals(1, e.state.stepIndex)
            assertEquals("정류장 지남", e.state.bannerTitle)
        }
    }

    @Test
    fun walkingBackAlongTheBusRoadAfterGettingOffArrives() {
        // 목적지가 내린 정류장 150m 뒤(버스가 온 길가) — 예전엔 버스 구간에 머물러 '곧 하차'에 남은 거리만 늘고 도착을 못 했다
        val dest = east(1_850.0)
        val (e, t0) = ridingEngine(busRoute(dest), dest)
        var t = t0
        repeat(4) { e.update(east(2_000.0), 5f, t); t += 3_000 }   // 정류장에 섬
        val events = ArrayList<NavEvent>()
        var m = 2_000.0
        while (m >= 1_850.0) {
            events += e.update(east(m), 5f, t)
            m -= 2.6
            t += 2_000
        }
        repeat(5) { events += e.update(dest, 5f, t); t += 2_000 }
        assertEquals("목적지에서 도착", 1, events.count { it == NavEvent.ARRIVED })
        assertTrue(e.state.arrived)
        assertEquals(0, events.count { it == NavEvent.PASSED_STOP || it == NavEvent.OFF_ROUTE })
    }

    @Test
    fun aBusDwellingLongAtTheStopThenLeavingIsStillAMissedStop() {
        // 내릴 정류장에서 버스가 60초 서 있다가(사용자는 안 내림) 다시 출발 — '내렸다'고 본 것을 되돌려 정류장 지남을 알린다
        val dest = east(2_150.0, 40.0)
        val (e, t0) = ridingEngine(busRoute(dest), dest)
        var t = t0
        val events = ArrayList<NavEvent>()
        repeat(20) { events += e.update(east(2_000.0), 5f, t); t += 3_000 }
        var s = 2_000.0
        while (s <= 3_000.0) {
            events += e.update(east(s), 5f, t)
            s += 24.0
            t += 3_000
        }
        assertEquals(1, events.count { it == NavEvent.PASSED_STOP })
        assertFalse(NavEvent.ARRIVED in events)
        assertTrue(e.state.passedStop)
    }

    @Test
    fun walkingPastTheStopAlongTheBusRoadIsNotBoarding() {
        // 정류장에 왔다가 버스 가는 방향으로 걸어서 지나감(다음 정류장까지 걷기 등) — 예전엔 60m 지나면 '탔다'
        val route = busRoute(east(2_000.0, 40.0))
        val e = NavEngine(route, east(2_000.0, 40.0), 1)
        val rnd = Random(3)
        var t = 0L
        val toStop = move(route[0].points, 1.3, 1_000, 3.0, rnd, t)
        toStop.forEach { e.update(it.c, it.acc, it.t) }
        t = toStop.last().t
        move(listOf(east(0.0), east(2_000.0)), 1.3, 2_000, 3.0, rnd, t + 2_000, to = 150.0).forEach { e.update(it.c, it.acc, it.t) }
        assertFalse("걷는 속도로는 버스에 탄 것이 아님", e.state.onRide)
        assertEquals(0, e.state.stepIndex)
        assertEquals("5번 승차", e.state.bannerTitle)
    }

    @Test
    fun aBusLeavingBackAlongTheWalkedStreetIsBoardedSoon() {
        // 걸어온 길(실제 걷는 길처럼 점 여러 개)을 따라 정류장에 왔는데, 버스는 길 건너(11m 북쪽)에서 그 길을 되짚어 감 —
        // 예전엔 500m 넘게 '○○m 후 102번 승차'(거리가 늘어남)로 남아 짧은 구간 하차 알림을 놓칠 수 있었다
        val walk = (0..8).map { east(it * 50.0) }
        val dest = east(-1_600.0, 50.0)
        val route = listOf(
            GuideStep("정류장까지 도보", walk, approx = true),
            GuideStep("102번 버스", listOf(east(400.0, 11.0), east(-1_600.0, 11.0)), ride = true, alight = "B", line = "102번", board = "A", timeSec = 400),
            GuideStep("도보", listOf(east(-1_600.0, 11.0), dest)),
        )
        val e = NavEngine(route, dest, 1)
        var t = 0L
        var m = 0.0
        while (m <= 400.0) {
            e.update(east(m), 5f, t)
            m += 1.3
            t += 1_000
        }
        repeat(24) { e.update(east(400.0, 3.0), 5f, t); t += 5_000 }
        assertEquals("102번 승차", e.state.bannerTitle)
        var boardedAt: Double? = null
        var x = 400.0
        while (x >= -700.0) {
            e.update(east(x, 11.0), 5f, t)
            if (boardedAt == null && e.state.onRide && e.state.stepIndex == 1) boardedAt = 400 - x
            x -= 24.0
            t += 3_000
        }
        assertTrue("떠난 뒤 150m 안에 탄 것으로 봄 (실제 ${boardedAt}m)", boardedAt != null && boardedAt <= 150.0)
        assertTrue(e.state.onRide)
    }

    // ── 실제 대전 응답(대전광역시청 → 대전역)의 '일반 106 → 일반 514' 같은 정류장(남선공원네거리) 환승 — 경로 좌표는 3개마다 하나 ──
    private val transferJson = """
        {"status":"OK","routes":[{"properties":{"type":"BUS","totalDistance":6258,"totalTime":2075,"transfers":1,"fare":{"value":1500}},"steps":[
        {"properties":{"guidance":"일반 106 (대전광역시청 > 남선공원네거리)","type":"BUS","distance":1948,"time":652,
          "stops":[{"name":"대전광역시청"},{"name":"시청역"},{"name":"목련아파트"},{"name":"청솔아파트"},{"name":"보라삼거리"},{"name":"남선공원네거리"}],
          "vehicles":[{"name":"106","type":"일반"}]},
         "path":{"points":[[127.3851,36.351208],[127.386843,36.351238],[127.388644,36.351191],[127.390104,36.351218],[127.392084,36.35122],
          [127.39719,36.351206],[127.397405,36.351174],[127.401601,36.350404],[127.401754,36.348894],[127.401623,36.347356]]}},
        {"properties":{"guidance":"일반 514 (남선공원네거리 > 대전역)","type":"BUS","distance":3840,"time":984,
          "stops":[{"name":"남선공원네거리"},{"name":"중촌초등학교"},{"name":"중촌동행정복지센터"},{"name":"세명요양병원"},{"name":"대전여상"},
            {"name":"중앙로역7번출구"},{"name":"으능정이거리"},{"name":"대전역"}],
          "vehicles":[{"name":"514","type":"일반"}]},
         "path":{"points":[[127.401623,36.347356],[127.401658,36.344839],[127.401832,36.344698],[127.405518,36.344318],[127.407194,36.343483],
          [127.409839,36.341408],[127.410163,36.341101],[127.413242,36.33873],[127.415877,36.336623],[127.417654,36.335201],[127.419577,36.333632],
          [127.421147,36.332387],[127.423828,36.330247],[127.42591,36.328649],[127.427404,36.329147],[127.430259,36.330275],[127.431936,36.330877]]}}]}]}
    """.trimIndent()

    private val cityHall = Coord(36.3504, 127.3849)
    private val daejeonStation = Coord(36.3323, 127.4346)

    @Test
    fun sameStopTransferShowsTheNextBusAfterGettingOff() {
        for (seed in 1..10) sameStopTransfer(seed)
    }

    private fun sameStopTransfer(seed: Int) {
        val r = TransitAccess.withAccessWalks(KakaoParsers.transitRoutes(JSONObject(transferJson)).single(), cityHall, daejeonStation, "대전역")
        val steps = TransitGuide.steps(r)
        assertEquals(listOf("도보", "106", "514", "도보"), r.steps.map { TransitGuide.label(it) })
        val run = Run(NavEngine(steps, daejeonStation, 1, destinationName = "대전역"))
        val rnd = Random(seed)
        val bus106 = steps[1].points
        val bus514 = steps[2].points
        var t = 0L
        run.feed("walk", move(steps[0].points, 1.3, 1_000, 3.0, rnd, t).also { t = it.last().t })
        run.feed("wait106", stay(bus106.first(), 120, 5_000, 4.0, rnd, t).also { t = it.last().t })
        run.feed("ride106", move(bus106, 8.0, 3_000, 4.0, rnd, t + 3_000).also { t = it.last().t })
        // 106에서 내려 같은 정류장에서 514를 5분 기다림
        val wait = stay(bus514.first(), 300, 5_000, 4.0, rnd, t).also { t = it.last().t }
        val waitTitles = ArrayList<String?>()
        wait.forEach { f ->
            run.engine.update(f.c, f.acc, f.t).forEach { run.events += it to "wait514" }
            waitTitles += run.engine.state.bannerTitle
        }
        assertTrue("내릴 곳에서 카카오맵처럼 '514번 환승': ${run.titles.takeLast(5)} / ${waitTitles.take(12)}", run.titles.any { it == "514번 환승" } || waitTitles.any { it == "514번 환승" })
        assertEquals("106 내릴 곳 앞에서 하차 알림 한 번", 1, run.count(NavEvent.ALIGHT_SOON))
        assertEquals("내린 뒤 514 승차 알림 한 번 (떠나기 전에)", 1, run.count(NavEvent.BOARD_SOON, "wait514"))
        // 1분 안에 '514번 승차'로 바뀌고 끝까지 그대로
        val firstBoard = waitTitles.indexOfFirst { it == "514번 승차" }
        assertTrue("내리고 1분 남짓 안에 514번 승차 (${firstBoard * 5}초)", firstBoard in 0..14)
        assertTrue("그 뒤로는 계속 514번 승차: ${waitTitles.drop(firstBoard)}", waitTitles.drop(firstBoard).all { it == "514번 승차" })
        assertEquals("남선공원네거리", run.engine.state.bannerSub)
        assertTrue("기다리는 동안엔 위치를 5초 간격으로", run.engine.state.waitingBus)
        assertEquals(2, run.engine.state.boardingStep)
        // 514가 떠나면 탄 것으로
        run.feed("ride514", move(bus514, 8.0, 3_000, 4.0, rnd, t + 3_000, to = 600.0))
        assertEquals(2, run.engine.state.stepIndex)
        assertTrue("514 타는 중", run.engine.state.onRide)
        assertEquals("지남·이탈 오판 없음: ${run.events}", 0, run.count(NavEvent.PASSED_STOP) + run.count(NavEvent.OFF_ROUTE))
    }

    @Test
    fun theRidingRulesHoldWithGpsNoise() {
        // 위 상황들을 GPS 잡음(σ 4m)으로 20가지씩 — 버스는 8m/s·3초 간격, 걷기는 1.3m/s
        for (seed in 1..20) {
            val rnd = Random(seed)
            fun ride(e: NavEngine, from: Double, to: Double, t0: Long): Long {
                var t = t0
                var s = from
                while (s < to) {
                    e.update(noisy(east(s), 4.0, rnd), 6f, t)
                    s += 24.0
                    t += 3_000
                }
                return t
            }
            fun engine(dest: Coord) = NavEngine(busRoute(dest), dest, 1).also { it.setStops(1, listOf(500.0, 1_000.0, 1_500.0, 2_000.0)) }
            // 내리지 않고 걸을 길(같은 길)을 따라 계속 감 → 지남 한 번, 도착 아님
            engine(east(2_150.0)).let { e ->
                val events = ArrayList<NavEvent>()
                var t = ride(e, 100.0, 1_990.0, 0)
                var s = 1_990.0
                while (s <= 3_000.0) {
                    events += e.update(noisy(east(s), 4.0, rnd), 6f, t)
                    s += 24.0
                    t += 3_000
                }
                assertEquals("seed $seed 지남", 1, events.count { it == NavEvent.PASSED_STOP })
                assertFalse("seed $seed 도착 오판", NavEvent.ARRIVED in events)
            }
            // 내려서 버스가 온 길로 되돌아 걸음 → 도착
            engine(east(1_850.0)).let { e ->
                var t = ride(e, 100.0, 1_990.0, 0)
                repeat(6) { e.update(noisy(east(2_000.0), 4.0, rnd), 6f, t); t += 3_000 }
                var m = 2_000.0
                while (m >= 1_850.0) {
                    e.update(noisy(east(m, 5.0), 4.0, rnd), 6f, t)
                    m -= 2.6
                    t += 2_000
                }
                repeat(8) { e.update(noisy(east(1_850.0), 3.0, rnd), 5f, t); t += 2_000 }
                assertTrue("seed $seed 되돌아 걸어 도착", e.state.arrived)
            }
            // 내릴 곳에 60초 선 버스에서 안 내림 → 지남
            engine(east(2_150.0, 40.0)).let { e ->
                val events = ArrayList<NavEvent>()
                var t = ride(e, 100.0, 2_000.0, 0)
                repeat(20) { events += e.update(noisy(east(2_000.0), 4.0, rnd), 6f, t); t += 3_000 }
                var s = 2_020.0
                while (s <= 3_000.0) {
                    events += e.update(noisy(east(s), 4.0, rnd), 6f, t)
                    s += 24.0
                    t += 3_000
                }
                assertEquals("seed $seed 오래 선 버스에서 지남", 1, events.count { it == NavEvent.PASSED_STOP })
                assertFalse(NavEvent.ARRIVED in events)
            }
            // 정류장을 걸어서 지나침 → 탄 것 아님
            NavEngine(busRoute(east(2_000.0, 40.0)), east(2_000.0, 40.0), 1).let { e ->
                var t = 0L
                move(busRoute(east(0.0))[0].points, 1.3, 1_000, 3.0, rnd, t).forEach { e.update(it.c, it.acc, it.t); t = it.t }
                move(listOf(east(0.0), east(2_000.0)), 1.3, 2_000, 3.0, rnd, t + 2_000, to = 150.0).forEach { e.update(it.c, it.acc, it.t) }
                assertFalse("seed $seed 걸어서 지나침", e.state.onRide)
            }
        }
    }

    @Test
    fun endToEndRidesWithNoiseGiveEachAlertOnce() {
        // 걷기 → 3분 기다림 → 버스(중간 정류장마다 30초 정차) → 내려서 15초 → 목적지까지 걷기, 30가지 잡음
        val dest = east(2_120.0, 30.0)
        val route = busRoute(dest)
        for (seed in 1..30) {
            val rnd = Random(seed)
            val run = Run(NavEngine(route, dest, 1))
            run.engine.setStops(1, listOf(500.0, 1_000.0, 1_500.0, 2_000.0))
            var t = 0L
            run.feed("walk", move(route[0].points, 1.3, 1_000, 4.0, rnd, t).also { t = it.last().t })
            run.feed("wait", stay(east(0.0), 180, 5_000, 4.0, rnd, t).also { t = it.last().t })
            var s = 0.0
            val ride = ArrayList<Fix>()
            while (s < 2_000.0) {
                ride += Fix(noisy(east(s), 4.0, rnd), t)
                t += 3_000
                if (listOf(500.0, 1_000.0, 1_500.0).any { kotlin.math.abs(it - s) < 12 }) {
                    repeat(10) { ride += Fix(noisy(east(s), 4.0, rnd), t); t += 3_000 }
                }
                s += 24.0
            }
            run.feed("ride", ride)
            run.feed("dwell", stay(east(2_000.0), 20, 3_000, 4.0, rnd, t).also { t = it.last().t })
            run.feed("off", stay(east(2_000.0, 6.0), 15, 3_000, 4.0, rnd, t).also { t = it.last().t })
            run.feed("walk2", move(listOf(east(2_000.0, 6.0), dest), 1.3, 1_000, 4.0, rnd, t).also { t = it.last().t })
            run.feed("dest", stay(dest, 20, 2_000, 3.0, rnd, t))
            assertEquals("seed $seed 승차", 1, run.count(NavEvent.BOARD_SOON))
            assertEquals("seed $seed 하차", 1, run.count(NavEvent.ALIGHT_SOON))
            assertEquals("seed $seed 지남·이탈 오판", 0, run.count(NavEvent.PASSED_STOP) + run.count(NavEvent.OFF_ROUTE))
            assertEquals("seed $seed 도착", 1, run.count(NavEvent.ARRIVED))
        }
    }

    // ── 실시간 도착을 안내에 붙이기 ──

    private val s0 = Coord(37.0, 127.0)
    private val stop = Coord(37.0, 127.0 + 250 / 88_900.0)
    private val liveRoute = listOf(
        GuideStep("시청역까지 도보", listOf(s0, stop), approx = true),
        GuideStep("617번 버스 · 시청역 → 은하수네거리", listOf(Coord(37.0001, stop.lng), Coord(37.0001, stop.lng - 1_200 / 88_900.0)),
            ride = true, alight = "은하수네거리", line = "617번", board = "시청역"),
        GuideStep("도보", listOf(Coord(37.0001, stop.lng - 1_200 / 88_900.0), Coord(37.0005, stop.lng - 1_200 / 88_900.0))),
    )

    @Test
    fun liveArrivalShowsInTheBannerAndBuzzesOnceWhenTheBusIsClose() {
        val e = NavEngine(liveRoute, liveRoute[2].points.last(), 1)
        assertEquals("처음부터 타러 갈 버스 구간", 1, e.state.boardingStep)
        // 정류장으로 걷는 중 (190m 남음): 도착 정보는 정류장 이름과 함께, 아직 멀어 진동 없음
        e.update(Coord(37.0, 127.0 + 60 / 88_900.0), 5f, 0)
        assertTrue(e.setLiveArrival(1, LiveBus("617번 1분 (1번째 전)", "617번 1분 (1번째 전)", 90)).isEmpty())
        assertEquals("617번 승차", e.state.bannerTitle)
        assertEquals("시청역 · 617번 1분 (1번째 전)", e.state.bannerSub)
        assertTrue("알림 첫 줄에도", e.state.guide!!.lines().first().endsWith("617번 승차 · 617번 1분 (1번째 전)"))
        // 정류장에 닿음: 둘째 줄은 도착 정보만, 2분 안이면 한 번 진동
        var t = 1_000L
        var m = 60.0
        while (m <= 250.0) {
            e.update(Coord(37.0, 127.0 + m / 88_900.0), 5f, t)
            m += 1.3
            t += 1_000
        }
        assertEquals(listOf(NavEvent.BUS_ARRIVING), e.setLiveArrival(1, LiveBus("617번 곧 도착", "617번 곧 도착", 40)))
        assertEquals("617번 곧 도착", e.state.bannerSub)
        assertTrue("두 번은 울리지 않음", e.setLiveArrival(1, LiveBus("617번 곧 도착", "617번 곧 도착", 20)).isEmpty())
        // 정보가 없으면 정류장 이름으로 돌아감 ('도착 정보 없음'을 배너에 띄우지 않음)
        e.setLiveArrival(1, LiveBus("도착 정보 없음"))
        assertEquals("시청역", e.state.bannerSub)
        // 다른 구간(도보)·지하철엔 붙이지 않음
        assertTrue(e.setLiveArrival(0, LiveBus("x", "x", 10)).isEmpty())
    }

    @Test
    fun liveArrivalIsAskedOnlyWhileGoingToOrWaitingForACityBus() {
        fun step(type: TransitStepType) = TransitStep(
            type, "", 1000, 300, stops = listOf("가", "나"),
            vehicles = if (type == TransitStepType.WALK) emptyList() else listOf(Vehicle("일반", "617")),
            points = listOf(s0, stop),
        )
        val route = TransitRoute("BUS", 3000, 1200, 0, 1500, listOf(step(TransitStepType.WALK), step(TransitStepType.BUS), step(TransitStepType.WALK)))
        val walking = NavState(stepIndex = 0, matched = true, boardingStep = 1)
        assertEquals(1, LiveBusTracker.targetStep(route, walking, navigating = true))
        assertEquals(1, LiveBusTracker.targetStep(route, walking.copy(waitingBus = true), navigating = true))
        assertNull("지도에서 보기만 하면 묻지 않음", LiveBusTracker.targetStep(route, walking, navigating = false))
        assertNull("타는 중엔 묻지 않음", LiveBusTracker.targetStep(route, walking.copy(onRide = true, boardingStep = null), navigating = true))
        assertNull(LiveBusTracker.targetStep(route, walking.copy(offRoute = true), navigating = true))
        assertNull(LiveBusTracker.targetStep(route, walking.copy(arrived = true), navigating = true))
        val subway = route.copy(steps = listOf(step(TransitStepType.WALK), step(TransitStepType.SUBWAY), step(TransitStepType.WALK)))
        assertNull("지하철은 실시간 버스가 아님", LiveBusTracker.targetStep(subway, walking, navigating = true))
    }

    @Test
    fun waitingAtABusStopUsesFiveSecondGpsEvenWhenShuffling() {
        val waiting = NavState(routeVersion = 1, stepIndex = 0, matched = true, toStepEndM = 0, toActionM = 0, waitingBus = true)
        // 서성여도(1m/s) 5초, 서 있어도 위치는 받음(실시간 도착을 45초마다 새로 묻기 위해 거리 조건 없음)
        val shuffling = LocationPolicy.navigation(waiting, false, 1.0f, 0)
        assertEquals(LocPower.HIGH, shuffling.power)
        assertEquals(5_000L, shuffling.intervalMs)
        assertEquals(0f, shuffling.minDistanceM)
        assertEquals(5_000L, LocationPolicy.navigation(waiting, false, 0f, 0).intervalMs)
        // 버스가 떠나는 속도면 다시 촘촘히 (탔는지 빨리 알게)
        assertEquals(1_000L, LocationPolicy.navigation(waiting, false, 6f, 0).intervalMs)
    }
}
