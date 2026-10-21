package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

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
        val w = Wakes.analyze(screen, notes, t0, t0 + day, screenPph = 24.0, zone = ZoneId.of("UTC"))
        assertEquals(280, w.wakes)
        assertEquals(200, w.shortWakes)
        assertEquals(60, w.notificationWakes)
        assertEquals(61, w.notifications)
        assertEquals("com.kakao.talk" to 51, w.topNotifiers.first())
        assertEquals(200, w.gestureLikeWakes)
        // 2초 × 200번 = 400초 ≈ 0.11시간 × 24%/h ≈ 2.7% + 깨어날 때 드는 몫
        assertTrue("${w.gestureCostPercent}", w.gestureCostPercent in 2.5..6.0)
        val advice = w.advice()
        // 낮(9~17시 UTC)의 짧은 켜짐은 시계를 본 것 — 손목 올려 켜기를 끄라고 하지 않는다
        assertTrue(advice.toString(), advice.none { it.contains("손목") })
        assertTrue(advice.toString(), advice.any { it.contains("알림") && it.contains("com.kakao.talk") })
        // 알림은 그대로 받고 화면만 안 켜지게 — 삼성 안내의 이름 그대로 (웨어러블 앱 › 워치 설정 › 알림 › 고급 설정 › '알림 받으면 화면 켜기')
        val (title, detail) = w.notificationTodo()!!
        assertTrue(title, title.contains("60번") && title.contains("하루 ") && TodoText.fitsTitle(title))
        // 삼성 안내의 경로 그대로 (워치 설정 › 를 빼먹지 않게 — 리뷰에서 찾음)
        assertTrue(detail, detail.contains("웨어러블 앱 › 워치 설정 › 알림 › 고급 설정 › '알림 받으면 화면 켜기' 끄기") && TodoText.fitsDetail(detail))
        assertTrue(advice.toString(), advice.any { it.contains("'알림 받으면 화면 켜기'") && it.contains("알림은 그대로") })
        // 삼성 배터리 안내는 같은 곳의 '세부 내용 자동 보기'도 끄라고 한다 — 한국어 메뉴 이름 그대로
        assertTrue(advice.toString(), advice.any { it.contains("'알림 수신 8초 내 확인 시 상세 내용 바로보기'") })
    }

    /**
     * 낮에 금방 꺼진 화면은 시계를 본 것 — 오작동으로 세지 않는다. 밤(0~6시)에 저절로 켜진 것이 많을 때만 '밤에만 끄기'를 권한다
     * (점검에서 찾음: 시계 보기를 오작동으로 세어 손목 올려 켜기를 통째로 끄라고 하던 것)
     */
    @Test
    fun onlyNightWakesCountAsMisfires() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 20, h, m).atZone(zone).toInstant().toEpochMilli()
        val glances = (0 until 150).map { k -> at(8, 0) + k * 5 * 60_000L }.map { Span(it, it + 3 * s) }   // 8시부터 5분마다 3초
        val w = Wakes.analyze(glances, emptyList(), at(0, 0), at(23, 59), 20.0, zone)
        assertEquals(150, w.gestureLikeWakes)
        assertEquals(0, w.nightGestureWakes)
        assertNull(w.gestureAdvice())
        assertNull(w.gestureTodo())
        val night = (0 until 24).map { k -> at(1, 0) + k * 10 * 60_000L }.map { Span(it, it + 2 * s) }   // 1~5시 10분마다 2초
        val n = Wakes.analyze(glances + night, emptyList(), at(0, 0), at(23, 59), 20.0, zone)
        assertEquals(24, n.nightGestureWakes)
        assertTrue(n.gestureAdvice()!!.contains("밤에만"))
        val (title, detail) = n.gestureTodo()!!
        assertTrue(title, TodoText.fitsTitle(title))
        assertTrue(detail, TodoText.fitsDetail(detail))
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
