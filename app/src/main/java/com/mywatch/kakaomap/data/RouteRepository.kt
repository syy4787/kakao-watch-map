package com.mywatch.kakaomap.data

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Fix
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TravelMode
import org.json.JSONArray
import org.json.JSONObject

/** 지도에 그릴 선 한 토막 */
data class MapSegment(val points: List<Coord>, val color: Int, val widthDp: Float)

/**
 * 지도에 띄워 둔(안내 중인) 경로.
 * 원래 경로(transit/simple)와 목적지를 함께 들고 있어서, 나중에 다른 곳을 검색해도
 * 지도 카드를 누르면 '지금 띄운 바로 그 경로'의 상세가 열린다.
 */
data class ActiveRoute(
    val mode: TravelMode,
    val place: Place,
    val summary: String,
    val origin: Coord,
    val segments: List<MapSegment>,
    val steps: List<GuideStep>,
    /** true = '안내 시작' (화면이 꺼져도 따라가며 진동 안내), false = 지도에서 보기만 */
    val startNavigation: Boolean,
    val transit: TransitRoute? = null,
    val simple: SimpleRoute? = null,
    /** 이 경로를 받은 시각 — 대중교통 도착 예정 시각 계산용 */
    val startedAtMs: Long = System.currentTimeMillis(),
    /** 한 번의 길찾기(여정) 번호 — 재탐색으로 경로가 바뀌어도 그대로 이어받는다 */
    val tripId: Long = System.nanoTime(),
) {
    val destinationName: String get() = place.name
    val destination: Coord get() = place.coord
    val totalDistanceM: Int get() = transit?.totalDistanceM ?: simple?.totalDistanceM ?: 0
    val totalTimeSec: Int get() = transit?.totalTimeSec ?: simple?.totalTimeSec ?: 0
}

/**
 * 화면 사이에서 주고받는 길찾기 상태 (메모리) + 최근 목적지(저장).
 * 앱 프로세스가 정리되면 메모리 상태는 사라지므로 각 화면은 null 을 견뎌야 한다.
 */
object RouteRepository {
    /** 가장 최근에 받은 위치 (측정 시각 포함) */
    @Volatile var lastFix: Fix? = null
        private set

    val lastLocation: Coord? get() = lastFix?.coord

    var destination: Place? = null
    var transitRoutes: List<TransitRoute>? = null
    var walkRoute: SimpleRoute? = null
    var bikeRoute: SimpleRoute? = null
    /** 경로를 계산한 출발 좌표 */
    var routeOrigin: Coord? = null

    var activeRoute: ActiveRoute? = null
        set(value) {
            field = value
            activeRouteVersion++
        }
    var activeRouteVersion = 0
        private set

    fun selectDestination(place: Place) {
        destination = place
        transitRoutes = null
        walkRoute = null
        bikeRoute = null
        routeOrigin = null
    }

    /** 더 최근에 측정된 위치만 받아 둔다 (오래된 '마지막 위치'가 새 위치를 덮지 않게) */
    fun updateFix(fix: Fix) {
        val cur = lastFix
        if (cur == null || fix.elapsedMs >= cur.elapsedMs) lastFix = fix
    }

    /** 측정된 지 [maxAgeMs] 이내인 위치 */
    fun freshFix(maxAgeMs: Long = 2 * 60_000L): Fix? =
        lastFix?.takeIf { SystemClock.elapsedRealtime() - it.elapsedMs <= maxAgeMs }

    fun freshLocation(maxAgeMs: Long = 2 * 60_000L): Coord? = freshFix(maxAgeMs)?.coord

    // ───────── 최근 목적지 (최대 10개) ─────────

    private const val PREFS = "route_prefs"
    private const val KEY_RECENTS = "recent_places"

    fun recents(context: Context): List<Place> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toPlace() }
        }.getOrDefault(emptyList())
    }

    fun addRecent(context: Context, place: Place) {
        val list = (listOf(place) + recents(context).filter { it.id != place.id || it.name != place.name }).take(10)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_RECENTS, arr.toString()) }
    }

    fun clearRecents(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_RECENTS) }
    }

    private fun Place.toJson() = JSONObject()
        .put("id", id).put("name", name).put("category", category)
        .put("address", address).put("road", roadAddress)
        .put("lat", coord.lat).put("lng", coord.lng)

    private fun JSONObject.toPlace(): Place? {
        if (!has("lat") || !has("lng")) return null
        return Place(
            id = optString("id"),
            name = optString("name"),
            category = optString("category"),
            address = optString("address"),
            roadAddress = optString("road"),
            coord = Coord(optDouble("lat"), optDouble("lng")),
            distanceM = null,
        )
    }
}
