package com.mywatch.kakaomap.model

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** 카카오 장소 검색 결과에서 역·터미널 후보 고르기 */
object IntercityCandidates {

    /** 열차가 많이 서는 큰 역 (가장 가까운 역이 작은 역이어도 이 역들은 함께 본다) */
    val HUBS: Set<String> = setOf(
        "서울", "용산", "수서", "청량리", "영등포", "광명", "행신", "동탄", "평택지제", "천안아산", "오송", "대전", "서대전",
        "김천구미", "동대구", "대구", "신경주", "경주", "울산", "부산", "포항", "익산", "전주", "광주송정", "목포", "순천",
        "여수엑스포", "마산", "창원중앙", "창원", "진주", "강릉", "원주", "안동", "공주", "정읍", "남원",
    )

    /** 가장 가까운 큰 역의 거리 × 1.5 + 3km 안의 큰 역까지 (서울시청: 서울역 1.5km → 용산역 4.2km 까지) */
    private const val HUB_RATIO = 1.5
    private const val HUB_SLACK_M = 3_000

    private fun dist(p: Place) = p.distanceM ?: Int.MAX_VALUE

    /** 승객이 타는 역 (카테고리 '기차역'·'KTX정차역'…, 폐역·화물역·신호장·가게 제외) */
    private fun passenger(p: Place): Boolean {
        val c = p.category
        return (c == "기차역" || c.endsWith("정차역")) && listOf("폐역", "화물", "신호장", "신호소").none { p.name.contains(it) }
    }

    private fun highSpeed(p: Place) = p.category.contains("KTX") || p.category.contains("SRT")

    /**
     * 승객이 타는 역 최대 [max] 곳: 가장 가까운 역 + 가장 가까운 KTX 역 + 가까운 큰 역들.
     * [any] = '기차역' 검색, [ktx] = 'KTX정차역' 검색 (카카오 결과, 가까운 순)
     */
    fun stations(any: List<Place>, ktx: List<Place>, max: Int = 3): List<Place> {
        val anyOk = any.filter(::passenger).sortedBy(::dist)
        val ktxOk = (ktx + any).filter { passenger(it) && highSpeed(it) }.sortedBy(::dist)
        val hubs = (anyOk + ktxOk).distinctBy { TagoMatching.stationKey(it.name) }.sortedBy(::dist)
            .filter { TagoMatching.stationKey(it.name) in HUBS }
        val nearestHub = hubs.firstOrNull()?.let(::dist)
        val nearHubs = if (nearestHub == null) emptyList() else hubs.filter { dist(it) <= nearestHub * HUB_RATIO + HUB_SLACK_M }
        return (listOfNotNull(anyOk.firstOrNull(), ktxOk.firstOrNull()) + nearHubs)
            .distinctBy { TagoMatching.stationKey(it.name) }
            .take(max)
    }

    /** '고속,시외버스터미널' 검색 결과 → 같은 터미널(서관·동관)은 하나로, 가까운 순 */
    fun terminals(found: List<Place>): List<Place> = found
        .filter { it.category.contains("버스터미널") }
        .sortedBy(::dist)
        .distinctBy { TagoMatching.terminalKeys(it.name, heavy = false).firstOrNull() ?: it.name }
}

/** 오늘 열차 시간표에 KTX 가 통째로 빠진 날 (2026-10-13 대전→서울: 28편 중 KTX 2편, 다른 날은 90편) */
object TimetableFill {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    fun highSpeed(grade: String): Boolean = grade.contains("KTX") || grade.contains("SRT")

    /** 두 역 모두 KTX 역인데 오늘 KTX 가 3편도 안 되면 다음 주 같은 요일 시간표로 채워 본다 */
    fun needsWeekly(today: List<ScheduledRun>, bothHighSpeed: Boolean): Boolean =
        bothHighSpeed && today.count { highSpeed(it.grade) } < 3

    /** 다음 주 같은 요일 시간표를 오늘 날짜로 옮겨, 오늘 목록에 없는 열차만 더한다 (출발 순) */
    fun merge(today: List<ScheduledRun>, nextWeek: List<ScheduledRun>): List<ScheduledRun> {
        val numbers = today.map { it.number }.toSet()
        val added = nextWeek
            .map { it.copy(depAtMs = weekBefore(it.depAtMs), arrAtMs = weekBefore(it.arrAtMs), weekly = true) }
            .filter { r ->
                r.number !in numbers &&
                    // 같은 시각에 같은 급 열차가 이미 있으면 같은 열차(번호만 다름)
                    today.none { t -> t.depAtMs == r.depAtMs && highSpeed(t.grade) == highSpeed(r.grade) }
            }
        return (today + added).sortedBy { it.depAtMs }
    }

    private fun weekBefore(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(SEOUL).minusDays(7).toInstant().toEpochMilli()
}

/**
 * 역·터미널까지 가는 길 (또는 역에서 목적지까지).
 * [route] = 카카오 대중교통 경로 (null 이면 직선 도보 어림), [fare] = 그 길의 요금 (모르면 null, 걸어가면 0)
 */
data class AccessLeg(val place: Place, val route: TransitRoute?, val timeSec: Int, val fare: Int?)

/** 출발·도착 역(터미널) 한 쌍과 그 사이 시간표 */
data class StationPair(val kind: IntercityKind, val dep: AccessLeg, val arr: AccessLeg, val runs: List<ScheduledRun>)

/** 역까지 가는 길 + 기차·버스 + 역에서 목적지까지를 카카오맵 대중교통 경로처럼 한 줄로 */
object IntercityItineraries {
    /** 역·터미널에 도착해서 타기까지의 여유 */
    const val BOARD_BUFFER_SEC = 5 * 60
    private const val WALK_MPS = 1.1
    private const val DETOUR = 1.3
    /** 이보다 가까우면 걸어서 (카카오 대중교통을 묻지 않음) */
    const val WALK_ONLY_M = 700.0
    private const val TRANSIT_WAIT_SEC = 8 * 60
    private const val TRANSIT_DETOUR = 1.35
    private const val TRANSIT_MPS = 5.0
    /** 선로·도로는 직선보다 길다 (거리 표시용 어림) */
    private const val RAIL_DETOUR = 1.15
    private const val ROAD_DETOUR = 1.2
    private const val MIN_WALK_M = 15.0

    /**
     * 지금([nowMs]) 출발해 탈 수 있는 편으로 만든 경로들 — 종류마다 [perKind] 개, 목적지에 빨리 닿는 순
     * (같은 시각이면 늦게 나가도 되는 것 먼저). 역까지 시간 + 여유 안에 떠나는 편은 뺀다.
     */
    fun build(nowMs: Long, origin: Coord, destination: Place, pairs: List<StationPair>, perKind: Int = 3): List<TransitRoute> {
        class Option(val pair: StationPair, val run: ScheduledRun, val arriveAt: Long, val leaveAt: Long) {
            /** 같은 열차가 번호 둘로(묶어 다니는 편성) 실려 있어도 한 번만 */
            val key = "${pair.kind}|${run.depAtMs}|${run.arrAtMs}|${pair.dep.place.name}|${pair.arr.place.name}"
        }
        val options = ArrayList<Option>()
        for (pair in pairs) {
            if (pair.dep.place.name == pair.arr.place.name) continue
            for (run in pair.runs) {
                val leaveAt = run.depAtMs - (pair.dep.timeSec + BOARD_BUFFER_SEC) * 1000L
                if (leaveAt < nowMs) continue
                options += Option(pair, run, run.arrAtMs + pair.arr.timeSec * 1000L, leaveAt)
            }
        }
        return IntercityKind.entries.flatMap { kind ->
            options.filter { it.pair.kind == kind }
                .sortedWith(compareBy<Option> { it.arriveAt }.thenByDescending { it.leaveAt })
                .distinctBy { it.key }
                .take(perKind)
                .map { route(nowMs, origin, destination, it.pair, it.run, it.arriveAt).copy(leaveByMs = it.leaveAt) }
        }
    }

    private fun route(nowMs: Long, origin: Coord, destination: Place, pair: StationPair, run: ScheduledRun, arriveAt: Long): TransitRoute {
        val dep = pair.dep
        val arr = pair.arr
        val board = dep.place.coord
        val alight = arr.place.coord
        val access = dep.route?.steps ?: walk(origin, board, "${dep.place.name}까지 도보", dep.timeSec)
        val egress = arr.route?.steps
            ?: walk(alight, destination.coord, "${destination.name.ifBlank { "도착지" }}까지 도보", arr.timeSec)
        val vehicle = vehicle(run)
        val ride = TransitStep(
            type = if (run.kind == IntercityKind.TRAIN) TransitStepType.TRAIN else TransitStepType.COACH,
            guidance = "${vehicle.type} ${vehicle.name} · ${dep.place.name} → ${arr.place.name}",
            distanceM = (Geo.distanceM(board, alight) * (if (run.kind == IntercityKind.TRAIN) RAIL_DETOUR else ROAD_DETOUR)).roundToInt(),
            timeSec = ((run.arrAtMs - run.depAtMs) / 1000).toInt(),
            stops = listOf(dep.place.name, arr.place.name),
            vehicles = listOf(vehicle),
            points = listOf(board, alight),
            departAtMs = run.depAtMs,
            arriveAtMs = run.arrAtMs,
            weekly = run.weekly,
        )
        val steps = access + ride + egress
        val depFare = dep.fare
        val arrFare = arr.fare
        return TransitRoute(
            type = run.kind.name,
            totalDistanceM = steps.sumOf { it.distanceM },
            totalTimeSec = ((arriveAt - nowMs) / 1000).toInt(),
            transfers = (steps.count { it.type.ride } - 1).coerceAtLeast(0),
            fare = if (run.fare == null || depFare == null || arrFare == null) null else run.fare + depFare + arrFare,
            steps = steps,
            arriveAtMs = arriveAt,
        )
    }

    private fun vehicle(run: ScheduledRun): Vehicle = when (run.kind) {
        IntercityKind.TRAIN -> Vehicle(run.grade.ifBlank { "열차" }, run.number)
        IntercityKind.EXPRESS -> Vehicle("고속", gradeLabel(run.kind, run.grade))
        IntercityKind.INTERCITY -> Vehicle("시외", gradeLabel(run.kind, run.grade))
    }

    /** 직선으로 채운 도보 구간 (15m 안이면 없음) — 역까지·역에서 목적지까지, 안내 중 다시 찾을 때도 */
    fun walk(from: Coord, to: Coord, guidance: String, timeSec: Int): List<TransitStep> {
        val d = Geo.distanceM(from, to)
        if (d < MIN_WALK_M) return emptyList()
        return listOf(
            TransitStep(
                type = TransitStepType.WALK,
                guidance = guidance,
                distanceM = (d * DETOUR).roundToInt(),
                timeSec = timeSec,
                stops = emptyList(),
                vehicles = emptyList(),
                points = listOf(from, to),
                approx = true,
            )
        )
    }

    /** 직선 도보 어림 (실제 걷는 길은 직선의 1.3배, 걸음 1.1m/s) */
    fun walkSec(from: Coord, to: Coord): Int = (Geo.distanceM(from, to) * DETOUR / WALK_MPS).roundToInt().coerceAtLeast(30)

    /** 카카오 경로를 받기 전 어림: 700m 안은 걸어서, 그보다 멀면 대중교통 (기다림 8분 + 시속 18km) */
    fun estimateSec(from: Coord, to: Coord): Int {
        val d = Geo.distanceM(from, to)
        return if (d <= WALK_ONLY_M) walkSec(from, to) else (TRANSIT_WAIT_SEC + d * TRANSIT_DETOUR / TRANSIT_MPS).roundToInt()
    }

    /** 버스 등급을 보이는 이름으로: 고속버스의 '고속' → '일반', '심야고속' → '심야' */
    fun gradeLabel(kind: IntercityKind, grade: String): String = when {
        kind != IntercityKind.EXPRESS -> grade
        grade == "고속" -> "일반"
        grade == "심야고속" -> "심야"
        else -> grade
    }
}

/** 안내 중 다시 찾기: 아직 타지 않은 기차·버스가 있으면 그 역까지만 새로 찾고 나머지는 그대로 */
object IntercitySplice {
    /** 역까지 가서 타기까지 남겨 둘 여유 */
    private const val MARGIN_SEC = 2 * 60

    /** 지금 [stepIndex] 부터 처음 나오는, 아직 타지 않은 기차·버스 구간 번호 ([onRide] 이고 지금 구간이 그것이면 이미 탄 것) */
    fun nextScheduled(route: TransitRoute, stepIndex: Int, onRide: Boolean): Int? {
        for (k in stepIndex.coerceAtLeast(0) until route.steps.size) {
            if (!route.steps[k].type.scheduled) continue
            return if (k == stepIndex && onRide) null else k
        }
        return null
    }

    /** 지금 출발해서 [accessSec] 걸려 역에 가도 탈 수 있는지 (2분 여유) */
    fun canMake(step: TransitStep, accessSec: Int, nowMs: Long): Boolean {
        val departs = step.departAtMs ?: return false
        return nowMs + (accessSec + MARGIN_SEC) * 1000L <= departs
    }

    /** [access] (지금 위치 → 그 역) + 원래 경로의 [k] 번째 구간부터. 총 시간은 마지막 도착 시각 기준으로 다시 */
    fun splice(route: TransitRoute, k: Int, access: List<TransitStep>, nowMs: Long): TransitRoute {
        val steps = access + route.steps.drop(k)
        val last = steps.indexOfLast { it.type.scheduled }
        val arrival = steps.getOrNull(last)?.arriveAtMs?.let { at -> at + steps.drop(last + 1).sumOf { it.timeSec } * 1000L }
        val total = arrival?.let { ((it - nowMs) / 1000).toInt() } ?: steps.sumOf { it.timeSec }
        val firstRide = steps.firstOrNull { it.type.scheduled }
        return route.copy(
            steps = steps,
            totalTimeSec = total.coerceAtLeast(0),
            totalDistanceM = steps.sumOf { it.distanceM },
            transfers = (steps.count { it.type.ride } - 1).coerceAtLeast(0),
            arriveAtMs = arrival,
            leaveByMs = firstRide?.departAtMs?.let { d -> d - steps.takeWhile { !it.type.scheduled }.sumOf { it.timeSec } * 1000L },
        )
    }
}
