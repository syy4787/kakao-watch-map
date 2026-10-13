package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.model.AccessLeg
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.IntercityCandidates
import com.mywatch.kakaomap.model.IntercityItineraries
import com.mywatch.kakaomap.model.IntercityKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.Regions
import com.mywatch.kakaomap.model.ScheduledRun
import com.mywatch.kakaomap.model.StationPair
import com.mywatch.kakaomap.model.TagoMatching
import com.mywatch.kakaomap.model.TagoNode
import com.mywatch.kakaomap.model.TimetableFill
import com.mywatch.kakaomap.model.TransitRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/** 장거리 경로를 찾는 데 쓰는 바깥 정보 (실제: 카카오·TAGO, 테스트: 가짜). 모두 백그라운드 스레드에서 부른다 */
interface IntercitySource {
    /** 카카오 키워드 검색 — [near] 에서 [radiusM] 안, 가까운 순 */
    fun places(query: String, near: Coord, radiusM: Int): List<Place>

    /** TAGO 도시별 기차역 */
    fun trainStations(cityCode: Int): List<TagoNode>

    fun expressTerminals(): List<TagoNode>

    fun intercityTerminals(): List<TagoNode>

    /** [day] 하루의 시간표 (출발 순) */
    fun runs(kind: IntercityKind, depId: String, arrId: String, day: LocalDate): List<ScheduledRun>

    /** 카카오 대중교통 경로 중 가장 빨리 도착하는 것. 대중교통 길이 없다는 답이면 null */
    fun transit(from: Coord, to: Coord, toName: String): TransitRoute?
}

/**
 * 기차·고속버스·시외버스 경로 찾기 (카카오맵의 '열차 · 고속버스 · 시외버스' 결과처럼).
 *  1. 출발지·도착지 근처 역('기차역'·'KTX정차역')과 터미널('고속,시외버스터미널')을 카카오에서 찾고
 *  2. TAGO 역·터미널 번호로 맞춰 오늘 시간표를 받는다 (KTX 가 빠진 날은 다음 주 같은 요일로 채움)
 *  3. 아직 탈 편이 남은 역·터미널만 카카오 대중교통으로 실제 길(역까지·역에서)을 받아 경로를 만든다
 * 한 종류가 실패해도(키 미등록·끊김) 나머지는 그대로 돌려주고, 실패는 [Result.problems] 로 알린다.
 */
class IntercityPlanner(private val source: IntercitySource) {

    /** [problems] = 받지 못한 것 ("열차: 공공데이터 키가 …") — 나머지 결과는 그대로 쓴다 */
    class Result(val routes: List<TransitRoute>, val problems: List<String>) {
        /** 다음 주 같은 요일 시간표로 채운 열차가 들어 있음 */
        val weekly: Boolean get() = routes.any { r -> r.steps.any { it.weekly } }
    }

    /** 카카오 장소 ↔ TAGO 역·터미널 */
    private class Stop(val place: Place, val node: TagoNode)

    /** 출발 역 → 도착 역 한 쌍 */
    private class Link(val kind: IntercityKind, val dep: Stop, val arr: Stop)

    suspend fun plan(origin: Coord, destination: Place, nowMs: Long): Result = coroutineScope {
        val problems = Collections.synchronizedList(ArrayList<String>())
        val today = Instant.ofEpochMilli(nowMs).atZone(SEOUL).toLocalDate()

        // 1. 근처 역·터미널 (카카오)
        val queries = listOf(STATION_QUERY, KTX_QUERY, TERMINAL_QUERY)
        val found = (queries.map { it to origin } + queries.map { it to destination.coord })
            .map { (q, at) -> async(Dispatchers.IO) { attempt(problems, "역·터미널 찾기") { source.places(q, at, RADIUS_M) }.orEmpty() } }
            .awaitAll()
        val depStations = IntercityCandidates.stations(found[0], found[1])
        val depTerminals = IntercityCandidates.terminals(found[2])
        val arrStations = IntercityCandidates.stations(found[3], found[4])
        val arrTerminals = IntercityCandidates.terminals(found[5])

        // 2. TAGO 역·터미널 번호
        val trainDep = async(Dispatchers.IO) { stations(depStations, problems) }
        val trainArr = async(Dispatchers.IO) { stations(arrStations, problems) }
        val needBuses = depTerminals.isNotEmpty() && arrTerminals.isNotEmpty()
        val express = async(Dispatchers.IO) {
            if (needBuses) attempt(problems, IntercityKind.EXPRESS.label) { source.expressTerminals() }.orEmpty() else emptyList()
        }
        val intercity = async(Dispatchers.IO) {
            if (needBuses) attempt(problems, IntercityKind.INTERCITY.label) { source.intercityTerminals() }.orEmpty() else emptyList()
        }
        val pairs = pairs(IntercityKind.TRAIN, trainDep.await(), trainArr.await()) +
            express.await().let { list ->
                pairs(IntercityKind.EXPRESS, terminals(depTerminals, list, regional = false), terminals(arrTerminals, list, regional = false))
            } +
            intercity.await().let { list ->
                pairs(IntercityKind.INTERCITY, terminals(depTerminals, list, regional = true), terminals(arrTerminals, list, regional = true))
            }
        if (pairs.isEmpty()) return@coroutineScope Result(emptyList(), problems.distinct())

        // 3. 오늘 시간표 — 열차는 KTX 가 빠진 날이면 다음 주 같은 요일 것으로 채운다
        val todayRuns = pairs.map { p ->
            async(Dispatchers.IO) { attempt(problems, p.kind.label) { source.runs(p.kind, p.dep.node.id, p.arr.node.id, today) }.orEmpty() }
        }.awaitAll()
        fun highSpeedPair(p: Link) = p.kind == IntercityKind.TRAIN && highSpeed(p.dep.place) && highSpeed(p.arr.place)
        // 일반 열차는 있는데 KTX 가 빠진 쌍이 하나라도 보이면 그날은 자료가 빠진 날
        val gapDay = pairs.indices.any { i ->
            highSpeedPair(pairs[i]) && todayRuns[i].isNotEmpty() && TimetableFill.needsWeekly(todayRuns[i], bothHighSpeed = true)
        }
        val runs = pairs.indices.map { i ->
            val p = pairs[i]
            val list = todayRuns[i]
            if (!gapDay || !highSpeedPair(p) || !TimetableFill.needsWeekly(list, bothHighSpeed = true)) {
                async { list }
            } else {
                async(Dispatchers.IO) {
                    val nextWeek = attempt(problems, p.kind.label) { source.runs(p.kind, p.dep.node.id, p.arr.node.id, today.plusDays(7)) }
                    if (nextWeek == null) list else TimetableFill.merge(list, nextWeek)
                }
            }
        }.awaitAll()

        // 4. 아직 탈 편이 남은 역·터미널만 실제 길을 받는다 (역까지·역에서)
        val soon = nowMs + MIN_LEAD_SEC * 1000L
        val live = pairs.indices.filter { i -> runs[i].any { it.depAtMs >= soon } }
        val depPlaces = live.map { pairs[it].dep.place }.distinctBy { it.name }
        val arrPlaces = live.map { pairs[it].arr.place }.distinctBy { it.name }
        val depLegs = depPlaces.map { pl -> async(Dispatchers.IO) { leg(origin, pl.coord, pl, pl.name) } }
        val arrLegs = arrPlaces.map { pl -> async(Dispatchers.IO) { leg(pl.coord, destination.coord, pl, destination.name) } }
        val depBy = depPlaces.zip(depLegs.awaitAll()).toMap()
        val arrBy = arrPlaces.zip(arrLegs.awaitAll()).toMap()

        val stationPairs = live.mapNotNull { i ->
            val p = pairs[i]
            val d = depBy[p.dep.place] ?: return@mapNotNull null
            val a = arrBy[p.arr.place] ?: return@mapNotNull null
            StationPair(p.kind, d, a, runs[i])
        }
        Result(IntercityItineraries.build(nowMs, origin, destination, stationPairs), problems.distinct())
    }

    /** 카카오 역 → TAGO 역 (역 주소의 시도로 도시 코드를 골라 그 도시 역 목록에서 이름으로) */
    private fun stations(places: List<Place>, problems: MutableList<String>): List<Stop> {
        val byCity = HashMap<Int, List<TagoNode>>()
        return places.mapNotNull { pl ->
            val code = Regions.ofAddress(pl.address)?.let(Regions::trainCityCode) ?: return@mapNotNull null
            val list = byCity.getOrPut(code) {
                attempt(problems, IntercityKind.TRAIN.label) { source.trainStations(code) }.orEmpty()
            }
            TagoMatching.station(pl.name, list)?.let { Stop(pl, it) }
        }.distinctBy { it.node.id }.take(MAX_STATIONS)
    }

    /** 카카오 터미널 → TAGO 터미널 (시외버스는 시도가 같은 것만) */
    private fun terminals(places: List<Place>, nodes: List<TagoNode>, regional: Boolean): List<Stop> {
        if (nodes.isEmpty()) return emptyList()
        return places.mapNotNull { pl ->
            val region = if (regional) Regions.ofAddress(pl.address) else null
            TagoMatching.terminals(pl.name, region, nodes).firstOrNull()?.let { Stop(pl, it) }
        }.distinctBy { it.node.id }.take(MAX_TERMINALS)
    }

    private fun pairs(kind: IntercityKind, deps: List<Stop>, arrs: List<Stop>): List<Link> =
        deps.flatMap { d -> arrs.filter { it.node.id != d.node.id }.map { a -> Link(kind, d, a) } }

    /**
     * [from] → [to] 의 실제 길: 700m 안이면 걸어서, 아니면 카카오 대중교통 (가장 빠른 것).
     * 대중교통 길이 없거나 받지 못하면 2.5km 안은 걸어서, 그보다 멀면 이 역은 쓰지 않는다(null).
     */
    private fun leg(from: Coord, to: Coord, place: Place, toName: String): AccessLeg? {
        val d = Geo.distanceM(from, to)
        val walk = AccessLeg(place, null, IntercityItineraries.walkSec(from, to), 0)
        if (d <= IntercityItineraries.WALK_ONLY_M) return walk
        val route = try {
            source.transit(from, to, toName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (route != null) return AccessLeg(place, route, route.totalTimeSec, route.fare)
        return if (d <= WALK_FALLBACK_M) walk else null
    }

    private fun highSpeed(p: Place) = p.category.contains("KTX") || p.category.contains("SRT")

    /** 실패하면 null 을 돌려주고 "열차: 오류 문구" 를 남긴다 */
    private inline fun <T> attempt(problems: MutableList<String>, what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        problems += "$what: ${e.message ?: "받지 못했어요"}"
        null
    }

    companion object {
        /** 출발·도착이 이만큼(직선) 넘게 떨어져야 기차·고속·시외버스를 찾아본다 (대전→옥천 정도부터) */
        const val MIN_DISTANCE_M = 25_000
        /** 역·터미널을 찾는 반경 (카카오 키워드 검색의 최대) */
        const val RADIUS_M = 20_000
        const val STATION_QUERY = "기차역"
        /** 카카오 카테고리 이름으로 찾으면 '…기차역점' 같은 가게 없이 역만 나온다 */
        const val KTX_QUERY = "KTX정차역"
        const val TERMINAL_QUERY = "고속,시외버스터미널"
        private const val MAX_STATIONS = 3
        private const val MAX_TERMINALS = 2
        /** 지금부터 이 안에 떠나는 편만 남은 역은 길을 받지 않는다 (어차피 못 탐) */
        private const val MIN_LEAD_SEC = 5 * 60
        private const val WALK_FALLBACK_M = 2_500.0
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
