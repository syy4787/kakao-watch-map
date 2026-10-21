package com.mywatch.kakaomap

import android.view.Surface
import com.mywatch.kakaomap.location.HeadingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 내 위치 부채꼴이 '내가 보는 방향'을 가리키는지.
 * 워치를 보려고 손목을 들면 화면이 얼굴 쪽으로 서는데, 12시 방향(Y축)만으로 방위를 재면 거의 하늘을 가리켜
 * 방향이 마구 흔들리거나 뒤집혔다(사용자: "반대였다가 비스듬했다가 난리").
 *
 * 사용자가 차는 법(2026-10-21): 오른손목, 보려고 들면 버튼이 오른쪽(팔꿈치 쪽)을 향한다 = 화면을 돌리지 않은 기본 방향.
 * 그러면 버튼 축(기기 X)은 팔뚝을 따라 좌우로 놓이고, 내가 보는 앞은 화면의 12시(기기 Y) 쪽이다 — 버튼 쪽도 그 반대도 아니다.
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

    /**
     * 사용자의 실제 자세: 오른손목, 팔뚝을 가슴 앞으로 가로질러(손은 왼쪽, 팔꿈치는 오른쪽) 화면을 [tiltDeg] 만큼 얼굴 쪽으로 세움.
     * 버튼(기기 +X)은 팔꿈치 쪽 = 내 오른쪽. [armDeg] = 손이 앞쪽으로 들린 정도(팔뚝이 정확히 좌우가 아닐 때)
     */
    private fun rightWristLooking(forward: V, tiltDeg: Double, armDeg: Double = 0.0): FloatArray {
        val right = forward cross up                       // 앞을 보고 선 내 오른쪽
        val a = Math.toRadians(armDeg)
        val t = Math.toRadians(tiltDeg)
        val y0 = forward * cos(a) + right * sin(a)          // 화면 12시(수평일 때) — 팔뚝에 수직, 몸 바깥쪽
        return matrix(y = y0 * cos(t) + up * sin(t), z = -y0 * sin(t) + up * cos(t))
    }

    private fun bearingOf(e: Double, n: Double) = (Math.toDegrees(kotlin.math.atan2(e, n)) + 360) % 360

    @Test
    fun rightWristButtonsFacingRightPointsWhereIFace() {
        for (forward in listOf(north, east, -north, -east)) {
            val expected = bearingOf(forward.e, forward.n)
            for (tilt in listOf(0.0, 15.0, 30.0, 45.0, 60.0, 80.0)) {
                val r = rightWristLooking(forward, tilt)
                // 버튼(기기 X)은 정말 내 오른쪽을 향한다 — 사용자가 말한 그대로
                assertHeading((expected + 90) % 360, bearingOf(r[0].toDouble(), r[3].toDouble()))
                assertHeading(expected, HeadingMath.screenHeadingDeg(r, Surface.ROTATION_0))
            }
        }
    }

    @Test
    fun theButtonAxisIsNeverTheAnswer() {
        // 어떤 기울기에서도 부채꼴은 버튼 쪽(오른쪽, +90°)도 그 반대(왼쪽, −90°)도 아니다
        for (tilt in 0..85 step 5) {
            val h = HeadingMath.screenHeadingDeg(rightWristLooking(north, tilt.toDouble()), Surface.ROTATION_0)!!
            val offRight = ((h - 90 + 540) % 360) - 180
            val offLeft = ((h - 270 + 540) % 360) - 180
            assertTrue("기울기 $tilt° 에서 $h°", kotlin.math.abs(offRight) > 80 && kotlin.math.abs(offLeft) > 80)
        }
    }

    @Test
    fun aForearmHeldAtAnAngleTurnsTheConeByThatMuch() {
        // 손이 앞쪽으로 20° 들린 채 보면 12시도 오른쪽으로 20° — 손목으로 재는 방향의 타고난 한계(크게 틀리지 않음)
        assertHeading(20.0, HeadingMath.screenHeadingDeg(rightWristLooking(north, 35.0, armDeg = 20.0), Surface.ROTATION_0))
    }

    @Test
    fun noReliableDirectionWhenTheScreenFacesTheGroundAtAnAngle() {
        // 화면 위쪽과 화면 앞쪽이 같은 수평 방향을 가리키면(화면을 비스듬히 땅으로) 바라보는 방향을 알 수 없다
        val s = sin(PI / 4)
        assertNull(HeadingMath.screenHeadingDeg(matrix(y = V(0.0, s, s), z = V(0.0, s, -s)), Surface.ROTATION_0))
    }

    // ── 움직이는 방향(GPS) — 자전거 손잡이를 잡으면 팔이 앞을 향해 워치 화면으로 잰 방향이 90° 틀어진다 ──

    @Test
    fun ridingOrCyclingFastUsesTheGpsCourse() {
        val now = 100_000L
        val bike = HeadingMath.Course(bearingDeg = 75f, speedMps = 5f, accuracyDeg = 10f, atMs = now - 1_000)
        assertTrue(HeadingMath.useCourse(bike, now))
        // 걷는 속도에선 손목 방향(센서)이 더 정확하다 (GPS 방향은 걸음마다 흔들림)
        assertFalse(HeadingMath.useCourse(bike.copy(speedMps = 1.3f), now))
        // 오래된 방향·흐린 방향은 안 씀
        assertFalse(HeadingMath.useCourse(bike.copy(atMs = now - 5_000), now))
        assertFalse(HeadingMath.useCourse(bike.copy(accuracyDeg = 40f), now))
        // 정확도를 모르면 속도만 충분하면
        assertTrue(HeadingMath.useCourse(bike.copy(accuracyDeg = null), now))
        assertFalse(HeadingMath.useCourse(null, now))
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
