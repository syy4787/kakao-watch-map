package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** 하루의 대기(화면 꺼짐·충전 안 함) 소모 — [pph] 는 그날 대기 기록이 [DayTrend.MIN_HOURS] 시간 미만이면 null */
data class DayRate(val date: LocalDate, val idle: Rate) {
    val pph: Double? get() = idle.percentPerHour?.takeIf { idle.hours >= DayTrend.MIN_HOURS }
}

/**
 * 대기 소모가 꺾인 자리 — [lastBefore] 까지(기록 있는 날 [beforeDays]일)의 중앙값 [before] 와
 * [firstAfter] 부터([afterDays]일) 중앙값 [after]. 그 사이(앞날 0시 ~ 첫날 끝)에 바뀐 것이 용의자다
 */
data class Shift(
    val lastBefore: LocalDate,
    val firstAfter: LocalDate,
    val before: Double,
    val after: Double,
    val beforeDays: Int,
    val afterDays: Int,
) {
    val worse: Boolean get() = after > before
}

/**
 * 며칠 단위 추세 (순수 계산 — 워치 화면과 PC 보고서가 같이 씀). 워치9 사용자 글은 "업데이트 뒤 32 → 17시간"처럼 꺾인 날이 분명하고,
 * 하루하루의 흔들림(그날 화면을 얼마나 봤나)은 꺾임이 아니다. 그래서:
 *  · 날마다 대기 %/h (표본의 화면 상태 기준 — 15분 사이에 잠깐 본 것은 대기에 섞이므로 하루 값보다 여러 날의 흐름을 본다)
 *  · 날들을 둘로 나누는 자리마다 앞·뒤 중앙값이 [RATIO] 배 이상이고 [MIN_DIFF] %p/h 이상 차이 나며, 양쪽 모두 2일 이상이고
 *    3분의 2 이상이 [CONSISTENT] 배 넘게 같은 쪽에 있어야 꺾임 — 하루 튄 날, 마지막 하루만 나쁜 것은 아니다
 *  · 그런 자리가 여럿이면(1.0 → 1.6 → 3.0 처럼 두 번 꺾임) 양쪽이 가장 고른 자리(로그 소모율의 제곱 오차 합이 가장 작은 곳, 같으면 최근)
 */
object DayTrend {
    const val DAYS = 10
    const val MIN_HOURS = 3.0
    const val RATIO = 1.5
    const val MIN_DIFF = 0.4
    const val CONSISTENT = 1.25
    const val MIN_SIDE_DAYS = 2

    /** 오늘을 포함한 최근 [days]일, 오래된 날부터 (오늘은 지금까지) */
    fun days(samples: List<BatterySample>, now: Long, zone: ZoneId, days: Int = DAYS): List<DayRate> {
        val sorted = samples.sortedBy { it.t }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = min(d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
            DayRate(d, if (end > start) Rates.idleBetween(sorted, start, end) else Rate.NONE)
        }
    }

    fun shift(days: List<DayRate>): Shift? {
        val valid = days.mapNotNull { d -> d.pph?.let { d.date to it } }
        if (valid.size < 2 * MIN_SIDE_DAYS) return null
        var best: Shift? = null
        var bestSse = Double.MAX_VALUE
        for (k in MIN_SIDE_DAYS..valid.size - MIN_SIDE_DAYS) {
            val before = valid.subList(0, k).map { it.second }
            val after = valid.subList(k, valid.size).map { it.second }
            val b = median(before)
            val a = median(after)
            val hi = max(a, b)
            val lo = min(a, b)
            if (hi - lo < MIN_DIFF) continue
            if (lo > 0 && hi / lo < RATIO) continue
            val worse = a > b
            val afterAgrees = after.count { if (worse) it >= b * CONSISTENT else it * CONSISTENT <= b }
            val beforeAgrees = before.count { if (worse) it * CONSISTENT <= a else it >= a * CONSISTENT }
            if (afterAgrees < twoThirds(after.size) || beforeAgrees < twoThirds(before.size)) continue
            val sse = sse(before) + sse(after)
            if (sse <= bestSse + 1e-9) {
                bestSse = sse
                best = Shift(valid[k - 1].first, valid[k].first, b, a, before.size, after.size)
            }
        }
        return best
    }

    private fun twoThirds(n: Int) = ceil(n * 2 / 3.0).toInt()

    /** 로그 소모율의 제곱 오차 합 — 배수로 달라지는 소모율을 고르게 본다 */
    private fun sse(xs: List<Double>): Double {
        val logs = xs.map { ln(max(it, 0.05)) }
        val mean = logs.average()
        return logs.sumOf { (it - mean) * (it - mean) }
    }

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
