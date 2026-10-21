package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면이 하루에 몇 번 켜졌고, 그중 몇 번이 알림 때문인지·몇 번이 금방 꺼졌는지(손목 올려 켜기 오작동 의심) —
 * 사용 통계의 화면 켜짐 구간과 알림 이벤트로. 많으면 무엇을 끄면 되는지와 하루 몇 %인지 어림을 준다.
 */
class WakeStatsTest {
    private val t0 = 1_760_000_000_000L
    private val day = 86_400_000L
    private val s = 1_000L

    @Test
    fun countsWakesShortWakesAndNotificationWakes() {
        val screen = ArrayList<Span>()
        val notes = ArrayList<Pair<String, Long>>()
        var t = t0 + 60_000L
        // 손목 들 때 저절로 켜졌다 2초 만에 꺼진 것 200번
        repeat(200) { screen += Span(t, t + 2 * s); t += 5 * 60_000L / 2 }
        // 알림이 켠 화면 60번 (알림 0.5초 뒤 켜져 6초), 카카오톡 50 · Gmail 10
        repeat(60) { k ->
            val pkg = if (k < 50) "com.kakao.talk" else "com.google.android.gm"
            notes += pkg to t
            screen += Span(t + 500, t + 6 * s)
            t += 4 * 60_000L
        }
        // 직접 본 긴 화면 20번 (1분)
        repeat(20) { screen += Span(t, t + 60 * s); t += 10 * 60_000L }
        // 화면을 안 켠 알림(이미 켜져 있었거나 조용한 것)
        notes += "com.kakao.talk" to t + 1_000_000L
        val w = Wakes.analyze(screen, notes, t0, t0 + day, screenPph = 24.0)
        assertEquals(280, w.wakes)
        assertEquals(200, w.shortWakes)
        assertEquals(60, w.notificationWakes)
        assertEquals(61, w.notifications)
        assertEquals("com.kakao.talk" to 51, w.topNotifiers.first())
        assertEquals(200, w.gestureLikeWakes)
        // 2초 × 200번 = 400초 ≈ 0.11시간 × 24%/h ≈ 2.7% + 깨어날 때 드는 몫
        assertTrue("${w.gestureCostPercent}", w.gestureCostPercent in 2.5..6.0)
        val advice = w.advice()
        assertTrue(advice.toString(), advice.any { it.contains("손목") && it.contains("200번") })
        assertTrue(advice.toString(), advice.any { it.contains("알림") && it.contains("com.kakao.talk") })
    }

    @Test
    fun aQuietDayHasNoAdvice() {
        val screen = (0 until 30).map { k -> Span(t0 + k * 40 * 60_000L, t0 + k * 40 * 60_000L + 30 * s) }   // 하루에 30번, 30초씩
        val w = Wakes.analyze(screen, emptyList(), t0, t0 + day, screenPph = null)
        assertEquals(30, w.wakes)
        assertEquals(0, w.shortWakes)
        assertTrue(w.advice().isEmpty())
    }

    @Test
    fun spansOutsideTheWindowAreIgnored() {
        val screen = listOf(Span(t0 - 10 * s, t0 - 5 * s), Span(t0 + s, t0 + 2 * s))
        val w = Wakes.analyze(screen, listOf("x" to t0 - 20 * s), t0, t0 + day, screenPph = null)
        assertEquals(1, w.wakes)
        assertEquals(0, w.notifications)
    }
    /**
     * 알림이 잠깐 켠 화면(알림 3초 안에 켜져 15초 안에 꺼짐)은 '사람이 봤다'가 아니다 — 지킴이가 새벽 알림 한 번에 밤 모드를 끝내지 않게.
     * 알림 뒤라도 오래 켜져 있었으면(읽음) 남긴다
     */
    @Test
    fun notificationFlashesAreNotPeopleLooking() {
        val t0 = 1_792_000_000_000L
        val flash = Span(t0 + 1_000, t0 + 9_000)               // 알림 1초 뒤 켜져 8초
        val read = Span(t0 + 60_000 + 1_000, t0 + 60_000 + 40_000)   // 알림 뒤 39초 — 읽음
        val raised = Span(t0 + 120_000, t0 + 125_000)          // 알림 없이 5초 — 손목을 듦
        val kept = Wakes.withoutNotificationFlashes(listOf(flash, read, raised), listOf(t0, t0 + 60_000))
        assertEquals(listOf(read, raised), kept)
        assertEquals(listOf(flash), Wakes.withoutNotificationFlashes(listOf(flash), emptyList()))
    }
}
