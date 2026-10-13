package com.mywatch.kakaomap.model

/** 위도·경도 (카카오 API의 x = 경도, y = 위도) */
data class Coord(val lat: Double, val lng: Double)

/**
 * GPS 위치 한 번 (좌표 + 정확도 + 측정 시각).
 * elapsedMs 는 부팅 후 경과 시간(SystemClock.elapsedRealtime 기준) — 위치가 '언제' 잡혔는지로 신선도를 판단한다.
 */
data class Fix(val coord: Coord, val accuracyM: Float, val elapsedMs: Long) {
    /** 길찾기 출발지로 그대로 써도 될 만큼 정확한지 (Wi-Fi·기지국 위치는 수백 m 틀릴 수 있다) */
    val goodForStart: Boolean get() = !accuracyM.isNaN() && accuracyM <= 50f
}

/** 키워드 검색 결과 장소 */
data class Place(
    val id: String,
    val name: String,
    val category: String,
    val address: String,
    val roadAddress: String,
    val coord: Coord,
    val distanceM: Int?,
    /** 카카오 카테고리 그룹 코드 (SW8 = 지하철역 …) */
    val group: String = "",
) {
    val displayAddress: String get() = roadAddress.ifBlank { address }
}

/** TAGO 버스 노선 (노선 번호로 찾은 것) */
data class BusRoute(val routeId: String, val routeNo: String)

enum class TravelMode { TRANSIT, WALK, BIKE }

/** 도보·자전거 경로의 한 구간 */
data class PathStep(
    val guidance: String,
    val distanceM: Int,
    val timeSec: Int,
    val start: Coord,
    val points: List<Coord>,
)

/** 도보·자전거 경로 */
data class SimpleRoute(
    val mode: TravelMode,
    val totalDistanceM: Int,
    val totalTimeSec: Int,
    val steps: List<PathStep>,
    /** 이 경로를 낸 옵션 (자전거도로 우선 등) — 경로 이탈 재탐색도 같은 옵션으로 */
    val option: RouteOption? = null,
)

enum class TransitStepType { WALK, BUS, SUBWAY, OTHER }

/** 버스 노선 / 지하철 노선 (type 예: 간선·지선·마을·일반, name 예: 102·1호선) */
data class Vehicle(val type: String, val name: String)

/** 대중교통 경로의 한 구간 (도보·버스·지하철) */
data class TransitStep(
    val type: TransitStepType,
    val guidance: String,
    val distanceM: Int,
    val timeSec: Int,
    val stops: List<String>,
    val vehicles: List<Vehicle>,
    val points: List<Coord>,
    /** 응답에 없어서 직선으로 채운 도보(출발지→첫 정류장, 마지막 정류장→도착지) */
    val approx: Boolean = false,
) {
    /** "마을 76 (판교역동편 > 성남시청전면)" 같은 안내문에서 승차·하차 정류장을 꺼낸다 */
    private val stopPair: Pair<String, String>? by lazy { parseStopPair(guidance) }

    val boardingStop: String? get() = stopPair?.first ?: stops.firstOrNull()
    val alightingStop: String? get() = stopPair?.second ?: stops.lastOrNull()

    /** 지나가는 정류장(역) 수 — 승차 정류장은 빼고 센다 */
    val stopCount: Int get() = if (stops.size >= 2) stops.size - 1 else stops.size

    companion object {
        fun parseStopPair(guidance: String): Pair<String, String>? {
            val open = guidance.lastIndexOf('(')
            val close = guidance.lastIndexOf(')')
            if (open < 0 || close <= open) return null
            val inner = guidance.substring(open + 1, close)
            val parts = inner.split('>')
            if (parts.size != 2) return null
            val a = parts[0].trim()
            val b = parts[1].trim()
            return if (a.isNotEmpty() && b.isNotEmpty()) a to b else null
        }
    }
}

/** 대중교통 경로 하나 (카카오 type: BUS / SUBWAY / BUS_AND_SUBWAY) */
data class TransitRoute(
    val type: String,
    val totalDistanceM: Int,
    val totalTimeSec: Int,
    val transfers: Int,
    val fare: Int?,
    val steps: List<TransitStep>,
) {
    val walkTimeSec: Int get() = steps.filter { it.type == TransitStepType.WALK }.sumOf { it.timeSec }
    val firstRide: TransitStep? get() = steps.firstOrNull { it.type == TransitStepType.BUS || it.type == TransitStepType.SUBWAY }
}

/** 공공데이터(TAGO) 버스 정류소 */
data class BusStop(
    val cityCode: Int,
    val nodeId: String,
    val name: String,
    val coord: Coord,
)

/** 공공데이터(TAGO) 버스 도착 예정 정보 */
data class BusArrival(
    val routeNo: String,
    val routeType: String,
    val arrivalSec: Int,
    val stopsAway: Int,
)
