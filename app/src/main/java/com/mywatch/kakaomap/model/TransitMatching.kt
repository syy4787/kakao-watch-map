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

    /** 근처 정류소들에 가장 많이 나온 도시 코드 — 같은 정류장이 이웃 도시(대전 시청의 세종 BRT 등) 데이터로도 나올 때 이 도시 것을 먼저 */
    fun dominantCity(candidates: List<BusStop>): Int? =
        candidates.groupingBy { it.cityCode }.eachCount().maxByOrNull { it.value }?.key

    /**
     * 승차 정류장 후보를 물어볼 순서대로: 이름이 같은 곳 → 이름이 포함 관계인 곳 → 60m 안.
     * 각 무리 안에서는 이 도시(가장 많이 나온 도시 코드) 것 먼저, 그다음 가까운 순. 최대 [limit] 곳.
     * (실제 응답: '대전광역시청'이 대전 25·세종 12 두 번 나오고, 카카오 승차 위치는 세종 쪽에 더 가깝다)
     */
    fun stopCandidates(candidates: List<BusStop>, boardingName: String?, boardingCoord: Coord, limit: Int = 3): List<BusStop> {
        val city = dominantCity(candidates)
        val target = boardingName?.let { normalizeStop(it) }.orEmpty()
        fun group(s: BusStop): Int {
            val n = normalizeStop(s.name)
            return when {
                target.isNotEmpty() && n == target -> 0
                target.isNotEmpty() && n.isNotEmpty() && (n.contains(target) || target.contains(n)) -> 1
                Geo.distanceM(s.coord, boardingCoord) <= 60.0 -> 2
                else -> 3
            }
        }
        return candidates.map { it to group(it) }
            .filter { it.second < 3 }
            .sortedWith(
                compareBy<Pair<BusStop, Int>> { it.second }
                    .thenBy { if (it.first.cityCode == city) 0 else 1 }
                    .thenBy { Geo.distanceM(it.first.coord, boardingCoord) }
            )
            .map { it.first }
            .distinctBy { it.nodeId }
            .take(limit)
    }

    /** 승차 정류장: 후보 중 첫 번째 */
    fun pickStop(candidates: List<BusStop>, boardingName: String?, boardingCoord: Coord): BusStop? =
        stopCandidates(candidates, boardingName, boardingCoord).firstOrNull()

    /** 후보 정류장을 차례로 물어서 이 노선 도착 정보가 있는 첫 정류장과 그 도착 정보 (없으면 null) */
    fun firstServing(
        candidates: List<BusStop>,
        routeNames: List<String>,
        fetch: (BusStop) -> List<BusArrival>,
    ): Pair<BusStop, List<BusArrival>>? {
        for (stop in candidates) {
            val mine = arrivalsFor(fetch(stop), routeNames)
            if (mine.isNotEmpty()) return stop to mine
        }
        return null
    }

    /** 도착 정보 중 이 노선의 것만 (가까운 순) */
    fun arrivalsFor(arrivals: List<BusArrival>, routeNames: List<String>): List<BusArrival> =
        arrivals.filter { a -> routeNames.any { sameRoute(it, a.routeNo) } }
}
