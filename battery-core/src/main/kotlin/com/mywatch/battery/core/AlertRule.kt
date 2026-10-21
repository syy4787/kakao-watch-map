package com.mywatch.battery.core

/**
 * 워치 알림 규칙: 최근 2시간이 전부 방전이고 화면이 대부분 꺼져 있었는데 시간당 5% 넘게 닳았으면 알린다.
 * 같은 알림은 6시간에 한 번까지. 창 안에 재시작(부팅 표본)이 있거나 기록을 새로 재기 시작한 때([check] 의 freshFrom — 지킴이가 본 재시작·
 * PC 치료)가 있으면 그 뒤만 본다 — 그러면 1시간 45분이 쌓일 때까지는 판단하지 않는다(알림대로 강제 재시작한 직후, 재시작 앞의 과소모로
 * 다시 알리지 않게). 부팅 표본은 게이지가 잔량을 다시 잰 값이라 출발점으로 쓰지 않는다
 */
object AlertRule {
    const val WINDOW_MS = 2L * 3_600_000L
    const val MIN_SAMPLES = 6
    const val MIN_SPAN_MS = (1.75 * 3_600_000L).toLong()
    const val SCREEN_OFF_RATIO = 0.75
    const val ABNORMAL_PPH = 5.0
    const val COOLDOWN_MS = 6L * 3_600_000L

    data class Alert(val pph: Double, val hours: Double)

    /**
     * [screenOnMs] = 최근 2시간(새로 재기 시작했으면 그 뒤) 중 화면이 켜져 있던 시간(사용 통계로 정확히 알 때) — 없으면 15분 표본의 화면
     * 상태로 어림. [freshFrom] = 기록을 새로 재기 시작한 때(지킴이가 본 재시작 — 부팅 표본이 늦게 남아도 — 이나 PC 치료, 모르면 null)
     */
    fun check(samples: List<BatterySample>, now: Long, lastAlertAt: Long?, screenOnMs: Long? = null, freshFrom: Long? = null): Alert? {
        // 시계가 뒤로 가 미래가 된 마지막 알림 시각은 쉼으로 치지 않는다
        if (lastAlertAt != null && now - lastAlertAt in 0 until COOLDOWN_MS) return null
        val window = samples.filter { it.t >= now - WINDOW_MS && it.t <= now }.sortedBy { it.t }
        val cut = listOfNotNull(window.lastOrNull { it.event == Event.BOOT }?.t, freshFrom?.takeIf { it <= now }).maxOrNull()
        val recent = if (cut == null) window else window.filter { it.t > cut && it.event != Event.BOOT }
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
