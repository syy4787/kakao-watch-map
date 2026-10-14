package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.NearbyKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.net.PlaceSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 장소 검색은 내 주변부터 (점검 A2). 카카오 키워드 검색은 x,y 를 주면 정확도순이어도 가까운 곳을 먼저 준다 —
 * 2026-10-14 실측(대전시청 기준): '스타벅스' → 0.2km 매장들, '부산역' → 부산역(203km), '롯데월드' → 잠실.
 * 거리순으로 바꾸면 '대전역'이 '시청역 렌터카 픽업존'처럼 엉뚱해지고 반경을 걸면 '부산역'이 0건이라 정확도순 그대로 둔다.
 * 위치 없이 찾으면 '스타벅스' → 은평·경주·강릉 매장이 나온다 — 예전엔 30분 안의 위치가 없으면(타일의 '검색' 등) 그랬다.
 */
class PlaceSearchTest {

    private val cityHall = Coord(36.3504, 127.3849)
    private fun place(name: String, category: String, m: Int? = null) =
        Place(name, name, category, "대전 서구", "", Coord(36.35, 127.38), m)

    private class Calls {
        val keyword = mutableListOf<Pair<String, Coord?>>()
        val nearby = mutableListOf<Pair<NearbyKind, Coord>>()
        var located = 0
    }

    private fun search(recent: Coord?, rough: Coord?, calls: Calls = Calls()) = PlaceSearch(
        recent = { recent },
        locate = { calls.located++; rough },
        keyword = { q, near -> calls.keyword += q to near; listOf(place("스타벅스 대전시청역8번출구점", "커피전문점", 200)) },
        nearby = { kind, near -> calls.nearby += kind to near; listOf(place("CU 대전시청역점", "CU", 197)) },
    )

    @Test
    fun withoutARecentFixItFindsARoughOneBeforeSearching() = runBlocking {
        val calls = Calls()
        val found = search(recent = null, rough = cityHall, calls = calls).search("스타벅스")
        assertEquals(listOf("스타벅스" to cityHall), calls.keyword)
        assertEquals(cityHall, found.center)
        assertEquals(1, calls.located)
    }

    @Test
    fun aRecentFixIsUsedWithoutWaking() = runBlocking {
        val calls = Calls()
        val near = Coord(36.36, 127.39)
        search(recent = near, rough = cityHall, calls = calls).search("스타벅스")
        assertEquals(listOf("스타벅스" to near), calls.keyword)
        assertEquals(0, calls.located)
    }

    @Test
    fun noLocationAtAllStillSearchesNationwideAndSaysSo() = runBlocking {
        val calls = Calls()
        val found = search(recent = null, rough = null, calls = calls).search("부산역")
        assertEquals(listOf<Pair<String, Coord?>>("부산역" to null), calls.keyword)
        assertNull("화면에 '전국에서 찾았어요'", found.center)
        assertEquals(1, found.places.size)
    }

    @Test
    fun nearbyNeedsALocation() = runBlocking {
        val calls = Calls()
        assertNull(search(recent = null, rough = null, calls = calls).nearby(NearbyKind.STORE))
        assertTrue(calls.nearby.isEmpty())
        val found = search(recent = null, rough = cityHall, calls = calls).nearby(NearbyKind.STORE)
        assertEquals(listOf(NearbyKind.STORE to cityHall), calls.nearby)
        assertEquals("CU 대전시청역점", found?.places?.single()?.name)
    }

    @Test
    fun nearbyKindsUseKakaoCategoriesSortedByDistance() {
        val (path, params) = KakaoApi.nearbyRequest(NearbyKind.PHARMACY, cityHall)
        assertEquals("/v2/local/search/category.json", path)
        assertEquals("PM9", params["category_group_code"])
        assertEquals("127.3849000", params["x"])
        assertEquals("36.3504000", params["y"])
        assertEquals("distance", params["sort"])
        assertEquals("5000", params["radius"])
        assertEquals(
            mapOf(NearbyKind.STORE to "CS2", NearbyKind.CAFE to "CE7", NearbyKind.SUBWAY to "SW8"),
            listOf(NearbyKind.STORE, NearbyKind.CAFE, NearbyKind.SUBWAY).associateWith { KakaoApi.nearbyRequest(it, cityHall).second["category_group_code"] },
        )
    }

    @Test
    fun toiletsHaveNoCategorySoTheyAreAKeywordSearchWithoutRenovationShops() {
        val (path, params) = KakaoApi.nearbyRequest(NearbyKind.TOILET, cityHall)
        assertEquals("/v2/local/search/keyword.json", path)
        assertEquals("화장실", params["query"])
        assertEquals("distance", params["sort"])
        assertFalse(params.containsKey("category_group_code"))
        // 실측: 1.5km 안에 '대전 가온 화장실천장 교체'(인테리어), '이동식화장실총판'(조립식,주문주택)이 섞여 나온다
        assertTrue(NearbyKind.TOILET.keeps(place("보라매공원 화장실", "화장실")))
        assertFalse(NearbyKind.TOILET.keeps(place("대전 가온 화장실천장 교체 욕실천장교체", "인테리어")))
        assertFalse(NearbyKind.TOILET.keeps(place("이동식화장실총판", "조립식,주문주택")))
        assertTrue(NearbyKind.CAFE.keeps(place("릴트커피", "커피전문점")))
    }
}
