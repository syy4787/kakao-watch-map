package com.mywatch.kakaomap.location

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * '내가 보는 쪽이 위'(나침반) 지도가 정말 그쪽으로 도는지 화면 좌표로 확인한다 (순수 계산 — 단위 테스트로 검증).
 *
 * 카카오 SDK 는 Label 회전만 '정북 0, 시계 방향 +, 라디안'으로 밝혀 두었고(데브톡 공식 답변) 지도(카메라) 회전값과
 * '라벨 따라 회전'(TrackingManager.setTrackingRotation)이 어느 쪽으로 도는지는 문서에 없다. 그래서 지도 가운데와
 * 그 북쪽 점을 화면 좌표로 바꿔(KakaoMap.toScreenPoint) 화면 위쪽이 실제로 가리키는 방위를 재고 [MapTurnCheck] 로 맞춘다.
 */
object MapTurn {

    /**
     * 화면 위쪽이 가리키는 방위(0~360°, 정북 0, 시계 방향). ([cx],[cy]) = 지도 가운데, ([nx],[ny]) = 그 북쪽 점의 화면 좌표
     * (y 는 아래로 +). 두 점이 너무 가까우면 null
     */
    fun screenTopBearing(cx: Float, cy: Float, nx: Float, ny: Float): Double? {
        val dx = (nx - cx).toDouble()
        val dy = (ny - cy).toDouble()
        if (hypot(dx, dy) < MIN_PIXELS) return null
        // 북쪽이 화면에서 시계 방향으로 α 만큼 돌아 보이면 화면 위쪽의 방위는 −α
        val alpha = Math.toDegrees(atan2(dx, -dy))
        return norm360(-alpha)
    }

    fun norm360(d: Double): Double = ((d % 360) + 360) % 360

    fun norm180(d: Double): Double {
        val x = norm360(d)
        return if (x > 180) x - 360 else x
    }

    /** 두 방위의 차이 0~180° */
    fun diff(a: Double, b: Double): Double = abs(norm180(a - b))

    private const val MIN_PIXELS = 4.0
}

/**
 * 지도 회전 방향을 배우고 지킨다.
 *  · [sign]: 카메라 회전값(라디안)을 도로 바꿨을 때 화면 위쪽 방위가 +회전값이면 +1, −회전값이면 −1. 화면에서 잰 위쪽과
 *    그때의 회전값이 3번 연속 반대 쪽으로 맞으면 바꾼다 (0°·180° 근처는 양쪽이 같아 보여 배우지 않는다)
 *  · [trackingBroken]: SDK 의 '라벨 따라 회전'으로 돌린 지도의 위쪽이 내가 보는 방향의 거울(−방위)로 3번 나오면 true —
 *    그때부터는 SDK 에 맡기지 않고 [cameraRadians] 로 직접 돌린다
 */
class MapTurnCheck(sign: Int = 1, trackingBroken: Boolean = false) {
    var sign: Int = if (sign < 0) -1 else 1
        private set
    var trackingBroken: Boolean = trackingBroken
        private set
    private var votesAgainst = 0
    private var mirrored = 0

    /** 화면 위쪽이 [bearingDeg] 가 되게 하는 카메라 회전값(라디안, −π~π) */
    fun cameraRadians(bearingDeg: Double): Double = Math.toRadians(MapTurn.norm180(bearingDeg)) * sign

    /**
     * 한 번 잰 것: [rotationRad] = 지금 카메라 회전값, [topDeg] = 화면에서 잰 위쪽 방위, [shownDeg] = 위로 오게 하려던 방위
     * (조금 전에 준 값 — 지도가 따라올 시간을 둔 것), [trackingRotation] = 지금 SDK 의 '라벨 따라 회전'으로 돌리는 중.
     * 판단이 바뀌었으면 true (저장하고 다시 돌릴 것)
     */
    fun observe(rotationRad: Double, topDeg: Double, shownDeg: Double, trackingRotation: Boolean): Boolean {
        var changed = false
        val r = MapTurn.norm180(Math.toDegrees(rotationRad))
        if (abs(r) in LEARN_MIN..LEARN_MAX) {
            val plus = MapTurn.diff(topDeg, r)
            val minus = MapTurn.diff(topDeg, -r)
            val vote = when {
                plus < AGREE && minus > DISAGREE -> 1
                minus < AGREE && plus > DISAGREE -> -1
                else -> 0
            }
            // 3번 '연속'으로 반대여야 바꾼다 (애매한 값이 끼면 처음부터)
            if (vote == 0 || vote == sign) votesAgainst = 0
            else if (++votesAgainst >= VOTES) {
                sign = vote
                votesAgainst = 0
                changed = true
            }
        }
        if (trackingRotation && !trackingBroken) {
            val w = MapTurn.norm180(shownDeg)
            if (abs(w) in MIRROR_MIN..MIRROR_MAX) {
                val off = MapTurn.diff(topDeg, w)
                val mirror = MapTurn.diff(topDeg, -w)
                // 거울(−방위)이 3번 '연속'일 때만 — 지도가 아직 따라오는 중인 값은 거울이 아니다
                if (off > MIRROR_OFF && mirror < AGREE + 10) {
                    if (++mirrored >= VOTES) {
                        trackingBroken = true
                        changed = true
                    }
                } else {
                    mirrored = 0
                }
            }
        }
        return changed
    }

    private companion object {
        const val LEARN_MIN = 20.0
        const val LEARN_MAX = 160.0
        const val MIRROR_MIN = 30.0
        const val MIRROR_MAX = 150.0
        const val AGREE = 15.0
        const val DISAGREE = 30.0
        const val MIRROR_OFF = 45.0
        const val VOTES = 3
    }
}
