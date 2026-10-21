package com.mywatch.kakaomap

import com.mywatch.kakaomap.nav.ProbeBackoff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 저전력 위치로 쉬는 동안의 GPS 확인(30초): 실내라 못 잡으면 4분 → 8분 → 16분 간격으로 늘린다 (50분 동안 30초씩 12번 켜지 않게).
 * 잡히면 다시 4분부터
 */
class ProbeBackoffTest {
    private val min = 60_000L

    @Test
    fun missesStretchTheIntervalAndAHitResetsIt() {
        val b = ProbeBackoff()
        assertEquals(4 * min, b.nextDelayMs())
        b.missed()
        assertEquals(8 * min, b.nextDelayMs())
        b.missed()
        assertEquals(16 * min, b.nextDelayMs())
        b.missed()
        b.missed()
        assertEquals("16분이 끝", 16 * min, b.nextDelayMs())
        b.found()
        assertEquals(4 * min, b.nextDelayMs())
    }
}
