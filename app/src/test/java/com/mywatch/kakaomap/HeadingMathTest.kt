package com.mywatch.kakaomap

import android.view.Surface
import com.mywatch.kakaomap.location.HeadingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 내 위치 부채꼴이 '내가 보는 방향'을 가리키는지.
 * 워치를 보려고 손목을 들면 화면이 얼굴 쪽으로 서는데, 12시 방향(Y축)만으로 방위를 재면 거의 하늘을 가리켜
 * 방향이 마구 흔들리거나 뒤집혔다(사용자: "반대였다가 비스듬했다가 난리"). 오른손목에 버튼 방향을 바꿔
 * 화면을 180° 돌려 차면 12시가 몸 쪽을 가리켜 정반대가 된다.
 *
 * 회전 행렬은 기기 축(X·Y·Z)을 세계 좌표(동·북·위)로 옮기는 것 — 열이 각 기기 축이다(안드로이드 SensorManager 와 같음).
 */
class HeadingMathTest {

    private data class V(val e: Double, val n: Double, val u: Double) {
        operator fun times(k: Double) = V(e * k, n * k, u * k)
        operator fun plus(o: V) = V(e + o.e, n + o.n, u + o.u)
        operator fun unaryMinus() = V(-e, -n, -u)
        infix fun cross(o: V) = V(n * o.u - u * o.n, u * o.e - e * o.u, e * o.n - n * o.e)
    }

    private val east = V(1.0, 0.0, 0.0)
    private val north = V(0.0, 1.0, 0.0)
    private val up = V(0.0, 0.0, 1.0)

    /** 기기의 Y(12시)·Z(화면 밖) 축이 세계에서 어디를 향하는지로 회전 행렬 (X = Y × Z) */
    private fun matrix(y: V, z: V): FloatArray {
        val x = y cross z
        return floatArrayOf(
            x.e.toFloat(), y.e.toFloat(), z.e.toFloat(),
            x.n.toFloat(), y.n.toFloat(), z.n.toFloat(),
            x.u.toFloat(), y.u.toFloat(), z.u.toFloat(),
        )
    }

    /** [forward] 쪽을 보며 손목을 들어 화면을 [tiltDeg] 만큼 얼굴 쪽으로 세운 워치 (0 = 바닥과 수평, 90 = 똑바로 섬) */
    private fun looking(forward: V, tiltDeg: Double): FloatArray {
        val a = Math.toRadians(tiltDeg)
        return matrix(y = forward * cos(a) + up * sin(a), z = -forward * sin(a) + up * cos(a))
    }

    private fun assertHeading(expected: Double, actual: Double?) {
        val a = actual ?: throw AssertionError("방위를 못 구함 (expected $expected)")
        val diff = ((a - expected + 540) % 360) - 180
        assertEquals("방위 $a° (기대 $expected°)", 0.0, diff, 1.0)
    }

    @Test
    fun flatWatchTwelveOClockNorthIsNorth() =
        assertHeading(0.0, HeadingMath.screenHeadingDeg(matrix(y = north, z = up), Surface.ROTATION_0))

    @Test
    fun flatWatchTwelveOClockEastIsEast() =
        assertHeading(90.0, HeadingMath.screenHeadingDeg(matrix(y = east, z = up), Surface.ROTATION_0))

    @Test
    fun raisedToLookAtItStillPointsWhereIFace() {
        for (tilt in listOf(30.0, 60.0, 80.0, 90.0)) {
            assertHeading(0.0, HeadingMath.screenHeadingDeg(looking(north, tilt), Surface.ROTATION_0))
        }
    }

    @Test
    fun tiltedJustPastUprightDoesNotFlipBackwards() {
        // 12시 축만 보면 이 자세에서 남쪽(뒤)을 가리킨다 — 사용자가 본 '반대'
        assertHeading(0.0, HeadingMath.screenHeadingDeg(looking(north, 100.0), Surface.ROTATION_0))
    }

    @Test
    fun facingWestWhileLooking() =
        assertHeading(270.0, HeadingMath.screenHeadingDeg(looking(-east, 45.0), Surface.ROTATION_0))

    @Test
    fun rightWristWithTheScreenTurned180PointsWhereIFace() {
        // 버튼 방향을 바꿔 화면을 180° 돌려 찬 경우: 기기의 12시(Y)는 몸 쪽(남), 화면의 위쪽은 북
        val flippedFacingNorth = matrix(y = -north, z = up)
        assertHeading(0.0, HeadingMath.screenHeadingDeg(flippedFacingNorth, Surface.ROTATION_180))
        // 손목을 들어 봐도 마찬가지
        val a = Math.toRadians(60.0)
        val raised = matrix(y = -(north * cos(a) + up * sin(a)), z = -north * sin(a) + up * cos(a))
        assertHeading(0.0, HeadingMath.screenHeadingDeg(raised, Surface.ROTATION_180))
    }

    @Test
    fun noReliableDirectionWhenTheScreenFacesTheGroundAtAnAngle() {
        // 화면 위쪽과 화면 앞쪽이 같은 수평 방향을 가리키면(화면을 비스듬히 땅으로) 바라보는 방향을 알 수 없다
        val s = sin(PI / 4)
        assertNull(HeadingMath.screenHeadingDeg(matrix(y = V(0.0, s, s), z = V(0.0, s, -s)), Surface.ROTATION_0))
    }

    @Test
    fun mapLabelGetsKakaosRangeMinus180To180() {
        // 카카오 지도 Label 회전은 정북 0, 오른쪽(시계) +, -180~180 (라디안)
        assertEquals(0.0, HeadingMath.labelRadians(0f).toDouble(), 1e-4)
        assertEquals(PI / 2, HeadingMath.labelRadians(90f).toDouble(), 1e-4)
        assertEquals(PI, HeadingMath.labelRadians(180f).toDouble(), 1e-4)
        assertEquals(-PI / 2, HeadingMath.labelRadians(270f).toDouble(), 1e-4)
        assertEquals(Math.toRadians(-1.0), HeadingMath.labelRadians(359f).toDouble(), 1e-4)
        assertEquals(Math.toRadians(-10.0), HeadingMath.labelRadians(-10f).toDouble(), 1e-4)
    }

    @Test
    fun turningAcrossTheBackDoesNotSpinTheLongWayRound() {
        // 179° → -179° 는 2°만 돈 것 — 숫자대로 애니메이션하면 반대쪽으로 358° 를 휘돈다(지도까지 같이 빙글)
        assertEquals(0, HeadingMath.rotationAnimMs(Math.toRadians(179.0).toFloat(), Math.toRadians(-179.0).toFloat(), 120))
        assertEquals(0, HeadingMath.rotationAnimMs(Math.toRadians(-170.0).toFloat(), Math.toRadians(175.0).toFloat(), 120))
        assertEquals(120, HeadingMath.rotationAnimMs(Math.toRadians(10.0).toFloat(), Math.toRadians(40.0).toFloat(), 120))
        assertEquals(120, HeadingMath.rotationAnimMs(Math.toRadians(-20.0).toFloat(), Math.toRadians(15.0).toFloat(), 120))
    }
}
