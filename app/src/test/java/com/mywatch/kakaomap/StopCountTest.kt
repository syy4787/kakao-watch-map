package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.StopPlacer
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.nav.NavEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** '정류장 n개 남음' — 정류장 좌표를 노선 위에 올려 남은 수를 세고, 다음 정류장이 내릴 곳일 때 알린다 */
class StopCountTest {

    private fun east(m: Double) = Coord(37.0, 127.0 + m / 88_900.0)

    @Test
    fun stopsAreProjectedOntoTheLineInOrder() {
        val path = listOf(east(0.0), east(1_000.0), Coord(37.0 + 500 / 111_130.0, 127.0 + 1_000 / 88_900.0))
        val stops = listOf(
            Coord(37.0001, 127.0 + 300 / 88_900.0),          // 300m, 길 옆 11m
            Coord(37.0 + 200 / 111_130.0, 127.0 + 1_000 / 88_900.0 + 0.0001), // 꺾인 뒤 200m → 1200m
            Coord(37.02, 127.02),                             // 노선에서 너무 멂
        )
        val along = StopPlacer.alongDistances(path, stops)
        assertEquals(300.0, along[0]!!, 3.0)
        assertEquals(1_200.0, along[1]!!, 3.0)
        assertNull(along[2])
    }

    @Test
    fun stationNamesBecomeSearchQueries() {
        assertEquals("탄방역", StopPlacer.stationQuery("탄방"))
        assertEquals("대전역", StopPlacer.stationQuery("대전역"))
        assertEquals("유성온천역", StopPlacer.stationQuery("유성온천(충남대.목원대)"))
    }

    // 정류장까지 걷기 200m → 버스 동쪽 2km (정류장 500·1000·1500·2000m, 2000m 가 내릴 곳) → 걷기
    private val s0 = Coord(37.0, 126.998)
    private val stop = Coord(37.0, 127.0)
    private val busEnd = east(2_000.0)
    private val dest = Coord(37.0 + 40 / 111_130.0, busEnd.lng)
    private val route = listOf(
        GuideStep("정류장까지 도보", listOf(s0, stop), approx = true),
        GuideStep("5번 버스", listOf(stop, busEnd), ride = true, alight = "E", line = "5번", board = "S"),
        GuideStep("도보", listOf(busEnd, dest)),
    )

    @Test
    fun ridingCountsStopsAndAlertsWhenTheNextStopIsYours() {
        val e = NavEngine(route, dest, 1)
        e.setStops(1, listOf(500.0, 1_000.0, 1_500.0, 2_000.0))
        var t = 0L
        val events = ArrayList<Pair<NavEvent, Double>>()
        var s = 0.0
        while (s <= 1_990.0) {       // 버스: 8m/s, 5초 간격
            e.update(Coord(37.0, 127.0 + s / 88_900.0), 5f, t).forEach { events += it to s }
            if (s in 650.0..900.0) {
                assertEquals("3정거장 남음", e.state.bannerTitle)
                assertEquals("E 하차", e.state.bannerSub)
            }
            s += 40.0
            t += 5_000
        }
        val alight = events.filter { it.first == NavEvent.ALIGHT_SOON }
        assertEquals("하차 알림 한 번", 1, alight.size)
        assertTrue("1500m 정류장을 지난 뒤(다음이 내릴 곳)에 알림: ${alight[0].second}", alight[0].second in 1_515.0..1_700.0)
        assertEquals("다음 정류장 하차", e.state.bannerTitle)
    }

    @Test
    fun withoutStopPositionsDistanceIsUsed() {
        val e = NavEngine(route, dest, 1)
        var t = 0L
        var s = 0.0
        var alertAt = -1.0
        while (s <= 1_990.0) {
            if (NavEvent.ALIGHT_SOON in e.update(Coord(37.0, 127.0 + s / 88_900.0), 5f, t)) alertAt = s
            s += 40.0
            t += 5_000
        }
        assertTrue("정류장 정보가 없으면 남은 500m 안에서: $alertAt", alertAt in 1_500.0..1_600.0)
    }
}
