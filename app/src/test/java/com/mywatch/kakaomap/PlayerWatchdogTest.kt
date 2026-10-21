package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.PlayerWatchdog
import com.mywatch.kakaomap.tube.PlayerWatchdog.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 재생 중 손대지 않으면: 2분 뒤 화면을 어둡게(소리만 듣는 경우가 대부분), 45분 뒤 멈춤(잊고 둔 재생·한 곡 반복·믹스 이어 듣기가
 * 몇 시간씩 화면을 켜 두지 않게). 손대면 밝게 돌리고 처음부터 센다. 다음 곡으로 넘어가는 잠깐의 멈춤은 그대로 이어 세고(깜빡이지 않음),
 * 멈춘 채 10초면 밝게, 30초 넘게 멈췄다 다시 틀면 새로 센다. 화면을 떠나면 바로 밝게
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
        // 다음 곡으로 넘어감: 엔진이 잠깐 멈췄다 다시 틂 — 밝아지지 않고 센 시간은 이어진다
        w.pause(20 * min)
        assertTrue(w.check(20 * min + 2_000).isEmpty())
        w.play(20 * min + 2_000)
        assertTrue(w.dimmed)
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
        assertEquals(listOf(Act.DIM), w.check(44 * min))
        assertTrue("만진 때부터 45분", w.check(48 * min).isEmpty())
        assertEquals(listOf(Act.BRIGHT, Act.PAUSE), w.check(49 * min))
    }

    @Test
    fun aRealPauseBrightensSoonAndALateResumeCountsAfresh() {
        val w = PlayerWatchdog(dim = true)
        w.play(0)
        w.check(3 * min)
        assertTrue(w.dimmed)
        w.pause(10 * min)
        assertEquals(10 * min + PlayerWatchdog.BRIGHT_AFTER_PAUSE_MS, w.nextCheckAt())
        assertEquals(listOf(Act.BRIGHT), w.check(10 * min + PlayerWatchdog.BRIGHT_AFTER_PAUSE_MS))
        assertNull(w.nextCheckAt())
        // 한참 뒤(손대지 않고 — 예: 엔진을 다시 열어 저절로) 다시 틂: 바로 어둡게·멈추지 않고 새로 센다
        w.play(60 * min)
        assertEquals(60 * min + PlayerWatchdog.DIM_AFTER_MS, w.nextCheckAt())
        assertTrue(w.check(60 * min + 1_000).isEmpty())
    }

    @Test
    fun leavingTheScreenBrightensAtOnce() {
        val w = PlayerWatchdog(dim = true)
        w.play(0)
        w.check(3 * min)
        assertEquals(listOf(Act.BRIGHT), w.stop())
        assertFalse(w.dimmed)
        assertNull(w.nextCheckAt())
        assertTrue(w.check(100 * min).isEmpty())
        assertTrue("밝을 때 떠나면 할 일 없음", PlayerWatchdog(dim = true).also { it.play(0) }.stop().isEmpty())
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
