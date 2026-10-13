package com.mywatch.kakaomap.model

/** 대중교통 경로 종류 거르기 (카카오맵 결과의 전체 · 버스 · 지하철 · 버스+지하철 · 열차 · 고속버스 · 시외버스) */
enum class TransitFilter(val label: String) {
    ALL("전체"), BUS("버스"), SUBWAY("지하철"), BUS_AND_SUBWAY("버스+지하철"),
    TRAIN("열차"), EXPRESS("고속버스"), INTERCITY("시외버스");
}

/** 대중교통 경로 정렬 */
enum class TransitSort(val label: String) {
    /** 카카오가 준 순서 */
    RECOMMENDED("추천순"),
    FASTEST("최소시간"),
    FEWEST_TRANSFERS("최소환승"),
    LEAST_WALK("최소도보");

    fun next(): TransitSort = entries[(ordinal + 1) % entries.size]

    companion object {
        /** 처음엔 가장 빨리 도착하는 경로부터 (사용자가 정렬 칩으로 바꾸면 그것을 기억) */
        val DEFAULT = FASTEST

        fun saved(name: String?): TransitSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 경로 목록 거르기·정렬 (순수 계산 — 단위 테스트로 검증) */
object TransitOptions {

    /**
     * 이 경로의 종류. 기차·고속·시외버스를 타면 그것(역까지 탄 버스·지하철보다 우선), 아니면 탈 것 구간으로 판단하고,
     * 없으면 카카오 type 으로. 모르는 것만 있으면 null(전체에만 보임)
     */
    fun kindOf(r: TransitRoute): TransitFilter? {
        if (r.steps.any { it.type == TransitStepType.TRAIN }) return TransitFilter.TRAIN
        r.steps.firstOrNull { it.type == TransitStepType.COACH }?.let { coach ->
            return if (coach.vehicles.firstOrNull()?.type?.contains("시외") == true) TransitFilter.INTERCITY else TransitFilter.EXPRESS
        }
        val bus = r.steps.any { it.type == TransitStepType.BUS }
        val subway = r.steps.any { it.type == TransitStepType.SUBWAY }
        return when {
            bus && subway -> TransitFilter.BUS_AND_SUBWAY
            bus -> TransitFilter.BUS
            subway -> TransitFilter.SUBWAY
            else -> when (r.type.uppercase()) {
                "BUS" -> TransitFilter.BUS
                "SUBWAY" -> TransitFilter.SUBWAY
                "BUS_AND_SUBWAY" -> TransitFilter.BUS_AND_SUBWAY
                "TRAIN" -> TransitFilter.TRAIN
                "EXPRESS" -> TransitFilter.EXPRESS
                "INTERCITY" -> TransitFilter.INTERCITY
                else -> null
            }
        }
    }

    /** 고를 수 있는 종류와 개수: 전체 + 실제로 있는 종류. 한 종류뿐이면 전체만 */
    fun filters(routes: List<TransitRoute>): List<Pair<TransitFilter, Int>> {
        val counts = routes.mapNotNull(::kindOf).groupingBy { it }.eachCount()
        val kinds = TransitFilter.entries.filter { it != TransitFilter.ALL && (counts[it] ?: 0) > 0 }
        val all = TransitFilter.ALL to routes.size
        return if (kinds.size <= 1) listOf(all) else listOf(all) + kinds.map { it to counts.getValue(it) }
    }

    /** 거르고 정렬한 결과. 상세 화면이 원래 목록의 번호를 쓰므로 번호(index)를 함께 돌려준다 */
    fun apply(routes: List<TransitRoute>, filter: TransitFilter, sort: TransitSort): List<IndexedValue<TransitRoute>> {
        val kept = routes.withIndex().filter { filter == TransitFilter.ALL || kindOf(it.value) == filter }
        val byTime = compareBy<IndexedValue<TransitRoute>> { it.value.totalTimeSec }
        return when (sort) {
            TransitSort.RECOMMENDED -> kept
            TransitSort.FASTEST -> kept.sortedWith(byTime.thenBy { it.value.transfers }.thenBy { it.value.walkTimeSec })
            TransitSort.FEWEST_TRANSFERS -> kept.sortedWith(compareBy<IndexedValue<TransitRoute>> { it.value.transfers }.then(byTime))
            TransitSort.LEAST_WALK -> kept.sortedWith(compareBy<IndexedValue<TransitRoute>> { it.value.walkTimeSec }.then(byTime))
        }
    }

    /** 지금 시각 기준으로 다시 잰 목록 — 기차·버스 시간표 경로는 도착 시각이 정해져 있어 볼 때마다 남은 시간이 준다 (번호 그대로) */
    fun current(routes: List<TransitRoute>, nowMs: Long): List<TransitRoute> =
        routes.map { if (it.arriveAtMs == null) it else it.copy(totalTimeSec = it.secondsFrom(nowMs)) }

    /**
     * 나가야 할 시각이 지나 못 타게 된 기차·버스 편을 뺀 목록과, 그 때문에 어떤 종류(열차·고속·시외)가 다 사라졌는지
     * (그럴 때만 다음 편을 다시 찾는다 — 한 편 지날 때마다 전부 다시 찾으면 API 를 수십 번 부른다)
     */
    fun dropExpired(routes: List<TransitRoute>, nowMs: Long): Pair<List<TransitRoute>, Boolean> {
        val kept = routes.filterNot { it.expired(nowMs) }
        if (kept.size == routes.size) return routes to false
        val keptKinds = kept.mapNotNull(::kindOf).toSet()
        val ranOut = kept.isEmpty() || routes.filter { it.expired(nowMs) }.mapNotNull(::kindOf).any { it !in keptKinds }
        return kept to ranOut
    }

    /** 가장 빨리 도착하는 경로의 번호 ('가장 빠름' 표시) — 최소시간 정렬의 첫 카드와 같다 */
    fun fastestIndex(routes: List<TransitRoute>): Int? =
        apply(routes, TransitFilter.ALL, TransitSort.FASTEST).firstOrNull()?.index

    /** 칩을 누르면 다음 종류로 (한 바퀴 돌면 전체) */
    fun next(current: TransitFilter, choices: List<TransitFilter>): TransitFilter {
        if (choices.isEmpty()) return TransitFilter.ALL
        val i = choices.indexOf(current)
        return choices[(i + 1).mod(choices.size)]
    }

    /** 새 결과에 없는 종류를 고르고 있었으면 전체로 */
    fun valid(current: TransitFilter, choices: List<TransitFilter>): TransitFilter =
        if (current in choices) current else TransitFilter.ALL
}
