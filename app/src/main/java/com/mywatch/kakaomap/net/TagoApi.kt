package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.model.ArrivalBoard
import com.mywatch.kakaomap.model.BusArrival
import com.mywatch.kakaomap.model.BusBoarding
import com.mywatch.kakaomap.model.BusRoute
import com.mywatch.kakaomap.model.BusStop
import com.mywatch.kakaomap.model.BusStopResolver
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.LiveBus
import com.mywatch.kakaomap.model.LiveBusText
import com.mywatch.kakaomap.model.TagoParsers
import com.mywatch.kakaomap.model.TransitMatching
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Semaphore

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

    /**
     * 노선 번호로 노선 찾기 (같은 번호의 여러 노선이 나올 수 있음). 번호가 들어간 노선이 모두 나오므로('5' → 5·15·105·…)
     * 짧은 번호도 그 노선이 빠지지 않게 넉넉히 받는다
     */
    fun routes(cityCode: Int, routeNo: String): List<BusRoute> = TagoParsers.routes(
        call("/BusRouteInfoInqireService/getRouteNoList", mapOf(
            "cityCode" to cityCode.toString(), "routeNo" to routeNo, "numOfRows" to "100", "pageNo" to "1",
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

/**
 * 버스 구간의 실시간 도착 (카카오맵처럼 "106번 3분 (2번째 전) · 다음 15분", 노선이 여럿이면 "106 2분 · 617 5분").
 *  · 승차 정류장은 노선 정류소 순서(승차 … 하차)로 찾아 방향까지 맞춘다 — 길 건너 같은 이름의 반대 방향 정류장 도착을 보이지 않게
 *    ([BusStopResolver], 타는 중 '정류장 n개 남음'([StopLocator])도 같은 결과를 씀)
 *  · 도착 정보는 정류장마다 한 번만 묻고 30초 동안 같이 쓴다 — 목록의 여러 경로가 같은 정류장이면 한 번 ([ArrivalBoard])
 *  · 공공데이터 요청은 동시에 3개까지, 연결이 안 되면 20초 동안은 다시 묻지 않는다 (워치 배터리·일일 사용량)
 */
object BusArrivalService {

    /** 동시에 보내는 공공데이터 요청 수 */
    private val gate = Semaphore(3)

    private fun <T> limited(block: () -> T): T {
        gate.acquire()
        try {
            return block()
        } finally {
            gate.release()
        }
    }

    /** 마지막으로 네트워크 오류가 난 시각과 그 문구 — 잠깐 동안 같은 오류로 요청을 되풀이하지 않게 */
    @Volatile private var failedAt = 0L
    @Volatile private var failedText: String? = null
    private const val BACKOFF_MS = 20_000L

    val resolver = BusStopResolver(
        nearby = { at -> limited { TagoApi.nearbyStops(at) } },
        routes = { city, no -> limited { TagoApi.routes(city, no) } },
        routeStops = { city, id -> limited { TagoApi.routeStops(city, id) } },
    )
    private val board = ArrivalBoard(fetch = { stop -> limited { TagoApi.arrivals(stop) } })

    /** 승차 정류장(방향까지 맞춘 것)·노선. 백그라운드 스레드에서. 키가 없으면 null, 네트워크 오류는 던진다 */
    fun boarding(step: TransitStep): BusBoarding? = if (TagoApi.isConfigured) resolver.resolve(step) else null

    /** 실시간 도착. 백그라운드 스레드에서. 키가 없거나 버스 구간이 아니면 null (오류는 문구로) */
    fun live(step: TransitStep): LiveBus? {
        if (!TagoApi.isConfigured || step.type != TransitStepType.BUS) return null
        if (step.vehicles.none { it.name.isNotBlank() } || step.points.isEmpty()) return null
        val recentFail = failedText
        if (recentFail != null && System.currentTimeMillis() - failedAt < BACKOFF_MS) return LiveBus(recentFail)
        return try {
            val b = resolver.resolve(step) ?: return LiveBus("실시간 정보 없음")
            val stop = b.stop
            val arrivals = if (stop != null) {
                board.at(stop)
            } else {
                // 노선 순서로 못 찾음: 이름·거리로 짐작한 정류장 (같은 도시의 같은 이름으로는 넘어가지 않고, 기억하지도 않음)
                if (b.candidates.isEmpty()) return LiveBus("실시간 정보 없음")
                TransitMatching.firstServingLines(b.candidates, b.lines) { board.at(it) }?.second.orEmpty()
            }
            failedText = null
            LiveBusText.of(b.lines, arrivals)
        } catch (e: ApiException) {
            ErrorLog.record("실시간 버스", e)
            val text = e.message ?: "실시간 정보 오류"
            failedAt = System.currentTimeMillis()
            failedText = text
            LiveBus(text)
        } catch (e: Exception) {
            // 예상 못 한 응답 등 — 앱이 멈추지 않게 여기서 끝낸다
            ErrorLog.record("실시간 버스", e)
            LiveBus("실시간 정보 오류")
        }
    }

    /** 목록·상세에 쓰는 문구 ("106번 3분 (2번째 전) · 다음 15분"). 키가 없거나 못 찾으면 null */
    fun describe(step: TransitStep): String? = live(step)?.text
}
