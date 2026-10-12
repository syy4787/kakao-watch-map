package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.KakaoParsers
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitAccess
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TravelMode
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** 화면에 그대로 보여 줄 수 있는 오류 */
class ApiException(message: String) : Exception(message)

/**
 * 카카오 REST API (장소 검색 · 대중교통/도보/자전거 경로).
 * 모두 네트워크를 쓰므로 반드시 백그라운드 스레드에서 호출한다.
 */
object KakaoApi {
    private const val BASE = "https://dapi.kakao.com"

    fun searchPlaces(query: String, near: Coord?): List<Place> {
        val params = linkedMapOf("query" to query, "size" to "15")
        if (near != null) {
            params["x"] = near.lng.fmt()
            params["y"] = near.lat.fmt()
        }
        return KakaoParsers.places(call("/v2/local/search/keyword.json", params))
    }

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

    fun walk(from: Coord, to: Coord, toName: String): SimpleRoute =
        KakaoParsers.simpleRoute(call("/v2/routing/walk", routeParams(from, to, toName)), TravelMode.WALK)

    fun bike(from: Coord, to: Coord, toName: String): SimpleRoute =
        KakaoParsers.simpleRoute(call("/v2/routing/bicycle", routeParams(from, to, toName)), TravelMode.BIKE)

    private fun routeParams(from: Coord, to: Coord, toName: String) = linkedMapOf(
        "start_x" to from.lng.fmt(),
        "start_y" to from.lat.fmt(),
        "end_x" to to.lng.fmt(),
        "end_y" to to.lat.fmt(),
        "s_name" to "내 위치",
        "e_name" to toName.take(30).ifBlank { "도착" },
    )

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
