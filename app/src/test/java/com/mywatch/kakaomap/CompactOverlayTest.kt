package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.nav.NavEngine
import com.mywatch.kakaomap.ui.NavOverlayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 지도를 덜 가리는 안내 배너(짧은 두 줄)·아래 한 줄 알약 문구 검증 */
class CompactOverlayTest {

    // 위도 37° 에서 경도 0.001° ≈ 89m
    private val a = Coord(37.0, 127.000)
    private val stop = Coord(37.0, 127.003)
    private val busEnd = Coord(37.0001, 126.990)
    private val dest = Coord(37.0005, 126.990)
    private val steps = listOf(
        GuideStep("시청역까지 도보", listOf(a, stop), approx = true),
        GuideStep(
            "617번 버스 · 시청역 → 은하수네거리", listOf(Coord(37.0001, 127.003), busEnd),
            ride = true, alight = "은하수네거리", line = "617번", board = "시청역",
        ),
        GuideStep("도보 1분 · 44m", listOf(busEnd, dest)),
    )

    @Test
    fun walkingToTheStopShowsOnlyTheBusAndTheStop() {
        val e = NavEngine(steps, dest, 1)
        e.update(Coord(37.0, 127.001), 5f)
        assertEquals(0, e.state.stepIndex)
        val (main, sub) = NavOverlayText.bannerLines(e.state.guide!!)
        assertTrue(main, Regex("""\d+m 후 617번 승차""").matches(main))
        assertEquals("시청역", sub)
    }

    @Test
    fun ridingShowsHowFarToGetOffThenTheStop() {
        val e = NavEngine(steps, dest, 1)
        e.update(Coord(37.0, 127.001), 5f)
        e.update(Coord(37.0001, 126.9985), 5f)     // 버스 위 첫 위치는 '탔는지' 확인 대기
        e.update(Coord(37.0001, 126.998), 5f)      // 버스 위, 내릴 곳까지 약 710m
        assertEquals(1, e.state.stepIndex)
        val (main, sub) = NavOverlayText.bannerLines(e.state.guide!!)
        assertTrue(main, Regex("""\d+m 후 하차""").matches(main))
        assertEquals("은하수네거리 · 617번", sub)

        e.update(Coord(37.0001, 126.993), 5f)      // 약 270m 남음 → 곧 하차
        val (soon, left) = NavOverlayText.bannerLines(e.state.guide!!)
        assertEquals("곧 하차 · 은하수네거리", soon)
        assertTrue(left!!, left.endsWith("m 남음"))
    }

    @Test
    fun bannerKeepsTheFirstLineAndJoinsTheRest() {
        assertEquals("목적지에 도착했어요" to null, NavOverlayText.bannerLines("목적지에 도착했어요"))
        assertEquals("120m 후 우회전" to "보행자도로", NavOverlayText.bannerLines("120m 후 우회전\n보행자도로"))
        assertEquals("가" to "나 · 다", NavOverlayText.bannerLines("가\n나\n\n다"))
    }

    private val t0 = 1_760_000_000_000L

    @Test
    fun pillShowsRemainingDistanceAndArrivalTime() {
        // 대중교통: 남은 거리 + 처음 계획한 도착 시각
        assertEquals(
            "8.3km · ${Format.clock24After(4200, t0)} 도착",
            NavOverlayText.pill(TravelMode.TRANSIT, 8300, false, 9000, 4200, t0, t0 + 600_000, "요약", "대전역"),
        )
        // 도보·자전거: 남은 거리만큼 걸리는 시간으로 지금부터 계산
        assertEquals(
            "600m · ${Format.clock24After(450, t0 + 60_000)} 도착",
            NavOverlayText.pill(TravelMode.WALK, 600, false, 1200, 900, t0, t0 + 60_000, "요약", "대전역"),
        )
    }

    @Test
    fun pillFallsBackToSummaryOrDestination() {
        assertEquals(
            "도보 15분 · 1.2km",
            NavOverlayText.pill(TravelMode.WALK, null, false, 1200, 900, t0, t0, "도보 15분 · 1.2km", "대전역"),
        )
        assertEquals("→ 대전역", NavOverlayText.pill(TravelMode.BIKE, 0, true, 1200, 300, t0, t0, "요약", "대전역"))
    }

    @Test
    fun clock24IsTwoDigitHoursAndMinutes() {
        assertTrue(Regex("""\d{2}:\d{2}""").matches(Format.clock24After(0, t0)))
        assertTrue(Regex("""\d{2}:\d{2}""").matches(Format.clock24After(13 * 3600, t0)))
    }

    @Test
    fun transitStepsCarryShortLineNameAndBoardingStop() {
        val r = TransitRoute(
            type = "BUS_SUBWAY", totalDistanceM = 9000, totalTimeSec = 4200, transfers = 1, fare = 1500,
            steps = listOf(
                TransitStep(
                    TransitStepType.BUS, "일반 617", 6000, 1800,
                    stops = listOf("시청역", "중리네거리", "은하수네거리"), vehicles = listOf(Vehicle("일반", "617")),
                    points = listOf(stop, busEnd),
                ),
                TransitStep(
                    TransitStepType.SUBWAY, "대전 1호선", 3000, 600,
                    stops = listOf("대전역", "중앙로"), vehicles = listOf(Vehicle("도시철도", "대전 1호선")),
                    points = listOf(busEnd, dest),
                ),
            ),
        )
        val (bus, subway) = TransitGuide.steps(r)
        assertEquals("617번", bus.line)
        assertEquals("시청역", bus.board)
        assertEquals("은하수네거리", bus.alight)
        assertTrue(bus.ride && !bus.subway)
        assertEquals("617번 버스 · 시청역 → 은하수네거리", bus.text)
        assertEquals("대전 1호선", subway.line)
        assertEquals("대전역", subway.board)
        assertTrue(subway.ride && subway.subway)
    }
}
