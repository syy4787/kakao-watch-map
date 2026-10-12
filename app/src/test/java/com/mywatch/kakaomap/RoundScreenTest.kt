package com.mywatch.kakaomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 원형 화면 배치 계산 검증 — 갤럭시 워치9 44mm(480×480px) 기준 */
class RoundScreenTest {

    private val screen = 480
    private val density = 2f
    private val button = (44 * density).toInt()
    private val inset = 4 * density

    private fun centerOf(angle: Float): Pair<Float, Float> {
        val (x, y) = RoundScreen.ringItemTopLeft(screen, screen, button, inset, angle)
        return Pair(x + button / 2f, y + button / 2f)
    }

    @Test
    fun buttonsStayInsideTheCircle() {
        val step = RoundScreen.ringAngleStep(screen, screen, button, inset, minGap = 8 * density)
        for (angle in listOf(180f - step, 180f, 180f + step)) {
            val (cx, cy) = centerOf(angle)
            val farthest = hypot(cx - screen / 2f, cy - screen / 2f) + button / 2f
            // 버튼의 가장 바깥 지점도 화면 원(반지름 240px) 안쪽, 여백만큼 떨어져 있어야 한다
            assertEquals(screen / 2f - inset, farthest, 0.5f)
        }
    }

    @Test
    fun bottomButtonIsCenteredAtSixOClock() {
        val (cx, cy) = centerOf(180f)
        assertEquals(screen / 2f, cx, 0.01f)
        assertTrue("6시 버튼은 화면 아래쪽", cy > screen * 0.8f)
    }

    @Test
    fun neighbouringButtonsDoNotOverlap() {
        for (size in listOf(396, 450, 480)) {
            val step = RoundScreen.ringAngleStep(size, size, button, inset, minGap = 8 * density)
            val a = RoundScreen.ringItemTopLeft(size, size, button, inset, 180f)
            val b = RoundScreen.ringItemTopLeft(size, size, button, inset, 180f + step)
            val distance = hypot(a.first - b.first, a.second - b.second)
            assertTrue("${size}px 화면에서 버튼 간격 부족: $distance", distance >= button + 8 * density - 0.5f)
        }
    }

    @Test
    fun bottomCardCornersStayInsideCircle() {
        val cardW = (screen * 0.62f).toInt()
        val bottom = RoundScreen.bottomForWidth(screen, screen, cardW, inset * 2)
        // 카드 아래 모서리(±폭/2, bottom)가 여백 안쪽 원 위에 있어야 한다
        val cornerDist = hypot(cardW / 2.0, bottom - screen / 2.0)
        assertEquals(screen / 2.0 - inset * 2, cornerDist, 0.5)
        assertTrue("카드가 화면 아래쪽에 있어야 함", bottom > screen * 0.8f)
        val top = RoundScreen.topForWidth(screen, screen, cardW, inset * 2)
        assertEquals(screen - bottom, top, 0.01f)
    }

    @Test
    fun inscribedSquareFitsInCircle() {
        val side = RoundScreen.inscribedSquareSide(screen, screen)
        assertEquals(339, side)
        // 정사각형 꼭짓점까지 거리 ≤ 반지름
        assertTrue(hypot(side / 2.0, side / 2.0) <= screen / 2.0)
    }
}
