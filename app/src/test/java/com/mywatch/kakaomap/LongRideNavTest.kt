package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.LongRide
import com.mywatch.kakaomap.nav.LocPower
import com.mywatch.kakaomap.nav.LocationPolicy
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.nav.NavEvent
import com.mywatch.kakaomap.nav.NavIcon
import com.mywatch.kakaomap.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.random.Random

/**
 * 기차·고속버스 타는 길안내를 가짜 GPS 로 재생.
 * KTX 선로는 역과 역을 이은 직선에서 9km 넘게 벗어나 굽고, 기차 안에선 GPS 대신 기지국 위치(정확도 1.2km)가 오기도 한다.
 * 역에서 20분 기다리기(실내라 위치가 수십~수백 m 흔들림), 타기, 하차 알림, 내려서 걸어가기, 지나치기, 놓치기를 확인한다.
 */
class LongRideNavTest {

    private val seoulZone = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 13, h, m).atZone(seoulZone).toInstant().toEpochMilli()

    // ── 장소 ──
    private val home = Coord(36.3370, 127.4300)            // 대전역에서 북서쪽 약 600m
    private val daejeonSt = Coord(36.3326, 127.4344)
    private val seoulSt = Coord(37.5547, 126.9707)
    private val cityHall = Coord(37.5663, 126.9779)

    /** 실제 경부선처럼 굽은 선로 (신탄진 → 조치원 → 천안(직선에서 서쪽 9km) → 수원 → 영등포 → 용산 → 서울역) */
    private val track = listOf(
        daejeonSt, Coord(36.4510, 127.4286), Coord(36.6000, 127.3000), Coord(36.8100, 127.1460),
        Coord(37.0000, 127.0700), Coord(37.2660, 127.0000), Coord(37.4800, 126.9000), Coord(37.5298, 126.9648), seoulSt,
    )

    private fun steps() = listOf(
        GuideStep("대전역까지 도보", listOf(home, daejeonSt), approx = true),
        GuideStep(
            "KTX 105 · 대전역 → 서울역", listOf(daejeonSt, seoulSt), ride = true, alight = "서울역", line = "KTX 105",
            board = "대전역", timeSec = 53 * 60, longRide = LongRide.TRAIN, departAtMs = at(9, 58), arriveAtMs = at(10, 51),
        ),
        GuideStep("서울시청까지 도보", listOf(seoulSt, cityHall), approx = true),
    )

    // ── 가짜 GPS ──
    private data class Fix(val c: Coord, val t: Long, val acc: Float)

    private fun length(path: List<Coord>) = (0 until path.size - 1).sumOf { Geo.distanceM(path[it], path[it + 1]) }

    private fun pointAt(path: List<Coord>, s: Double): Coord {
        var left = s
        for (i in 0 until path.size - 1) {
            val len = Geo.distanceM(path[i], path[i + 1])
            if (left <= len) {
                val f = if (len == 0.0) 0.0 else left / len
                return Coord(path[i].lat + (path[i + 1].lat - path[i].lat) * f, path[i].lng + (path[i + 1].lng - path[i].lng) * f)
            }
            left -= len
        }
        return path.last()
    }

    private fun Random.gauss(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    private fun noisy(c: Coord, sigma: Double, rnd: Random) = Coord(
        c.lat + rnd.gauss() * sigma / 111_320.0,
        c.lng + rnd.gauss() * sigma / (111_320.0 * cos(Math.toRadians(c.lat))),
    )

    /** [path] 를 [seconds] 동안 고르게 지나가는 위치 ([everyMs] 간격) */
    private fun move(path: List<Coord>, t0: Long, seconds: Double, everyMs: Long, acc: (Double) -> Float, sigma: (Double) -> Double, rnd: Random): List<Fix> {
        val len = length(path)
        val out = ArrayList<Fix>()
        var t = 0L
        while (t <= seconds * 1000) {
            val s = len * (t / 1000.0) / seconds
            out += Fix(noisy(pointAt(path, s), sigma(s), rnd), t0 + t, acc(s))
            t += everyMs
        }
        return out
    }

    /** 앱 시계(부팅 후 ms)와 벽시계를 함께 가는 재생기. 벽시계 0 = 9:30 */
    private inner class Trip(seed: Int) {
        val rnd = Random(seed)
        var nowElapsed = 0L
        val wallStart = at(9, 30)
        val engine = NavEngine(steps(), cityHall, routeVersion = 1, destinationName = "서울시청", clock = { wallStart + nowElapsed })
        val events = ArrayList<Pair<NavEvent, Long>>()
        val states = ArrayList<NavState>()
        /** 위치마다 (시각, 실제 좌표) — states 와 같은 순서 */
        val fixes = ArrayList<Fix>()

        fun play(list: List<Fix>) = list.forEach { f ->
            nowElapsed = f.t
            engine.update(f.c, f.acc, f.t).forEach { events += it to f.t }
            states += engine.state
            fixes += f
        }

        /** 집 → 대전역 걸어서 (약 600m, 7분 반) */
        fun walkToStation() = play(move(listOf(home, daejeonSt), 0, 460.0, 2_000, { 5f }, { 3.0 }, rnd))

        /** 역 안에서 [untilWall] 까지 기다림 — 실내라 20~80m, 가끔 300m(기지국) 정확도로 흔들림 */
        fun waitAtStation(untilWall: Long) {
            val fixes = ArrayList<Fix>()
            var t = nowElapsed + 5_000
            while (wallStart + t <= untilWall) {
                val coarse = rnd.nextDouble() < 0.1
                val acc = if (coarse) 300f else (20 + rnd.nextDouble() * 60).toFloat()
                fixes += Fix(noisy(daejeonSt, if (coarse) 200.0 else acc / 2.0, rnd), t, acc)
                t += 5_000
            }
            play(fixes)
        }

        /** KTX: 9:58 출발 → 10:51 서울역. 가운데 구간은 GPS 대신 기지국 위치(1.2km)를 1분마다 */
        fun ride(path: List<Coord> = track, seconds: Double = 53 * 60.0) {
            val t0 = at(9, 58) - wallStart
            val len = length(path)
            val fixes = ArrayList<Fix>()
            var t = 0L
            while (t <= seconds * 1000) {
                val s = len * (t / 1000.0) / seconds
                val coarse = s in 30_000.0..90_000.0
                fixes += Fix(noisy(pointAt(path, s), if (coarse) 700.0 else 8.0, rnd), t0 + t, if (coarse) 1200f else 15f)
                t += if (coarse) 60_000 else 10_000
            }
            play(fixes)
        }

        /** 서울역 → 서울시청 걸어서 (약 1.4km, 18분) */
        fun walkToCityHall() = play(move(listOf(seoulSt, cityHall), at(10, 52) - wallStart, 1080.0, 2_000, { 5f }, { 3.0 }, rnd))

        fun count(e: NavEvent) = events.count { it.first == e }
    }

    @Test
    fun ktxTripFromHomeToSeoulCityHall() {
        val trip = Trip(seed = 7)
        trip.walkToStation()
        assertEquals("역 앞에서 승차 진동 한 번", 1, trip.count(NavEvent.BOARD_SOON))
        trip.engine.state.let {
            assertEquals("KTX 105 승차", it.bannerTitle)
            assertEquals("09:58 출발", it.bannerSub)
            assertEquals(NavIcon.TRAIN, it.icon)
            assertFalse(it.onRide)
        }

        trip.waitAtStation(at(9, 57))
        assertTrue("역에서 기다리는 동안엔 탄 것으로 보지 않음", trip.states.none { it.onRide })
        assertEquals(1, trip.count(NavEvent.BOARD_SOON))
        assertTrue(trip.events.none { it.first == NavEvent.OFF_ROUTE || it.first == NavEvent.MISSED_RIDE })
        assertTrue(trip.engine.state.guide!!.contains("09:58 출발"))

        trip.ride()
        val riding = trip.states.filter { it.onRide }
        assertTrue("기차를 탔다고 봄", riding.isNotEmpty())
        assertTrue(riding.all { it.onLongRide && it.stepIndex == 1 && it.icon == NavIcon.TRAIN })
        // 출발 뒤 1.5km 쯤(약 1분)에서 탔다고 본다 — 선로가 직선과 달라도
        val boardedAt = trip.states.indexOfFirst { it.onRide }
        assertTrue(boardedAt >= 0)
        assertTrue("선로가 직선에서 9km 벗어나도 이탈·지나침 없음", trip.events.none {
            it.first == NavEvent.OFF_ROUTE || it.first == NavEvent.PASSED_STOP || it.first == NavEvent.MISSED_RIDE
        })
        assertEquals("하차 알림은 한 번", 1, trip.count(NavEvent.ALIGHT_SOON))
        // 하차 알림은 서울역 4km 안, 도착 5분 안쪽에서 (기차 안이라 탄 상태 그대로)
        val alertT = trip.events.first { it.first == NavEvent.ALIGHT_SOON }.second
        val i = trip.fixes.indexOfFirst { it.t == alertT }
        assertTrue(trip.states[i].onRide)
        assertTrue("서울역까지 ${Geo.distanceM(trip.fixes[i].c, seoulSt)}m", Geo.distanceM(trip.fixes[i].c, seoulSt) <= 4_100)
        assertTrue(at(10, 51) - (trip.wallStart + alertT) in 0..5 * 60_000L)
        assertEquals("곧 하차", trip.states[i].bannerTitle)
        // 타는 중 배너: 시간표 도착 시각 기준 'n분 후 하차' + 서울역
        val midRide = riding[riding.size / 3]
        assertTrue(midRide.bannerTitle!!, midRide.bannerTitle!!.endsWith("분 후 하차"))
        assertEquals("서울역 10:51", midRide.bannerSub)
        // 멀리 있을 땐 다음 할 일까지 거리 = 서울역까지 직선거리 (위치 간격을 정하는 데 씀)
        assertTrue(midRide.toActionM!! > 10_000)

        trip.walkToCityHall()
        assertEquals(1, trip.count(NavEvent.ARRIVED))
        assertTrue(trip.engine.state.arrived)
        assertTrue(trip.events.none { it.first == NavEvent.OFF_ROUTE || it.first == NavEvent.PASSED_STOP })
    }

    @Test
    fun theTripHoldsUpForManyDifferentGpsNoises() {
        for (seed in 1..25) {
            val trip = Trip(seed)
            trip.walkToStation()
            trip.waitAtStation(at(9, 57))
            trip.ride()
            trip.walkToCityHall()
            val bad = trip.events.filter { it.first in setOf(NavEvent.OFF_ROUTE, NavEvent.PASSED_STOP, NavEvent.MISSED_RIDE) }
            assertTrue("seed $seed: $bad", bad.isEmpty())
            assertEquals("seed $seed 승차", 1, trip.count(NavEvent.BOARD_SOON))
            assertEquals("seed $seed 하차", 1, trip.count(NavEvent.ALIGHT_SOON))
            assertEquals("seed $seed 도착", 1, trip.count(NavEvent.ARRIVED))
            // 역에서 기다리는 동안(9:57 까지)은 한 번도 '탔다'가 아님
            val waitEnd = trip.fixes.indexOfLast { trip.wallStart + it.t <= at(9, 57) }
            assertTrue("seed $seed", trip.states.take(waitEnd + 1).none { it.onRide })
        }
    }

    @Test
    fun stayingOnTheTrainPastSeoulIsReportedOnce() {
        val trip = Trip(seed = 11)
        trip.walkToStation()
        trip.waitAtStation(at(9, 57))
        // 서울역에서 내리지 않고 행신 쪽(북서 15km)으로 계속 감
        val beyond = track + listOf(Coord(37.5800, 126.9100), Coord(37.6123, 126.8340))
        trip.ride(beyond, seconds = 63 * 60.0)
        assertEquals(1, trip.count(NavEvent.ALIGHT_SOON))
        assertEquals("지나침은 한 번", 1, trip.count(NavEvent.PASSED_STOP))
        assertTrue(trip.engine.state.passedStop)
        val passedT = trip.events.first { it.first == NavEvent.PASSED_STOP }.second
        val alightT = trip.events.first { it.first == NavEvent.ALIGHT_SOON }.second
        assertTrue(passedT > alightT)
    }

    @Test
    fun missingTheTrainIsReportedSoTheNextOneCanBeFound() {
        val trip = Trip(seed = 3)
        trip.walkToStation()
        trip.waitAtStation(at(10, 6))
        assertEquals("역에 있으면 출발 10분 뒤까지는 기다려 줌(늦게 떠날 수도)", 0, trip.count(NavEvent.MISSED_RIDE))
        trip.waitAtStation(at(10, 10))
        assertEquals(1, trip.count(NavEvent.MISSED_RIDE))
        assertTrue(trip.engine.state.missedRide)
        assertEquals(NavIcon.WARNING, trip.engine.state.icon)
        assertFalse(trip.engine.state.onRide)
    }

    @Test
    fun cellTowerFixesAfterDepartureMeanBoardedNotMissed() {
        // 기차 안에서 GPS 가 안 잡혀 기지국 위치(1.5km)만 1분마다, 20km 지나서야 GPS — '놓침'이 아니라 '탔음'
        val trip = Trip(seed = 21)
        trip.walkToStation()
        trip.waitAtStation(at(9, 57))
        val t0 = at(9, 58) - trip.wallStart
        val len = length(track)
        val speed = len / (53 * 60.0)
        val fixes = ArrayList<Fix>()
        var t = 0L
        while (t <= 25 * 60_000L) {
            val s = speed * t / 1000.0
            val gps = s > 20_000
            fixes += Fix(noisy(pointAt(track, s), if (gps) 8.0 else 900.0, trip.rnd), t0 + t, if (gps) 15f else 1500f)
            t += if (gps) 10_000 else 60_000
        }
        trip.play(fixes)
        assertEquals(0, trip.count(NavEvent.MISSED_RIDE))
        assertTrue(trip.engine.state.onRide && trip.engine.state.onLongRide)
        assertEquals(1, trip.engine.state.stepIndex)
    }

    @Test
    fun aTrainLeavingSixMinutesLateIsNotMissed() {
        val trip = Trip(seed = 4)
        trip.walkToStation()
        trip.waitAtStation(at(10, 4))
        assertEquals("역에서 기다리는 중엔 출발 10분 뒤까지 놓침으로 보지 않음", 0, trip.count(NavEvent.MISSED_RIDE))
        // 10:04 에 떠나 서울역으로
        val t0 = at(10, 4) - trip.wallStart
        trip.play(move(track, t0, 53 * 60.0, 10_000, { 15f }, { 8.0 }, trip.rnd))
        assertEquals(0, trip.count(NavEvent.MISSED_RIDE))
        assertEquals(1, trip.count(NavEvent.ALIGHT_SOON))
    }

    @Test
    fun trainDoublingBackAlongTheWalkToTheStationStaysBoarded() {
        // 남영역 근처 집 → 서울역 걸어서, KTX 강릉행은 서울역에서 남쪽(용산)으로 집 옆을 다시 지나 동쪽으로 돈다
        val start = Coord(37.5410, 126.9715)
        val seoul = Coord(37.5547, 126.9707)
        val gangneung = Coord(37.7638, 128.8995)
        val wall0 = at(9, 30)
        var now = 0L
        val e = NavEngine(
            listOf(
                GuideStep("서울역까지 도보", listOf(start, seoul), approx = true),
                GuideStep("KTX-이음 815", listOf(seoul, gangneung), ride = true, alight = "강릉역", line = "KTX-이음 815",
                    board = "서울역", timeSec = 115 * 60, longRide = LongRide.TRAIN, departAtMs = at(9, 58), arriveAtMs = at(11, 53)),
                GuideStep("목적지까지 도보", listOf(gangneung, Coord(37.7700, 128.9000)), approx = true),
            ),
            Coord(37.7700, 128.9000), 1, clock = { wall0 + now },
        )
        val rnd = Random(9)
        val events = ArrayList<NavEvent>()
        fun play(list: List<Fix>) = list.forEach { f -> now = f.t; events += e.update(f.c, f.acc, f.t) }
        play(move(listOf(start, seoul), 0, 1200.0, 2_000, { 5f }, { 3.0 }, rnd))
        play((1..24).map { Fix(noisy(seoul, 15.0, rnd), 1_200_000L + it * 20_000L, 25f) })   // 9:50~9:58 기다림
        val track = listOf(seoul, Coord(37.5410, 126.9725), Coord(37.5298, 126.9648), Coord(37.5405, 127.0181), Coord(37.5803, 127.0470), Coord(37.6400, 127.2000))
        play(move(track, at(9, 58) - wall0, 20 * 60.0, 10_000, { 10f }, { 6.0 }, rnd))
        assertTrue("$events", events.none { it == NavEvent.MISSED_RIDE || it == NavEvent.OFF_ROUTE || it == NavEvent.PASSED_STOP })
        assertTrue(e.state.onRide && e.state.onLongRide)
        assertEquals(1, e.state.stepIndex)
    }

    @Test
    fun aRouteThatStartsWithTheTrainItself() {
        // 역에서 놓친 뒤 다시 찾은 경로: 역까지 걷는 구간 없이 바로 기차부터
        val wall0 = at(9, 50)
        var now = 0L
        val e = NavEngine(
            listOf(
                GuideStep("KTX 107", listOf(daejeonSt, seoulSt), ride = true, alight = "서울역", line = "KTX 107", board = "대전역",
                    timeSec = 53 * 60, longRide = LongRide.TRAIN, departAtMs = at(10, 12), arriveAtMs = at(11, 5)),
                GuideStep("서울시청까지 도보", listOf(seoulSt, cityHall), approx = true),
            ),
            cityHall, 1, clock = { wall0 + now },
        )
        val rnd = Random(2)
        val events = ArrayList<NavEvent>()
        var t = 0L
        while (wall0 + t <= at(10, 30)) {
            now = t
            events += e.update(noisy(daejeonSt, 20.0, rnd), 30f, t)
            t += 10_000
        }
        assertFalse(e.state.onRide)
        assertEquals("역에서 출발 10분 뒤(10:22)가 지나면 놓침", 1, events.count { it == NavEvent.MISSED_RIDE })
    }

    @Test
    fun coachCrawlingOutOfTheTerminalInTrafficIsNotMissed() {
        val a = Coord(36.3500, 127.4370)       // 대전복합
        val b = Coord(36.6400, 127.4300)       // 청주
        val wall0 = at(9, 55)
        var now = 0L
        val e = NavEngine(
            listOf(
                GuideStep("터미널까지 도보", listOf(Coord(36.3480, 127.4350), a), approx = true),
                GuideStep("시외 우등", listOf(a, b), ride = true, alight = "청주", line = "시외버스 우등", board = "대전복합",
                    timeSec = 66 * 60, longRide = LongRide.COACH, departAtMs = at(10, 0), arriveAtMs = at(11, 6)),
            ),
            b, 1, clock = { wall0 + now },
        )
        val rnd = Random(8)
        val events = ArrayList<NavEvent>()
        fun at2(t: Long, c: Coord) { now = t; events += e.update(noisy(c, 5.0, rnd), 10f, t) }
        var t = 0L
        while (wall0 + t < at(10, 0)) { at2(t, a); t += 5_000 }
        // 10:00 출발했지만 막혀서 12분 동안 북쪽으로 600m 만 감 (가다 서다), 그 뒤 고속도로
        val crawlEnd = Coord(36.3554, 127.4370)
        while (wall0 + t < at(10, 12)) {
            val f = (wall0 + t - at(10, 0)) / (12 * 60_000.0)
            at2(t, Coord(a.lat + (crawlEnd.lat - a.lat) * f, a.lng))
            t += 5_000
        }
        while (wall0 + t < at(10, 30)) {
            val f = (wall0 + t - at(10, 12)) / (18 * 60_000.0)
            at2(t, Coord(crawlEnd.lat + (b.lat - crawlEnd.lat) * f * 0.8, a.lng))
            t += 5_000
        }
        assertEquals(0, events.count { it == NavEvent.MISSED_RIDE })
        assertTrue(e.state.onRide && e.state.onLongRide)
    }

    @Test
    fun waitingAtTheStationIsLowPowerUntilJustBeforeDeparture() {
        val departs = at(9, 58)
        fun plan(minutesBefore: Int) = LocationPolicy.navigation(
            NavState(matched = true, waitingLongRide = true, longRideAtMs = departs, toActionM = 0),
            bike = false, speedMps = 0.2f, sinceArrivalMs = 0, nowMs = departs - minutesBefore * 60_000L,
        )
        plan(20).let {
            assertEquals(LocPower.BALANCED, it.power)
            assertEquals(30_000L, it.intervalMs)
        }
        plan(2).let {
            assertEquals(LocPower.HIGH, it.power)
            assertTrue(it.intervalMs <= 5_000L)
        }
    }

    @Test
    fun aFastTrainSwitchesToGpsByTimeNotJustDistance() {
        val arrives = at(10, 51)
        fun plan(toAction: Int, minutesLeft: Int) = LocationPolicy.navigation(
            NavState(matched = true, onRide = true, onLongRide = true, longRideAtMs = arrives, toActionM = toAction),
            bike = false, speedMps = 80f, sinceArrivalMs = 0, nowMs = arrives - minutesLeft * 60_000L,
        )
        // 아직 40분 남음 → 저전력, 모아 받기 없음 (모아 받은 오래된 위치는 버려지므로)
        plan(60_000, 40).let {
            assertEquals(LocPower.BALANCED, it.power)
            assertEquals(0L, it.maxDelayMs)
        }
        // 60km 남았어도 도착 8분 전이면(시속 300km) GPS 10초
        plan(60_000, 8).let {
            assertEquals(LocPower.HIGH, it.power)
            assertEquals(10_000L, it.intervalMs)
        }
    }

    @Test
    fun aWildCellFixWhileWaitingForALateTrainIsNotMissed() {
        val trip = Trip(seed = 31)
        trip.walkToStation()
        trip.waitAtStation(at(9, 56))
        // 9:56~10:03 역 안에서 기지국 위치(정확도 2.5km)만 — 남서쪽 2.2km 로 튐 (걸어온 길 끝 쪽으로 잡힘)
        val sw = Coord(daejeonSt.lat - 0.0140, daejeonSt.lng - 0.0172)
        val t0 = at(9, 56) - trip.wallStart
        trip.play((0..14).map { Fix(sw, t0 + it * 30_000L, 2500f) })
        assertEquals(0, trip.count(NavEvent.MISSED_RIDE))
        // 10:04 에야 떠남 (6분 늦음)
        trip.play(move(track, at(10, 4) - trip.wallStart, 53 * 60.0, 10_000, { 15f }, { 8.0 }, trip.rnd))
        assertEquals(0, trip.count(NavEvent.MISSED_RIDE))
        assertEquals(1, trip.count(NavEvent.ALIGHT_SOON))
    }

    @Test
    fun aLateWalkerStillOnTheWayIsToldWithinMinutes() {
        // 역 1.2km 앞에서 아주 천천히 걷다가 9:58 열차를 놓침 — 역에 닿은 적이 없으니 10분이 아니라 3분 뒤 알림
        val far = Coord(36.3430, 127.4250)
        val wall0 = at(9, 50)
        var now = 0L
        val e = NavEngine(
            listOf(
                GuideStep("대전역까지 도보", listOf(far, daejeonSt), approx = true),
                GuideStep("KTX 105", listOf(daejeonSt, seoulSt), ride = true, alight = "서울역", line = "KTX 105", board = "대전역",
                    timeSec = 53 * 60, longRide = LongRide.TRAIN, departAtMs = at(9, 58), arriveAtMs = at(10, 51)),
            ),
            seoulSt, 1, clock = { wall0 + now },
        )
        val rnd = Random(12)
        val events = ArrayList<Pair<NavEvent, Long>>()
        val walk = move(listOf(far, daejeonSt), 0, 3_000.0, 10_000, { 8f }, { 4.0 }, rnd)   // 1.4km 를 50분에
        walk.filter { wall0 + it.t <= at(10, 5) }.forEach { f -> now = f.t; e.update(f.c, f.acc, f.t).forEach { events += it to (wall0 + f.t) } }
        val missed = events.filter { it.first == NavEvent.MISSED_RIDE }
        assertEquals(1, missed.size)
        assertTrue(missed.single().second in at(10, 1)..at(10, 2))
    }

    @Test
    fun aFixAFewHundredMetresAwayWhileWaitingKeepsTheWaitingState() {
        val trip = Trip(seed = 17)
        trip.walkToStation()
        trip.waitAtStation(at(9, 40))
        assertTrue(trip.engine.state.waitingLongRide)
        // 승강장이 역 좌표에서 300m 떨어져 있음 (기차 가는 쪽)
        val platform = Coord(daejeonSt.lat + 0.0027, daejeonSt.lng - 0.0005)
        trip.play((1..6).map { Fix(platform, at(9, 40) - trip.wallStart + it * 10_000L, 20f) })
        assertTrue(trip.engine.state.waitingLongRide)
        assertEquals("KTX 105 승차", trip.engine.state.bannerTitle)
        assertEquals(0, trip.count(NavEvent.MISSED_RIDE))
    }

    @Test
    fun aDelayedTrainOrOnePastTheStopDoesNotKeepGpsOn() {
        val arrives = at(10, 51)
        // 시간표보다 20분 늦어 아직 30km 남음 → 거리로 판단 (3초 GPS 를 20분 내내 쓰지 않음)
        LocationPolicy.navigation(
            NavState(matched = true, onRide = true, onLongRide = true, longRideAtMs = arrives, toActionM = 30_000),
            bike = false, speedMps = 60f, sinceArrivalMs = 0, nowMs = arrives + 20 * 60_000L,
        ).let { assertEquals(LocPower.BALANCED, it.power) }
        // 내릴 역을 지나친 기차: 다음 역에 설 때까지(재탐색은 멈춘 뒤) 저전력으로
        LocationPolicy.navigation(
            NavState(matched = true, onRide = true, onLongRide = true, passedStop = true, toActionM = 6_000),
            bike = false, speedMps = 60f, sinceArrivalMs = 0,
        ).let {
            assertEquals(LocPower.BALANCED, it.power)
            assertEquals(30_000L, it.intervalMs)
        }
    }

    @Test
    fun coachAlertsTwoKilometresBeforeTheTerminal() {
        val a = Coord(36.3500, 127.4370)       // 대전복합
        val b = Coord(36.6400, 127.4300)       // 청주 (북쪽 약 32km)
        val start = at(10, 0)
        var now = 0L
        val e = NavEngine(
            listOf(
                GuideStep("시외 우등", listOf(a, b), ride = true, alight = "청주", line = "시외버스 우등", board = "대전복합",
                    timeSec = 66 * 60, longRide = LongRide.COACH, departAtMs = start, arriveAtMs = start + 66 * 60_000L),
            ),
            b, 1, clock = { start + now },
        )
        // 고속도로는 직선에서 동쪽으로 2km 벌어졌다가 터미널로 들어옴
        val road = listOf(a, Coord(36.4500, 127.4600), Coord(36.5800, 127.4600), b)
        val rnd = Random(5)
        val events = ArrayList<Pair<NavEvent, Double>>()
        val len = length(road)
        var t = 0L
        while (t <= 40 * 60_000L) {
            now = t
            val c = noisy(pointAt(road, len * t / (40 * 60_000.0)), 6.0, rnd)
            e.update(c, 10f, t).forEach { events += it to Geo.distanceM(c, b) }
            t += 5_000
        }
        val alert = events.single { it.first == NavEvent.ALIGHT_SOON }
        assertTrue("터미널 2km 안, 너무 늦지 않게 알림: ${alert.second}", alert.second in 1_500.0..2_000.0)
        assertTrue(events.none { it.first == NavEvent.PASSED_STOP || it.first == NavEvent.OFF_ROUTE })
    }

    @Test
    fun ridingFarFromTheStopUsesLowPowerLocation() {
        fun plan(toAction: Int, speed: Float? = 40f) = LocationPolicy.navigation(
            NavState(matched = true, onRide = true, onLongRide = true, toActionM = toAction), bike = false, speedMps = speed, sinceArrivalMs = 0,
        )
        // 내릴 역이 10km 넘게 → GPS 를 끄고 기지국·Wi-Fi 위치를 1분에 한 번
        plan(60_000).let {
            assertEquals(LocPower.BALANCED, it.power)
            assertEquals(60_000L, it.intervalMs)
        }
        // 역에 서 있어도(중간 역 정차) 멀면 그대로 저전력
        assertEquals(LocPower.BALANCED, plan(60_000, speed = 0f).power)
        // 4~10km → GPS 10초, 4km 안 → 3초 (하차 알림)
        plan(8_000).let {
            assertEquals(LocPower.HIGH, it.power)
            assertEquals(10_000L, it.intervalMs)
        }
        plan(3_000).let {
            assertEquals(LocPower.HIGH, it.power)
            assertEquals(3_000L, it.intervalMs)
        }
        // 놓쳐서 다시 찾는 중엔 촘촘히
        assertEquals(
            1_000L,
            LocationPolicy.navigation(NavState(matched = true, missedRide = true), bike = false, speedMps = 1f, sinceArrivalMs = 0).intervalMs,
        )
    }
}
