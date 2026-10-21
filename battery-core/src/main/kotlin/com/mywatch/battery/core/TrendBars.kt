package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

/** 막대 하나 = 하루. [changes] = 그날 바뀐 것 수(막대 위 점) */
data class TrendBar(val date: LocalDate, val label: String, val pph: Double?, val grade: Grade, val changes: Int, val today: Boolean)

/**
 * '최근 며칠' 막대 그래프. 세로는 0 ~ [top] (대기 기준 [Thresholds.IDLE_NORMAL] 보다 낮지 않게 — 작은 값을 크게 부풀리지 않는다).
 * [splitAfter] = 꺾이기 전 마지막 날 막대 번호(그 뒤에 세로 점선), [worse] = 늘었나 줄었나.
 */
data class TrendBarsModel(val bars: List<TrendBar>, val top: Double, val splitAfter: Int?, val worse: Boolean?, val description: String)

object TrendBars {
    fun build(days: List<DayRate>, shift: Shift?, changes: List<Change>, zone: ZoneId, today: LocalDate): TrendBarsModel? {
        if (days.none { it.pph != null }) return null
        val perDay = changes.groupingBy { Instant.ofEpochMilli(it.t).atZone(zone).toLocalDate() }.eachCount()
        val bars = days.map { d ->
            TrendBar(
                date = d.date,
                // 오늘도 날짜 숫자로 (열흘 막대 아래 '오늘'은 옆 글자와 겹친다) — 그리는 쪽이 굵게
                label = d.date.dayOfMonth.toString(),
                pph = d.pph,
                grade = Thresholds.idle(d.pph),
                changes = perDay[d.date] ?: 0,
                today = d.date == today,
            )
        }
        val top = max(bars.mapNotNull { it.pph }.maxOrNull() ?: 0.0, Thresholds.IDLE_NORMAL) * 1.1
        val split = shift?.let { s -> bars.indexOfFirst { it.date == s.lastBefore }.takeIf { it >= 0 } }
        val values = bars.filter { it.pph != null }.joinToString(", ") { b ->
            (if (b.today) "오늘" else ChangeFinder.day(b.date)) + " " + String.format(Locale.US, "%.1f", b.pph)
        }
        val tail = shift?.let { s -> ". ${ChangeFinder.day(s.firstAfter)}부터 ${if (s.worse) "늘어남" else "줄어듦"}" } ?: ""
        return TrendBarsModel(bars, top, split, shift?.worse, "최근 ${bars.size}일 대기 소모(시간당 %): $values$tail")
    }
}

/**
 * '최근 며칠' 한 묶음 (워치 화면·요약·PC 보고서가 같이 씀): 날마다 대기 %/h([days]), 꺾인 자리([shift]),
 * 그 무렵 바뀐 것(가능성 순 [suspects]), 최근 7일 바뀐 것(최근 것부터 [recent]), 막대 그래프([bars]).
 * [fresh] = 최근 7일 안에 늘어난 꺾임 — '지금 할 일'·소견에 올린다
 */
data class TrendReport(
    val days: List<DayRate>,
    val shift: Shift?,
    val suspects: List<Change>,
    val recent: List<Change>,
    val bars: TrendBarsModel?,
    val fresh: Boolean,
) {
    /** 보여 줄 날들 — 기록이 시작되기 전의 빈 날은 뺀다 */
    val shownDays: List<DayRate> get() = days.dropWhile { it.pph == null }

    /** 기록 있는 날 수 — [DayTrend.MIN_SIDE_DAYS] 의 두 배는 있어야 꺾임을 본다 */
    val validDays: Int get() = days.count { it.pph != null }

    companion object {
        const val RECENT_MS = 7 * 24 * 3_600_000L
        const val FRESH_DAYS = 7L

        fun of(samples: List<BatterySample>, now: Long, zone: ZoneId): TrendReport {
            val days = DayTrend.days(samples, now, zone)
            val shift = DayTrend.shift(days)
            val changes = ChangeNote.all(samples).filter { it.t <= now }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return TrendReport(
                days = days,
                shift = shift,
                suspects = shift?.let { ChangeFinder.around(it, changes, zone) }.orEmpty(),
                recent = changes.filter { it.t >= now - RECENT_MS }.sortedByDescending { it.t },
                bars = TrendBars.build(days.dropWhile { it.pph == null }, shift, changes, zone, today),
                fresh = shift != null && shift.worse && !shift.firstAfter.isBefore(today.minusDays(FRESH_DAYS)),
            )
        }
    }
}
