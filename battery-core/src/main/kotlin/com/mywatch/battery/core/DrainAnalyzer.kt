package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** 실험(비행기 모드·절전·시계만 등) 구간 — 끝 표시가 없으면 [end] 가 null(진행 중) */
data class Experiment(val name: String, val start: Long, val end: Long?, val rate: Rate, val grade: Grade)

/**
 * 한 줄 결론 + 근거. [tags]: "gauge"(잔량 표시 오류 의심) · "capacity"(용량 저하 의심) · "insufficient"(표본 부족) ·
 * "drift"(전압으로 본 게이지 드리프트) · "phantom"(계수기보다 표시가 더 빨리 줄어듦 = 가짜 소모) · "hot"(화면 꺼진 채 과열)
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
    /** 전압으로 본 잔량 게이지 드리프트 (쉬는 표본이 모자라면 null) */
    val drift: GaugeDrift? = null,
    /** 재부팅·강제 재시작 전후 잔량 회복 (최근 것부터) */
    val recoveries: List<RestartRecovery> = emptyList(),
    /** 최근 24시간, 충전 안 하고 화면 꺼진 채 40℃ 이상이던 표본 수 (15분마다 하나) */
    val hotIdleCount: Int = 0,
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
        val drift = GaugeDriftRule.estimate(sorted, now, anomalies)
        val recoveries = GaugeDriftRule.recoveries(sorted, anomalies)
        val hot = sorted.count { it.t >= now - 24 * 3_600_000L && it.t <= now && !it.plugged && !it.interactive && (it.tempDeciC ?: 0) >= HOT_DECI_C }
        // 판정엔 최근 것만 — 3주 전 한 번의 점프가 30일 내내 '잔량 표시 오류 의심'으로 묶어 두지 않게 (목록엔 다 남긴다)
        val recentAnomalies = anomalies.filter { it.t >= now - RECENT_ANOMALY_MS }
        val verdict = verdict(span, w24, idle24, overnight, overnightLabel, recentAnomalies, capacity, experiments, drift, recoveries, hot)
        return Analysis(sorted.size, span, w1, w8, w24, idle24, overnight, overnightLabel, anomalies, capacity, experiments, verdict, drift, recoveries, hot)
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
        drift: GaugeDrift? = null, recoveries: List<RestartRecovery> = emptyList(), hotIdleCount: Int = 0,
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
        // 가짜 소모: 전하량 계수기로 잰 실제 소모가 표시 소모보다 눈에 띄게 적으면 게이지 오차 (100% 의 기준은 충전 곡선으로 안 실제 용량, 모르면 공칭)
        val fullMah = capacity?.estimatedMah ?: nominalMah?.toDouble()
        val realPph = w24.mahPerHour?.let { mah -> fullMah?.let { mah / it * 100.0 } }
        val shownPph = w24.percentPerHour
        if (realPph != null && shownPph != null && shownPph - realPph >= 1.5) {
            reasons += "표시 소모 시간당 ${fmt(shownPph)}%지만 전하량 계수기로는 ${fmt(realPph)}% — 차이만큼은 게이지 오차(가짜 소모)"
            tags += "phantom"
        }
        if (drift != null && drift.suspicious) {
            reasons += drift.headline + " (신뢰도 ${drift.confidence})"
            tags += "drift"
        }
        recoveries.firstOrNull()?.let { r ->
            if (r.gained >= Gauge.REBOOT_JUMP_MIN) reasons += "마지막 재시작 때 잔량 ${r.before}% → ${r.after}% (+${r.gained}) — 게이지 드리프트가 있었다는 증거"
        }
        if (hotIdleCount >= HOT_MIN_SAMPLES) {
            reasons += "최근 24시간 충전 안 하고 화면 꺼진 채 ${HOT_DECI_C / 10}℃ 이상이 ${hotIdleCount}회(15분 표본) — 백그라운드 CPU·무선 과열, 진짜 소모 의심"
            tags += "hot"
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
        } else if (("drift" in tags || "phantom" in tags) && grade == Grade.NORMAL) {
            grade = Grade.CAUTION
            headline = "잔량 표시 오차 의심 — $headline"
        } else if ("capacity" in tags && grade == Grade.NORMAL) {
            grade = Grade.CAUTION
            headline = "배터리 용량 저하 의심 — $headline"
        } else if ("hot" in tags && grade == Grade.NORMAL) {
            grade = Grade.CAUTION
            headline = "화면 꺼진 채 과열 — $headline"
        }
        return Verdict(grade, headline, reasons, tags)
    }

    companion object {
        /** 화면 꺼진 채 이 온도(0.1℃) 이상이면 뜨거운 것으로 */
        const val HOT_DECI_C = 400
        /** 뜨거운 표본이 이만큼(1시간어치) 넘어야 말한다 */
        const val HOT_MIN_SAMPLES = 4
        /** 판정에 쓰는 잔량 표시 이상은 최근 이만큼 안의 것 */
        const val RECENT_ANOMALY_MS = 7L * 24 * 3_600_000L
        fun fmt(x: Double): String = String.format(Locale.US, "%.1f", x)
        fun overnightDate(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }
}
