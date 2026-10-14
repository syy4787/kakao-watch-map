package com.mywatch.kakaomap.location

import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 내 위치 부채꼴의 방위 계산 (순수 계산 — 단위 테스트로 검증).
 *
 * 예전에는 기기 12시 축(Y)의 수평 방향만 썼다. 워치를 보려고 손목을 들면 화면이 얼굴 쪽으로 서서 Y축이 거의
 * 하늘을 가리키므로 방위가 흔들리고, 조금만 더 세우면 뒤로 뒤집혔다. 또 오른손목에 버튼 방향을 바꿔 차면
 * 화면이 180° 돌아가 12시가 몸 쪽이 된다. 그래서 '화면의 위쪽(U) − 화면이 바라보는 쪽(Z)' 의 수평 방향을 쓴다:
 * 수평으로 놓으면 U, 똑바로 세우면 −Z(화면 뒤 = 내가 보는 앞)가 주로 쓰여 어떤 기울기에서도 앞을 가리킨다.
 * 갤럭시워치는 버튼 방향을 바꿔 차도 앱에 화면 회전을 알려 주지 않아(사용자: "정면을 보면 뒤를 본다고 뜸")
 * 화면의 위쪽이 실제로는 몸 쪽일 수 있다 — 그건 [WearSide] 가 센서로 알아내 [screenHeadingDeg] 의 flipped 로 준다.
 */
object HeadingMath {

    /** 바라보는 방향이 거의 수직이면(화면을 비스듬히 땅으로 등) 방위를 믿지 않는다 */
    private const val MIN_HORIZONTAL = 0.2

    /**
     * [r] = SensorManager.getRotationMatrixFromVector 의 3×3 행렬(기기 → 세계: 동·북·위, 행 우선).
     * [displayRotation] = 화면이 기기 기본 방향에서 돌아간 정도(Surface.ROTATION_*).
     * [flipped] = 화면의 위쪽이 실제로는 몸 쪽을 향한다([WearSide] 가 알아낸 것).
     * 돌려주는 값은 화면 위쪽이 가리키는 쪽에서 본 '내가 보는 방향'의 방위 0~360° (정북 0, 시계 방향), 모르면 null
     */
    fun screenHeadingDeg(r: FloatArray, displayRotation: Int, flipped: Boolean = false): Double? {
        val (ue, un) = screenUp(r, displayRotation)
        val s = if (flipped) -1.0 else 1.0
        val fe = s * ue - r[2]
        val fn = s * un - r[5]
        if (hypot(fe, fn) < MIN_HORIZONTAL) return null
        return (Math.toDegrees(atan2(fe, fn)) + 360) % 360
    }

    /** 화면의 위쪽이 가리키는 세계 방향의 (동, 북) 성분 — 시스템이 알려 준 화면 회전 기준 */
    internal fun screenUp(r: FloatArray, displayRotation: Int): Pair<Double, Double> {
        // 기기 축(열)의 세계 좌표: X = (r0, r3, r6), Y = (r1, r4, r7), Z = (r2, r5, r8)
        val (ue, un) = when (displayRotation) {
            Surface.ROTATION_90 -> -r[0] to -r[3]     // 화면 위쪽 = 기기 −X
            Surface.ROTATION_180 -> -r[1] to -r[4]    // 화면 위쪽 = 기기 −Y (오른손목에 화면을 뒤집어 찬 경우)
            Surface.ROTATION_270 -> r[0] to r[3]      // 화면 위쪽 = 기기 +X
            else -> r[1] to r[4]                      // 화면 위쪽 = 기기 +Y (12시)
        }
        return ue.toDouble() to un.toDouble()
    }

    /** GPS 가 잰 움직이는 방향 — [atMs] 는 받은 때 (부팅 후 ms) */
    data class Course(val bearingDeg: Float, val speedMps: Float, val accuracyDeg: Float?, val atMs: Long)

    /**
     * 손목 방향(센서) 대신 움직이는 방향(GPS)을 쓸지: 자전거·버스처럼 빠르게 움직이면 그쪽이 맞다 —
     * 자전거 손잡이를 잡으면 팔이 앞을 향해 화면으로 잰 방향이 90° 틀어지고, 버스에선 옆으로 앉기도 한다.
     * 걷는 속도에선 GPS 방향이 걸음마다 흔들려 손목 방향이 낫다
     */
    fun useCourse(c: Course?, nowMs: Long): Boolean {
        if (c == null) return false
        if (nowMs - c.atMs > COURSE_MAX_AGE_MS) return false
        if (c.speedMps < COURSE_MIN_SPEED_MPS) return false
        return c.accuracyDeg == null || c.accuracyDeg <= COURSE_MAX_ACCURACY_DEG
    }

    private const val COURSE_MAX_AGE_MS = 3_000L
    /** 시속 9km — 빨리 걷기보다 빠름 (자전거·차) */
    private const val COURSE_MIN_SPEED_MPS = 2.5f
    private const val COURSE_MAX_ACCURACY_DEG = 25f

    /** 카카오 지도 Label 회전값: 정북 0, 시계 방향 +, −180~180° 를 라디안으로 (카카오 데브톡 공식 답변 기준) */
    fun labelRadians(headingDeg: Float): Float {
        var d = headingDeg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return Math.toRadians(d.toDouble()).toFloat()
    }

    /**
     * 부채꼴을 [fromRad] → [toRad] 로 돌릴 때 애니메이션 시간. 뒤쪽(±180°)을 넘어가면 숫자대로 돌리면 반대쪽으로
     * 한 바퀴 가까이 휘도니(나침반 모드에선 지도까지 빙글) 그때는 바로 바꾼다
     */
    fun rotationAnimMs(fromRad: Float, toRad: Float, normalMs: Int): Int =
        if (abs(toRad - fromRad) > PI) 0 else normalMs
}
