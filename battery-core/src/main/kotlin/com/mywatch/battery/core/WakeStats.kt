package com.mywatch.battery.core

import java.time.Instant
import java.time.ZoneId

/**
 * 하루 동안 화면이 켜진 횟수. [shortWakes] = [Wakes.SHORT_MS] 안에 꺼진 것, [notificationWakes] = 알림 직후([Wakes.NOTIF_WINDOW_MS] 안)
 * 켜진 것, [gestureLikeWakes] = 알림 없이 금방 꺼진 것 — 낮엔 대개 시계를 본 것(손목 올려 켜기의 쓰임)이라 그 자체로는 조언하지 않는다.
 * [nightGestureWakes] = 그중 밤(0~6시)의 것 — 자는 동안 손목이 움직여 저절로 켜진 것으로 보인다.
 */
data class WakeStats(
    val wakes: Int,
    val shortWakes: Int,
    val notificationWakes: Int,
    val gestureLikeWakes: Int,
    val gestureLikeMs: Long,
    /** 화면을 켤 수 있는 알림 수 */
    val notifications: Int,
    /** 알림을 많이 보낸 앱 (많은 순) */
    val topNotifiers: List<Pair<String, Int>>,
    /** 화면 켜진 동안 시간당 % (모르면 null) */
    val screenPph: Double?,
    /** 밤(0~6시)에 알림 없이 금방 꺼진 화면 수와 그 시간 */
    val nightGestureWakes: Int = 0,
    val nightGestureMs: Long = 0,
) {
    /** 금방 꺼진 화면들이 하루에 쓴 % 어림 — 켜진 시간 × 화면 소모율 + 한 번 깨어날 때마다 드는 몫 */
    val gestureCostPercent: Double
        get() = gestureLikeMs / 3_600_000.0 * (screenPph ?: Wakes.DEFAULT_SCREEN_PPH) + gestureLikeWakes * Wakes.WAKE_OVERHEAD_PERCENT

    /** 밤에 저절로 켜진 화면들이 쓴 % 어림 */
    val nightGestureCostPercent: Double
        get() = nightGestureMs / 3_600_000.0 * (screenPph ?: Wakes.DEFAULT_SCREEN_PPH) + nightGestureWakes * Wakes.WAKE_OVERHEAD_PERCENT

    val notificationCostPercent: Double
        get() = notificationWakes * (Wakes.NOTIF_SCREEN_MS / 3_600_000.0 * (screenPph ?: Wakes.DEFAULT_SCREEN_PPH) + Wakes.WAKE_OVERHEAD_PERCENT)

    /** 많을 때만: 무엇을 끄면 되는지. [label] = 패키지 이름 → 보일 이름 */
    fun advice(label: (String) -> String = { it }): List<String> = listOfNotNull(gestureAdvice(), notificationAdvice(label))

    /**
     * 밤에 저절로 켜진 화면이 많을 때 — 낮의 짧은 켜짐은 시계를 본 것이라 세지 않는다(점검에서 찾음: 낮에 시계 보는 것을 '오작동'으로 세어
     * 손목 올려 켜기를 통째로 끄라고 하던 것). 밤에만 끄는 길(취침 모드·지킴이)을 권한다
     */
    fun gestureAdvice(): String? = if (nightGestureWakes < Wakes.NIGHT_GESTURE_ADVICE) null else
        "밤(0~6시)에 알림 없이 5초 안에 꺼진 화면 ${nightGestureWakes}번 — 자는 동안 손목이 움직여 저절로 켜진 것으로 보입니다. " +
            "취침 모드를 켜거나 지킴이 '밤에 손목·터치 켜기 끄기'로 밤에만 끄면 하루 약 ${DrainAnalyzer.fmt(nightGestureCostPercent)}% 아낍니다 " +
            "(낮에 시계를 볼 때 켜지는 것은 그대로)"

    /** [gestureAdvice] 의 '지금 할 일' 줄 */
    fun gestureTodo(): Pair<String, String>? = if (nightGestureWakes < Wakes.NIGHT_GESTURE_ADVICE) null else
        "밤에 저절로 켜진 화면 ${nightGestureWakes}번" to
            "취침 모드나 지킴이 '밤에 손목·터치 켜기 끄기'로 밤에만 끄기 (하루 약 ${DrainAnalyzer.fmt(nightGestureCostPercent)}%)"

    /**
     * 알림이 화면을 켠 일이 많을 때 (폰의 Galaxy Wearable 앱). 삼성 안내(배터리가 빨리 닳을 때·알림 설정): 웨어러블 앱 › 워치 설정 ›
     * 알림 › 고급 설정 › '알림 받으면 화면 켜기'를 끄면 알림은 그대로 오고 화면만 안 켜진다 — 앱 알림을 통째로 끄는 것보다 먼저.
     * 삼성 배터리 안내는 같은 곳의 '알림 수신 8초 내 확인 시 상세 내용 바로보기'(영문 'Show details automatically …')도 끄라고 한다
     */
    fun notificationAdvice(label: (String) -> String = { it }): String? {
        if (notificationWakes < Wakes.NOTIF_ADVICE) return null
        val top = topNotifiers.take(2).joinToString(", ") { "${label(it.first)} ${it.second}번" }
        return "알림이 화면을 ${notificationWakes}번 켬 (많이 보낸 앱: $top) — 폰의 Galaxy Wearable 앱 › 워치 설정 › 알림 › 고급 설정에서 " +
            "'알림 받으면 화면 켜기'를 끄면(알림은 그대로 옴) 하루 약 ${DrainAnalyzer.fmt(notificationCostPercent)}% 아낍니다. " +
            "같은 곳의 '알림 수신 8초 내 확인 시 상세 내용 바로보기'도 끄면 알림을 볼 때 화면이 덜 켜져 있습니다(삼성 배터리 안내). " +
            "한 앱이 대부분이면 그 앱의 워치 알림만 꺼도 됩니다"
    }

    /** [notificationAdvice] 의 '지금 할 일' 줄 — 알림은 그대로 받고 화면만 안 켜지게 (앱 알림을 끄라고 하지 않는다) */
    fun notificationTodo(): Pair<String, String>? {
        if (notificationWakes < Wakes.NOTIF_ADVICE) return null
        // 경로는 삼성 안내 그대로(워치 설정 › 포함) — 설명 세 줄을 넘지 않게 아끼는 양은 제목으로
        return "알림 화면 켜짐 ${notificationWakes}번 · 하루 ${DrainAnalyzer.fmt(notificationCostPercent)}%" to
            "웨어러블 앱 › 워치 설정 › 알림 › 고급 설정 › '알림 받으면 화면 켜기' 끄기"
    }
}

object Wakes {
    const val SHORT_MS = 5_000L
    /** 알림 뒤 이 안에 켜진 화면은 알림이 켠 것 */
    const val NOTIF_WINDOW_MS = 3_000L
    /** 알림이 켠 화면이 보통 켜져 있는 시간 (워치 기본 화면 꺼짐 시간 근처) */
    const val NOTIF_SCREEN_MS = 10_000L
    /** 화면 소모율을 모를 때의 어림 (워치9 화면 켜짐 시간당 %) */
    const val DEFAULT_SCREEN_PPH = 20.0
    /** 한 번 깨어날 때(프로세서·화면 켜기) 드는 몫 % — 경험칙 */
    const val WAKE_OVERHEAD_PERCENT = 0.005
    const val GESTURE_ADVICE = 80
    /** 밤(0~6시)에 저절로 켜진 화면이 이만큼이면 밤에만 끄기를 권한다 */
    const val NIGHT_GESTURE_ADVICE = 10
    const val NIGHT_FROM_HOUR = 0
    const val NIGHT_UNTIL_HOUR = 6
    const val NOTIF_ADVICE = 50

    /** 알림이 잠깐 켠 화면으로 보는 길이 (이보다 오래 켜져 있으면 읽은 것) */
    const val FLASH_MAX_MS = 15_000L

    /**
     * 알림이 켜고 사람이 보지 않은 화면을 뺀다 — 알림 [NOTIF_WINDOW_MS] 안에 켜져 [FLASH_MAX_MS] 안에 꺼진 구간.
     * 지킴이의 '아침에 화면을 봤다(일어났다)' 판단용 (화면 켜짐 시간 합계에는 그대로 넣는다 — 전기는 썼으니)
     */
    fun withoutNotificationFlashes(screen: List<Span>, notificationTimes: List<Long>): List<Span> {
        if (notificationTimes.isEmpty()) return screen
        val notes = notificationTimes.sorted()
        return screen.filterNot { s ->
            val i = notes.binarySearch(s.start).let { if (it >= 0) it else -it - 2 }   // s.start 이하인 마지막 알림
            i >= 0 && s.start - notes[i] <= NOTIF_WINDOW_MS && s.ms < FLASH_MAX_MS
        }
    }

    fun analyze(
        screen: List<Span>, notifications: List<Pair<String, Long>>, from: Long, to: Long, screenPph: Double?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WakeStats {
        val spans = screen.filter { it.start >= from && it.start < to }.sortedBy { it.start }
        val notes = notifications.filter { it.second in from until to }.sortedBy { it.second }
        val noteTimes = notes.map { it.second }
        fun afterNotification(t: Long): Boolean {
            // t 직전 NOTIF_WINDOW_MS 안에 알림이 있었나 (이진 탐색)
            var lo = 0
            var hi = noteTimes.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (noteTimes[mid] <= t) lo = mid + 1 else hi = mid }
            return lo > 0 && t - noteTimes[lo - 1] <= NOTIF_WINDOW_MS
        }
        var short = 0
        var notif = 0
        var gesture = 0
        var gestureMs = 0L
        var night = 0
        var nightMs = 0L
        for (s in spans) {
            val isShort = s.ms < SHORT_MS
            val byNote = afterNotification(s.start)
            if (isShort) short++
            if (byNote) notif++
            if (isShort && !byNote) {
                gesture++; gestureMs += s.ms
                val h = Instant.ofEpochMilli(s.start).atZone(zone).hour
                if (h >= NIGHT_FROM_HOUR && h < NIGHT_UNTIL_HOUR) { night++; nightMs += s.ms }
            }
        }
        val top = notes.groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        return WakeStats(spans.size, short, notif, gesture, gestureMs, notes.size, top, screenPph, night, nightMs)
    }
}
