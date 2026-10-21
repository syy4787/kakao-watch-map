package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.PlayerWatchdog
import com.mywatch.kakaomap.tube.PlayerWatchdog.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 재생 중 손대지 않으면: 2분 뒤 화면을 어둡게(소리만 듣는 경우가 대부분), 45분 뒤 멈춤(잊고 둔 재생·한 곡 반복·믹스 이어 듣기가
 * 몇 시간씩 화면을 켜 두지 않게). 손대면 밝게 돌리고 처음부터 센다. 다음 곡으로 저절로 넘어가도 센 시간은 이어진다
 */
class PlayerWatchdogTest {
    private val min = 60_000L

    @Test
    fun dimsAfterTwoMinutesAndPausesAfterFortyFiveWithoutATouch() {
        val w = PlayerWatchdog(dim = true)
        assertNull("재생 전엔 볼 일 없음", w.nextCheckAt())
        w.input(0)
        w.play(10_000)
        assertEquals(PlayerWatchdog.DIM_AFTER_MS, w.nextCheckAt())
        assertTrue(w.check(1 * min).isEmpty())
        assertEquals(listOf(Act.DIM), w.check(2 * min))
        assertTrue(w.dimmed)
        assertEquals(PlayerWatchdog.PAUSE_AFTER_MS, w.nextCheckAt())
        // 다음 곡으로 저절로 넘어감 — 센 시간은 이어진다
        w.play(20 * min)
        assertEquals(listOf(Act.BRIGHT, Act.PAUSE), w.check(45 * min))
        assertNull(w.nextCheckAt())
    }

    @Test
    fun aTouchBrightensAndStartsCountingAgain() {
        val w = PlayerWatchdog(dim = true)
        w.input(0)
        w.play(0)
        assertEquals(listOf(Act.DIM), w.check(3 * min))
        assertEquals(listOf(Act.BRIGHT), w.input(4 * min))
        assertEquals(4 * min + PlayerWatchdog.DIM_AFTER_MS, w.nextCheckAt())
        assertTrue(w.check(44 * min).let { it == listOf(Act.DIM) })
        assertTrue("만진 때부터 45분", w.check(48 * min).isEmpty())
        assertEquals(listOf(Act.BRIGHT, Act.PAUSE), w.check(49 * min))
    }

    @Test
    fun pausingOrLeavingStopsTheWatchAndBrightensBack() {
        val w = PlayerWatchdog(dim = true)
        w.play(0)
        w.check(3 * min)
        assertEquals(listOf(Act.BRIGHT), w.pause())
        assertNull(w.nextCheckAt())
        assertTrue(w.check(100 * min).isEmpty())
        assertTrue("밝을 때 멈추면 할 일 없음", PlayerWatchdog(dim = true).also { it.play(0) }.pause().isEmpty())
    }

    @Test
    fun shortsAreWatchedSoTheyAreNotDimmed() {
        val w = PlayerWatchdog(dim = false)
        w.play(0)
        assertEquals(PlayerWatchdog.PAUSE_AFTER_MS, w.nextCheckAt())
        assertTrue(w.check(10 * min).isEmpty())
        assertEquals(listOf(Act.PAUSE), w.check(45 * min))
    }
}
