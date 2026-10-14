package com.mywatch.kakaomap.data

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Fix
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.PlaceJson
import com.mywatch.kakaomap.model.RouteChoice
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TravelMode
import org.json.JSONArray
import java.util.IdentityHashMap

/** 지도에 그릴 선 한 토막. [dotted] = 카카오맵의 걷는 길처럼 점을 찍은 선 */
data class MapSegment(val points: List<Coord>, val color: Int, val widthDp: Float, val dotted: Boolean = false)

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
        set(value) {
            // 새 목록에 없는 경로의 '실제 걷는 길'은 잊는다 (같은 경로가 남은 목록이면 그대로 — 기차·버스 결과를 붙일 때 등)
            if (value !== field) synchronized(walkedRoutes) {
                walkedRoutes.keys.retainAll { old -> value?.any { it === old } == true }
            }
            field = value
        }

    /**
     * 경로 상세에서 받아 둔, 정류장까지 걷는 길을 실제 길로 바꾼 경로 (원래 경로 객체 → 바꾼 경로).
     * 목록([transitRoutes])은 바꾸지 않는다 — 길찾기 화면이 '같은 검색 결과인지'를 목록 객체로 가려내고(실시간 도착·
     * 기차 시간표 찾기), 목록의 시간·거리도 카카오맵과 같은 숫자로 두기 위해. 같은 경로를 다시 열면 또 묻지 않는다
     */
    private val walkedRoutes = IdentityHashMap<TransitRoute, TransitRoute>()

    /** [route] 의 걷는 길을 실제 길로 바꿔 둔 것이 있으면 그것, 없으면 그대로 */
    fun walked(route: TransitRoute): TransitRoute = synchronized(walkedRoutes) { walkedRoutes[route] } ?: route

    /** 지금 목록에 있는 경로만 (지난 검색의 늦은 응답은 버림) */
    fun rememberWalked(original: TransitRoute, walked: TransitRoute) {
        synchronized(walkedRoutes) {
            if (transitRoutes?.any { it === original } == true) walkedRoutes[original] = walked
        }
    }

    /** 대중교통 목록 아래에 붙일 말 (기차·버스 시간표를 받지 못함 · 지난 시간표로 채움 등) */
    var transitNote: String? = null
        set(value) {
            field = value
            if (value == null) transitIncomplete = false
        }
    /** 대중교통 결과에 받지 못한 것이 있음 (목록 아래 '다시 찾기') — [transitNote] 를 지우면 함께 */
    var transitIncomplete: Boolean = false
    /** 도보·자전거 경로 카드 (카카오맵의 경로 옵션별 결과, 같은 길은 한 카드) */
    var walkChoices: List<RouteChoice>? = null
    var bikeChoices: List<RouteChoice>? = null
    /** 경로를 계산한 출발 좌표 */
    var routeOrigin: Coord? = null

    fun choices(mode: TravelMode): List<RouteChoice>? = when (mode) {
        TravelMode.WALK -> walkChoices
        TravelMode.BIKE -> bikeChoices
        TravelMode.TRANSIT -> null
    }

    fun setChoices(mode: TravelMode, choices: List<RouteChoice>?) {
        when (mode) {
            TravelMode.WALK -> walkChoices = choices
            TravelMode.BIKE -> bikeChoices = choices
            TravelMode.TRANSIT -> Unit
        }
    }

    /** 이 목적지로 받아 둔 경로를 모두 버린다 (출발지가 바뀌었을 때 등) */
    fun clearRoutes() {
        transitRoutes = null
        transitNote = null
        walkChoices = null
        bikeChoices = null
    }

    var activeRoute: ActiveRoute? = null
        set(value) {
            field = value
            activeRouteVersion++
            rememberGuidance(value)
        }
    var activeRouteVersion = 0
        private set

    fun selectDestination(place: Place) {
        destination = place
        clearRoutes()
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

    // ───────── 끊긴 안내 ─────────
    // 안내 중에 앱 본체 프로세스가 꺼지면(2GB 워치에서 동영상 재생 화면이 메모리를 많이 쓸 때 등) 메모리의 경로·안내가
    // 말없이 사라진다. 안내를 시작한 목적지를 적어 두고 정상으로 끝나면(도착·종료·스스로 끝냄) 지운다 —
    // 다음에 지도를 열 때 남아 있으면 그 목적지를 다시 찾아 준다

    /** 끊긴 안내 — 다시 찾을 목적지와 이동 수단 */
    data class Interrupted(val place: Place, val mode: TravelMode)

    @Volatile private var appContext: Context? = null
    private var clock: () -> Long = System::currentTimeMillis

    /** Application.onCreate 에서 한 번 (시험은 시계를 바꿔 끼운다) */
    fun init(context: Context, clock: () -> Long = System::currentTimeMillis) {
        appContext = context.applicationContext
        this.clock = clock
    }

    private fun rememberGuidance(route: ActiveRoute?) {
        val c = appContext ?: return
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                if (route != null && route.startNavigation) {
                    putString(
                        KEY_INTERRUPTED,
                        PlaceJson.toJson(route.place).put("mode", route.mode.name).put("at", clock()).toString(),
                    )
                } else {
                    remove(KEY_INTERRUPTED)
                }
            }
        }
    }

    /** 끊긴 안내가 있으면(6시간 안) 돌려주고 지운다 */
    fun takeInterrupted(context: Context): Interrupted? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_INTERRUPTED, null) ?: return null
        prefs.edit { remove(KEY_INTERRUPTED) }
        return runCatching {
            val o = org.json.JSONObject(raw)
            if (clock() - o.optLong("at") > INTERRUPTED_MAX_AGE_MS) return null
            val place = PlaceJson.fromJson(o) ?: return null
            Interrupted(place, TravelMode.valueOf(o.optString("mode")))
        }.getOrNull()
    }

    private const val KEY_INTERRUPTED = "interrupted_guidance"
    private const val INTERRUPTED_MAX_AGE_MS = 6 * 3_600_000L

    // ───────── 최근 목적지 (최대 10개) ─────────

    private const val PREFS = "route_prefs"
    private const val KEY_RECENTS = "recent_places"

    fun recents(context: Context): List<Place> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(PlaceJson::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun addRecent(context: Context, place: Place) {
        val list = (listOf(place) + recents(context).filter { it.id != place.id || it.name != place.name }).take(10)
        val arr = JSONArray()
        list.forEach { arr.put(PlaceJson.toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_RECENTS, arr.toString()) }
    }

    fun clearRecents(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_RECENTS) }
    }
}
