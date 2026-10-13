package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.PathStep
import com.mywatch.kakaomap.model.RouteChoices
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.net.KakaoApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카카오맵 도보·자전거 경로 옵션 (카카오 길찾기 API route_mode).
 * 거리·시간은 2026-10-13 실제 응답 값: 대전시청 → 한밭수목원(자전거), 대전시청 → 대전시립미술관(도보).
 *  자전거도로 우선 4,384m 996초 / 최단 거리 3,104m 781초 / 편안한 길 3,220m 799초
 *  큰길 우선 2,538m 2,415초 / 최단 거리 2,410m 2,320초 / 편안한 길 = 큰길 우선과 같은 경로
 */
class RouteChoicesTest {

    private fun route(mode: TravelMode, m: Int, s: Int, vararg pts: Double) = SimpleRoute(
        mode, m, s,
        listOf(PathStep("출발", m, s, Coord(pts[0], pts[1]), pts.toList().chunked(2).map { Coord(it[0], it[1]) })),
    )

    private val bike = listOf(
        RouteOption.BIKE_ROAD to route(TravelMode.BIKE, 4384, 996, 36.35, 127.38, 36.36, 127.39, 36.367, 127.388),
        RouteOption.BIKE_SHORTEST to route(TravelMode.BIKE, 3104, 781, 36.35, 127.38, 36.367, 127.388),
        RouteOption.BIKE_COMFORT to route(TravelMode.BIKE, 3220, 799, 36.35, 127.38, 36.355, 127.386, 36.367, 127.388),
    )
    private val walk = listOf(
        RouteOption.WALK_BROAD to route(TravelMode.WALK, 2538, 2415, 36.35, 127.38, 36.36, 127.385, 36.366, 127.388),
        RouteOption.WALK_SHORTEST to route(TravelMode.WALK, 2410, 2320, 36.35, 127.38, 36.366, 127.388),
        RouteOption.WALK_COMFORT to route(TravelMode.WALK, 2538, 2415, 36.35, 127.38, 36.36, 127.385, 36.366, 127.388),
    )

    @Test
    fun optionsUseKakaoMapsNamesAndApiValues() {
        assertEquals(listOf("자전거도로 우선", "최단 거리", "편안한 길"), RouteOption.of(TravelMode.BIKE).map { it.label })
        assertEquals(listOf("BIKE_ONLY", "SHORTEST", "ACCESSIBLE"), RouteOption.of(TravelMode.BIKE).map { it.api })
        assertEquals(listOf("큰길 우선", "최단 거리", "편안한 길"), RouteOption.of(TravelMode.WALK).map { it.label })
        assertEquals(listOf("BROAD_FIRST", "SHORTEST", "ACCESSIBLE"), RouteOption.of(TravelMode.WALK).map { it.api })
        // 경로 요청에 그대로 실린다
        val params = KakaoApi.routeParams(Coord(36.35, 127.38), Coord(36.36, 127.39), "한밭수목원", RouteOption.BIKE_ROAD)
        assertEquals("BIKE_ONLY", params["route_mode"])
        assertNull(KakaoApi.routeParams(Coord(36.35, 127.38), Coord(36.36, 127.39), "x", null)["route_mode"])
    }

    @Test
    fun sameRouteFromTwoOptionsBecomesOneCardLikeKakaoMap() {
        val choices = RouteChoices.merge(walk)
        assertEquals(listOf("큰길 우선 · 편안한 길", "최단 거리"), choices.map { it.label })
        assertEquals(3, RouteChoices.merge(bike).size)
    }

    @Test
    fun theFastestIsMarkedAndChosenUnlessTheUserPickedAnother() {
        val bikeChoices = RouteChoices.merge(bike)
        assertEquals(RouteOption.BIKE_SHORTEST, RouteChoices.fastest(bikeChoices)!!.primary)
        assertEquals(RouteOption.WALK_SHORTEST, RouteChoices.fastest(RouteChoices.merge(walk))!!.primary)
        // 처음엔 가장 빠른 길, 사용자가 '자전거도로 우선'을 골랐으면 다음에도 그것
        assertEquals(RouteOption.BIKE_SHORTEST, RouteChoices.pick(bikeChoices, remembered = null)!!.primary)
        assertEquals(RouteOption.BIKE_ROAD, RouteChoices.pick(bikeChoices, remembered = RouteOption.BIKE_ROAD)!!.primary)
        // 고른 옵션이 이번엔 실패해서 없으면 가장 빠른 길
        assertEquals(RouteOption.BIKE_SHORTEST, RouteChoices.pick(bikeChoices.drop(0).filter { RouteOption.BIKE_ROAD !in it.options }, RouteOption.BIKE_ROAD)!!.primary)
        // 합쳐진 카드의 두 번째 이름으로 기억해 둔 것도 찾는다
        assertEquals(RouteOption.WALK_BROAD, RouteChoices.pick(RouteChoices.merge(walk), RouteOption.WALK_COMFORT)!!.primary)
        assertNull(RouteChoices.pick(emptyList(), null))
    }

    @Test
    fun tappingTheFastestCardGoesBackToAlwaysFastest() {
        val bikeChoices = RouteChoices.merge(bike)
        val road = bikeChoices.first { RouteOption.BIKE_ROAD in it.options }
        // 자전거도로 우선을 누르면 다음 길찾기에서도 그것
        assertEquals(RouteOption.BIKE_ROAD, RouteChoices.rememberAfterTap(road, bikeChoices, remembered = null))
        // '가장 빠름' 카드를 누르면 기억을 지우고 다시 늘 가장 빠른 길
        assertNull(RouteChoices.rememberAfterTap(RouteChoices.fastest(bikeChoices)!!, bikeChoices, remembered = RouteOption.BIKE_ROAD))
        assertNull(RouteChoices.rememberAfterTap(RouteChoices.fastest(bikeChoices)!!, bikeChoices, remembered = null))
        // 합쳐진 카드(큰길 우선 · 편안한 길)는 앞 이름으로 기억
        val walkChoices = RouteChoices.merge(walk)
        assertEquals(RouteOption.WALK_BROAD, RouteChoices.rememberAfterTap(walkChoices.first(), walkChoices, remembered = null))
    }

    @Test
    fun aMergedCardKeepsTheOptionTheUserActuallyChose() {
        val walkChoices = RouteChoices.merge(walk)
        val merged = walkChoices.first()   // 큰길 우선 · 편안한 길 (이번엔 같은 길)
        // 편안한 길을 골라 두었으면 같은 카드를 다시 눌러도 편안한 길 그대로 (큰길 우선으로 바뀌지 않음)
        assertEquals(RouteOption.WALK_COMFORT, RouteChoices.rememberAfterTap(merged, walkChoices, remembered = RouteOption.WALK_COMFORT))
        // 안내·경로 이탈 재탐색에 싣는 옵션도 편안한 길 (다른 곳에서 다시 찾으면 두 옵션의 길이 달라질 수 있음)
        assertEquals(RouteOption.WALK_COMFORT, RouteChoices.optionFor(merged, RouteOption.WALK_COMFORT))
        assertEquals(RouteOption.WALK_BROAD, RouteChoices.optionFor(merged, null))
        assertEquals(RouteOption.WALK_BROAD, RouteChoices.optionFor(merged, RouteOption.WALK_SHORTEST))
    }

    @Test
    fun aChoiceMissingThisTimeIsNotForgottenByTappingTheFastest() {
        // 자전거도로 우선 요청만 잠깐 실패해 그 카드가 없을 때 '가장 빠름' 카드를 눌러도 기억은 그대로
        val withoutRoad = RouteChoices.merge(bike.filter { it.first != RouteOption.BIKE_ROAD })
        val fastest = RouteChoices.fastest(withoutRoad)!!
        assertEquals(RouteOption.BIKE_ROAD, RouteChoices.rememberAfterTap(fastest, withoutRoad, remembered = RouteOption.BIKE_ROAD))
        // 다른 카드를 누른 건 분명한 선택 → 그것으로
        val comfort = withoutRoad.first { RouteOption.BIKE_COMFORT in it.options }
        assertEquals(RouteOption.BIKE_COMFORT, RouteChoices.rememberAfterTap(comfort, withoutRoad, remembered = RouteOption.BIKE_ROAD))
    }

    @Test
    fun theTabTimeIsTheRouteYouWouldGetByTappingIt() {
        val bikeChoices = RouteChoices.merge(bike)
        assertEquals(781, RouteChoices.seconds(bikeChoices, remembered = null))
        assertEquals(996, RouteChoices.seconds(bikeChoices, remembered = RouteOption.BIKE_ROAD))
        assertNull(RouteChoices.seconds(emptyList(), null))
        assertNull(RouteChoices.seconds(null, null))
    }

    @Test
    fun farWalkOrBikeRoutesAreFetchedOnlyWhenThatTabIsOpened() {
        // 다른 탭의 시간을 미리 받아 둘 때만 — 너무 먼 도보·자전거는 응답이 커서(좌표 수천 개) 워치에서 아낀다
        assertTrue(RouteChoices.worthPrefetching(TravelMode.WALK, 2_538))
        assertFalse(RouteChoices.worthPrefetching(TravelMode.WALK, 25_000))
        assertTrue(RouteChoices.worthPrefetching(TravelMode.BIKE, 40_000))
        assertFalse(RouteChoices.worthPrefetching(TravelMode.BIKE, 70_000))
        assertTrue(RouteChoices.worthPrefetching(TravelMode.TRANSIT, 300_000))
    }

    @Test
    fun theTabsShowEachModesBestTimeAndTheFastestMode() {
        val times = mapOf(TravelMode.TRANSIT to 1500, TravelMode.WALK to 2320, TravelMode.BIKE to 781)
        assertEquals(TravelMode.BIKE, RouteChoices.fastestMode(times))
        // 결과가 없는 수단(null)은 빼고 비교
        assertEquals(TravelMode.TRANSIT, RouteChoices.fastestMode(mapOf(TravelMode.TRANSIT to 900, TravelMode.WALK to 1200, TravelMode.BIKE to null)))
        assertNull(RouteChoices.fastestMode(emptyMap()))
        // 한 수단만 결과가 있으면 '가장 빠름'을 붙이지 않는다 (비교할 게 없음)
        assertNull(RouteChoices.fastestMode(mapOf(TravelMode.WALK to 600, TravelMode.BIKE to null, TravelMode.TRANSIT to null)))
    }
}
