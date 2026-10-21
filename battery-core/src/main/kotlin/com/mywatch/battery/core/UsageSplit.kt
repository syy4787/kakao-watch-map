package com.mywatch.battery.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/** 시각 구간 [start, end) (epoch ms) */
data class Span(val start: Long, val end: Long) {
    val ms: Long get() = end - start
    fun overlap(a: Long, b: Long): Long = max(0L, min(end, b) - max(start, a))
}

/** 화면에 떠 있던 앱 하나의 구간 */
data class AppSpan(val pkg: String, val start: Long, val end: Long)

/** 앱별 사용량 한 묶음: [t] 에 끝난 [ms] 길이 구간 동안 (워치 HealthStore 의 15분 차 한 줄) */
data class TimedUse(val t: Long, val ms: Long, val uses: List<UidUse>)

/** 한 시간(정각~정각)의 소모 */
data class HourDrain(
    val start: Long,
    val end: Long,
    val dropPercent: Double,
    /** 그 시간 중 충전 없이 기록이 이어진 시간 */
    val coveredHours: Double,
    /** 화면 켜져 있던 시간 — 사용 통계가 없으면 null */
    val screenOnMs: Long?,
    /** 그 시간에 화면에 가장 오래 떠 있던 앱 */
    val topApp: String?,
    val topAppMs: Long,
    /** 그 시간에 뒤에서 가장 많이 쓴 앱 */
    val culprit: AppDrainRank?,
) {
    val pph: Double get() = dropPercent / coveredHours
}

/**
 * 하루(기본 24시간) 배터리 사용 내역. 화면 정보([exactScreen])가 있으면 줄어든 잔량을 화면 켜짐·꺼짐으로 나눈다:
 * 화면이 한 번도 안 켜진 구간들로 '대기 시간당 %'를 재고, 나머지를 화면 켜진 시간의 몫으로 본다.
 */
data class UsageBreakdown(
    val coveredHours: Double,
    val totalDrop: Double,
    val screenOnHours: Double?,
    val screenOffHours: Double?,
    val screenDrop: Double?,
    val idleDrop: Double?,
    /** 화면 꺼진 동안 시간당 % */
    val idlePph: Double?,
    /** 화면 켜진 동안 시간당 % */
    val screenPph: Double?,
    /** 가장 많이 닳은 시간 (최대 3개, 많이 닳은 순) */
    val steepest: List<HourDrain>,
    val exactScreen: Boolean,
) {
    val screenShare: Double? get() = if (screenDrop != null && totalDrop > 0) screenDrop / totalDrop else null

    /** "하루 82% 중 화면(1시간 30분)이 41%, 대기(22시간)가 41% — 대기 시간당 1.8%" */
    val headline: String
        get() {
            val total = "${DrainAnalyzer.fmt(coveredHours)}시간 동안 ${DrainAnalyzer.fmt(totalDrop)}%"
            if (screenDrop == null || idleDrop == null || screenOnHours == null || screenOffHours == null) {
                return total + (idlePph?.let { " — 대기 시간당 ${DrainAnalyzer.fmt(it)}%" } ?: "")
            }
            val which = when {
                (screenShare ?: 0.0) >= 0.6 -> "화면 켜진 시간이 대부분"
                (screenShare ?: 0.0) <= 0.4 -> "화면이 꺼진 대기가 대부분"
                else -> "화면과 대기가 비슷"
            }
            return "$total 중 화면(${hm(screenOnHours)}) ${DrainAnalyzer.fmt(screenDrop)}% · 대기(${hm(screenOffHours)}) ${DrainAnalyzer.fmt(idleDrop)}% — $which"
        }

    companion object {
        fun hm(hours: Double): String {
            val m = (hours * 60).toInt()
            return if (m < 60) "${m}분" else if (m % 60 == 0) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
        }
    }
}

object UsageSplit {
    /** 대기 시간당 %를 믿으려면 화면이 안 켜진 구간이 이만큼은 있어야 */
    const val MIN_IDLE_HOURS = 1.0
    const val MIN_SCREEN_HOURS = 0.1
    /** 한 시간 칸을 보여 주려면 기록이 이만큼은 이어져야 */
    const val MIN_HOUR_COVER = 0.75
    /** 사용 내역을 내려면 충전 없이 쓴 시간이 이만큼은 */
    const val MIN_COVERED_HOURS = 2.0
    const val STEEPEST = 3

    /**
     * [samples] 배터리 표본, [screenOn] 화면이 켜져 있던 구간(사용 통계, 없으면 null), [apps] 화면에 떠 있던 앱 구간,
     * [usage] 15분마다의 앱별 사용량, [ownPkg] 이 앱. 기록이 모자라면 null
     */
    fun analyze(
        samples: List<BatterySample>,
        screenOn: List<Span>?,
        apps: List<AppSpan>,
        usage: List<TimedUse>,
        now: Long,
        zone: ZoneId,
        hours: Int = 24,
        ownPkg: String? = null,
    ): UsageBreakdown? {
        val from = now - hours * 3_600_000L
        val sorted = samples.sortedBy { it.t }
        val clips = Rates.dischargeSegments(sorted).mapNotNull { s ->
            val a = max(s.from.t, from)
            val b = min(s.to.t, now)
            if (b <= a) null else Clip(s, a, b)
        }
        val covered = clips.sumOf { it.hours }
        if (covered < MIN_COVERED_HOURS) return null
        val totalDrop = clips.sumOf { it.drop }

        // 화면 켜짐: 사용 통계가 있으면 그대로, 없으면 표본 양 끝이 다 꺼짐인 구간만 '대기'로
        val onMs = clips.map { c -> screenOn?.sumOf { it.overlap(c.a, c.b) } }
        val idleClips = clips.indices.filter { i -> if (screenOn != null) onMs[i] == 0L else clips[i].segment.idle }.map { clips[it] }
        val idleHours = idleClips.sumOf { it.hours }
        val idlePph = if (idleHours >= MIN_IDLE_HOURS) idleClips.sumOf { it.drop } / idleHours else null

        var screenOnHours: Double? = null
        var screenOffHours: Double? = null
        var screenDrop: Double? = null
        var idleDrop: Double? = null
        var screenPph: Double? = null
        if (screenOn != null) {
            val onH = onMs.sumOf { it ?: 0L } / 3_600_000.0
            val offH = covered - onH
            screenOnHours = onH
            screenOffHours = offH
            if (idlePph != null) {
                val idle = min(totalDrop, idlePph * offH)
                idleDrop = idle
                screenDrop = totalDrop - idle
                screenPph = if (onH >= MIN_SCREEN_HOURS) (totalDrop - idle) / onH else null
            }
        }

        val steepest = hoursOf(clips, from, now, zone).mapNotNull { (hs, he) ->
            val inHour = clips.mapNotNull { cl ->
                val a = max(cl.a, hs)
                val b = min(cl.b, he)
                if (b <= a) null else cl to (b - a)
            }
            val cov = inHour.sumOf { (_, ms) -> ms } / 3_600_000.0
            if (cov < MIN_HOUR_COVER) return@mapNotNull null
            val drop = inHour.sumOf { (cl, ms) -> cl.drop * ms / (cl.b - cl.a).toDouble() }
            val on = screenOn?.sumOf { it.overlap(hs, he) }
            val app = apps.groupBy { it.pkg }
                .mapValues { (_, spans) -> spans.sumOf { Span(it.start, it.end).overlap(hs, he) } }
                .filterValues { it > 0 }
                .maxByOrNull { it.value }
            val windows = usage.filter { it.t > hs && it.t <= he }
            val culprit = if (windows.isEmpty()) null
            else AppDrain.rank(AppDrain.sum(windows.map { it.uses }), windows.sumOf { it.ms }.coerceAtLeast(15 * 60_000L), ownPkg).firstOrNull()
            HourDrain(hs, he, drop, cov, on, app?.key, app?.value ?: 0L, culprit)
        }.filter { it.dropPercent >= 1.0 }
            .sortedByDescending { it.pph }
            .take(STEEPEST)

        return UsageBreakdown(covered, totalDrop, screenOnHours, screenOffHours, screenDrop, idleDrop, idlePph, screenPph, steepest, screenOn != null)
    }

    /** [from, now) 안의 정각~정각 한 시간 칸들 */
    private fun hoursOf(clips: List<Clip>, from: Long, now: Long, zone: ZoneId): List<Pair<Long, Long>> {
        if (clips.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        var h = Instant.ofEpochMilli(from).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
        if (h < from) h += 3_600_000L
        while (h + 3_600_000L <= now) {
            out += h to h + 3_600_000L
            h += 3_600_000L
        }
        return out
    }

    /** 방전 구간 하나를 창 [a, b) 로 자른 것 */
    private class Clip(val segment: Segment, val a: Long, val b: Long) {
        private val fraction = (b - a).toDouble() / (segment.to.t - segment.from.t)
        val hours: Double get() = (b - a) / 3_600_000.0
        val drop: Double get() = segment.dropPercent * fraction
    }
}
