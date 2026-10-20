package com.mywatch.battery.core

import kotlin.math.max
import kotlin.math.min

/**
 * 소모율. [percentPerHour]는 방전 시간이 30분 미만이면 null(너무 짧아 믿을 수 없음).
 * [mahPerHour]는 전하량 계수기가 있는 표본으로만 계산하고, 하나도 없으면 null.
 */
data class Rate(
    val percentPerHour: Double?,
    val mahPerHour: Double?,
    /** 계산에 쓴 방전 시간 */
    val hours: Double,
    val dropPercent: Double,
    val dropUah: Double?,
) {
    companion object {
        val NONE = Rate(null, null, 0.0, 0.0, null)
    }
}

/** 연속한 두 표본 사이의 방전 구간 */
data class Segment(val from: BatterySample, val to: BatterySample) {
    val hours: Double get() = (to.t - from.t) / 3_600_000.0
    val dropPercent: Int get() = from.level - to.level
    val dropUah: Int? get() = if (from.chargeUah != null && to.chargeUah != null) from.chargeUah - to.chargeUah else null
    /** 양 끝 다 화면 꺼짐 — 대기(워치페이스 기준) 비교용 */
    val idle: Boolean get() = !from.interactive && !to.interactive
}

object Rates {
    /** 이보다 긴 공백은 기록이 끊긴 것(꺼짐·앱 삭제)으로 보고 구간으로 치지 않는다 */
    const val MAX_GAP_MS = 2L * 3_600_000L
    /** 이보다 짧은 방전 시간으로는 %/h 를 내지 않는다 */
    const val MIN_HOURS = 0.5

    /** 충전기 없이 잔량이 오르지 않은 연속 표본 쌍 */
    fun dischargeSegments(samples: List<BatterySample>): List<Segment> =
        samples.zipWithNext().mapNotNull { (a, b) ->
            val gap = b.t - a.t
            if (a.plugged || b.plugged) return@mapNotNull null
            if (gap <= 0 || gap > MAX_GAP_MS) return@mapNotNull null
            if (b.level > a.level) return@mapNotNull null
            Segment(a, b)
        }

    fun rate(segments: List<Segment>): Rate = rateOfClips(segments.map { Clip(it, 1.0) })

    /** 최근 [hours]시간 창. 경계에 걸친 구간은 시간 비례로 자른다 */
    fun window(samples: List<BatterySample>, now: Long, hours: Int): Rate =
        between(samples, now - hours * 3_600_000L, now)

    /** 최근 [hours]시간 창 중 대기(화면 꺼짐) 구간만 */
    fun idleWindow(samples: List<BatterySample>, now: Long, hours: Int): Rate =
        rateOfClips(clip(dischargeSegments(samples).filter { it.idle }, now - hours * 3_600_000L, now))

    fun between(samples: List<BatterySample>, start: Long, end: Long): Rate =
        rateOfClips(clip(dischargeSegments(samples), start, end))

    /** 구간과 창이 겹치는 비율 */
    private class Clip(val segment: Segment, val fraction: Double)

    private fun clip(segments: List<Segment>, start: Long, end: Long): List<Clip> = segments.mapNotNull { s ->
        val a = max(s.from.t, start)
        val b = min(s.to.t, end)
        if (b <= a) null else Clip(s, (b - a).toDouble() / (s.to.t - s.from.t))
    }

    private fun rateOfClips(clips: List<Clip>): Rate {
        if (clips.isEmpty()) return Rate.NONE
        val hours = clips.sumOf { it.segment.hours * it.fraction }
        val drop = clips.sumOf { it.segment.dropPercent * it.fraction }
        val withUah = clips.filter { it.segment.dropUah != null }
        val uahHours = withUah.sumOf { it.segment.hours * it.fraction }
        val dropUah = if (withUah.isEmpty()) null else withUah.sumOf { it.segment.dropUah!! * it.fraction }
        val pph = if (hours >= MIN_HOURS) drop / hours else null
        val mah = if (dropUah != null && uahHours >= MIN_HOURS) dropUah / 1000.0 / uahHours else null
        return Rate(pph, mah, hours, drop, dropUah)
    }
}
