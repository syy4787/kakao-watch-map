package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** 실험(비행기 모드·절전·시계만 등) 구간 — 끝 표시가 없으면 [end] 가 null(진행 중) */
data class Experiment(val name: String, val start: Long, val end: Long?, val rate: Rate, val grade: Grade)

/**
 * 한 줄 결론 + 근거. [tags]: "gauge"(잔량 표시 오류 의심) · "capacity"(용량 저하 의심) · "insufficient"(표본 부족)
 */
data class Verdict(val grade: Grade, val headline: String, val reasons: List<String>, val tags: Set<String>)

data class Analysis(
    val samples: Int,
    val spanHours: Double,
    val w1: Rate,
    val w8: Rate,
    val w24: Rate,
    /** 최근 24시간 중 화면 꺼짐 구간만 */
    val idle24: Rate,
    val overnight: Rate?,
    /** 예: "10월 19일 밤(00~07시)" */
    val overnightLabel: String?,
    val anomalies: List<GaugeAnomaly>,
    val capacity: CapacityEstimate?,
    val experiments: List<Experiment>,
    val verdict: Verdict,
)

/** 표본 묶음 → 소모율·이상·판정. 워치 화면과 PC 보고서가 같은 계산을 쓴다 */
class DrainAnalyzer(private val nominalMah: Int?, private val zone: ZoneId = ZoneId.systemDefault()) {

    fun analyze(samples: List<BatterySample>, now: Long = System.currentTimeMillis()): Analysis {
        val sorted = samples.sortedBy { it.t }
        val span = if (sorted.size >= 2) (sorted.last().t - sorted.first().t) / 3_600_000.0 else 0.0
        val w1 = Rates.window(sorted, now, 1)
        val w8 = Rates.window(sorted, now, 8)
        val w24 = Rates.window(sorted, now, 24)
        val idle24 = Rates.idleWindow(sorted, now, 24)
        val (overnight, overnightLabel) = overnight(sorted, now)
        val anomalies = Gauge.anomalies(sorted, nominalMah)
        val capacity = Gauge.capacity(sorted, nominalMah)
        val experiments = experiments(sorted, now)
        val verdict = verdict(span, w24, idle24, overnight, overnightLabel, anomalies, capacity, experiments)
        return Analysis(sorted.size, span, w1, w8, w24, idle24, overnight, overnightLabel, anomalies, capacity, experiments, verdict)
    }

    /** 가장 최근에 끝난 00:00~07:00 창(기기 시간대). 그 창에 표본이 2개 미만이면 null */
    private fun overnight(samples: List<BatterySample>, now: Long): Pair<Rate?, String?> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (back in 0..7) {
            val date = today.minusDays(back.toLong())
            val start = date.atTime(LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            if (end > now) continue
            val inside = samples.count { it.t in start..end }
            if (inside < 2) continue
            val rate = Rates.between(samples, start, end)
            if (rate.hours <= 0) continue
            return rate to "${date.monthValue}월 ${date.dayOfMonth}일 밤(00~07시)"
        }
        return null to null
    }

    private fun experiments(samples: List<BatterySample>, now: Long): List<Experiment> {
        val out = ArrayList<Experiment>()
        var open: BatterySample? = null
        for (s in samples) {
            when (s.event) {
                Event.EXPERIMENT_START -> {
                    open?.let { out += experiment(samples, it, null, now) }   // 끝 없이 새로 시작하면 앞 것은 진행 중으로 닫지 않고 기록
                    open = s
                }
                Event.EXPERIMENT_END -> {
                    val st = open
                    if (st != null) { out += experiment(samples, st, s, now); open = null }
                }
                else -> {}
            }
        }
        open?.let { out += experiment(samples, it, null, now) }
        return out
    }

    private fun experiment(samples: List<BatterySample>, start: BatterySample, end: BatterySample?, now: Long): Experiment {
        val rate = Rates.between(samples, start.t, end?.t ?: now)
        val name = start.note.ifBlank { "실험" }
        return Experiment(name, start.t, end?.t, rate, Thresholds.experiment(rate.percentPerHour))
    }

    private fun verdict(
        span: Double, w24: Rate, idle24: Rate, overnight: Rate?, overnightLabel: String?,
        anomalies: List<GaugeAnomaly>, capacity: CapacityEstimate?, experiments: List<Experiment>,
    ): Verdict {
        val reasons = ArrayList<String>()
        val tags = HashSet<String>()
        if (span < 2.0) {
            return Verdict(Grade.UNKNOWN, "기록 모으는 중 — 2시간 뒤부터 판정합니다", listOf("지금까지 ${fmt(span)}시간치 기록"), setOf("insufficient"))
        }
        data class Candidate(val grade: Grade, val headline: String, val order: Int)
        val candidates = ArrayList<Candidate>()

        val gIdle = Thresholds.idle(idle24.percentPerHour)
        idle24.percentPerHour?.let { pph ->
            reasons += "대기(화면 꺼짐) 중 시간당 ${fmt(pph)}% — ${gIdle.label} (기준 ${Thresholds.IDLE_NORMAL}~${Thresholds.IDLE_ABNORMAL}%)"
            candidates += Candidate(gIdle, "대기 중 시간당 ${fmt(pph)}% — ${gIdle.label} (기준 ${Thresholds.IDLE_ABNORMAL}%)", 0)
        }
        val g24 = Thresholds.mixed24h(w24.percentPerHour)
        w24.percentPerHour?.let { pph ->
            val mah = w24.mahPerHour?.let { " (실측 ${fmt(it)} mAh/h)" } ?: ""
            reasons += "24시간 평균 시간당 ${fmt(pph)}%$mah — ${g24.label} (기준 ${Thresholds.MIXED_NORMAL}~${Thresholds.MIXED_ABNORMAL}%)"
            candidates += Candidate(g24, "24시간 평균 시간당 ${fmt(pph)}% — ${g24.label} (기준 ${Thresholds.MIXED_ABNORMAL}%)", 1)
        }
        val gNight = Thresholds.overnight(overnight?.percentPerHour)
        overnight?.percentPerHour?.let { pph ->
            reasons += "${overnightLabel ?: "밤사이"} 시간당 ${fmt(pph)}% (총 ${fmt(overnight.dropPercent)}%) — ${gNight.label}"
            candidates += Candidate(gNight, "${overnightLabel ?: "밤사이"} 시간당 ${fmt(pph)}% — ${gNight.label} (기준 ${Thresholds.NIGHT_ABNORMAL}%)", 2)
        }
        for (e in experiments) {
            val pph = e.rate.percentPerHour ?: continue
            val state = if (e.end == null) "진행 중" else "끝"
            reasons += "실험 '${e.name}'($state) 시간당 ${fmt(pph)}% — ${e.grade.label}"
            candidates += Candidate(e.grade, "${e.name}에서도 시간당 ${fmt(pph)}% — ${e.grade.label} (경험칙 ${Thresholds.EXPERIMENT_ABNORMAL}%)", 3)
        }
        if (anomalies.isNotEmpty()) {
            tags += "gauge"
            val kinds = anomalies.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.first().label} ${it.value.size}회" }
            reasons += "잔량 표시 이상 ${anomalies.size}회 ($kinds) — 잔량 표시(게이지) 오류 의심"
        }
        capacity?.percentOfNominal?.let { pct ->
            reasons += "충전 곡선으로 추정한 용량 ${fmt(capacity.estimatedMah)} mAh (공칭의 ${fmt(pct)}%)"
            if (pct < 80.0) tags += "capacity"
        }

        var grade = Grade.worst(candidates.map { it.grade })
        var headline = candidates.filter { it.grade == grade }.minByOrNull { it.order }?.headline
            ?: "아직 소모율을 낼 만큼 충전기 없이 쓴 시간이 없습니다"
        if (grade == Grade.NORMAL) {
            headline = "정상 범위 — $headline"
        }
        if ("gauge" in tags && grade != Grade.ABNORMAL) {
            grade = Grade.CAUTION
            headline = "잔량 표시 오류 의심 — $headline"
        } else if ("capacity" in tags && grade == Grade.NORMAL) {
            grade = Grade.CAUTION
            headline = "배터리 용량 저하 의심 — $headline"
        }
        return Verdict(grade, headline, reasons, tags)
    }

    companion object {
        fun fmt(x: Double): String = String.format(Locale.US, "%.1f", x)
        fun overnightDate(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }
}
