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
    /** [advice] 와 같은 차례의 '지금 할 일' 줄에 맞춘 짧은 제목·설명 */
    val todos: List<Pair<String, String>> = emptyList(),
    /** 충전 속도 (잴 만한 충전이 없으면 null) */
    val speed: ChargeSpeed? = null,
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

        /** 충전 기록이 없으면 null. [model] = 워치 모델명(공식 충전 시간과 견주려고 — 모르면 속도만 잼) */
        fun analyze(samples: List<BatterySample>, now: Long, days: Int = 7, model: String? = null): ChargeHabits? {
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
            val speed = speedOf(s, model)
            val advice = ArrayList<String>()
            val todos = ArrayList<Pair<String, String>>()
            if (!protect && hoursAtFull >= FULL_ADVICE_HOURS) {
                advice += "충전기에 100%로 하루 ${fmt(hoursAtFull)}시간 머뭅니다 — 설정 > 배터리 > 배터리 보호를 켜면 90%에서 멈춰(85% 아래면 다시 충전) 배터리가 덜 늙습니다"
                todos += "충전기에서 하루 ${fmt(hoursAtFull)}시간 100%" to "설정 › 배터리 › 배터리 보호 켜기 (90%에서 멈춰 덜 늙음)"
            }
            val slowHot = speed != null && speed.slow && speed.hot
            if (speed != null && speed.slow) {
                val took = "0→100%에 ${duration(speed.minutesPerFull)} 넘게 걸리는 빠르기(삼성 공식 고속 충전 ${speed.specMinutes}분)"
                if (slowHot) {
                    // 워치9 사용자 보고: 충전 중 44℃까지 뜨거워지며 느려짐, 선풍기 받침으로 15→100% 2시간 10분 → 1시간 15분. 얼음은 습기·급한 온도
                    // 변화로 위험 (Android Authority 2026-08-12·10-13, Notebookcheck, Sammy Fans 2026-10-13 — 2026년 10월 기준 삼성은 고침을 알리지 않음)
                    val t = fmt(speed.maxTempC!!)
                    advice += "충전이 공식보다 느립니다 — $took, 충전 중 ${t}℃: 워치9는 충전 중 뜨거워지면 느려질 수 있다는 보고가 많습니다(2026년 10월 기준 삼성은 " +
                        "고침을 알리지 않음). 서늘한 곳에서 케이스를 빼고 작은 선풍기 바람을 워치와 충전기 쪽으로 — 한 사용자: 15→100% 2시간 10분 → 1시간 15분. " +
                        "얼음은 습기·급한 온도 변화 때문에 쓰지 마세요"
                    todos += "충전이 느림 · 충전 중 ${t}℃" to "서늘한 곳에서 케이스 빼고 선풍기 바람 (얼음은 쓰지 않기)"
                } else {
                    // 워치8 충전기·타사 거치대는 느리다는 알림이 뜨거나 안 된다는 보고, 삼성: 승인된 충전기만 (Android Authority·Gadget Hacks, 삼성 지원 2026-03)
                    advice += "충전이 공식보다 느립니다 — $took: 워치9에 들어 있던 충전기와 맞는 어댑터인지(워치8 충전기·타사 거치대는 느리거나 안 된다는 보고), " +
                        "충전기 윗면·워치 뒷면을 닦고 가운데 맞추기, 소프트웨어 업데이트 — 그래도 한참 느리면 이 기록과 함께 삼성 서비스에 문의"
                    todos += "충전이 느림 (완충 ${duration(speed.minutesPerFull)}+)" to "들어 있던 충전기·어댑터 확인 · 충전기·워치 뒷면 닦기"
                }
            }
            // 느린 충전이 열 때문이면 위 한 줄로 (같은 말을 두 번 하지 않게) — 다른 충전이 더 뜨거웠으면 그 온도로 따로
            if (tempC != null && tempC >= HOT_C && !(slowHot && tempC <= (speed?.maxTempC ?: 0.0))) {
                advice += "충전 중 최고 ${fmt(tempC)}℃ — 충전할 땐 워치를 벗기고 직사광선·이불 속·두꺼운 케이스를 피하세요 (열이 배터리를 가장 빨리 늙게 합니다)"
                todos += "충전 중 최고 ${fmt(tempC)}℃" to "충전할 땐 벗기고 햇빛·이불 속·두꺼운 케이스 피하기"
            }
            return ChargeHabits(covered, sessions.size, hoursAtFull, protect, tempC, advice, todos, speed)
        }

        /**
         * 충전마다 처음 오른 구간의 [ChargeSpeed.FROM]~[ChargeSpeed.TO]%(빠른 충전 구간)에서 오른 빠르기로 0→100% 걸릴 시간을 어림한다 —
         * 처음 [ChargeSpeed.TO]%에 닿은 뒤(배터리 보호로 머물거나 내려왔다 다시 차는 것)는 보지 않는다. 그 구간에서 [ChargeSpeed.MIN_RISE]포인트
         * 이상 오른 충전만, 기록이 빈 곳([MAX_GAP_MS] 넘게)이 있거나 충전기 위에서 화면을 켜고 쓴 충전(표본의 1/3 넘게 화면 켜짐)은 뺀다.
         * 가운데 값은 아래쪽으로(둘이면 빠른 쪽) — 느리다고 하려면 잰 충전의 절반 넘게(둘이면 둘 다) 느려야. 열 탓인지·그때 온도는 공식의
         * [ChargeSpeed.SLOW_RATIO]배 이상 걸린 충전들로만. 잴 만한 충전이 없으면 null
         */
        private fun speedOf(s: List<BatterySample>, model: String?): ChargeSpeed? {
            val runs = ArrayList<List<BatterySample>>()
            var cur = ArrayList<BatterySample>()
            for (p in s) {
                if (p.plugged) cur += p else { if (cur.isNotEmpty()) runs += cur; cur = ArrayList() }
            }
            if (cur.isNotEmpty()) runs += cur
            val per = ArrayList<Pair<Double, Int?>>()
            for (r in runs) {
                val top = r.indexOfFirst { it.level >= ChargeSpeed.TO }
                val w = (if (top >= 0) r.subList(0, top + 1) else r).filter { it.level in ChargeSpeed.FROM..ChargeSpeed.TO }
                if (w.size < 2) continue
                val rise = w.last().level - w.first().level
                val minutes = (w.last().t - w.first().t) / 60_000.0
                if (rise < ChargeSpeed.MIN_RISE || minutes <= 0.0) continue
                if (w.zipWithNext().any { (a, b) -> b.t - a.t > MAX_GAP_MS }) continue
                if (w.count { it.interactive } * 3 > w.size) continue
                per += minutes / rise * 100.0 to w.mapNotNull { it.tempDeciC }.maxOrNull()
            }
            if (per.isEmpty()) return null
            val sorted = per.map { it.first }.sorted()
            val spec = WatchModels.fullChargeMinutes(model)
            val slowOnes = spec?.let { sp -> per.filter { it.first >= sp * ChargeSpeed.SLOW_RATIO } }.orEmpty()
            val judged = slowOnes.ifEmpty { per }
            val hot = slowOnes.isNotEmpty() && slowOnes.count { (it.second ?: 0) >= ChargeSpeed.HOT_C * 10 } * 2 > slowOnes.size
            return ChargeSpeed(per.size, sorted[(sorted.size - 1) / 2], spec, judged.mapNotNull { it.second }.maxOrNull()?.let { it / 10.0 }, hot)
        }

        /** "2시간 47분" · "45분" · "2시간" */
        fun duration(minutes: Double): String {
            val m = Math.round(minutes).toInt()
            return when {
                m < 60 -> "${m}분"
                m % 60 == 0 -> "${m / 60}시간"
                else -> "${m / 60}시간 ${m % 60}분"
            }
        }

        private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    }
}

/**
 * 충전 속도 — 충전마다 처음 오른 [FROM]~[TO]% 구간(빠른 충전 구간)의 빠르기로 어림한 0→100% 시간(분)의 가운데 값(아래쪽). 뒤쪽(85~100%)은
 * 원래 느려 실제 완충 시간은 이보다 길다 — 그래서 공식 0→100% 시간([specMinutes], 고속 충전 기준, 모르면 null)과 견줘 느리다고 할 때
 * 넉넉하다. [measured] = 잰 충전 수(그 주의 모든 충전이 아니다 — 잠깐 충전·화면을 켜고 쓴 충전 등은 빠짐), [maxTempC] = 느린 충전들(없으면
 * 잰 충전 모두)의 그 구간 최고 온도, [hot] = 느린 충전의 절반 넘게가 [HOT_C]℃ 이상이었다 (워치9는 충전 중 44℃까지 뜨거워지며 느려진다는
 * 보고 — Notebookcheck 2026-08-12)
 */
data class ChargeSpeed(val measured: Int, val minutesPerFull: Double, val specMinutes: Int?, val maxTempC: Double?, val hot: Boolean = false) {
    /** 가운데 값이 공식 시간의 [SLOW_RATIO]배 이상이고, 잰 충전이 [MIN_SESSIONS]번 이상 (그날만 그랬을 수 있어 한 번으론 말하지 않는다) */
    val slow: Boolean get() = specMinutes != null && measured >= MIN_SESSIONS && minutesPerFull >= specMinutes * SLOW_RATIO

    companion object {
        const val FROM = 10
        const val TO = 85
        const val MIN_RISE = 30
        const val SLOW_RATIO = 1.3
        const val MIN_SESSIONS = 2
        /** 이보다 뜨거우면 열로 느려진 것으로 (충전 습관의 '뜨거운 충전' 42℃보다 낮게 — 느려지는 것은 그 전부터) */
        const val HOT_C = 40.0
    }
}
