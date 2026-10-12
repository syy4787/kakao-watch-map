package com.mywatch.kakaomap

import com.mywatch.kakaomap.data.Favorite
import com.mywatch.kakaomap.data.FavoriteSlot
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.PlaceJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 카카오맵처럼 집·회사를 등록해 두고 한 번에 길찾기 */
class FavoritesTest {

    private val apt = Place(
        id = "123", name = "목련아파트", category = "아파트",
        address = "대전 서구 둔산동 1", roadAddress = "대전 서구 둔산로 1",
        coord = Coord(36.3512345, 127.3812345), distanceM = 120, group = "",
    )

    @Test
    fun savedPlacesComeBackTheSame() {
        val back = PlaceJson.fromJson(PlaceJson.toJson(apt))
        // 거리는 '검색한 곳에서의 거리'라 저장하지 않는다
        assertEquals(apt.copy(distanceM = null), back)
        assertNull("좌표 없는 기록은 버린다", PlaceJson.fromJson(JSONObject("""{"name":"좌표 없음"}""")))
    }

    @Test
    fun favoritesShowTheirLabelAndGoUnderThatName() {
        assertEquals("등록하기", FavoriteSlot(Favorite.HOME, null).subtitle)
        assertEquals("목련아파트", FavoriteSlot(Favorite.HOME, apt).subtitle)
        assertEquals("회사", FavoriteSlot(Favorite.WORK, null).title)

        // 길찾기·안내 배너에는 카카오맵처럼 '→ 집'
        val dest = Favorite.HOME.asDestination(apt)
        assertEquals("집", dest.name)
        assertEquals(apt.coord, dest.coord)
        assertEquals(apt.id, dest.id)
    }
}
