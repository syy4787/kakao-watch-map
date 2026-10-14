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

    /**
     * 리뷰에서 찾음: '내 주변'이 몇 시간 전 위치를 중심으로 찾을 수 있었다 — 새 위치를 못 잡으면 6시간 안의 마지막 위치를 써서
     * 다른 동네(도시)의 화장실이 지금 거리처럼 나왔다. 내 주변은 10분 안 위치, 새로 못 잡으면 15분 안의 마지막 위치까지만
     */
    @Test
    fun nearbyDoesNotUseAnOldLocation() = runBlocking {
        val min = 60_000L
        val calls = Calls()
        val busan = Coord(35.1151, 129.0415)
        // 20분 전에 잡힌 위치가 있고, 시스템의 마지막 위치는 2시간 전 — 새로는 못 잡음
        val s = PlaceSearch(
            recent = { maxAgeMs -> cityHall.takeIf { 20 * min <= maxAgeMs } },
            locate = { lastMaxAgeMs -> calls.located++; busan.takeIf { 120 * min <= lastMaxAgeMs } },
            keyword = { q, near -> calls.keyword += q to near; emptyList() },
            nearby = { kind, near -> calls.nearby += kind to near; emptyList() },
        )
        assertNull("오래된 위치로는 '내 주변'을 찾지 않는다 (위치를 잡지 못했어요)", s.nearby(NearbyKind.TOILET))
        assertTrue(calls.nearby.isEmpty())
        assertEquals("새 위치는 잡아 봄", 1, calls.located)
        // 이름 검색은 가까운 곳을 먼저 받는 데만 쓰므로 조금 지난 위치도 그대로 (전국보다 낫다)
        assertEquals(cityHall, s.search("스타벅스").center)
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
