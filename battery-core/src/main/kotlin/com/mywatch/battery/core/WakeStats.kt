package com.mywatch.battery.core

/**
 * 하루 동안 화면이 켜진 횟수. [shortWakes] = [Wakes.SHORT_MS] 안에 꺼진 것, [notificationWakes] = 알림 직후([Wakes.NOTIF_WINDOW_MS] 안)
 * 켜진 것, [gestureLikeWakes] = 알림 없이 금방 꺼진 것(손목을 들 때 저절로 켜진 것으로 보임).
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
) {
    /** 금방 꺼진 화면들이 하루에 쓴 % 어림 — 켜진 시간 × 화면 소모율 + 한 번 깨어날 때마다 드는 몫 */
    val gestureCostPercent: Double
        get() = gestureLikeMs / 3_600_000.0 * (screenPph ?: Wakes.DEFAULT_SCREEN_PPH) + gestureLikeWakes * Wakes.WAKE_OVERHEAD_PERCENT

    val notificationCostPercent: Double
        get() = notificationWakes * (Wakes.NOTIF_SCREEN_MS / 3_600_000.0 * (screenPph ?: Wakes.DEFAULT_SCREEN_PPH) + Wakes.WAKE_OVERHEAD_PERCENT)

    /** 많을 때만: 무엇을 끄면 되는지. [label] = 패키지 이름 → 보일 이름 */
    fun advice(label: (String) -> String = { it }): List<String> {
        val out = ArrayList<String>()
        if (gestureLikeWakes >= Wakes.GESTURE_ADVICE) {
            out += "알림 없이 5초 안에 꺼진 화면 ${gestureLikeWakes}번 — 손목을 들 때 저절로 켜진 것으로 보입니다. " +
                "설정 > 디스플레이에서 '손목 올려 화면 켜기'(화면 켜기 제스처)를 끄면 하루 약 ${DrainAnalyzer.fmt(gestureCostPercent)}% 아낍니다"
        }
        if (notificationWakes >= Wakes.NOTIF_ADVICE) {
            val top = topNotifiers.take(2).joinToString(", ") { "${label(it.first)} ${it.second}번" }
            out += "알림이 화면을 ${notificationWakes}번 켬 (많이 보낸 앱: $top) — Galaxy Wearable 앱 > 워치 설정 > 알림 > 고급 알림 설정에서 " +
                "'화면 켜기'를 끄거나 그 앱의 워치 알림을 끄면 하루 약 ${DrainAnalyzer.fmt(notificationCostPercent)}% 아낍니다"
        }
        return out
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

    fun analyze(screen: List<Span>, notifications: List<Pair<String, Long>>, from: Long, to: Long, screenPph: Double?): WakeStats {
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
        for (s in spans) {
            val isShort = s.ms < SHORT_MS
            val byNote = afterNotification(s.start)
            if (isShort) short++
            if (byNote) notif++
            if (isShort && !byNote) { gesture++; gestureMs += s.ms }
        }
        val top = notes.groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        return WakeStats(spans.size, short, notif, gesture, gestureMs, notes.size, top, screenPph)
    }
}
