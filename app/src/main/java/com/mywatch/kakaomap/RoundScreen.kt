package com.mywatch.kakaomap

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 원형 워치 화면 계산 도우미 (안드로이드 클래스를 쓰지 않는 순수 계산) */
object RoundScreen {

    /**
     * 원형 화면 테두리를 따라 놓을 버튼의 왼쪽 위 좌표(x, y)를 구한다.
     *
     * @param width     화면(부모 뷰) 너비 px
     * @param height    화면(부모 뷰) 높이 px
     * @param itemSize  버튼 지름 px
     * @param edgeInset 화면 원 테두리와 버튼 사이 여백 px
     * @param angleDeg  12시 = 0°, 시계 방향으로 증가 (3시 = 90°, 6시 = 180°, 9시 = 270°)
     */
    fun ringItemTopLeft(
        width: Int,
        height: Int,
        itemSize: Int,
        edgeInset: Float,
        angleDeg: Float,
    ): Pair<Float, Float> {
        val cx = width / 2f
        val cy = height / 2f
        val ringRadius = min(cx, cy) - edgeInset - itemSize / 2f
        val rad = Math.toRadians(angleDeg.toDouble())
        val centerX = cx + ringRadius * sin(rad).toFloat()
        val centerY = cy - ringRadius * cos(rad).toFloat()
        return Pair(centerX - itemSize / 2f, centerY - itemSize / 2f)
    }

    /**
     * 테두리 위에 나란히 놓은 버튼끼리 [minGap] 이상 떨어지도록 하는 버튼 사이 각도(도).
     * 화면이 작을수록 각도가 커지고, 44mm 워치에선 [minStepDeg] 가 그대로 쓰인다.
     */
    fun ringAngleStep(
        width: Int,
        height: Int,
        itemSize: Int,
        edgeInset: Float,
        minGap: Float,
        minStepDeg: Float = 36f,
    ): Float {
        val ringRadius = min(width, height) / 2f - edgeInset - itemSize / 2f
        if (ringRadius <= 0f) return minStepDeg
        val chord = (itemSize + minGap).coerceAtMost(2f * ringRadius)
        val needed = Math.toDegrees(2.0 * asin(chord / (2.0 * ringRadius))).toFloat()
        return maxOf(minStepDeg, needed)
    }

    /**
     * 폭이 [itemWidth] 인 상자를 화면 아래쪽에 놓을 때, 아래 모서리가 원 밖으로 나가지 않는
     * 가장 낮은 '아래쪽 y' (위에서부터 px). 위쪽에 놓을 때는 [topForWidth].
     */
    fun bottomForWidth(width: Int, height: Int, itemWidth: Int, edgeInset: Float): Float {
        val r = min(width, height) / 2f - edgeInset
        val half = itemWidth / 2f
        val dy = if (half >= r) 0f else sqrt(r * r - half * half)
        return height / 2f + dy
    }

    fun topForWidth(width: Int, height: Int, itemWidth: Int, edgeInset: Float): Float =
        height - bottomForWidth(width, height, itemWidth, edgeInset)

    /** 원 안에 꼭 들어가는 정사각형의 한 변 길이 (지름 ÷ √2 ≈ 70.7%) — 글자를 안전하게 둘 영역 */
    fun inscribedSquareSide(width: Int, height: Int): Int =
        (min(width, height) / sqrt(2.0)).toInt()
}
