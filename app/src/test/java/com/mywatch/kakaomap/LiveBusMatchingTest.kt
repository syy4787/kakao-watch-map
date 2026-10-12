package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.BusArrival
import com.mywatch.kakaomap.model.BusStop
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.TagoParsers
import com.mywatch.kakaomap.model.TransitMatching
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 실제 TAGO 응답(2026-10-13 아침, 대전광역시청 근처)으로 확인한 함정:
 * 같은 '대전광역시청' 정류장이 대전(25, DJB…)과 세종(12, SJB…) 두 도시로 따로 나오고,
 * 카카오가 준 승차 위치는 세종 쪽 항목에 더 가깝다(9m vs 24m).
 * 가까운 것만 고르면 세종 정류장으로 대전 707번 도착을 물어 '도착 정보 없음'이 된다.
 */
class LiveBusMatchingTest {

    // 카카오 길찾기 '일반 707 (대전광역시청 > 대전역)' 승차 지점
    private val board = Coord(36.35120829, 127.38510038)

    // getCrdntPrxmtSttnList 실제 응답 (순서 그대로)
    private val nearby = listOf(
        BusStop(25, "DJB8001378", "대전광역시청", Coord(36.351112, 127.38486)),
        BusStop(12, "SJB186005227", "대전광역시청", Coord(36.3511393, 127.3851554)),
        BusStop(34020, "GOB286071285", "btest", Coord(36.3512207, 127.3851056)),
        BusStop(25, "DJB9002089", "시청환승지", Coord(36.351566, 127.38445)),
        BusStop(25, "DJB8002087", "시청.교육청", Coord(36.351395, 127.3835)),
        BusStop(12, "SJB186005229", "시청.교육청", Coord(36.3514272, 127.3833863)),
        BusStop(25, "DJB8002090", "시청환승지", Coord(36.35203, 127.38534)),
        BusStop(25, "DJB9002090", "시청환승지", Coord(36.352467, 127.385284)),
        BusStop(25, "DJB8002089", "시청환승지", Coord(36.352467, 127.38446)),
        BusStop(12, "SJB187001500", "시청환승지", Coord(36.35251, 127.38456)),
        BusStop(12, "SJB185003249", "시청환승지", Coord(36.35251, 127.38456)),
        BusStop(25, "DJB8001572", "둔산초등학교", Coord(36.34839, 127.383026)),
    )

    @Test
    fun theLocalCitysStopComesFirstEvenIfAnotherCitysCopyIsCloser() {
        assertEquals(25, TransitMatching.dominantCity(nearby))
        val order = TransitMatching.stopCandidates(nearby, "대전광역시청", board)
        assertEquals(listOf("DJB8001378", "SJB186005227"), order.take(2).map { it.nodeId })
        assertEquals("최대 3곳까지만 물어본다", 3, order.size)
    }

    @Test
    fun ifTheFirstStopDoesNotServeTheBusTryTheNext() {
        val asked = mutableListOf<String>()
        val arrivalsAt = mapOf(
            "DJB8001378" to listOf(BusArrival("707", "간선버스", 748, 7), BusArrival("106", "간선버스", 617, 6)),
            "SJB186005227" to listOf(BusArrival("1001", "간선급행", 300, 2)),
        )
        val hit = TransitMatching.firstServing(
            TransitMatching.stopCandidates(nearby, "대전광역시청", board), listOf("707"),
        ) { stop -> asked += stop.nodeId; arrivalsAt[stop.nodeId].orEmpty() }!!
        assertEquals("DJB8001378", hit.first.nodeId)
        assertEquals(listOf("707"), hit.second.map { it.routeNo })
        assertEquals("맞는 곳을 찾으면 더 묻지 않음", listOf("DJB8001378"), asked)

        // 세종 버스(1001)를 타는 경우엔 대전 정류장에 없으니 다음 후보(세종)까지 물어본다
        asked.clear()
        val sejong = TransitMatching.firstServing(
            TransitMatching.stopCandidates(nearby, "대전광역시청", board), listOf("1001"),
        ) { stop -> asked += stop.nodeId; arrivalsAt[stop.nodeId].orEmpty() }!!
        assertEquals("SJB186005227", sejong.first.nodeId)
        assertEquals(listOf("DJB8001378", "SJB186005227"), asked)

        // 어디에도 없으면 null (지금 다니는 차가 없음 등)
        assertNull(TransitMatching.firstServing(TransitMatching.stopCandidates(nearby, "대전광역시청", board), listOf("999")) { emptyList() })
    }

    @Test
    fun realArrivalResponseHasNumericRouteNumbers() {
        // getSttnAcctoArvlPrearngeInfoList 실제 응답 일부 — routeno 가 숫자로 온다
        val json = JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"items":{"item":[
              {"arrprevstationcnt":7,"arrtime":748,"nodeid":"DJB8001378","nodenm":"대전광역시청","routeid":"DJB30300152","routeno":707,"routetp":"간선버스","vehicletp":"일반차량"},
              {"arrprevstationcnt":6,"arrtime":617,"nodeid":"DJB8001378","nodenm":"대전광역시청","routeid":"DJB30300041","routeno":106,"routetp":"간선버스","vehicletp":"일반차량"}]},
            "numOfRows":50,"pageNo":1,"totalCount":2}}}""")
        assertNull(TagoParsers.errorMessage(json))
        val arrivals = TagoParsers.arrivals(json)
        assertEquals(listOf("106", "707"), arrivals.map { it.routeNo })   // 빨리 오는 순
        val mine = TransitMatching.arrivalsFor(arrivals, listOf("707"))
        assertEquals(748, mine.single().arrivalSec)
        assertEquals(7, mine.single().stopsAway)
    }

    @Test
    fun portalErrorsBecomePlainKorean() {
        // 키를 아직 그 API 에 신청하지 않았을 때 실제로 받은 응답
        val notRegistered = JSONObject("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}""")
        assertEquals("공공데이터 키가 이 기능에 아직 등록되지 않았어요", TagoParsers.errorMessage(notRegistered))
        val busy = JSONObject("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"HTTP_ERROR","returnAuthMsg":"HTTP 에러","returnReasonCode":"04"}}}""")
        assertEquals("실시간 정보를 잠시 받을 수 없어요", TagoParsers.errorMessage(busy))
        val quota = JSONObject("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR","returnReasonCode":"22"}}}""")
        assertEquals("오늘 공공데이터 사용량을 다 썼어요", TagoParsers.errorMessage(quota))
    }
}
