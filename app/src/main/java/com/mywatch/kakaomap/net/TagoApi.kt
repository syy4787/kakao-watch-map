package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
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
import java.net.SocketTimeoutException
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

    // ───────── 기차·고속버스·시외버스 시간표 (TAGO 열차정보·고속버스정보·시외버스정보) ─────────

    /** [ymd] = "20261013" 하루의 출발지→도착지 열차 (KTX·ITX·무궁화 …) */
    fun trainRuns(depId: String, arrId: String, ymd: String): JSONObject = call("/TrainInfo/GetStrtpntAlocFndTrainInfo", mapOf(
        "depPlaceId" to depId, "arrPlaceId" to arrId, "depPlandTime" to ymd, "numOfRows" to "300", "pageNo" to "1",
    ))

    fun expressRuns(depId: String, arrId: String, ymd: String): JSONObject = call("/ExpBusInfo/GetStrtpntAlocFndExpbusInfo", mapOf(
        "depTerminalId" to depId, "arrTerminalId" to arrId, "depPlandTime" to ymd, "numOfRows" to "300", "pageNo" to "1",
    ))

    fun intercityRuns(depId: String, arrId: String, ymd: String): JSONObject = call("/SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo", mapOf(
        "depTerminalId" to depId, "arrTerminalId" to arrId, "depPlandTime" to ymd, "numOfRows" to "300", "pageNo" to "1",
    ))

    /** 도시(cityCode 25 = 대전 …)의 기차역 목록 */
    fun trainStations(cityCode: Int, page: Int): JSONObject = call("/TrainInfo/GetCtyAcctoTrainSttnList", mapOf(
        "cityCode" to cityCode.toString(), "numOfRows" to "500", "pageNo" to page.toString(),
    ))

    /** 고속버스 터미널 전체 (약 450곳) */
    fun expressTerminals(page: Int): JSONObject =
        call("/ExpBusInfo/GetExpBusTrminlList", mapOf("numOfRows" to "1000", "pageNo" to page.toString()), timeoutMs = 15_000)

    /** 시외버스 터미널·정류소 전체 (약 2,300곳, 150KB) — 한 달에 한 번 받아 파일로 둔다 */
    fun intercityTerminals(page: Int): JSONObject =
        call("/SuburbsBusInfo/GetSuberbsBusTrminlList", mapOf("numOfRows" to "3000", "pageNo" to page.toString()), timeoutMs = 20_000)

    private fun call(path: String, params: Map<String, String>, timeoutMs: Int = 8000): JSONObject {
        val raw = BuildConfig.TAGO_SERVICE_KEY
        // 포털의 '인코딩' 키(%가 들어 있음)와 '디코딩' 키를 모두 받아 준다
        val key = if (raw.contains('%')) raw else URLEncoder.encode(raw, "UTF-8")
        val url = "$BASE$path?serviceKey=$key&_type=json&${Http.query(params)}"
        val res = try {
            Http.get(url, timeoutMs = timeoutMs)
        } catch (e: IOException) {
            // apis.data.go.kr 은 연결이 가끔 끊긴다(연결 재설정) — 시간 초과가 아니면 한 번만 다시
            if (e is SocketTimeoutException) throw ApiException("네트워크 오류 (시간 초과)")
            try {
                Thread.sleep(400)
                Http.get(url, timeoutMs = timeoutMs)
            } catch (e2: IOException) {
                throw ApiException("네트워크 오류")
            }
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
    /** 승차 정류장 후보 (정류장 정보는 하루 한 번 바뀌므로 앱이 켜져 있는 동안 재사용 — 호출 수 절반) */
    private val candidatesCache = HashMap<String, List<BusStop>>()
    /** 이 노선 도착이 실제로 나온 정류장 — 다음 새로 고침부터 그곳을 먼저 물어본다 */
    private val servingStop = HashMap<String, String>()

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
            val candidates = synchronized(cache) { candidatesCache[cacheKey] } ?: TransitMatching.stopCandidates(
                TagoApi.nearbyStops(boardCoord), step.boardingStop, boardCoord,
            ).also { found -> synchronized(cache) { candidatesCache[cacheKey] = found } }
            if (candidates.isEmpty()) return "실시간 정보 없음"
            // 지난번에 맞았던 정류장을 먼저 (대전 시청처럼 같은 이름이 이웃 도시 데이터로도 있는 곳)
            val known = synchronized(cache) { servingStop[cacheKey] }
            val ordered = candidates.sortedBy { if (it.nodeId == known) 0 else 1 }
            val hit = TransitMatching.firstServing(ordered, names) { TagoApi.arrivals(it) }
            if (hit != null) synchronized(cache) { servingStop[cacheKey] = hit.first.nodeId }
            val mine = hit?.second.orEmpty()
            when {
                mine.isEmpty() -> "도착 정보 없음"
                mine.size == 1 -> "${mine[0].routeNo}번 ${Format.busArrival(mine[0])}"
                else -> "${mine[0].routeNo}번 ${Format.busArrival(mine[0])} · 다음 ${Format.arrival(mine[1].arrivalSec)}"
            }
        } catch (e: ApiException) {
            ErrorLog.record("실시간 버스", e)
            e.message ?: "실시간 정보 오류"
        } catch (e: Exception) {
            // 예상 못 한 응답 등 — 앱이 멈추지 않게 여기서 끝낸다
            ErrorLog.record("실시간 버스", e)
            "실시간 정보 오류"
        }
        synchronized(cache) { cache[cacheKey] = Cached(text, System.currentTimeMillis()) }
        return text
    }
}
