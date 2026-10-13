package com.mywatch.kakaomap.model

/**
 * 카카오 도보·자전거 경로 옵션 (카카오 길찾기 API 의 route_mode) — 카카오맵 앱과 같은 이름·순서.
 * 자전거 기본은 '자전거도로 우선'(조금 돌아가더라도 자전거도로로), 도보 기본은 '큰길 우선'.
 */
enum class RouteOption(val mode: TravelMode, val api: String, val label: String) {
    BIKE_ROAD(TravelMode.BIKE, "BIKE_ONLY", "자전거도로 우선"),
    BIKE_SHORTEST(TravelMode.BIKE, "SHORTEST", "최단 거리"),
    BIKE_COMFORT(TravelMode.BIKE, "ACCESSIBLE", "편안한 길"),
    WALK_BROAD(TravelMode.WALK, "BROAD_FIRST", "큰길 우선"),
    WALK_SHORTEST(TravelMode.WALK, "SHORTEST", "최단 거리"),
    WALK_COMFORT(TravelMode.WALK, "ACCESSIBLE", "편안한 길");

    companion object {
        fun of(mode: TravelMode): List<RouteOption> = entries.filter { it.mode == mode }
    }
}

/** 경로 카드 하나: 같은 경로를 낸 옵션들 (카카오맵처럼 같은 길은 한 카드로) */
data class RouteChoice(val options: List<RouteOption>, val route: SimpleRoute) {
    val primary: RouteOption get() = options.first()
    val label: String get() = options.joinToString(" · ") { it.label }
}

/** 옵션별 경로 합치기·가장 빠른 길 고르기 (순수 계산 — 단위 테스트로 검증) */
object RouteChoices {

    /** 옵션 순서를 지키며 같은 경로(거리·시간·좌표가 같음)는 한 카드로 */
    fun merge(results: List<Pair<RouteOption, SimpleRoute>>): List<RouteChoice> {
        val out = ArrayList<RouteChoice>()
        for ((option, route) in results) {
            val i = out.indexOfFirst { same(it.route, route) }
            if (i >= 0) out[i] = out[i].copy(options = out[i].options + option)
            else out += RouteChoice(listOf(option), route)
        }
        return out
    }

    private fun same(a: SimpleRoute, b: SimpleRoute): Boolean =
        a.totalDistanceM == b.totalDistanceM && a.totalTimeSec == b.totalTimeSec &&
            a.steps.flatMap { it.points } == b.steps.flatMap { it.points }

    /** 가장 빨리 도착하는 카드 (같으면 짧은 것, 그다음 카카오 순서) */
    fun fastest(choices: List<RouteChoice>): RouteChoice? =
        choices.minWithOrNull(compareBy<RouteChoice> { it.route.totalTimeSec }.thenBy { it.route.totalDistanceM })

    /** 보여 줄 카드: 사용자가 전에 고른 옵션이 있으면 그것, 없으면 가장 빠른 길 */
    fun pick(choices: List<RouteChoice>, remembered: RouteOption?): RouteChoice? =
        choices.firstOrNull { remembered != null && remembered in it.options } ?: fastest(choices)

    /**
     * 카드를 눌렀을 때 기억할 옵션 ([remembered] = 지금까지 기억하던 것).
     *  · 기억하던 옵션이 그 카드에 들어 있으면(같은 길로 합쳐진 카드) 그대로 — '편안한 길'이 '큰길 우선'으로 바뀌지 않게
     *  · '가장 빠름' 카드면 null (다음에도 늘 가장 빠른 길). 단, 기억하던 옵션이 이번에만 받지 못해 빠진 거면 그대로 둔다
     *  · 다른 카드면 그 카드의 옵션(합쳐진 카드는 앞 이름)
     */
    fun rememberAfterTap(tapped: RouteChoice, choices: List<RouteChoice>, remembered: RouteOption?): RouteOption? = when {
        remembered != null && remembered in tapped.options -> remembered
        tapped == fastest(choices) ->
            if (remembered != null && choices.none { remembered in it.options }) remembered else null
        else -> tapped.primary
    }

    /** 고른 카드로 안내할 때 실을 옵션 — 경로 이탈 재탐색도 이 옵션으로 다시 찾는다 */
    fun optionFor(picked: RouteChoice, remembered: RouteOption?): RouteOption =
        remembered?.takeIf { it in picked.options } ?: picked.primary

    /** 이동수단 탭에 적을 시간 = 그 탭을 누르면 고르게 될 길의 시간 */
    fun seconds(choices: List<RouteChoice>?, remembered: RouteOption?): Int? =
        choices?.let { pick(it, remembered) }?.route?.totalTimeSec

    /**
     * 지금 보고 있지 않은 탭의 경로도 미리 받아 시간을 적을지. 도보는 직선 15km(4시간 남짓), 자전거는 50km 까지만 —
     * 그보다 먼 길은 응답이 커서(좌표 수천 개 × 옵션 3개) 그 탭을 열 때만 받는다. 대중교통은 늘.
     */
    fun worthPrefetching(mode: TravelMode, straightM: Int): Boolean = when (mode) {
        TravelMode.TRANSIT -> true
        TravelMode.WALK -> straightM <= PREFETCH_WALK_M
        TravelMode.BIKE -> straightM <= PREFETCH_BIKE_M
    }

    private const val PREFETCH_WALK_M = 15_000
    private const val PREFETCH_BIKE_M = 50_000

    /** 이동수단 탭의 '가장 빠름': 결과가 둘 이상 있을 때만 */
    fun fastestMode(bestSec: Map<TravelMode, Int?>): TravelMode? {
        val known = bestSec.filterValues { it != null && it > 0 }
        if (known.size < 2) return null
        return known.minByOrNull { it.value!! }?.key
    }
}
