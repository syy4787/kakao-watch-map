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

    /**
     * 타일 한 줄용 짧은 글 (소모율은 위 줄에 있으니 빼고): "15% 오후 10:05 · 0% 내일 새벽 2:10".
     * 둘 다 같은 날(내일 등)이면 그 날짜는 앞에 한 번: "내일 15% 새벽 3:24 · 0% 오전 7:40"
     */
    fun short(zone: ZoneId, now: Long = System.currentTimeMillis()): String {
        val zero = clock(at0, zone, now)
        val fifteen = at15?.let { clock(it, zone, now) } ?: return "0% $zero"
        val day15 = fifteen.substringBefore(' ', "").takeIf { it.isNotEmpty() && it !in AMPM }
        val day0 = zero.substringBefore(' ', "").takeIf { it.isNotEmpty() && it !in AMPM }
        return if (day15 != null && day15 == day0) "$day15 15% ${fifteen.removePrefix("$day15 ")} · 0% ${zero.removePrefix("$day0 ")}"
        else "15% $fifteen · 0% $zero"
    }

    companion object {
        private val AMPM = setOf("밤", "새벽", "오전", "오후")

        /** "오후 10:05" — 내일이면 앞에 "내일 ". 자정은 '밤 12:03', 1~5시는 '새벽 3:24' ('오전 12:03'은 헷갈림) */
        fun clock(t: Long, zone: ZoneId, now: Long = System.currentTimeMillis()): String {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val h = z.hour
            val day = when {
                z.toLocalDate() == today -> ""
                // 다음 날 0시대는 말로 '오늘 밤 12시'
                z.toLocalDate() == today.plusDays(1) && h == 0 -> "오늘 "
                z.toLocalDate() == today.plusDays(1) -> "내일 "
                else -> "${z.monthValue}/${z.dayOfMonth} "
            }
            val ampm = when (h) { 0 -> "밤"; in 1..5 -> "새벽"; in 6..11 -> "오전"; else -> "오후" }
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
