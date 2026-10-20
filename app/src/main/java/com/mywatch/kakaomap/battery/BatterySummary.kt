package com.mywatch.kakaomap.battery

import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.Status
import com.mywatch.battery.core.Thresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 배터리 화면과 summary.txt 가 같이 쓰는 글 만들기 */
object BatterySummary {

    private val clock = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

    fun time(t: Long): String = clock.format(Date(t))

    /** "57% · 3.91V · 33.1℃ · −84mA · 방전 중" */
    fun nowLine(s: BatterySample?): String {
        if (s == null) return "읽는 중…"
        val parts = ArrayList<String>()
        parts += "${s.level}%"
        s.voltageMv?.let { parts += String.format(Locale.US, "%.2fV", it / 1000.0) }
        s.tempDeciC?.let { parts += String.format(Locale.US, "%.1f℃", it / 10.0) }
        s.currentUa?.let { parts += "${it / 1000}mA" }
        parts += when {
            s.status == Status.FULL -> "완충"
            s.plugged || s.status == Status.CHARGING -> "충전 중"
            else -> "방전 중"
        }
        return parts.joinToString(" · ")
    }

    /** "시간당 2.9% (실측 13 mAh/h)" / "아직 모름" */
    fun rateLine(r: Rate): String {
        val pph = r.percentPerHour ?: return if (r.hours > 0) "아직 모름 (${DrainAnalyzer.fmt(r.hours)}시간치)" else "충전기 없이 쓴 기록 없음"
        val mah = r.mahPerHour?.let { " (실측 ${DrainAnalyzer.fmt(it)} mAh/h)" } ?: ""
        return "시간당 ${DrainAnalyzer.fmt(pph)}%$mah"
    }

    fun gradeOf(r: Rate, hours: Int): Grade = when (hours) {
        24 -> Thresholds.mixed24h(r.percentPerHour)
        else -> Thresholds.mixed24h(r.percentPerHour)
    }

    fun overnightLine(a: Analysis): String {
        val r = a.overnight ?: return "아직 밤(00~07시)을 지난 기록이 없음"
        val pph = r.percentPerHour ?: return "${a.overnightLabel}: 기록이 짧아 모름"
        return "${a.overnightLabel}: 시간당 ${DrainAnalyzer.fmt(pph)}%, 총 ${DrainAnalyzer.fmt(r.dropPercent)}% — ${Thresholds.overnight(pph).label}"
    }

    fun capacityLine(a: Analysis, supported: Boolean): String {
        val c = a.capacity
        if (c == null) return if (supported) "아직 충전 곡선이 충분하지 않음 (40% 넘게 한 번에 충전하면 추정)" else "이 워치는 전하량 계수기를 앱에 열어 주지 않아 추정 불가"
        val pct = c.percentOfNominal?.let { " (공칭 ${c.nominalMah} mAh 의 ${DrainAnalyzer.fmt(it)}%)" } ?: ""
        return "추정 ${DrainAnalyzer.fmt(c.estimatedMah)} mAh$pct — 충전 세션 ${c.sessions}회"
    }

    fun experimentLines(a: Analysis, now: Long): List<String> = a.experiments.map { e ->
        val span = ((e.end ?: now) - e.start) / 60_000
        val state = if (e.end == null) "진행 중" else "끝"
        "'${e.name}' $state · ${span / 60}시간 ${span % 60}분 · ${rateLine(e.rate)} — ${e.grade.label}"
    }

    /** summary.txt 본문 */
    fun text(a: Analysis, live: BatterySample?, model: String, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("갤럭시워치 배터리 기록 요약 — ${time(now)}")
        appendLine("기기: $model")
        appendLine("지금: ${nowLine(live)}")
        appendLine()
        appendLine("[판정] ${a.verdict.grade.label} — ${a.verdict.headline}")
        a.verdict.reasons.forEach { appendLine(" · $it") }
        appendLine()
        appendLine("[소모율] 1시간 ${rateLine(a.w1)} / 8시간 ${rateLine(a.w8)} / 24시간 ${rateLine(a.w24)}")
        appendLine("[대기(화면 꺼짐) 24시간] ${rateLine(a.idle24)} — ${Thresholds.idle(a.idle24.percentPerHour).label}")
        appendLine("[밤사이] ${overnightLine(a)}")
        appendLine("[잔량 표시 이상] " + if (a.anomalies.isEmpty()) "없음" else a.anomalies.size.toString() + "회")
        a.anomalies.takeLast(10).forEach { appendLine(" · ${time(it.t)} ${it.label}: ${it.detail}") }
        appendLine("[용량 추정] ${capacityLine(a, live?.chargeUah != null)}")
        val ex = experimentLines(a, now)
        appendLine("[실험] " + if (ex.isEmpty()) "없음" else "")
        ex.forEach { appendLine(" · $it") }
        appendLine()
        appendLine("기록 ${a.samples}개, ${DrainAnalyzer.fmt(a.spanHours)}시간치")
        appendLine("기준: ${Thresholds.SOURCE_IDLE} / ${Thresholds.SOURCE_MIXED}")
    }
}
