package com.mywatch.battery.core

/**
 * 마지막 재시작 전후의 대기(화면 꺼짐·충전 안 함) 소모 — 시킨 대로 강제 재시작한 뒤 나아졌는지 숫자로 보여 준다.
 * [before] = 재시작 전 [RestartEffects.BEFORE_HOURS]시간(그 안에 앞 재시작이 있으면 그 30분 뒤부터),
 * [after] = 재시작 [RestartEffects.SETTLE_MS] 뒤부터 [RestartEffects.AFTER_HOURS]시간 — 며칠 뒤 다시 나빠진 것까지 평균하지 않게.
 */
data class RestartEffect(val bootAt: Long, val before: Rate, val after: Rate) {
    enum class Verdict { BETTER, SAME, WORSE }

    /**
     * 양쪽 다 대기 기록이 [RestartEffects.MIN_HOURS] 넘게 있어야 견준다 (없으면 null). 잔량이 1% 단위라 짧은 창의 계단 오차
     * (양쪽 1포인트씩)보다 커야 줄었다/늘었다고 한다
     */
    val verdict: Verdict?
        get() {
            val b = before.percentPerHour ?: return null
            val a = after.percentPerHour ?: return null
            if (before.hours < RestartEffects.MIN_HOURS || after.hours < RestartEffects.MIN_HOURS) return null
            val noise = maxOf(RestartEffects.MIN_DIFF, 1.0 / before.hours + 1.0 / after.hours)
            return when {
                a <= b * 0.7 && b - a >= noise -> Verdict.BETTER
                a >= b * 1.3 && a - b >= noise -> Verdict.WORSE
                else -> Verdict.SAME
            }
        }

    /** "재시작 뒤 대기 시간당 1.0% (그 전 4.0%) — 줄었습니다" / 아직이면 언제 견주는지 */
    val line: String
        get() {
            val v = verdict
            val a = after.percentPerHour
            val b = before.percentPerHour
            if (v == null || a == null || b == null) {
                return "재시작 뒤 대기 기록이 2시간 쌓이면 그 전과 견줍니다 (지금 ${DrainAnalyzer.fmt(after.hours)}시간)"
            }
            return "재시작 뒤 대기 시간당 ${DrainAnalyzer.fmt(a)}% (그 전 ${DrainAnalyzer.fmt(b)}%) — " + when (v) {
                Verdict.BETTER -> "줄었습니다"
                Verdict.SAME -> "비슷합니다"
                Verdict.WORSE -> "늘었습니다"
            }
        }
}

object RestartEffects {
    /** 재시작 전 이만큼을 '그 전'으로 */
    const val BEFORE_HOURS = 12
    /** 재시작 뒤 이만큼을 '그 뒤'로 */
    const val AFTER_HOURS = 12
    /** 재시작 직후는 동기화·업데이트가 몰려 빼고 본다 */
    const val SETTLE_MS = 30L * 60_000L
    /** 부팅 기록(한 번짜리 작업)보다 밀린 15분 기록이 먼저 남을 수 있다 — 부팅 기록 앞 이만큼 안에서 끝나는 구간은 재시작을 걸쳤을 수 있어 뺀다 */
    const val BOOT_ROW_LAG_MS = 20L * 60_000L
    /** 양쪽 대기 기록이 이만큼은 있어야 견준다 */
    const val MIN_HOURS = 2.0
    /** 줄었다/늘었다고 할 최소 차이 (%/h) */
    const val MIN_DIFF = 0.3
    /** 이보다 오래된 재시작은 보지 않는다 (화면·요약에 일주일씩 남지 않게) */
    const val RECENT_MS = 48L * 3_600_000L

    /**
     * 최근(이틀 안) 마지막 재시작 전후 — 부팅 표본이 없거나 재시작 전 대기 기록이 모자라 견줄 수 없으면 null.
     * [bootHint] = 실제로 켜진 때(지금 − 켜진 뒤 시간)를 알면 — 그 부팅이 마지막 부팅 표본과 30분 안이면 그때를 재시작으로 본다
     */
    fun last(samples: List<BatterySample>, now: Long, bootHint: Long? = null): RestartEffect? {
        val sorted = samples.sortedBy { it.t }
        val boots = sorted.filter { it.event == Event.BOOT && it.t <= now }
        val boot = boots.lastOrNull()?.takeIf { now - it.t <= RECENT_MS } ?: return null
        val at = bootHint?.takeIf { kotlin.math.abs(it - boot.t) <= SETTLE_MS }?.let { minOf(it, boot.t) } ?: boot.t
        // 재시작을 걸친 구간·부팅 표본에 닿은 구간(게이지가 잔량을 다시 잼)은 어느 쪽에도 넣지 않는다
        val segs = Rates.dischargeSegments(sorted).filter { it.idle && it.from.event != Event.BOOT && it.to.event != Event.BOOT }
        val beforeEnd = minOf(at, boot.t - BOOT_ROW_LAG_MS)
        val prev = boots.getOrNull(boots.size - 2)
        val beforeStart = maxOf(at - BEFORE_HOURS * 3_600_000L, prev?.let { it.t + SETTLE_MS } ?: Long.MIN_VALUE)
        val before = Rates.rateWithin(segs.filter { it.to.t <= beforeEnd }, beforeStart, beforeEnd)
        if (before.hours < MIN_HOURS || before.percentPerHour == null) return null
        val afterStart = boot.t + SETTLE_MS
        val after = Rates.rateWithin(segs.filter { it.from.t >= at }, afterStart, minOf(now, afterStart + AFTER_HOURS * 3_600_000L))
        return RestartEffect(boot.t, before, after)
    }
}
