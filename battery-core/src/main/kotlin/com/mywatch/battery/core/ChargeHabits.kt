package com.mywatch.battery.core

import java.util.Locale

/**
 * 충전 습관 (배터리 수명 쪽): 최근 며칠 동안 충전 횟수, 충전기에 100%로 머문 시간(하루 평균), 충전 중 최고 온도,
 * 그리고 '배터리 보호'(90%에서 멈추고 85% 아래로 내려가면 다시 충전 — 워치8부터 설정 > 배터리)가 켜진 것으로 보이는지.
 */
data class ChargeHabits(
    val days: Double,
    val sessions: Int,
    val hoursAtFullPerDay: Double,
    val protectLikely: Boolean,
    val maxChargeTempC: Double?,
    val advice: List<String>,
) {
    /** 배터리 보호를 권하나 (그러면 [advice] 의 첫 줄) */
    val protectAdvised: Boolean get() = !protectLikely && hoursAtFullPerDay >= FULL_ADVICE_HOURS

    companion object {
        const val FULL_LEVEL = 99
        /** 하루 이만큼 넘게 100% 로 충전기에 있으면 배터리 보호를 권한다 */
        const val FULL_ADVICE_HOURS = 2.0
        /** 충전 중 이보다 뜨거우면 (리튬 배터리 수명이 빨리 줄어드는 영역 — 경험칙, 삼성: 너무 뜨거우면 충전이 느려지거나 멈춤) */
        const val HOT_C = 42.0
        /** 표본 사이가 이보다 길면 그 사이는 세지 않는다 (기록이 빠진 곳) */
        private const val MAX_GAP_MS = 30 * 60_000L
        private const val DAY_MS = 24 * 3_600_000L
        /** 배터리 보호로 보는 충전: 2시간 넘게 꽂아 두었는데 최고 잔량이 85~91% 에서 1시간 넘게 머묾 */
        private const val PROTECT_SESSION_MS = 2 * 3_600_000L
        private const val PROTECT_HOLD_MS = 3_600_000L

        private class Session(var start: Long, var end: Long = start, var maxLevel: Int = 0, var heldAtMaxMs: Long = 0)

        /** 충전 기록이 없으면 null */
        fun analyze(samples: List<BatterySample>, now: Long, days: Int = 7): ChargeHabits? {
            val s = samples.filter { it.t in (now - days * DAY_MS)..now }.sortedBy { it.t }
            if (s.none { it.plugged }) return null
            val sessions = ArrayList<Session>()
            var cur: Session? = null
            var fullMs = 0L
            var maxTemp: Int? = null
            for (i in s.indices) {
                val p = s[i]
                if (!p.plugged) { cur = null; continue }
                val sess = cur ?: Session(p.t).also { sessions += it; cur = it }
                sess.end = p.t
                p.tempDeciC?.let { t -> maxTemp = maxOf(maxTemp ?: t, t) }
                val next = s.getOrNull(i + 1)
                val gap = if (next != null && next.plugged) minOf(next.t - p.t, MAX_GAP_MS) else 0L
                if (p.level >= FULL_LEVEL) fullMs += gap
                when {
                    p.level > sess.maxLevel -> { sess.maxLevel = p.level; sess.heldAtMaxMs = gap }
                    p.level >= sess.maxLevel - 1 -> sess.heldAtMaxMs += gap
                }
            }
            val covered = ((s.last().t - s.first().t).toDouble() / DAY_MS).coerceAtLeast(1.0)
            val hoursAtFull = fullMs / 3_600_000.0 / covered
            val long = sessions.filter { it.end - it.start >= PROTECT_SESSION_MS }
            val protect = long.isNotEmpty() && long.all { it.maxLevel in 85..91 && it.heldAtMaxMs >= PROTECT_HOLD_MS }
            val tempC = maxTemp?.let { it / 10.0 }
            val advice = ArrayList<String>()
            if (!protect && hoursAtFull >= FULL_ADVICE_HOURS) {
                advice += "충전기에 100%로 하루 ${fmt(hoursAtFull)}시간 머뭅니다 — 설정 > 배터리 > 배터리 보호를 켜면 90%에서 멈춰(85% 아래면 다시 충전) 배터리가 덜 늙습니다"
            }
            if (tempC != null && tempC >= HOT_C) {
                advice += "충전 중 최고 ${fmt(tempC)}℃ — 충전할 땐 워치를 벗기고 직사광선·이불 속·두꺼운 케이스를 피하세요 (열이 배터리를 가장 빨리 늙게 합니다)"
            }
            return ChargeHabits(covered, sessions.size, hoursAtFull, protect, tempC, advice)
        }

        private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    }
}
