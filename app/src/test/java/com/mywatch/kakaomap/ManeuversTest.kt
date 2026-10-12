package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Action
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.Maneuvers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 카카오 도보·자전거 안내 문구(실제 대전 응답) → 짧은 회전 안내 */
class ManeuversTest {

    @Test
    fun readsLandmarksRoadsAndDirectionFromKakaoGuidance() {
        val p = Maneuvers.parse("역전약국 앞에서 맛있는 햇살과일까지 오른쪽길로 67m 이동(대전로)")
        assertEquals(Action.SLIGHT_RIGHT, p.action)
        assertEquals("역전약국 앞", p.at)
        assertEquals("맛있는 햇살과일", p.to)
        assertEquals("대전로", p.road)

        val left = Maneuvers.parse("평송네거리에서 좌회전 후 둔산대로 482m 이동")
        assertEquals(Action.LEFT, left.action)
        assertEquals("평송네거리", left.at)
        assertEquals("둔산대로", left.road)

        val right = Maneuvers.parse("우회전 후 둔산로 1.5km 이동")
        assertEquals(Action.RIGHT, right.action)
        assertEquals("둔산로", right.road)
    }

    @Test
    fun placeNamesDoNotBecomeActions() {
        // 장소·길 이름 속 낱말(육교·출구)은 할 일이 아니다
        assertEquals(Action.SLIGHT_RIGHT, Maneuvers.parse("갑천육교 앞에서 맛있는 햇살과일까지 오른쪽길로 67m 이동").action)
        assertNull(Maneuvers.parse("대전역 3번출구까지 85m 이동").action)
        assertEquals("대전역 3번출구", Maneuvers.parse("대전역 3번출구까지 85m 이동").to)
        assertEquals(Action.INDOOR, Maneuvers.parse("판교역 1번 출구까지 역사 내 이동").action)
    }

    @Test
    fun recognisesCrosswalksStairsAndPlainMoves() {
        val cross = Maneuvers.parse("검찰청네거리에서 2개의 횡단보도 이용")
        assertEquals(Action.CROSSWALK, cross.action)
        assertEquals("검찰청네거리", cross.at)
        assertEquals(2, cross.count)
        assertEquals(Action.CROSSWALK, Maneuvers.parse("맛있는 햇살과일 앞에서 횡단보도 이용").action)
        assertEquals("평송네거리", Maneuvers.parse("평송네거리까지 횡단보도 이용").to)
        assertEquals(Action.STAIRS, Maneuvers.parse("계단을 이용하여 자전거전용도로 진입 후 유등천자전거길 19m 이동").action)
        assertEquals(Action.INDOOR, Maneuvers.parse("판교역 1번 출구까지 역사 내 이동").action)
        assertNull("방향 말이 없으면 경로 모양으로 판단", Maneuvers.parse("146m 이동").action)
        assertNull(Maneuvers.parse("교량 진입 후 570m 이동").action)
        assertEquals("역전약국", Maneuvers.parse("역전약국까지 214m 이동").to)
    }

    // 위도 37° 근처: 경도 0.001° ≈ 89m, 위도 0.001° ≈ 111m
    private val o = Coord(37.0, 127.0)
    private val east = Coord(37.0, 127.002)

    @Test
    fun turnAngleComesFromTheRouteShape() {
        val north = Coord(37.002, 127.002)
        val south = Coord(36.998, 127.002)
        val back = Coord(37.0, 127.0)
        assertEquals(-90.0, Maneuvers.turnAngle(listOf(o, east), listOf(east, north))!!, 2.0)   // 동→북 = 좌
        assertEquals(90.0, Maneuvers.turnAngle(listOf(o, east), listOf(east, south))!!, 2.0)    // 동→남 = 우
        assertTrue(kotlin.math.abs(Maneuvers.turnAngle(listOf(o, east), listOf(east, back))!!) > 170)
    }

    @Test
    fun keywordsWinButShapeDecidesSlightOrSharpAndUnnamedTurns() {
        val north = Coord(37.002, 127.002)
        val ne = Coord(37.0008, 127.003)       // 약 45° 왼쪽 비스듬히
        fun steps(next: Coord, text: String) = listOf(
            GuideStep("출발", listOf(o, east), turnGuidance = true),
            GuideStep(text, listOf(east, next), turnGuidance = true),
        )
        assertEquals(Action.LEFT, Maneuvers.forSteps(steps(north, "좌회전 후 100m 이동"))[1]!!.action)
        assertEquals(Action.SLIGHT_LEFT, Maneuvers.forSteps(steps(ne, "왼쪽길로 46m 이동"))[1]!!.action)
        assertEquals("이름 없는 90° 꺾임도 좌회전", Action.LEFT, Maneuvers.forSteps(steps(north, "96m 이동"))[1]!!.action)
        assertEquals("완만한 굽이는 직진", Action.STRAIGHT, Maneuvers.forSteps(steps(Coord(37.0002, 127.004), "146m 이동"))[1]!!.action)
        assertNull("첫 구간은 출발", Maneuvers.forSteps(steps(north, "x"))[0])
        assertNull("대중교통 구간엔 회전 안내 없음", Maneuvers.forSteps(listOf(GuideStep("a", listOf(o, east)), GuideStep("b", listOf(east, north))))[1])
    }

    @Test
    fun shortPhrasesForTheBanner() {
        assertEquals("좌회전", Maneuvers.phrase(Action.LEFT))
        assertEquals("오른쪽 길로", Maneuvers.phrase(Action.SLIGHT_RIGHT))
        assertEquals("횡단보도 건너기", Maneuvers.phrase(Action.CROSSWALK))
        assertEquals("횡단보도 2번", Maneuvers.phrase(Action.CROSSWALK, count = 2))
        assertEquals("유턴", Maneuvers.phrase(Action.UTURN))
    }
}
