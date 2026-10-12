package com.mywatch.kakaomap.nav

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo

/**
 * 멈춤/움직임 판단 (위치를 얼마나 자주 받을지 = 배터리). 순수 계산이라 단위 테스트로 검증한다.
 *  · GPS 위치는 도플러 속도가 있어 그대로 쓴다: 0.4m/s 아래 '멈춤', 0.9m/s 넘어야 '움직임' (경계에서 깜빡이지 않게)
 *  · 속도가 없는 Wi-Fi·기지국 위치는 수십 m 씩 튄다. 튄 것을 '걷기 시작'으로 보면 GPS 를 켰다 껐다 반복하므로,
 *    멈춰 있을 땐 '멈춘 곳'에서 두 위치의 오차 + 20m 보다 확실히 멀어졌을 때만 움직임으로 본다.
 *    움직이는 중엔 두 위치 사이 거리/시간 (멈춤으로 바뀌는 건 주로 GPS 속도가 정한다)
 */
class StillDetector(
    private val stillMps: Float = LocationPolicy.STILL_MPS,
    private val movingMps: Float = MOVING_MPS,
) {
    var still = false
        private set

    /** 최근 속도 m/s (멈춤이면 0, 아직 모르면 null) */
    var speedMps: Float? = null
        private set

    private class Sample(val timeMs: Long, val c: Coord, val acc: Float)

    private var prev: Sample? = null
    /** 멈춘 곳 */
    private var anchor: Sample? = null

    /**
     * @param timeMs 위치를 잰 시각 (부팅 후 ms)
     * @param accuracyM 위치 오차 m (모르면 null)
     * @param speed GPS 가 준 속도 m/s (없으면 null)
     */
    fun update(timeMs: Long, c: Coord, accuracyM: Float?, speed: Float?) {
        val acc = accuracyM?.takeIf { !it.isNaN() && it > 0f } ?: UNKNOWN_ACCURACY_M
        val cur = Sample(timeMs, c, acc)
        val raw: Float? = when {
            speed != null && !speed.isNaN() -> speed
            still -> anchor?.let { a ->
                if (Geo.distanceM(a.c, c) > a.acc + acc + MOVE_MARGIN_M) movingMps + 0.1f else 0f
            }
            else -> prev?.let { p ->
                val dt = (timeMs - p.timeMs) / 1000f
                if (dt in 0.5f..MAX_GAP_S) Geo.distanceM(p.c, c).toFloat() / dt else null
            }
        }
        prev = cur
        if (raw == null) return
        if (raw < stillMps) still = true else if (raw > movingMps) still = false
        speedMps = if (still) 0f else raw
        anchor = if (still) anchor ?: cur else null
    }

    companion object {
        const val MOVING_MPS = 0.9f
        private const val MOVE_MARGIN_M = 20f
        private const val UNKNOWN_ACCURACY_M = 30f
        private const val MAX_GAP_S = 120f
    }
}
