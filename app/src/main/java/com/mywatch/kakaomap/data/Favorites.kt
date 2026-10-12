package com.mywatch.kakaomap.data

import android.content.Context
import androidx.core.content.edit
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.PlaceJson
import org.json.JSONObject

/** 카카오맵의 집·회사 바로가기 */
enum class Favorite(val label: String, internal val prefKey: String, /** "집으로", "회사로" */ val toward: String) {
    HOME("집", "fav_home", "집으로"),
    WORK("회사", "fav_work", "회사로");

    /** 길찾기·안내 배너에 '→ 집'으로 보이게 이름만 바꾼 목적지 */
    fun asDestination(place: Place): Place = place.copy(name = label)
}

/** 검색 첫 화면의 집·회사 한 줄 */
data class FavoriteSlot(val kind: Favorite, val place: Place?) {
    val title: String get() = kind.label
    val subtitle: String get() = place?.name?.ifBlank { null } ?: "등록하기"
}

object Favorites {
    private const val PREFS = "route_prefs"

    fun get(context: Context, kind: Favorite): Place? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind.prefKey, null)
            ?.let { runCatching { PlaceJson.fromJson(JSONObject(it)) }.getOrNull() }

    fun set(context: Context, kind: Favorite, place: Place) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(kind.prefKey, PlaceJson.toJson(place).toString()) }

    fun clear(context: Context, kind: Favorite) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(kind.prefKey) }

    fun slots(context: Context): List<FavoriteSlot> = Favorite.entries.map { FavoriteSlot(it, get(context, it)) }
}
