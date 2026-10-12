package com.mywatch.kakaomap.model

/**
 * 카카오 경로의 '승차 정류장·버스 번호'를 공공데이터(TAGO) 정류소·노선과 맞춰 보는 규칙.
 * 이름 표기가 조금씩 달라서(공백, 괄호, '.') 정규화한 뒤 비교한다.
 */
object TransitMatching {

    fun normalizeStop(name: String): String =
        name.replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[\\s.·ㆍ\\-]"), "")
            .lowercase()

    /** "마을 76", "76번", "간선 102" → "76", "102" / "급행1" 은 그대로 */
    fun normalizeRoute(no: String): String =
        no.replace(Regex("\\s"), "")
            .replace(Regex("번$"), "")
            .replace(Regex("^(간선|지선|광역|순환|마을|좌석|직행|일반|공항|외곽|시외|농어촌)"), "")
            .lowercase()

    fun sameRoute(a: String, b: String): Boolean {
        val x = normalizeRoute(a)
        val y = normalizeRoute(b)
        return x.isNotEmpty() && x == y
    }

    /**
     * 근처 정류소 후보 중에서 승차 정류장을 고른다.
     * 이름이 같거나 포함 관계인 곳 중 가장 가까운 곳, 없으면 60m 안의 가장 가까운 곳.
     */
    fun pickStop(candidates: List<BusStop>, boardingName: String?, boardingCoord: Coord): BusStop? {
        if (candidates.isEmpty()) return null
        val target = boardingName?.let { normalizeStop(it) }.orEmpty()
        val byDistance = candidates.sortedBy { Geo.distanceM(it.coord, boardingCoord) }
        if (target.isNotEmpty()) {
            byDistance.firstOrNull { normalizeStop(it.name) == target }?.let { return it }
            byDistance.firstOrNull {
                val n = normalizeStop(it.name)
                n.isNotEmpty() && (n.contains(target) || target.contains(n))
            }?.let { return it }
        }
        return byDistance.firstOrNull { Geo.distanceM(it.coord, boardingCoord) <= 60.0 }
    }

    /** 도착 정보 중 이 노선의 것만 (가까운 순) */
    fun arrivalsFor(arrivals: List<BusArrival>, routeNames: List<String>): List<BusArrival> =
        arrivals.filter { a -> routeNames.any { sameRoute(it, a.routeNo) } }
}
