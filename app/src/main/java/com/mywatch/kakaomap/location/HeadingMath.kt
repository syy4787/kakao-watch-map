package com.mywatch.kakaomap.location

import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 내 위치 부채꼴의 방위 계산 (순수 계산 — 단위 테스트로 검증).
 *
 * 내가 보는 앞 = 화면의 12시(위쪽)가 가리키는 쪽. 사용자는 오른손목에 차고, 보려고 들면 버튼이 오른쪽(팔꿈치 쪽)을 향한다
 * — 화면을 돌리지 않은 기본 방향이라 버튼 축(기기 X)은 팔뚝을 따라 좌우로 놓이고, 앞은 그에 수직인 12시(기기 Y)다.
 * 버튼 쪽도, 그 반대쪽도 앞이 아니다.
 *
 * 12시 축만 쓰면 워치를 보려고 손목을 들어 화면이 얼굴 쪽으로 설 때 그 축이 거의 하늘을 가리켜 방위가 흔들리고 뒤로 뒤집힌다.
 * 그래서 '화면의 위쪽(U) − 화면이 바라보는 쪽(Z)' 의 수평 방향을 쓴다: 수평으로 놓으면 U, 똑바로 세우면 −Z(화면 뒤 = 내가 보는 앞)가
 * 주로 쓰여 어떤 기울기에서도 앞을 가리킨다. 화면이 돌아가 있으면(시스템이 알려 준 Surface.ROTATION_*) 그 위쪽을 쓴다.
 *
 * (2026-10-14 판은 '오른손목에 화면을 뒤집어 찼다'고 짐작해 센서로 앞뒤를 배웠다 — 사용자는 뒤집어 차지 않으며, 그 짐작은 지도를
 * 남에게 보여 주는 자세 같은 것에 속아 방향을 거꾸로 만들 수 있어 뺐다. 그때의 증상 "정면을 보면 뒤를 본다고 뜸"·오늘의
 * "버튼 쪽을 가리킴"은 북쪽이 위인 지도에서 부채꼴이 지도 위의 방위를 가리킨 것 — 이제 지도가 내가 보는 쪽으로 돈다.)
 */
object HeadingMath {

    /** 바라보는 방향이 거의 수직이면(화면을 비스듬히 땅으로 등) 방위를 믿지 않는다 */
    private const val MIN_HORIZONTAL = 0.2

    /**
     * [r] = SensorManager.getRotationMatrixFromVector 의 3×3 행렬(기기 → 세계: 동·북·위, 행 우선).
     * [displayRotation] = 화면이 기기 기본 방향에서 돌아간 정도(Surface.ROTATION_*).
     * 돌려주는 값은 '내가 보는 방향'의 방위 0~360° (정북 0, 시계 방향), 모르면 null
     */
    fun screenHeadingDeg(r: FloatArray, displayRotation: Int): Double? {
        val (ue, un) = screenUp(r, displayRotation)
        val fe = ue - r[2]
        val fn = un - r[5]
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
