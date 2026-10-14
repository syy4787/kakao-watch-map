package com.mywatch.kakaomap.model

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 이 버스 구간에서 탈 수 있는 노선 하나: 카카오 노선(종류·번호) + 찾았으면 TAGO 노선 번호([routeId]) */
data class BusLine(val vehicle: Vehicle, val routeId: String? = null) {
    /** 화면에 보일 이름 ("106", "급행2") */
    val name: String get() = TransitMatching.displayName(vehicle)

    /** 이 노선의 도착 정보인지: TAGO 노선 번호를 알면 그것으로(같은 번호의 다른 노선과 구별), 모르면 번호로 */
    fun matches(a: BusArrival): Boolean =
        if (routeId != null && a.routeId.isNotEmpty()) a.routeId == routeId else TransitMatching.sameLine(vehicle, a.routeNo)
}

/**
 * 버스 구간의 TAGO 승차 정류장.
 *  · [stop] 이 있으면 노선 정류소 순서(승차 … 하차, 정거장 수까지 맞음)로 찾은 것 — 길 건너 같은 이름의 반대 방향 정류장과
 *    헷갈리지 않는다. [slice] = 그 노선의 승차~하차 정류장 (타는 중 '정류장 n개 남음'용)
 *  · 없으면 이름·거리로 짐작한 [candidates] — 앞에서부터 묻되 같은 도시의 같은 이름(대개 반대 방향)으로는 넘어가지 않는다
 */
data class BusBoarding(
    val lines: List<BusLine>,
    val stop: BusStop? = null,
    val slice: List<BusStop>? = null,
    val candidates: List<BusStop> = emptyList(),
)

/**
 * 카카오 버스 구간 → TAGO 승차 정류장·노선 (순수 계산 + 밖에서 넣어 준 네트워크 함수 — 테스트는 가짜로).
 * 노선 정류소 목록에서 [StopPlacer.rideSlice] 로 '승차 … 하차' 순서가 맞는 정류장을 골라 방향까지 맞춘다.
 * 같은 것은 다시 묻지 않는다: 근처 정류장은 좌표(소수 넷째 자리 ≈ 10m)로, 노선 검색·정류소 목록은 노선마다 [ttlMs] 동안.
 * 찾지 못한 결과(빈 응답 포함)는 [missTtlMs] 동안만 기억한다 — 잠깐의 빈 응답이 앱이 켜져 있는 내내 남지 않게.
 * 네트워크 오류는 기억하지 않고 그대로 던진다. 여러 스레드에서 불러도 된다 (같은 것을 동시에 물으면 한 번만).
 */
class BusStopResolver(
    private val nearby: (Coord) -> List<BusStop>,
    private val routes: (cityCode: Int, routeNo: String) -> List<BusRoute>,
    private val routeStops: (cityCode: Int, routeId: String) -> List<BusStop>,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 12 * 60 * 60_000L,
    private val missTtlMs: Long = 5 * 60_000L,
) {
    private class Entry(val value: Any?, val at: Long, val ttl: Long)

    private val cache = LinkedHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, miss: (T) -> Boolean, load: () -> T): T {
        synchronized(locks.getOrPut(key) { Any() }) {
            synchronized(cache) { cache[key] }?.let { e -> if (now() - e.at < e.ttl) return e.value as T }
            val v = load()
            synchronized(cache) {
                cache.remove(key)
                if (cache.size >= MAX_ENTRIES) cache.keys.firstOrNull()?.let { cache.remove(it) }
                cache[key] = Entry(v, now(), if (miss(v)) missTtlMs else ttlMs)
            }
            return v
        }
    }

    /** 승차 정류장·노선. 버스 구간이 아니거나 좌표·정류장 이름·노선 번호가 없으면 null */
    fun resolve(step: TransitStep): BusBoarding? {
        if (step.type != TransitStepType.BUS) return null
        val start = step.points.firstOrNull() ?: return null
        val board = step.boardingStop ?: return null
        val vehicles = step.vehicles.filter { it.name.isNotBlank() }.distinctBy { it.type + it.name }
        if (vehicles.isEmpty()) return null
        val key = "b|${round(start)}|$board|${step.alightingStop}|${step.stops.size}|" + vehicles.joinToString(",") { it.type + it.name }
        return cached(key, { b: BusBoarding? -> b?.stop == null }) { find(step, start, board, vehicles) }
    }

    private fun find(step: TransitStep, start: Coord, board: String, vehicles: List<Vehicle>): BusBoarding? {
        val near = cached("n|${round(start)}", { l: List<BusStop> -> l.isEmpty() }) { nearby(start) }
        val candidates = TransitMatching.stopCandidates(near, board, start)
        // 같은 정류장이 이웃 도시 데이터로도 나오므로 이 도시(가장 많이 나온 도시 코드) 것으로 노선을 찾는다
        val city = candidates.firstOrNull()?.cityCode ?: TransitMatching.dominantCity(near) ?: return null
        val alight = step.alightingStop
        if (alight != null) {
            for (v in vehicles.take(LOOKUP_LINES)) {
                for (q in TransitMatching.routeQueries(v)) {
                    val found = cached("q|$city|$q", { l: List<BusRoute> -> l.isEmpty() }) { routes(city, q) }
                        .filter { TransitMatching.sameLine(v, it.routeNo) }
                    for (r in found.take(3)) {
                        val seq = cached("s|$city|${r.routeId}", { l: List<BusStop> -> l.isEmpty() }) { routeStops(city, r.routeId) }
                        // 도는 노선은 같은 이름이 갈 때·올 때 두 번 — 정거장 수·승차 위치가 맞는 쪽 (카카오와 다르면 다른 노선)
                        val slice = StopPlacer.rideSlice(seq, board, alight, start, step.stops.size) ?: continue
                        val located = slice.map { it.copy(cityCode = city) }
                        // 승차 정류장이 방향까지 정해졌으니 다른 노선은 그 정류장에서 번호로만 맞춰도 같은 방향이다
                        val lines = vehicles.map { if (it == v) BusLine(it, r.routeId) else BusLine(it) }
                        return BusBoarding(lines, located.first(), located, candidates)
                    }
                }
            }
        }
        return BusBoarding(vehicles.map { BusLine(it) }, candidates = candidates)
    }

    private fun round(c: Coord) = String.format(Locale.US, "%.4f,%.4f", c.lat, c.lng)

    companion object {
        /** 노선 정류소 목록으로 방향을 맞춰 볼 노선 수 (구간에 노선이 여럿이어도 앞의 둘만 — 요청 수 절약) */
        private const val LOOKUP_LINES = 2
        private const val MAX_ENTRIES = 200
    }
}

/**
 * 정류장마다 도착 정보를 한 번만 물어 [ttlMs] 동안 같이 쓴다 — 목록의 여러 경로가 같은 정류장에서 타도 한 번
 * (동시에 물어도 한 번). 네트워크 함수는 밖에서 넣어 준다.
 */
class ArrivalBoard(
    private val fetch: (BusStop) -> List<BusArrival>,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 30_000L,
) {
    private val cache = HashMap<String, Pair<Long, List<BusArrival>>>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun at(stop: BusStop): List<BusArrival> {
        val key = "${stop.cityCode}|${stop.nodeId}"
        synchronized(locks.getOrPut(key) { Any() }) {
            synchronized(cache) { cache[key] }?.let { (at, v) -> if (now() - at < ttlMs) return v }
            val v = fetch(stop)
            synchronized(cache) {
                if (cache.size >= 100) cache.clear()
                cache[key] = now() to v
            }
            return v
        }
    }
}

/**
 * 실시간 버스 도착 문구 (카카오맵 표기).
 * [text] = 목록·상세 ("106번 3분 (2번째 전) · 다음 15분", 노선이 여럿이면 "106 2분 · 617 5분"),
 * [short] = 안내 배너·알림용 가장 빨리 오는 버스 하나 ("617번 2분 (1번째 전)", 정보가 없으면 null),
 * [soonestSec] = 그 버스까지 초
 */
data class LiveBus(val text: String, val short: String? = null, val soonestSec: Int? = null)

object LiveBusText {
    const val NO_INFO = "도착 정보 없음"

    /**
     * 이 구간 노선들([lines])의 도착 정보로 문구를 만든다. '다음 버스'는 같은 노선(TAGO 노선 번호)일 때만.
     * [walkSec] = 지금부터 이 정류장까지 걸리는 시간 (길찾기 목록) — 그보다 먼저 오는 버스는 타기 어려우므로
     * '놓칠 수 있어요'와 함께 탈 수 있는 다음 버스를 (도착 예측은 대략이라 [GRACE_SEC] 은 봐준다)
     */
    fun of(lines: List<BusLine>, arrivals: List<BusArrival>, walkSec: Int? = null): LiveBus {
        val byName = lines.groupBy { it.name }.mapNotNull { (name, group) ->
            val mine = arrivals.filter { a -> a.arrivalSec >= 0 && group.any { it.matches(a) } }
                .distinct()
                .sortedBy { it.arrivalSec }
            if (mine.isEmpty()) null else name to mine
        }.sortedBy { it.second.first().arrivalSec }
        if (byName.isEmpty()) return LiveBus(NO_INFO)
        fun catchable(a: BusArrival) = walkSec == null || a.arrivalSec + GRACE_SEC >= walkSec
        fun next(a: BusArrival) = "다음 ${Format.arrival(a.arrivalSec)}"
        val (name, mine) = byName.first()
        val first = mine.first()
        val short = "${name}번 ${Format.busArrival(first)}"
        val text = if (byName.size == 1) {
            val later = mine.drop(1).filter { sameRun(first, it) }
            if (catchable(first)) {
                listOfNotNull(short, later.firstOrNull()?.let(::next)).joinToString(" · ")
            } else {
                listOfNotNull(short, MISS, later.firstOrNull(::catchable)?.let(::next)).joinToString(" · ")
            }
        } else {
            // 노선이 여럿이면 탈 수 있는 버스만 빨리 오는 순으로 (하나도 없으면 그대로 + '놓칠 수 있어요')
            val reachable = byName.mapNotNull { (n, a) -> a.firstOrNull(::catchable)?.let { n to it } }.sortedBy { it.second.arrivalSec }
            if (reachable.isNotEmpty()) {
                reachable.take(MAX_LINES).joinToString(" · ") { (n, a) -> "$n ${Format.arrival(a.arrivalSec)}" }
            } else {
                (byName.take(MAX_LINES).map { (n, a) -> "$n ${Format.arrival(a.first().arrivalSec)}" } + MISS).joinToString(" · ")
            }
        }
        return LiveBus(text, short, first.arrivalSec)
    }

    const val MISS = "놓칠 수 있어요"
    /** 걷는 시간보다 이만큼 먼저 와도 탈 만하다고 본다 (도착 예측은 대략) */
    private const val GRACE_SEC = 60

    /** 같은 노선의 다음 버스인지 (번호가 같아도 TAGO 노선 번호가 다르면 다른 노선 — 일반 607·농어촌 607) */
    private fun sameRun(a: BusArrival, b: BusArrival): Boolean =
        if (a.routeId.isNotEmpty() || b.routeId.isNotEmpty()) a.routeId == b.routeId else a.routeNo == b.routeNo

    private const val MAX_LINES = 3
}
