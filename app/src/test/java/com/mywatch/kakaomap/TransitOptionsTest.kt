package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitOptions
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitSort
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 카카오맵 대중교통 결과처럼 '버스 / 지하철 / 버스+지하철'로 거르고 빠른·환승 적은·덜 걷는 순으로 정렬.
 * 순서·개수는 실제 대전 응답(대전역 → KAIST, 15개 경로)의 모양을 줄여 만든 것.
 */
class TransitOptionsTest {

    private val a = Coord(36.33, 127.43)
    private val b = Coord(36.37, 127.36)

    private fun step(type: TransitStepType, min: Int) = TransitStep(
        type, "", 1000, min * 60, stops = listOf("가", "나"),
        vehicles = if (type == TransitStepType.WALK) emptyList() else listOf(Vehicle("일반", "1")),
        points = listOf(a, b),
    )

    private fun route(type: String, min: Int, transfers: Int, walkMin: Int, vararg rides: TransitStepType) = TransitRoute(
        type = type, totalDistanceM = 9000, totalTimeSec = min * 60, transfers = transfers, fare = 1500,
        steps = listOf(step(TransitStepType.WALK, walkMin)) + rides.map { step(it, 10) },
    )

    // 카카오가 준 순서(추천순) 그대로
    private val routes = listOf(
        route("BUS_AND_SUBWAY", 51, 1, 9, TransitStepType.SUBWAY, TransitStepType.BUS),   // 0
        route("BUS", 61, 1, 4, TransitStepType.BUS, TransitStepType.BUS),                // 1
        route("BUS_AND_SUBWAY", 50, 2, 12, TransitStepType.SUBWAY, TransitStepType.BUS), // 2
        route("BUS", 75, 0, 3, TransitStepType.BUS),                                    // 3
        route("BUS", 81, 0, 2, TransitStepType.BUS),                                    // 4
    )

    private fun order(f: TransitFilter, s: TransitSort) = TransitOptions.apply(routes, f, s).map { it.index }

    @Test
    fun recommendedKeepsKakaosOrderAndOtherSortsReorder() {
        assertEquals(listOf(0, 1, 2, 3, 4), order(TransitFilter.ALL, TransitSort.RECOMMENDED))
        assertEquals(listOf(2, 0, 1, 3, 4), order(TransitFilter.ALL, TransitSort.FASTEST))
        // 환승이 같으면 빠른 것 먼저
        assertEquals(listOf(3, 4, 0, 1, 2), order(TransitFilter.ALL, TransitSort.FEWEST_TRANSFERS))
        // 걷는 시간이 같으면 빠른 것 먼저
        assertEquals(listOf(4, 3, 1, 0, 2), order(TransitFilter.ALL, TransitSort.LEAST_WALK))
    }

    @Test
    fun filtersKeepTheOriginalIndexForTheDetailScreen() {
        assertEquals(listOf(1, 3, 4), order(TransitFilter.BUS, TransitSort.RECOMMENDED))
        assertEquals(listOf(2, 0), order(TransitFilter.BUS_AND_SUBWAY, TransitSort.FASTEST))
        assertEquals(emptyList<Int>(), order(TransitFilter.SUBWAY, TransitSort.RECOMMENDED))
    }

    @Test
    fun onlyKindsThatExistCanBeChosenWithTheirCounts() {
        assertEquals(
            listOf(TransitFilter.ALL to 5, TransitFilter.BUS to 3, TransitFilter.BUS_AND_SUBWAY to 2),
            TransitOptions.filters(routes),
        )
        // 한 종류뿐이면 거를 필요가 없다
        assertEquals(listOf(TransitFilter.ALL to 3), TransitOptions.filters(routes.filter { it.type == "BUS" }))
        // 응답의 type 이 비어 있어도 구간으로 판단
        assertEquals(TransitFilter.SUBWAY, TransitOptions.kindOf(route("", 20, 0, 3, TransitStepType.SUBWAY)))
        assertEquals(TransitFilter.BUS_AND_SUBWAY, TransitOptions.kindOf(route("", 20, 1, 3, TransitStepType.BUS, TransitStepType.SUBWAY)))
    }

    @Test
    fun theFastestRouteIsKnownWhateverTheSort() {
        // 카카오 순서(추천순)에선 셋째지만 가장 빨리 도착하는 경로 — '가장 빠름' 표시
        assertEquals(2, TransitOptions.fastestIndex(routes))
        assertEquals(null, TransitOptions.fastestIndex(emptyList()))
        // 시간이 같으면 환승이 적은 쪽 (최소시간 정렬의 첫 카드와 같게)
        val tie = listOf(route("BUS", 50, 2, 3, TransitStepType.BUS), route("BUS", 50, 1, 9, TransitStepType.BUS))
        assertEquals(1, TransitOptions.fastestIndex(tie))
        assertEquals(TransitOptions.apply(tie, TransitFilter.ALL, TransitSort.FASTEST).first().index, TransitOptions.fastestIndex(tie))
    }

    @Test
    fun everySearchStartsWithTheFastestRouteOnTop() {
        // 실제 카카오 응답(코스트코 → 송촌동, 2026-10-13 밤) 의 경로 시간을 카카오가 준 순서대로.
        // 카카오맵은 가장 빠른 44분(601 → 616)을 맨 위에 보이는데, 예전 앱은 고른 정렬('추천순' 등)을 기억해
        // 카카오 순서 첫 경로 59.3분이 '1시간'으로 맨 위에 떴다. 이제 검색마다 최소시간으로 시작한다
        val minutes = listOf(59.3, 52.0, 43.9, 49.3, 46.2, 69.0, 56.6, 56.1, 72.5, 55.9, 58.4, 66.2, 58.1, 58.2, 62.6)
        val transfers = listOf(0, 0, 1, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 1)
        val routes = minutes.mapIndexed { i, m ->
            route("BUS", 1, transfers[i], 10, TransitStepType.BUS).copy(totalTimeSec = Math.round(m * 60).toInt())
        }
        assertEquals(TransitSort.FASTEST, TransitSort.DEFAULT)
        val first = TransitOptions.apply(routes, TransitFilter.ALL, TransitSort.DEFAULT).first()
        assertEquals(2, first.index)
        assertEquals("44분", com.mywatch.kakaomap.model.Format.duration(first.value.totalTimeSec))
        // 사용자가 본 것: 카카오 순서(추천순)의 첫 경로
        assertEquals("1시간", com.mywatch.kakaomap.model.Format.duration(TransitOptions.apply(routes, TransitFilter.ALL, TransitSort.RECOMMENDED).first().value.totalTimeSec))
    }

    @Test
    fun tappingAChipMovesToTheNextChoice() {
        val choices = TransitOptions.filters(routes).map { it.first }
        assertEquals(TransitFilter.BUS, TransitOptions.next(TransitFilter.ALL, choices))
        assertEquals(TransitFilter.ALL, TransitOptions.next(TransitFilter.BUS_AND_SUBWAY, choices))
        // 지금 고른 종류가 새 결과에 없으면 전체로
        assertEquals(TransitFilter.ALL, TransitOptions.valid(TransitFilter.SUBWAY, choices))
        assertEquals(TransitSort.FASTEST, TransitSort.RECOMMENDED.next())
        assertEquals(TransitSort.RECOMMENDED, TransitSort.LEAST_WALK.next())
    }
}
