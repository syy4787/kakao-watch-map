package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.KakaoParsers
import com.mywatch.kakaomap.model.NearbyKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitAccess
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.tube.TubeParsers
import com.mywatch.kakaomap.tube.TubeSearcher
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** 화면에 그대로 보여 줄 수 있는 오류 */
open class ApiException(message: String) : Exception(message)

/**
 * 카카오 REST API (장소 검색 · 대중교통/도보/자전거 경로).
 * 모두 네트워크를 쓰므로 반드시 백그라운드 스레드에서 호출한다.
 */
object KakaoApi {
    private const val BASE = "https://dapi.kakao.com"

    /**
     * 장소 검색 (정확도순). [near] 를 주면 카카오가 가까운 곳을 먼저 준다 — 실측: '스타벅스' → 내 주변 매장, '부산역' → 부산역.
     * 거리순·반경은 쓰지 않는다 ('대전역'이 근처 렌터카 픽업존이 되고, 반경 밖의 '부산역'은 0건)
     */
    fun searchPlaces(query: String, near: Coord?): List<Place> {
        val params = linkedMapOf("query" to query, "size" to "15")
        if (near != null) {
            params["x"] = near.lng.fmt()
            params["y"] = near.lat.fmt()
        }
        return KakaoParsers.places(call("/v2/local/search/keyword.json", params))
    }

    /** 내 주변 [kind] (편의점·약국·화장실·카페·지하철역) — 5km 안을 가까운 순으로 */
    fun nearby(kind: NearbyKind, near: Coord): List<Place> {
        val (path, params) = nearbyRequest(kind, near)
        return KakaoParsers.places(call(path, params)).filter(kind::keeps)
    }

    /** [nearby] 의 요청 (주소, 매개변수) */
    fun nearbyRequest(kind: NearbyKind, near: Coord): Pair<String, Map<String, String>> {
        val around = linkedMapOf(
            "x" to near.lng.fmt(), "y" to near.lat.fmt(),
            "radius" to NEARBY_RADIUS_M.toString(), "sort" to "distance", "size" to "15",
        )
        val code = kind.code
        return if (code != null) {
            "/v2/local/search/category.json" to (linkedMapOf("category_group_code" to code) + around)
        } else {
            "/v2/local/search/keyword.json" to (linkedMapOf("query" to kind.label) + around)
        }
    }

    /** '내 주변'을 찾는 반경 — 가까운 순이라 넓혀도 가까운 곳이 먼저 나오고, 시골에서도 빈 목록이 덜 나온다 */
    private const val NEARBY_RADIUS_M = 5_000

    /** [near] 에서 [radiusM](최대 20km) 안을 가까운 순으로 — 역·터미널 찾기용 */
    fun searchNear(query: String, near: Coord, radiusM: Int): List<Place> = KakaoParsers.places(
        call("/v2/local/search/keyword.json", linkedMapOf(
            "query" to query, "x" to near.lng.fmt(), "y" to near.lat.fmt(),
            "radius" to radiusM.coerceIn(0, 20_000).toString(), "sort" to "distance", "size" to "15",
        ))
    )

    /** 다음 동영상 검색 (유튜브 등) — 유튜브 영상만 골라 [TubeParsers] 로. [page] 1~15 */
    fun videos(query: String, page: Int): TubeParsers.KakaoPage = TubeParsers.kakaoVclip(
        call("/v2/search/vclip", linkedMapOf(
            "query" to query, "sort" to "accuracy", "size" to "30",
            "page" to page.coerceIn(1, TubeSearcher.KAKAO_LAST_PAGE).toString(),
        ))
    )

    /** 지하철역 찾기 (카테고리 SW8 만) */
    fun subwayStations(query: String, near: Coord): List<Place> = KakaoParsers.places(
        call("/v2/local/search/keyword.json", linkedMapOf(
            "query" to query, "category_group_code" to "SW8", "x" to near.lng.fmt(), "y" to near.lat.fmt(),
            "radius" to "20000", "sort" to "accuracy", "size" to "15",
        ))
    ).filter { it.group == "SW8" }

    fun transit(from: Coord, to: Coord, toName: String): List<TransitRoute> =
        KakaoParsers.transitRoutes(call("/v2/routing/publictraffic", routeParams(from, to, toName)))
            .map { TransitAccess.withAccessWalks(it, from, to, toName) }

    /** 도보 경로. [option] = 큰길 우선(기본)·최단 거리·편안한 길 */
    fun walk(from: Coord, to: Coord, toName: String, option: RouteOption? = null): SimpleRoute =
        KakaoParsers.simpleRoute(call("/v2/routing/walk", routeParams(from, to, toName, option)), TravelMode.WALK)
            .copy(option = option)

    /** 자전거 경로. [option] = 자전거도로 우선(기본)·최단 거리·편안한 길 */
    fun bike(from: Coord, to: Coord, toName: String, option: RouteOption? = null): SimpleRoute =
        KakaoParsers.simpleRoute(call("/v2/routing/bicycle", routeParams(from, to, toName, option)), TravelMode.BIKE)
            .copy(option = option)

    fun simple(mode: TravelMode, from: Coord, to: Coord, toName: String, option: RouteOption?): SimpleRoute =
        if (mode == TravelMode.BIKE) bike(from, to, toName, option) else walk(from, to, toName, option)

    fun routeParams(from: Coord, to: Coord, toName: String, option: RouteOption? = null): Map<String, String> = linkedMapOf(
        "start_x" to from.lng.fmt(),
        "start_y" to from.lat.fmt(),
        "end_x" to to.lng.fmt(),
        "end_y" to to.lat.fmt(),
        "s_name" to "내 위치",
        "e_name" to toName.take(30).ifBlank { "도착" },
    ).apply { if (option != null) put("route_mode", option.api) }

    private fun call(path: String, params: Map<String, String>): JSONObject {
        val key = BuildConfig.KAKAO_REST_API_KEY
        if (key.isBlank()) throw ApiException("REST API 키가 빌드에 들어 있지 않아요")
        val res = try {
            Http.get("$BASE$path?${Http.query(params)}", mapOf("Authorization" to "KakaoAK $key"))
        } catch (e: IOException) {
            throw ApiException("네트워크 연결을 확인하세요")
        }
        if (res.code !in 200..299) throw ApiException(errorMessage(res.code, res.body))
        return try {
            JSONObject(res.body)
        } catch (e: JSONException) {
            throw ApiException("카카오 응답을 읽지 못했어요")
        }
    }

    fun errorMessage(code: Int, body: String): String = when {
        code == 403 && body.contains("disabled", ignoreCase = true) ->
            "카카오맵 사용 설정이 꺼져 있어요\n(디벨로퍼스 > 카카오맵 > 사용 설정 ON)"
        code == 401 -> "REST API 키 인증 실패 (401)"
        code == 403 -> "권한 오류 (403)"
        code == 429 || (code == 400 && Regex(""""code"\s*:\s*-10\b""").containsMatchIn(body)) -> "오늘 사용량을 다 썼어요"
        code >= 500 -> "카카오 서버 오류 ($code)"
        else -> "요청 오류 ($code)"
    }

    private fun Double.fmt() = String.format(Locale.US, "%.7f", this)
}
