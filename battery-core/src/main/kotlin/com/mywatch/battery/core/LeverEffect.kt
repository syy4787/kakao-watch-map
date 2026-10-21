package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 밤(00~07시) 하나의 소모 */
data class NightResult(val date: LocalDate, val pph: Double, val hours: Double, val withNightMode: Boolean)

/** 밤 모드를 켠 밤과 안 켠 밤 비교 */
data class LeverEffect(val with: List<NightResult>, val without: List<NightResult>) {
    val withMedian: Double? get() = median(with.map { it.pph })
    val withoutMedian: Double? get() = median(without.map { it.pph })

    /** 밤 소모가 몇 % 줄었나 — 양쪽 다 2밤 이상일 때만 */
    val reductionPercent: Double?
        get() {
            if (with.size < MIN_NIGHTS || without.size < MIN_NIGHTS) return null
            val w = withMedian ?: return null
            val wo = withoutMedian ?: return null
            if (wo <= 0) return null
            return (wo - w) / wo * 100
        }

    val headline: String?
        get() {
            val cut = reductionPercent ?: return null
            val w = DrainAnalyzer.fmt(withMedian!!)
            val wo = DrainAnalyzer.fmt(withoutMedian!!)
            val n = "${with.size}밤 vs ${without.size}밤"
            return when {
                cut >= SAME -> "밤 모드 켠 밤 시간당 $w% vs 안 켠 밤 $wo% — 밤 소모 ${cut.toInt()}% 줄임 ($n)"
                cut <= -SAME -> "밤 모드 켠 밤이 오히려 더 닳음 (시간당 $w% vs $wo%, $n) — 밤 소모는 다른 원인"
                else -> "밤 모드 켠 밤과 안 켠 밤 차이 없음 (시간당 $w% vs $wo%, $n) — 밤 소모의 원인은 화면·AOD 가 아니라 뒤에서 도는 것"
            }
        }

    companion object {
        const val MIN_NIGHTS = 2
        /** 이보다 작은 차이는 '차이 없음' */
        const val SAME = 15.0

        fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

object LeverEffects {
    /** 비교에 쓰려면 밤 7시간 중 충전 없이 기록된 시간이 이만큼 */
    const val MIN_NIGHT_HOURS = 5.0
    /** 밤 모드가 밤의 이만큼을 덮었으면 '켠 밤' */
    const val COVER = 0.6

    /** 최근 [days]일의 밤을 밤 모드([sessions]: 켜져 있던 구간) 켠 밤·안 켠 밤으로 나눠 비교 */
    fun nightMode(samples: List<BatterySample>, sessions: List<Span>, now: Long, zone: ZoneId, days: Int = 14): LeverEffect {
        val sorted = samples.sortedBy { it.t }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val with = ArrayList<NightResult>()
        val without = ArrayList<NightResult>()
        for (back in days downTo 0) {
            val date = today.minusDays(back.toLong())
            val start = date.atTime(LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            if (end > now) continue
            val rate = Rates.between(sorted, start, end)
            val pph = rate.percentPerHour ?: continue
            if (rate.hours < MIN_NIGHT_HOURS) continue
            val covered = sessions.sumOf { it.overlap(start, end) }
            val on = covered >= (end - start) * COVER
            val r = NightResult(date, pph, rate.hours, on)
            if (on) with += r else without += r
        }
        return LeverEffect(with, without)
    }
}
