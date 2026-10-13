package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.BusArrival
import com.mywatch.kakaomap.model.BusStop
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.RouteProgress
import com.mywatch.kakaomap.model.TransitMatching
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LogicTest {

    @Test
    fun formatsLikeKakaoMap() {
        assertEquals("850m", Format.distance(850))
        assertEquals("1.2km", Format.distance(1234))
        assertEquals("12km", Format.distance(12_300))
        assertEquals("1분", Format.duration(20))
        assertEquals("42분", Format.duration(2500))
        assertEquals("1시간 5분", Format.duration(3900))
        assertEquals("2시간", Format.duration(7200))
        assertEquals("1,500원", Format.fare(1500))
        assertEquals("환승 없음", Format.transfers(0))
        assertEquals("곧 도착", Format.arrival(30))
        // 실시간 버스: 카카오맵 표기 "3분 (2번째 전)", 1분 안이면 "곧 도착"
        assertEquals("3분 (2번째 전)", Format.busArrival(BusArrival("102", "", 200, 2)))
        assertEquals("곧 도착", Format.busArrival(BusArrival("102", "", 45, 1)))
        assertEquals("7분", Format.busArrival(BusArrival("102", "", 430, 0)))
        assertEquals("1,500~3,150원", Format.fare(1500, 3150))
        assertEquals("1,500원", Format.fare(1500, null))
    }

    @Test
    fun clockAfterUsesKoreanAmPm() {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            val c = Calendar.getInstance(Locale.KOREA).apply { set(2026, 9, 12, 21, 13, 0) }
            assertEquals("오후 9:55", Format.clockAfter(42 * 60, c.timeInMillis))
            assertEquals("오전 12:03", Format.clockAfter(170 * 60, c.timeInMillis))
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun distanceIsReasonable() {
        // 대전역 ↔ 서대전역 직선 약 3.5km
        val d = Geo.distanceM(Coord(36.3323, 127.4342), Coord(36.3219, 127.4040))
        assertTrue("거리=$d", d in 2_800.0..3_400.0)
        assertEquals(90.0, Geo.bearingDeg(Coord(37.0, 127.0), Coord(37.0, 127.01)), 0.5)
    }

    @Test
    fun routeProgressFindsCurrentStep() {
        // 동쪽으로 약 0.9km 직선, 두 구간
        val a = Coord(37.0, 127.0)
        val b = Coord(37.0, 127.005)
        val c = Coord(37.0, 127.01)
        val steps = listOf(GuideStep("첫 구간", listOf(a, b)), GuideStep("둘째 구간", listOf(b, c)))
        val total = Geo.distanceM(a, c)

        val p1 = RouteProgress.locate(Coord(37.0, 127.001), steps)!!
        assertEquals(0, p1.stepIndex)
        assertTrue(p1.offRouteM < 2)
        assertTrue(p1.remainingM in (total * 0.85).toInt()..(total * 0.95).toInt())

        val p2 = RouteProgress.locate(Coord(37.0003, 127.008), steps)!!   // 약 33m 북쪽으로 벗어남
        assertEquals(1, p2.stepIndex)
        assertTrue("벗어난 거리=${p2.offRouteM}", p2.offRouteM in 25..40)
        assertTrue(p2.toStepEndM in 150..200)

        assertNull(RouteProgress.locate(a, emptyList()))
    }

    @Test
    fun stopAndRouteMatching() {
        assertEquals("76", TransitMatching.normalizeRoute("마을 76"))
        assertEquals("102", TransitMatching.normalizeRoute("102번"))
        assertEquals("급행1", TransitMatching.normalizeRoute("급행1"))
        assertTrue(TransitMatching.sameRoute("간선 102", "102"))

        val here = Coord(36.35, 127.38)
        val stops = listOf(
            BusStop(25, "A", "둔산동 (건너편)", Coord(36.3502, 127.3801)),
            BusStop(25, "B", "목련아파트", Coord(36.3500, 127.3800)),
        )
        // 이름이 같은 정류장을 우선 (괄호·공백 무시)
        assertEquals("A", TransitMatching.pickStop(stops, "둔산동", here)?.nodeId)
        // 이름이 안 맞으면 60m 안의 가장 가까운 곳
        assertEquals("B", TransitMatching.pickStop(stops, "없는정류장", here)?.nodeId)
        // 아무것도 가깝지 않으면 null
        assertNull(TransitMatching.pickStop(stops, "없는정류장", Coord(36.36, 127.39)))

        val arrivals = listOf(BusArrival("101", "", 100, 1), BusArrival("102", "", 300, 3))
        assertEquals(listOf("102"), TransitMatching.arrivalsFor(arrivals, listOf("102")).map { it.routeNo })
    }

    @Test
    fun parsesStopPairFromGuidance() {
        assertEquals("판교역동편" to "성남시청전면", TransitStep.parseStopPair("마을 76 (판교역동편 > 성남시청전면)"))
        assertNull(TransitStep.parseStopPair("도보 이동"))
        // 이름 안의 괄호 (실제 대전 응답의 '유성온천(충남대.목원대)') — 예전엔 승차 정류장이 "충남대.목원대)"
        assertEquals("유성온천(충남대.목원대)" to "대전역", TransitStep.parseStopPair("1호선 (유성온천(충남대.목원대) > 대전역)"))
        assertEquals("대전역" to "유성온천(충남대.목원대)", TransitStep.parseStopPair("1호선 (대전역 > 유성온천(충남대.목원대))"))
        assertEquals("갤러리아백화점(타임월드)" to "카이스트", TransitStep.parseStopPair("일반 604 (갤러리아백화점(타임월드) > 카이스트)"))
        assertNull(TransitStep.parseStopPair("유성온천역7번출구정류장까지 도보로 이동 (7번 출구)"))
    }

    @Test
    fun boardingAndAlightingStopsComeFromTheStopList() {
        // 정류장 목록이 있으면 그 처음·끝 (안내문을 읽지 않아도 됨), 없으면 안내문에서
        val s = TransitStep(
            TransitStepType.SUBWAY, "1호선 (유성온천(충남대.목원대) > 대전역)", 11400, 1380,
            stops = listOf("유성온천(충남대.목원대)", "구암", "대전역"), vehicles = emptyList(), points = emptyList(),
        )
        assertEquals("유성온천(충남대.목원대)", s.boardingStop)
        assertEquals("대전역", s.alightingStop)
        assertEquals("유성온천(충남대.목원대)", s.copy(stops = emptyList()).boardingStop)
    }
}
