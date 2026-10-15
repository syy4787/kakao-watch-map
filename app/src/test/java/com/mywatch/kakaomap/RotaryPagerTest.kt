package com.mywatch.kakaomap

import com.mywatch.kakaomap.ui.RotaryPager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shorts 재생 화면의 베젤·크라운: 한 칸 = 한 장 (시계 방향 = 다음 장). 베젤 한 칸이 굴리는 양은 기기마다 달라
 * 칸 수로 센다. 빨리 돌리면 [RotaryPager.GAP_MS] 마다 한 장 — 한 번에 여러 장을 건너뛰지 않게
 */
class RotaryPagerTest {

    @Test
    fun oneDetentIsOneShort() {
        val p = RotaryPager()
        assertEquals("시계 방향(음수) = 다음 장", 1, p.step(-1f, 1_000))
        assertEquals("반시계 방향 = 앞 장", -1, p.step(1f, 2_000))
        assertEquals("크라운의 작은 값도 한 장", 1, p.step(-0.1f, 3_000))
        assertEquals("움직임 없음", 0, p.step(0f, 4_000))
    }

    @Test
    fun spinningFastMovesOneShortPerGap() {
        val p = RotaryPager()
        assertEquals(1, p.step(-1f, 10_000))
        assertEquals("바로 다음 칸은 넘기지 않는다", 0, p.step(-1f, 10_000 + 50))
        assertEquals(0, p.step(-1f, 10_000 + RotaryPager.GAP_MS - 1))
        assertEquals(1, p.step(-1f, 10_000 + RotaryPager.GAP_MS))
        assertEquals("방향을 바꿔도 같은 간격", 0, p.step(1f, 10_000 + RotaryPager.GAP_MS + 10))
    }

    @Test
    fun theFirstDetentAlwaysCounts() {
        assertEquals(1, RotaryPager().step(-1f, 0))
    }
}
