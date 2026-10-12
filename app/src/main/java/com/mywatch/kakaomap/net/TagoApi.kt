package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.model.BusArrival
import com.mywatch.kakaomap.model.BusRoute
import com.mywatch.kakaomap.model.BusStop
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.TagoParsers
import com.mywatch.kakaomap.model.TransitMatching
import com.mywatch.kakaomap.model.TransitStep
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * 국토교통부 TAGO(국가대중교통정보센터) 버스 실시간 도착 정보 — 공공데이터포털 인증키 필요.
 * 서울·경기처럼 TAGO 에 연결되지 않은 지역은 결과가 없을 수 있다.
 */
object TagoApi {
    private const val BASE = "https://apis.data.go.kr/1613000"

    val isConfigured: Boolean get() = BuildConfig.TAGO_SERVICE_KEY.isNotBlank()

    /** 좌표 근처 정류소 목록 */
    fun nearbyStops(at: Coord): List<BusStop> = TagoParsers.stops(
        call("/BusSttnInfoInqireService/getCrdntPrxmtSttnList", mapOf(
            "gpsLati" to String.format(Locale.US, "%.6f", at.lat),
            "gpsLong" to String.format(Locale.US, "%.6f", at.lng),
            "numOfRows" to "20",
            "pageNo" to "1",
        ))
    )

    /** 정류소에 곧 도착할 버스 목록 (가까운 순) */
    fun arrivals(stop: BusStop): List<BusArrival> = TagoParsers.arrivals(
        call("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", mapOf(
            "cityCode" to stop.cityCode.toString(),
            "nodeId" to stop.nodeId,
            "numOfRows" to "50",
            "pageNo" to "1",
        ))
    )

    /** 노선 번호로 노선 찾기 (같은 번호의 여러 노선이 나올 수 있음) */
    fun routes(cityCode: Int, routeNo: String): List<BusRoute> = TagoParsers.routes(
        call("/BusRouteInfoInqireService/getRouteNoList", mapOf(
            "cityCode" to cityCode.toString(), "routeNo" to routeNo, "numOfRows" to "20", "pageNo" to "1",
        ))
    )

    /** 노선이 지나는 정류소 (노선 순서대로, 좌표 포함) */
    fun routeStops(cityCode: Int, routeId: String): List<BusStop> = TagoParsers.routeStops(
        call("/BusRouteInfoInqireService/getRouteAcctoThrghSttnList", mapOf(
            "cityCode" to cityCode.toString(), "routeId" to routeId, "numOfRows" to "300", "pageNo" to "1",
        ))
    )

    private fun call(path: String, params: Map<String, String>): JSONObject {
        val raw = BuildConfig.TAGO_SERVICE_KEY
        // 포털의 '인코딩' 키(%가 들어 있음)와 '디코딩' 키를 모두 받아 준다
        val key = if (raw.contains('%')) raw else URLEncoder.encode(raw, "UTF-8")
        val url = "$BASE$path?serviceKey=$key&_type=json&${Http.query(params)}"
        val res = try {
            Http.get(url, timeoutMs = 8000)
        } catch (e: IOException) {
            throw ApiException("네트워크 오류")
        }
        val json = try {
            JSONObject(res.body)
        } catch (e: Exception) {
            throw ApiException("공공데이터 응답 오류 (${res.code})")
        }
        TagoParsers.errorMessage(json)?.let { throw ApiException(it) }
        return json
    }
}

/** 버스 구간의 실시간 도착 정보를 찾아서 "102번 3분 후 (2정거장 전)" 형태로 만든다 */
object BusArrivalService {

    private class Cached(val text: String, val at: Long)
    private val cache = HashMap<String, Cached>()
    private const val TTL_MS = 30_000L

    /** 백그라운드 스레드에서 호출. 키가 없거나 못 찾으면 null */
    fun describe(step: TransitStep): String? {
        if (!TagoApi.isConfigured) return null
        val boardCoord = step.points.firstOrNull() ?: return null
        val names = step.vehicles.map { it.name }.filter { it.isNotBlank() }
        if (names.isEmpty()) return null
        val cacheKey = "${step.boardingStop}|${names.joinToString(",")}"
        synchronized(cache) {
            cache[cacheKey]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.text }
        }
        val text = try {
            val stop = TransitMatching.pickStop(TagoApi.nearbyStops(boardCoord), step.boardingStop, boardCoord)
                ?: return "실시간 정보 없음"
            val mine = TransitMatching.arrivalsFor(TagoApi.arrivals(stop), names)
            when {
                mine.isEmpty() -> "도착 정보 없음"
                mine.size == 1 -> "${mine[0].routeNo}번 ${Format.busArrival(mine[0])}"
                else -> "${mine[0].routeNo}번 ${Format.busArrival(mine[0])} · 다음 ${Format.arrival(mine[1].arrivalSec)}"
            }
        } catch (e: ApiException) {
            e.message ?: "실시간 정보 오류"
        }
        synchronized(cache) { cache[cacheKey] = Cached(text, System.currentTimeMillis()) }
        return text
    }
}
