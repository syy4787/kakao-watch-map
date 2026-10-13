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

/**
 * 도보 · 버스 · 지하철 · 기차(KTX·ITX·무궁화) · 고속·시외버스(COACH) · 기타.
 * 기차와 고속·시외버스는 카카오 대중교통 API에 없어 공공데이터 시간표로 붙인다 — 정해진 시각에 떠나는 '긴 탈 것'
 */
enum class TransitStepType {
    WALK, BUS, SUBWAY, TRAIN, COACH, OTHER;

    /** 타는 구간인지 */
    val ride: Boolean get() = this == BUS || this == SUBWAY || this == TRAIN || this == COACH

    /** 시간표대로 떠나는 장거리 탈 것 (역·터미널을 직선으로 이은 구간) */
    val scheduled: Boolean get() = this == TRAIN || this == COACH
}

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
    /**
     * 응답에 없어서 우리가 채운 도보(출발지→첫 정류장, 마지막 정류장→도착지, 기차역 오가는 짧은 도보).
     * 안내 중 이 구간은 길을 벗어나도 이탈로 보지 않는다 (정류장까지는 아는 길로 가도 되므로)
     */
    val approx: Boolean = false,
    /** [approx] 도보를 카카오 도보 길찾기의 실제 걷는 길로 바꿨음 (아니면 직선) */
    val pathFound: Boolean = false,
    /** 기차·고속·시외버스: 시간표의 출발·도착 시각 (그 밖엔 null) */
    val departAtMs: Long? = null,
    val arriveAtMs: Long? = null,
    /** 오늘 시간표에 빠져 있어 다음 주 같은 요일 시간표로 채운 편 */
    val weekly: Boolean = false,
) {
    /** "마을 76 (판교역동편 > 성남시청전면)" 같은 안내문에서 승차·하차 정류장을 꺼낸다 (정류장 목록이 없을 때만 씀) */
    private val stopPair: Pair<String, String>? by lazy { parseStopPair(guidance) }

    /** 승차·하차 정류장: 응답의 정류장 목록 처음·끝이 가장 정확하다 (실제 대전 응답에서 안내문 이름과 늘 같음) */
    val boardingStop: String? get() = if (stops.size >= 2) stops.first() else stopPair?.first ?: stops.firstOrNull()
    val alightingStop: String? get() = if (stops.size >= 2) stops.last() else stopPair?.second ?: stops.lastOrNull()

    /** 지나가는 정류장(역) 수 — 승차 정류장은 빼고 센다 */
    val stopCount: Int get() = if (stops.size >= 2) stops.size - 1 else stops.size

    companion object {
        /**
         * 안내문 맨 끝 괄호 "(승차 > 하차)" 의 두 정류장. 이름 안의 괄호("1호선 (유성온천(충남대.목원대) > 대전역)")까지
         * 짝을 맞춰 바깥 괄호를 찾고 " > " 로 나눈다
         */
        fun parseStopPair(guidance: String): Pair<String, String>? {
            val close = guidance.lastIndexOf(')')
            if (close < 0) return null
            var depth = 0
            var open = -1
            for (i in close downTo 0) {
                when (guidance[i]) {
                    ')' -> depth++
                    '(' -> if (--depth == 0) {
                        open = i
                        break
                    }
                }
            }
            if (open < 0) return null
            val inner = guidance.substring(open + 1, close)
            val parts = inner.split(" > ").takeIf { it.size == 2 } ?: inner.split('>').takeIf { it.size == 2 } ?: return null
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
    /** 요금 (거리 따라 달라지면 가장 싼 값) */
    val fare: Int?,
    val steps: List<TransitStep>,
    /** 기차·버스 시간표 경로: 목적지 도착 시각 (epoch ms). 시내 경로는 null — 언제 떠나도 걸리는 시간이 같다 */
    val arriveAtMs: Long? = null,
    /** 기차·버스 시간표 경로: 늦어도 이때 출발해야 탈 수 있음 */
    val leaveByMs: Long? = null,
    /** 거리 따라 요금이 달라지는 경로(광역·농어촌 버스 등)의 가장 비싼 값 — 카카오맵처럼 "1,500~3,150원" */
    val fareMax: Int? = null,
) {
    /** 지금부터 도착까지 (시간표 경로는 도착 시각 기준, 그 밖엔 걸리는 시간 그대로) */
    fun secondsFrom(nowMs: Long): Int = arriveAtMs?.let { ((it - nowMs) / 1000).toInt().coerceAtLeast(0) } ?: totalTimeSec

    /** 시간표 경로인데 나가야 할 시각이 지나 이제 못 탐 */
    fun expired(nowMs: Long): Boolean = leaveByMs?.let { nowMs > it } ?: false

    val walkTimeSec: Int get() = steps.filter { it.type == TransitStepType.WALK }.sumOf { it.timeSec }
    val firstRide: TransitStep? get() = steps.firstOrNull { it.type.ride }
}

/** 공공데이터(TAGO) 버스 정류소 */
data class BusStop(
    val cityCode: Int,
    val nodeId: String,
    val name: String,
    val coord: Coord,
)

/** 공공데이터(TAGO) 버스 도착 예정 정보. [routeId] = TAGO 노선 번호(같은 번호의 다른 노선과 구별) */
data class BusArrival(
    val routeNo: String,
    val routeType: String,
    val arrivalSec: Int,
    val stopsAway: Int,
    val routeId: String = "",
)
