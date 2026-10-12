package com.mywatch.kakaomap.model

/** 대중교통 경로 종류 거르기 (카카오맵 결과의 전체 · 버스 · 지하철 · 버스+지하철) */
enum class TransitFilter(val label: String) {
    ALL("전체"), BUS("버스"), SUBWAY("지하철"), BUS_AND_SUBWAY("버스+지하철");
}

/** 대중교통 경로 정렬 */
enum class TransitSort(val label: String) {
    /** 카카오가 준 순서 */
    RECOMMENDED("추천순"),
    FASTEST("최소시간"),
    FEWEST_TRANSFERS("최소환승"),
    LEAST_WALK("최소도보");

    fun next(): TransitSort = entries[(ordinal + 1) % entries.size]
}

/** 경로 목록 거르기·정렬 (순수 계산 — 단위 테스트로 검증) */
object TransitOptions {

    /** 이 경로의 종류. 탈 것 구간으로 판단하고, 없으면 카카오 type 으로. 기차 등 모르는 것만 있으면 null(전체에만 보임) */
    fun kindOf(r: TransitRoute): TransitFilter? {
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
