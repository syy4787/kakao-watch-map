package com.mywatch.kakaomap.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 저전력 화면용 미니 지도 좌표 변환 (북쪽이 위).
 * 화면 반경 수백 m 범위라 평면 근사로 충분하다.
 * @param center 화면 가운데에 둘 좌표 (보통 내 위치)
 * @param metersPerPx 1픽셀이 몇 m 인지
 */
class MiniMapProjection(
    private val center: Coord,
    val metersPerPx: Double,
    private val cx: Float,
    private val cy: Float,
) {
    private val mPerDegLat = 110_574.0
    private val mPerDegLng = 111_320.0 * cos(Math.toRadians(center.lat))

    fun x(c: Coord): Float = (cx + (c.lng - center.lng) * mPerDegLng / metersPerPx).toFloat()
    fun y(c: Coord): Float = (cy - (c.lat - center.lat) * mPerDegLat / metersPerPx).toFloat()

    /** 화면 가운데에서 이 좌표까지 픽셀 거리 */
    fun distancePx(c: Coord): Float = hypot(x(c) - cx, y(c) - cy)

    /** 화면 가운데에서 이 좌표 쪽 방향 (라디안, 화면 좌표계: 0=오른쪽, 시계 방향 +) */
    fun angleTo(c: Coord): Float = atan2(y(c) - cy, x(c) - cx)

    companion object {
        /** 반지름 [radiusPx] 픽셀 원에 [radiusM] 미터가 들어가도록 */
        fun fit(center: Coord, radiusM: Double, radiusPx: Float, cx: Float, cy: Float) =
            MiniMapProjection(center, radiusM / radiusPx.coerceAtLeast(1f), cx, cy)
    }
}
