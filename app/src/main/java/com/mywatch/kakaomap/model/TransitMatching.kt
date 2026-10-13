package com.mywatch.kakaomap.model

/**
 * 카카오 경로의 '승차 정류장·버스 번호'를 공공데이터(TAGO) 정류소·노선과 맞춰 보는 규칙.
 * 이름 표기가 조금씩 달라서(공백, 괄호, '.') 정규화한 뒤 비교한다.
 */
object TransitMatching {

    fun normalizeStop(name: String): String =
        name.replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[\\s.·ㆍ\\-]"), "")
            .removeSuffix("정류장")
            .removeSuffix("정류소")
            .lowercase()

    /**
     * 비교할 이름들: 전체 + 카카오가 두 이름을 '/'로 붙인 정류장("삼성동행정복지센터/중부소방서", "대전역/역전시장")은 각각도.
     * TAGO 는 그중 하나로만 부르는 일이 많다
     */
    fun stopKeys(name: String): List<String> =
        (listOf(name) + name.split('/')).map(::normalizeStop).filter { it.isNotEmpty() }.distinct()

    /** 정류장 이름이 같은지 (괄호·띄어쓰기·'.'·'정류장' 무시, '/'로 붙인 이름은 하나만 같아도) */
    fun sameStopName(a: String, b: String): Boolean {
        val kb = stopKeys(b)
        return stopKeys(a).any { it in kb }
    }

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
     * 카카오 노선(종류·번호)과 TAGO 노선 번호가 같은 버스인지. 카카오는 대전 급행버스를 종류 '급행' + 번호 '2'로 주고
     * TAGO·정류장 표지는 '급행2' 라서 종류를 붙인 이름도 본다 ("일반 106"·"마을 5" 는 번호만 같으면 됨)
     */
    fun sameLine(v: Vehicle, routeNo: String): Boolean = sameRoute(v.type + v.name, routeNo) || sameRoute(v.name, routeNo)

    /** TAGO 노선 번호 검색어: 종류가 이름의 일부인 노선('급행2')은 그것부터, 그다음 번호만 */
    fun routeQueries(v: Vehicle): List<String> {
        val name = v.name.trim()
        val typed = (v.type + name).replace(Regex("\\s"), "")
        return (if (normalizeRoute(typed) != normalizeRoute(name)) listOf(typed, name) else listOf(name)).filter { it.isNotEmpty() }
    }

    /** 화면에 보일 노선 이름: 대전 급행버스는 정류장 표지처럼 '급행2' (그 밖엔 번호 그대로) */
    fun displayName(v: Vehicle): String =
        if (v.type == "급행" && v.name.isNotBlank() && !v.name.startsWith("급행")) "급행${v.name}" else v.name

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
        val targets = boardingName?.let(::stopKeys).orEmpty()
        fun group(s: BusStop): Int {
            val keys = stopKeys(s.name)
            return when {
                targets.isNotEmpty() && keys.any { it in targets } -> 0
                targets.isNotEmpty() && keys.any { k -> targets.any { t -> k.contains(t) || t.contains(k) } } -> 1
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

    /**
     * 후보 정류장을 차례로 물어서 이 노선 도착 정보가 있는 첫 정류장과 그 도착 정보 (없으면 null).
     * 다른 도시 데이터로 나온 같은 정류장(대전 시청의 세종 BRT)으로만 넘어가고, 같은 도시의 다른 후보로는 넘어가지 않는다 —
     * 같은 이름의 같은 도시 정류장은 대개 길 건너 반대 방향이라, 그쪽 버스 도착을 보여 주면 틀린 정보가 된다
     */
    fun firstServing(
        candidates: List<BusStop>,
        routeNames: List<String>,
        fetch: (BusStop) -> List<BusArrival>,
    ): Pair<BusStop, List<BusArrival>>? = firstServingWith(candidates, { a -> routeNames.any { sameRoute(it, a.routeNo) } }, fetch)

    /** [firstServing] 의 노선(종류·번호·TAGO 노선 번호) 버전 */
    fun firstServingLines(
        candidates: List<BusStop>,
        lines: List<BusLine>,
        fetch: (BusStop) -> List<BusArrival>,
    ): Pair<BusStop, List<BusArrival>>? = firstServingWith(candidates, { a -> lines.any { it.matches(a) } }, fetch)

    private fun firstServingWith(
        candidates: List<BusStop>,
        mine: (BusArrival) -> Boolean,
        fetch: (BusStop) -> List<BusArrival>,
    ): Pair<BusStop, List<BusArrival>>? {
        val askedCities = HashSet<Int>()
        for (stop in candidates) {
            if (!askedCities.add(stop.cityCode)) continue
            val found = fetch(stop).filter(mine)
            if (found.isNotEmpty()) return stop to found
        }
        return null
    }

    /** 도착 정보 중 이 노선의 것만 (가까운 순) */
    fun arrivalsFor(arrivals: List<BusArrival>, routeNames: List<String>): List<BusArrival> =
        arrivals.filter { a -> routeNames.any { sameRoute(it, a.routeNo) } }
}
