package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.ArrivalBoard
import com.mywatch.kakaomap.model.BusArrival
import com.mywatch.kakaomap.model.BusLine
import com.mywatch.kakaomap.model.BusRoute
import com.mywatch.kakaomap.model.BusStop
import com.mywatch.kakaomap.model.BusStopResolver
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.LiveBusText
import com.mywatch.kakaomap.model.StopPlacer
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitMatching
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실시간 버스 도착을 카카오맵처럼: 승차 정류장은 노선 정류소 순서로 방향까지 맞추고(길 건너 같은 이름 정류장과 헷갈리지 않게),
 * 같은 정류장의 도착 정보는 한 번만 묻고, 문구는 노선마다 ("106 2분 · 617 5분").
 * 정류장 이름·좌표는 실제 TAGO 응답(대전광역시청 근처, 2026-10-13)을 본떠 줄인 것.
 */
class LiveBusTest {

    // 카카오 길찾기 '일반 707 (대전광역시청 > 대전역)' 승차 지점
    private val board = Coord(36.35120829, 127.38510038)

    private fun step(vararg vehicles: Vehicle, stops: List<String> = listOf("대전광역시청", "시청역", "목련아파트", "대전역")) = TransitStep(
        TransitStepType.BUS, "일반 707 (대전광역시청 > 대전역)", 6333, 1782,
        stops = stops, vehicles = vehicles.toList(), points = listOf(board, Coord(36.3323, 127.4346)),
    )

    // 이쪽(대전역 방향) 정류장은 카카오 승차 지점에서 24m, 길 건너 반대 방향 정류장은 TAGO 좌표 오차로 9m — 가까운 것만 고르면 틀린다
    private val mine = BusStop(25, "DJB8001378", "대전광역시청", Coord(36.351112, 127.38486))
    private val opposite = BusStop(25, "DJB8001379", "대전광역시청", Coord(36.3511393, 127.3851554))
    private val sejong = BusStop(12, "SJB186005227", "대전광역시청", Coord(36.3512, 127.3852))
    private val nearby = listOf(opposite, mine, sejong, BusStop(25, "DJB8002087", "시청.교육청", Coord(36.351395, 127.3835)))

    // 707 노선 정류소 (노선 순서): 대전역 방향으로 가며 이쪽 정류장 → … → 대전역, 돌아오며 길 건너 정류장
    private fun seq707() = listOf(
        BusStop(0, "DJB1", "관저동", Coord(36.30, 127.33)),
        BusStop(0, mine.nodeId, "대전광역시청", mine.coord),
        BusStop(0, "DJB2", "시청역", Coord(36.35112, 127.3887)),
        BusStop(0, "DJB3", "목련아파트", Coord(36.3512, 127.3921)),
        BusStop(0, "DJB4", "대전역", Coord(36.3309, 127.4319)),
        BusStop(0, "DJB5", "대전역", Coord(36.3311, 127.4322)),
        BusStop(0, "DJB6", "목련아파트", Coord(36.3514, 127.3920)),
        BusStop(0, opposite.nodeId, "대전광역시청", opposite.coord),
        BusStop(0, "DJB7", "관저동", Coord(36.30, 127.33)),
    )

    private class Calls {
        val nearby = AtomicInteger()
        val routes = AtomicInteger()
        val stops = AtomicInteger()
    }

    private fun resolver(calls: Calls, clock: () -> Long = { 0L }, routesFor: (String) -> List<BusRoute> = { no ->
        if (no == "707") listOf(BusRoute("DJB30300152", "707"), BusRoute("DJB30300199", "707-1")) else emptyList()
    }) = BusStopResolver(
        nearby = { calls.nearby.incrementAndGet(); nearby },
        routes = { _, no -> calls.routes.incrementAndGet(); routesFor(no) },
        routeStops = { _, id -> calls.stops.incrementAndGet(); if (id == "DJB30300152") seq707() else emptyList() },
        now = clock,
    )

    @Test
    fun theBoardingStopIsChosenByTheRouteOrderNotByTheNearestSameName() {
        // 이름·거리로만 고르면 길 건너(반대 방향) 정류장이 먼저 — 예전엔 그쪽 707 도착을 보여 줄 수 있었다
        assertEquals(opposite.nodeId, TransitMatching.stopCandidates(nearby, "대전광역시청", board).first().nodeId)
        val b = resolver(Calls()).resolve(step(Vehicle("일반", "707")))!!
        assertEquals("노선 순서(승차 … 하차)로 고른 이쪽 정류장", mine.nodeId, b.stop!!.nodeId)
        assertEquals(25, b.stop!!.cityCode)
        assertEquals(listOf("대전광역시청", "시청역", "목련아파트", "대전역"), b.slice!!.map { it.name })
        assertEquals("DJB30300152", b.lines.single().routeId)
        // 그 정류장에서 이 노선(TAGO 노선 번호) 도착만: 같은 번호의 다른 노선(707-1이 707로 오는 등)은 빼고
        val arrivals = listOf(
            BusArrival("707", "간선버스", 240, 2, routeId = "DJB30300152"),
            BusArrival("707", "간선버스", 60, 1, routeId = "DJB30399999"),
            BusArrival("106", "간선버스", 100, 1, routeId = "DJB30300041"),
        )
        assertEquals("707번 4분 (2번째 전)", LiveBusText.of(b.lines, arrivals).text)
    }

    @Test
    fun lookupsAreCachedByRoundedCoordinatesAndMissesExpire() {
        val calls = Calls()
        var now = 0L
        val r = resolver(calls, clock = { now })
        r.resolve(step(Vehicle("일반", "707")))
        // 같은 정류장(좌표 1m 차이)·같은 노선은 다시 묻지 않는다
        r.resolve(step(Vehicle("일반", "707")).copy(points = listOf(Coord(board.lat + 0.000005, board.lng), Coord(36.3323, 127.4346))))
        assertEquals(1, calls.nearby.get())
        assertEquals(1, calls.routes.get())
        assertEquals(1, calls.stops.get())
        // 못 찾은 노선(카카오에만 있는 번호)은 5분 동안만 기억 — 그 뒤엔 다시 물어본다
        val unknown = step(Vehicle("일반", "999"))
        assertNull(r.resolve(unknown)!!.stop)
        r.resolve(unknown)
        assertEquals(2, calls.routes.get())
        now += 6 * 60_000L
        r.resolve(unknown)
        assertEquals(3, calls.routes.get())
        assertEquals("근처 정류장은 그대로 씀", 1, calls.nearby.get())
        // 네트워크 오류는 기억하지 않는다
        var fail = true
        val flaky = BusStopResolver(
            nearby = { if (fail) throw java.io.IOException("끊김") else nearby },
            routes = { _, no -> if (no == "707") listOf(BusRoute("DJB30300152", "707")) else emptyList() },
            routeStops = { _, _ -> seq707() },
        )
        assertTrue(runCatching { flaky.resolve(step(Vehicle("일반", "707"))) }.isFailure)
        fail = false
        assertEquals(mine.nodeId, flaky.resolve(step(Vehicle("일반", "707")))!!.stop!!.nodeId)
    }

    @Test
    fun expressBusesAreLookedUpAsGeuphaeng() {
        // 카카오는 대전 급행버스를 종류 '급행' + 번호 '2'로 준다 — TAGO·정류장 표지는 '급행2'
        val express = Vehicle("급행", "2")
        assertEquals(listOf("급행2", "2"), TransitMatching.routeQueries(express))
        assertEquals(listOf("106"), TransitMatching.routeQueries(Vehicle("일반", "106")))
        assertEquals(listOf("B1"), TransitMatching.routeQueries(Vehicle("광역", "B1")))
        assertTrue(TransitMatching.sameLine(express, "급행2"))
        assertFalse(TransitMatching.sameRoute("2", "급행2"))
        assertEquals("급행2", TransitMatching.displayName(express))
        val asked = ArrayList<String>()
        val r = BusStopResolver(
            nearby = { nearby },
            routes = { _, no -> asked += no; if (no == "급행2") listOf(BusRoute("DJB30300402", "급행2")) else emptyList() },
            routeStops = { _, _ -> seq707() },
        )
        val b = r.resolve(step(express))!!
        assertEquals(listOf("급행2"), asked)
        assertEquals(mine.nodeId, b.stop!!.nodeId)
        // 같은 정류장의 일반 '2'번(다른 노선)은 급행2 가 아니다
        val line = b.lines.single()
        assertFalse(line.matches(BusArrival("2", "", 60, 1, routeId = "DJB30300002")))
        assertTrue(line.matches(BusArrival("급행2", "", 180, 2, routeId = "DJB30300402")))
        assertEquals("급행2번 3분 (2번째 전)", LiveBusText.of(b.lines, listOf(BusArrival("급행2", "", 180, 2, routeId = "DJB30300402"))).text)
    }

    @Test
    fun liveTextIsPerLineAndTheNextBusOnlyForTheSameRoute() {
        val l106 = BusLine(Vehicle("일반", "106"), "R106")
        val l617 = BusLine(Vehicle("일반", "617"))
        val arrivals = listOf(
            BusArrival("617", "", 300, 4, "R617"), BusArrival("106", "", 120, 1, "R106"), BusArrival("106", "", 900, 8, "R106"),
        )
        // 노선이 여럿('106외 1대')이면 노선마다 가장 빨리 오는 것 — 빨리 오는 순
        val multi = LiveBusText.of(listOf(l106, l617), arrivals)
        assertEquals("106 2분 · 617 5분", multi.text)
        assertEquals("배너용은 가장 빨리 오는 버스 하나", "106번 2분 (1번째 전)", multi.short)
        assertEquals(120, multi.soonestSec)
        // 노선 하나면 다음 버스까지 (같은 노선일 때만)
        assertEquals("106번 2분 (1번째 전) · 다음 15분", LiveBusText.of(listOf(l106), arrivals).text)
        // 번호는 같아도 다른 노선(일반 607·농어촌 607)의 버스는 '다음'이 아니다
        val two607 = listOf(BusLine(Vehicle("일반", "607")), BusLine(Vehicle("농어촌", "607")))
        assertEquals(
            "607번 2분 (1번째 전)",
            LiveBusText.of(two607, listOf(BusArrival("607", "", 120, 1, "DJB607"), BusArrival("607", "", 300, 3, "CNB607"))).text,
        )
        // 1분 안이면 '곧 도착', 없으면 '도착 정보 없음'(배너엔 아무것도 안 붙임)
        assertEquals("106번 곧 도착", LiveBusText.of(listOf(l106), listOf(BusArrival("106", "", 40, 1, "R106"))).short)
        val none = LiveBusText.of(listOf(l106), emptyList())
        assertEquals(LiveBusText.NO_INFO, none.text)
        assertNull(none.short)
    }

    /**
     * 정류장까지 걸어가는 동안 떠나는 버스는 탈 수 없다 — 길찾기 목록에서 '106번 3분'만 보고 나섰다가 놓친다.
     * 걷는 시간보다 먼저 오는 버스면 '놓칠 수 있어요'와 함께 탈 수 있는 다음 버스를 (1분은 봐줌: 도착 예측은 대략)
     */
    @Test
    fun aBusThatComesBeforeYouCanWalkToTheStopIsFlagged() {
        val l106 = BusLine(Vehicle("일반", "106"), "R106")
        val l617 = BusLine(Vehicle("일반", "617"))
        val arrivals = listOf(BusArrival("106", "", 180, 2, "R106"), BusArrival("106", "", 900, 8, "R106"), BusArrival("617", "", 480, 4, "R617"))
        val nbsp = { s: String -> s.replace(' ', ' ') }
        // 걸어서 6분: 3분 뒤 106 은 못 탐 → 다음 15분
        assertEquals("106번 3분 (2번째 전) · 놓칠 수 있어요 · 다음 15분", nbsp(LiveBusText.of(listOf(l106), arrivals, walkSec = 6 * 60).text))
        // 걸어서 3분 반: 3분 뒤 버스도 탈 만함 (1분 봐줌) → 그대로
        assertEquals("106번 3분 (2번째 전) · 다음 15분", nbsp(LiveBusText.of(listOf(l106), arrivals, walkSec = 210).text))
        // 다음 버스도 못 타면 '놓칠 수 있어요'만
        assertEquals("106번 3분 (2번째 전) · 놓칠 수 있어요", nbsp(LiveBusText.of(listOf(l106), arrivals, walkSec = 20 * 60).text))
        // 노선이 여럿이면 탈 수 있는 버스만 빨리 오는 순으로 (617 8분 · 106 15분)
        assertEquals("617 8분 · 106 15분", nbsp(LiveBusText.of(listOf(l106, l617), arrivals, walkSec = 6 * 60).text))
        // 하나도 못 타면 원래대로 + '놓칠 수 있어요'
        assertEquals("106 3분 · 617 8분 · 놓칠 수 있어요", nbsp(LiveBusText.of(listOf(l106, l617), arrivals.take(1) + arrivals[2], walkSec = 20 * 60).text))
        // 배너용(안내 중)은 그대로 가장 빨리 오는 버스
        assertEquals("106번 3분 (2번째 전)", LiveBusText.of(listOf(l106), arrivals, walkSec = 6 * 60).short)
    }

    @Test
    fun oneArrivalsRequestPerStopIsSharedByTheRoutesThatBoardThere() {
        val fetched = AtomicInteger()
        var now = 0L
        val boardInfo = ArrivalBoard(fetch = { fetched.incrementAndGet(); Thread.sleep(20); listOf(BusArrival("707", "", 240, 2)) }, now = { now })
        // 목록의 여러 경로가 같은 정류장에서 타면(동시에 물어도) 한 번만
        val threads = (1..6).map { Thread { boardInfo.at(mine) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, fetched.get())
        boardInfo.at(opposite)
        assertEquals("다른 정류장은 따로", 2, fetched.get())
        now += 31_000
        boardInfo.at(mine)
        assertEquals("30초가 지나면 새로", 3, fetched.get())
    }

    @Test
    fun aGuessedStopNeverFallsThroughToTheSameNamedStopOfTheSameCity() {
        // 노선 순서로 못 찾았을 때(이름이 다른 노선 등): 첫 후보에 이 버스가 없다고 길 건너(같은 도시·같은 이름)로 넘어가지 않는다.
        // 다른 도시 데이터(세종)로 나온 같은 정류장으로만 넘어간다
        val asked = ArrayList<String>()
        val candidates = listOf(mine, opposite, sejong)
        val hit = TransitMatching.firstServingLines(candidates, listOf(BusLine(Vehicle("일반", "707")))) { s ->
            asked += s.nodeId
            if (s == opposite) listOf(BusArrival("707", "", 120, 1)) else emptyList()
        }
        assertNull(hit)
        assertEquals(listOf(mine.nodeId, sejong.nodeId), asked)
    }

    @Test
    fun routeCardsShowLiveArrivalOnlyWhenTheFirstRideIsABus() {
        // 카카오맵처럼 첫 탈 것이 버스일 때만 — 지하철을 먼저 타면 갈아탈 버스의 '지금' 도착은 붙이지 않는다 (대전역 → KAIST 응답 모양)
        fun s(type: TransitStepType) = TransitStep(
            type, "", 1000, 300, stops = listOf("가", "나"),
            vehicles = if (type == TransitStepType.WALK) emptyList() else listOf(Vehicle("일반", "604")),
            points = listOf(board, Coord(36.36, 127.36)),
        )
        val busFirst = TransitRoute("BUS", 9000, 3000, 1, 1500, listOf(s(TransitStepType.WALK), s(TransitStepType.BUS), s(TransitStepType.BUS)))
        val subwayFirst = TransitRoute("BUS_AND_SUBWAY", 9000, 3000, 1, 1550, listOf(s(TransitStepType.SUBWAY), s(TransitStepType.WALK), s(TransitStepType.BUS)))
        assertEquals(1, TransitGuide.listLiveIndex(busFirst))
        assertNull(TransitGuide.listLiveIndex(subwayFirst))
        // 경로 상세에선 갈아탈 버스도 (기차를 타기 전 버스만)
        assertEquals(listOf(2), TransitGuide.liveBusIndexes(subwayFirst))
    }

    @Test
    fun compositeStopNamesMatchEitherPart() {
        // 카카오가 두 이름을 '/'로 붙인 정류장 (실제 대전 응답: 삼성동행정복지센터/중부소방서, 대전역/역전시장, 나라키움청사/3.8기념관)
        assertTrue(StopPlacer.sameStop("삼성동행정복지센터/중부소방서", "삼성동행정복지센터"))
        assertTrue(StopPlacer.sameStop("대전역/역전시장", "역전시장"))
        assertTrue(StopPlacer.sameStop("나라키움청사/3.8기념관", "3.8기념관"))
        assertTrue(StopPlacer.sameStop("시청.교육청", "시청교육청"))
        assertTrue(StopPlacer.sameStop("유성온천역7번출구", "유성온천역 7번출구"))
        assertFalse(StopPlacer.sameStop("대전역", "서대전역"))
        val stops = listOf(BusStop(25, "A", "삼성동행정복지센터", Coord(36.3405, 127.4246)), BusStop(25, "B", "삼성네거리", Coord(36.3402, 127.4240)))
        assertEquals("A", TransitMatching.pickStop(stops, "삼성동행정복지센터/중부소방서", Coord(36.3404, 127.4245))?.nodeId)
    }
}
