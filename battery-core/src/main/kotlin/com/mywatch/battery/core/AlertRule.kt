package com.mywatch.battery.core

/**
 * 워치 알림 규칙: 최근 2시간이 전부 방전이고 화면이 대부분 꺼져 있었는데 시간당 5% 넘게 닳았으면 알린다.
 * 같은 알림은 6시간에 한 번까지.
 */
object AlertRule {
    const val WINDOW_MS = 2L * 3_600_000L
    const val MIN_SAMPLES = 6
    const val MIN_SPAN_MS = (1.75 * 3_600_000L).toLong()
    const val SCREEN_OFF_RATIO = 0.75
    const val ABNORMAL_PPH = 5.0
    const val COOLDOWN_MS = 6L * 3_600_000L

    data class Alert(val pph: Double, val hours: Double)

    /** [screenOnMs] = 최근 2시간 중 화면이 켜져 있던 시간(사용 통계로 정확히 알 때) — 없으면 15분 표본의 화면 상태로 어림 */
    fun check(samples: List<BatterySample>, now: Long, lastAlertAt: Long?, screenOnMs: Long? = null): Alert? {
        if (lastAlertAt != null && now - lastAlertAt < COOLDOWN_MS) return null
        val recent = samples.filter { it.t >= now - WINDOW_MS && it.t <= now }.sortedBy { it.t }
        if (recent.size < MIN_SAMPLES) return null
        if (recent.any { it.plugged }) return null
        val first = recent.first()
        val last = recent.last()
        if (last.t - first.t < MIN_SPAN_MS) return null
        val off = if (screenOnMs != null) 1.0 - screenOnMs.toDouble() / (last.t - first.t).coerceAtLeast(1)
        else recent.count { !it.interactive }.toDouble() / recent.size
        if (off < SCREEN_OFF_RATIO) return null
        val hours = (last.t - first.t) / 3_600_000.0
        val pph = (first.level - last.level) / hours
        if (pph < ABNORMAL_PPH) return null
        return Alert(pph, hours)
    }
}
