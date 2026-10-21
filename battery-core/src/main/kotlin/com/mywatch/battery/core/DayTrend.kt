package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 하루의 대기(화면 꺼짐·충전 안 함) 소모 — [pph] 는 그날 대기 기록이 [DayTrend.MIN_HOURS] 시간 미만이면 null.
 * [exact] = 사용 통계의 화면 켜짐 구간으로 화면이 한 번도 안 켜진 구간만 셌다 (아니면 15분 표본의 화면 상태로)
 */
data class DayRate(val date: LocalDate, val idle: Rate, val exact: Boolean = false, val partial: Boolean = false) {
    val pph: Double? get() = idle.percentPerHour?.takeIf { idle.hours >= DayTrend.MIN_HOURS }

    /** 꺾임을 찾는 데 쓸 값 — 아직 끝나지 않은 오늘은 대기 기록이 [DayTrend.PARTIAL_MIN_HOURS] 시간 넘을 때만 (막대는 그대로 그림) */
    val forShift: Double? get() = pph?.takeIf { !partial || idle.hours >= DayTrend.PARTIAL_MIN_HOURS }
}

/** 사용 통계로 본 화면 켜짐 구간 — 시스템이 며칠만 보관하므로 [from] 부터만 믿는다 */
data class ScreenCover(val spans: List<Span>, val from: Long)

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
 *  · 날마다 대기 %/h — 사용 통계가 있으면 화면이 한 번도 안 켜진 구간만, 없으면 15분 표본의 화면 상태로(15분 사이에 잠깐 본 것이
 *    대기에 섞이므로 하루 값보다 여러 날의 흐름을 본다)
 *  · 날들을 둘로 나누는 자리마다 앞·뒤 중앙값이 [RATIO] 배 이상이고 [MIN_DIFF] %p/h 이상 차이 나며, 양쪽 모두 2일 이상이고
 *    3분의 2 이상이 [CONSISTENT] 배 넘게 같은 쪽에 있어야 꺾임 — 하루 튄 날, 마지막 하루만 나쁜 것은 아니다
 *  · 그런 자리가 여럿이면(1.0 → 1.6 → 3.0 처럼 두 번 꺾임) 양쪽이 가장 고른 자리(로그 소모율의 제곱 오차 합이 가장 작은 곳, 같으면 최근)
 */
object DayTrend {
    const val DAYS = 10
    /** 하루 값으로 쓰려면 대기 기록이 이만큼 (3시간이면 시간당 1%에서 ±33% 의 계단 오차 — 6시간이면 ±17%) */
    const val MIN_HOURS = 6.0
    /** 아직 끝나지 않은 오늘은 이만큼 쌓여야 꺾임 판단에 넣는다 (아침 몇 시간이 하루를 대표하지 않게) */
    const val PARTIAL_MIN_HOURS = 12.0
    const val RATIO = 1.5
    const val MIN_DIFF = 0.4
    const val CONSISTENT = 1.25
    const val MIN_SIDE_DAYS = 2

    /**
     * 오늘을 포함한 최근 [days]일, 오래된 날부터 (오늘은 지금까지). [screen] 이 있으면 그 기록이 하루 내내 있는 날은
     * 화면이 한 번도 안 켜진 구간만 대기로 센다(표본 사이 15분 안에 몇 분씩 본 날이 대기 소모를 부풀리지 않게) — 그런 날이
     * [MIN_SIDE_DAYS] 의 두 배가 안 되거나, 표본으로 쓸 수 있는 앞날을 잃게 되면 섞지 않고 모든 날을 표본으로,
     * 아니면 기록이 없는 앞날은 비운다
     */
    fun days(samples: List<BatterySample>, now: Long, zone: ZoneId, days: Int = DAYS, screen: ScreenCover? = null): List<DayRate> {
        val sorted = samples.sortedBy { it.t }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val windows = (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            Triple(d, d.atStartOfDay(zone).toInstant().toEpochMilli(), min(d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now))
        }
        val sampled = windows.map { (d, start, end) -> DayRate(d, if (end > start) Rates.idleBetween(sorted, start, end) else Rate.NONE, partial = d == today) }
        if (screen == null) return sampled
        val spans = screen.spans.sortedBy { it.start }
        // 15분 표본이 화면 켜짐을 본 구간도 뺀다 (사용 통계가 그 켜짐을 놓쳤어도)
        val quiet = Rates.dischargeSegments(sorted).filter { it.idle && untouched(it, spans) }
        val exact = windows.map { (d, start, end) ->
            if (start < screen.from || end <= start) DayRate(d, Rate.NONE, partial = d == today)
            else DayRate(d, Rates.rateWithin(quiet, start, end), exact = true, partial = d == today)
        }
        // 꺾임에 쓸 수 있는 날로 견준다 (끝나지 않은 오늘의 아침 몇 시간은 빼고). 화면 기록이 표본이 쓸 수 있는 날을 다 덮지 못하면
        // (시스템이 며칠만 보관) 정확한 날만으로는 앞날을 잃어 그 무렵의 꺾임을 놓친다 — 그땐 쓸 수 있는 날이 줄지 않을 때만
        val exactUsable = exact.count { it.exact && it.forShift != null }
        val sampledUsable = sampled.filter { it.forShift != null }
        val covers = sampledUsable.firstOrNull()?.let { it.date.atStartOfDay(zone).toInstant().toEpochMilli() >= screen.from } ?: true
        return if (exactUsable >= 2 * MIN_SIDE_DAYS && (covers || exactUsable >= sampledUsable.size)) exact else sampled
    }

    /** 이 방전 구간 동안 화면이 한 번도 안 켜졌나 — [spans] 는 시작 순 */
    private fun untouched(s: Segment, spans: List<Span>): Boolean {
        val a = s.from.t
        val b = s.to.t
        // b 보다 늦게(같게) 시작하는 첫 화면 구간
        var lo = 0
        var hi = spans.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (spans[mid].start < b) lo = mid + 1 else hi = mid }
        // 그 앞 구간들은 b 전에 시작했다 — a 뒤에 끝나면 겹침. 화면 구간은 서로 겹치지 않으니 바로 앞 몇 개만 본다
        for (i in lo - 1 downTo maxOf(0, lo - MAX_LOOKBACK)) if (spans[i].end > a) return false
        return true
    }

    /** 겹친 화면 구간을 찾을 때 거슬러 보는 수 (사용 통계 구간이 혹시 겹쳐 있어도 놓치지 않게 여유) */
    private const val MAX_LOOKBACK = 4

    fun shift(days: List<DayRate>): Shift? {
        val valid = days.mapNotNull { d -> d.forShift?.let { d.date to it } }
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
