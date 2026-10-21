package com.mywatch.kakaomap

import com.mywatch.kakaomap.location.MapTurn
import com.mywatch.kakaomap.location.MapTurnCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * '내가 보는 쪽이 위' 지도가 정말 그쪽으로 도는지 — 카카오 SDK 는 지도(카메라) 회전값의 방향을 문서에 밝히지 않으므로
 * 화면 좌표(toScreenPoint)로 화면 위쪽이 실제로 가리키는 방위를 재서 확인하고, 반대로 돌면 스스로 고친다.
 */
class MapTurnTest {

    /** 화면 위쪽이 [topDeg] 를 가리키는 지도에서 가운데(100,100)와 그 북쪽 점의 화면 좌표 (y 는 아래로 +) */
    private fun northPoint(topDeg: Double): Pair<Float, Float> {
        // 북쪽은 화면에서 시계 방향으로 −topDeg 만큼 돌아 보인다
        val a = Math.toRadians(-topDeg)
        return (100 + 40 * sin(a)).toFloat() to (100 - 40 * cos(a)).toFloat()
    }

    private fun near(expected: Double, actual: Double?, tol: Double = 0.5) {
        val a = actual ?: throw AssertionError("못 잼 (기대 $expected)")
        assertTrue("$a (기대 $expected)", MapTurn.diff(a, expected) < tol)
    }

    @Test
    fun screenTopBearingFromTwoPoints() {
        for (top in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0)) {
            val (nx, ny) = northPoint(top)
            near(top, MapTurn.screenTopBearing(100f, 100f, nx, ny))
        }
        // 북쪽 점이 오른쪽에 보이면(시계 방향 90°) 화면 위쪽은 서쪽
        near(270.0, MapTurn.screenTopBearing(100f, 100f, 140f, 100f))
        // 두 점이 겹치면 모른다
        assertNull(MapTurn.screenTopBearing(100f, 100f, 101f, 100f))
    }

    /** 회전값 [rotationRad] 를 받으면 화면 위쪽이 [convention]·회전값(도) 이 되는 지도 */
    private fun top(rotationRad: Double, convention: Int) = MapTurn.norm360(convention * Math.toDegrees(rotationRad))

    @Test
    fun learnsWhichWayTheCameraTurns() {
        for (truth in listOf(1, -1)) {
            val check = MapTurnCheck()
            var changed = false
            // 직접 돌린 지도에서 본 것 (따라 돌리기 안 씀) — 사람이 30~120° 쪽을 볼 때
            for (want in listOf(30.0, 60.0, 90.0, 120.0)) {
                val rot = check.cameraRadians(want)
                changed = check.observe(rot, top(rot, truth), want, trackingRotation = false) || changed
            }
            assertEquals("실제 방향 $truth", truth, check.sign)
            assertEquals(truth == -1, changed)
            // 배운 뒤엔 화면 위쪽이 원하는 방위
            for (want in listOf(10.0, 75.0, 200.0, 300.0)) near(MapTurn.norm360(want), top(check.cameraRadians(want), truth))
        }
    }

    @Test
    fun northAndSouthTeachNothing() {
        // 0°·180° 근처는 어느 쪽으로 돌든 똑같아 보인다 — 거기선 배우지 않는다
        val check = MapTurnCheck()
        repeat(10) {
            assertFalse(check.observe(Math.toRadians(5.0), 355.0, 5.0, false))
            assertFalse(check.observe(Math.toRadians(178.0), 182.0, 178.0, false))
        }
        assertEquals(1, check.sign)
    }

    @Test
    fun oneOddReadingDoesNotFlipIt() {
        val check = MapTurnCheck()
        check.observe(Math.toRadians(90.0), 270.0, 90.0, false)     // 반대로 본 듯한 한 번
        check.observe(Math.toRadians(80.0), 80.0, 80.0, false)      // 다시 맞음
        check.observe(Math.toRadians(70.0), 290.0, 70.0, false)
        assertEquals(1, check.sign)
    }

    @Test
    fun sdkFollowRotationThatTurnsTheWrongWayIsDetected() {
        // SDK 의 '라벨 따라 회전'이 반대로 돌아 화면 위쪽이 내가 보는 방향의 거울(−방위)이 되는 경우
        val check = MapTurnCheck()
        assertFalse(check.trackingBroken)
        var changed = false
        for (want in listOf(60.0, 90.0, 120.0)) changed = check.observe(0.0, MapTurn.norm360(-want), want, trackingRotation = true) || changed
        assertTrue(changed)
        assertTrue(check.trackingBroken)
    }

    @Test
    fun mirrorReadingsMustComeInARow() {
        // 거울 두 번 → 맞는 값 한 번 → 거울 두 번: 연속 3번이 아니므로 그대로
        val check = MapTurnCheck()
        check.observe(0.0, 300.0, 60.0, trackingRotation = true)
        check.observe(0.0, 270.0, 90.0, trackingRotation = true)
        check.observe(0.0, 100.0, 100.0, trackingRotation = true)
        check.observe(0.0, 250.0, 110.0, trackingRotation = true)
        check.observe(0.0, 240.0, 120.0, trackingRotation = true)
        assertFalse(check.trackingBroken)
    }

    @Test
    fun sdkFollowRotationThatWorksIsKept() {
        val check = MapTurnCheck()
        for (want in listOf(40.0, 90.0, 130.0, 250.0, 300.0)) check.observe(Math.toRadians(want), want, want, trackingRotation = true)
        assertFalse(check.trackingBroken)
        assertEquals(1, check.sign)
    }

    @Test
    fun aMapStillCatchingUpIsNotCalledBroken() {
        // 빨리 돌아서 지도가 아직 따라오는 중(중간쯤) — 거울이 아니면 고장으로 보지 않는다
        val check = MapTurnCheck()
        repeat(6) { check.observe(Math.toRadians(45.0), 45.0, 90.0, trackingRotation = true) }
        assertFalse(check.trackingBroken)
    }

    @Test
    fun cameraRadiansStayWithinMinusPiToPi() {
        val plus = MapTurnCheck(sign = 1)
        assertEquals(PI / 2, plus.cameraRadians(90.0), 1e-9)
        assertEquals(-PI / 2, plus.cameraRadians(270.0), 1e-9)
        val minus = MapTurnCheck(sign = -1)
        assertEquals(-PI / 2, minus.cameraRadians(90.0), 1e-9)
        assertEquals(0.0, minus.cameraRadians(0.0), 1e-9)
    }
}
