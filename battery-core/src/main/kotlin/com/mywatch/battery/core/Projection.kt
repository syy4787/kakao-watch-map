package com.mywatch.battery.core

import java.time.Instant
import java.time.ZoneId

/** 지금 페이스로 본 앞날: [level]% 에서 시간당 [pph]% 로 줄면 15%·0% 가 되는 때 (epoch ms) */
data class Forecast(val level: Int, val pph: Double, val at15: Long?, val at0: Long) {
    /** "지금 페이스(시간당 4.0%)면 오후 10:00에 15%, 오후 11:45에 0%" */
    fun label(zone: ZoneId): String {
        val p = "지금 페이스(시간당 ${DrainAnalyzer.fmt(pph)}%)면 "
        val zero = "${clock(at0, zone)}에 0%"
        return p + (at15?.let { "${clock(it, zone)}에 15%, " } ?: "") + zero
    }

    companion object {
        /** "오후 10:05" — 내일이면 앞에 "내일 " */
        fun clock(t: Long, zone: ZoneId, now: Long = System.currentTimeMillis()): String {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val day = when (z.toLocalDate()) {
                today -> ""
                today.plusDays(1) -> "내일 "
                else -> "${z.monthValue}/${z.dayOfMonth} "
            }
            val h = z.hour
            val ampm = if (h < 12) "오전" else "오후"
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "$day$ampm $h12:${"%02d".format(z.minute)}"
        }
    }
}

object Projection {
    /** 이보다 느리면(거의 안 닳으면) 앞날을 말하지 않는다 */
    const val MIN_PPH = 0.3

    /** 지금 페이스: 최근 2시간 소모율, 모자라면 8시간 — 마지막 표본이 충전 중이면 null */
    fun pace(samples: List<BatterySample>, now: Long): Double? {
        val sorted = samples.sortedBy { it.t }
        if (sorted.lastOrNull()?.plugged != false) return null
        val w2 = Rates.window(sorted, now, 2)
        if (w2.percentPerHour != null && w2.hours >= 1.0) return w2.percentPerHour
        return Rates.window(sorted, now, 8).percentPerHour
    }

    fun forecast(samples: List<BatterySample>, now: Long): Forecast? {
        val pph = pace(samples, now)?.takeIf { it >= MIN_PPH } ?: return null
        val level = samples.maxByOrNull { it.t }?.level ?: return null
        fun until(target: Int): Long = now + ((level - target) / pph * 3_600_000L).toLong()
        return Forecast(level, pph, if (level > 15) until(15) else null, until(0))
    }
}
