package com.mywatch.kakaomap.battery

import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.GaugeDrift
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.Status
import com.mywatch.battery.core.SummaryMetrics
import com.mywatch.battery.core.Thresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 배터리 화면과 summary.txt 가 같이 쓰는 글 만들기 */
object BatterySummary {
    private const val K_SUMMARY_AT = "summary_written_at"
    /** 하루에 한 번 (시각이 조금씩 밀려도 되게 20시간) */
    const val DAILY_MS = 20L * 3_600_000L

    /**
     * 15분 작업에서: 마지막으로 쓴 지 하루가 지났으면 요약 파일을 새로 쓴다. 시각은 먼저 적는다 — 분석·쓰기가 실패해도
     * 15분마다 30일치를 다시 읽고 분석하지 않게(이 앱이 배터리를 덜 쓰게). 실패한 날은 다음 날 다시
     */
    fun writeDailyIfDue(context: android.content.Context, log: BatteryLog, now: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(BatteryLog.PREFS, android.content.Context.MODE_PRIVATE)
        if (now - p.getLong(K_SUMMARY_AT, 0L) < DAILY_MS) return
        p.edit().putLong(K_SUMMARY_AT, now).apply()
        val all = log.readAll()
        val ins = BatteryInsights.compute(context, all, now)
        val a = ins.analysis ?: return
        log.writeSummary(text(a, null, android.os.Build.MODEL ?: "", now, ins))
    }

    private val clock = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

    fun time(t: Long): String = clock.format(Date(t))

    /** "57% · 3.91V · 33.1℃ · −84mA · 방전 중" — 게이지 드리프트가 의심되면 "(전압 기준 약 N%)" 를 덧붙인다 */
    fun nowLine(s: BatterySample?, drift: GaugeDrift? = null): String {
        if (s == null) return "읽는 중…"
        val parts = ArrayList<String>()
        parts += "${s.level}%" + (drift?.takeIf { it.suspicious }?.let { " (전압 기준 약 ${(s.level - it.driftPoints).coerceIn(0, 100)}%)" } ?: "")
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

    /** PC 툴킷이 읽는 숫자 한 줄 (조치 추천·치료 전후 비교) — [SummaryMetrics] */
    fun metricsLine(a: Analysis, insights: Insights?): String = SummaryMetrics.line(linkedMapOf(
        "idle_pph" to (insights?.breakdown?.idlePph ?: a.idle24.percentPerHour),
        "day_pph" to a.w24.percentPerHour,
        "night_pph" to a.overnight?.percentPerHour,
        "screen_pph" to insights?.breakdown?.screenPph,
        "screen_share" to insights?.breakdown?.screenShare,
        "wakes" to insights?.wakes?.wakes?.toDouble(),
        "short_wakes" to insights?.wakes?.shortWakes?.toDouble(),
        "gesture_wakes" to insights?.wakes?.gestureLikeWakes?.toDouble(),
        "notif_wakes" to insights?.wakes?.notificationWakes?.toDouble(),
        "notifications" to insights?.wakes?.notifications?.toDouble(),
        "drift" to a.drift?.driftPoints?.toDouble(),
    ))

    /** summary.txt 본문 */
    fun text(a: Analysis, live: BatterySample?, model: String, now: Long = System.currentTimeMillis(), insights: Insights? = null): String = buildString {
        appendLine("갤럭시워치 배터리 기록 요약 — ${time(now)}")
        appendLine("기기: $model")
        appendLine("지금: ${nowLine(live, a.drift)}")
        appendLine()
        appendLine("[판정] ${a.verdict.grade.label} — ${a.verdict.headline}")
        a.verdict.reasons.forEach { appendLine(" · $it") }
        appendLine()
        insights?.breakdown?.let { b ->
            appendLine("[사용 내역 24시간] ${b.headline}")
            b.screenPph?.let { appendLine(" · 화면 켜진 동안 시간당 ${DrainAnalyzer.fmt(it)}%") }
            b.steepest.forEach { h ->
                appendLine(" · ${time(h.start)}~ ${DrainAnalyzer.fmt(h.dropPercent)}% (시간당 ${DrainAnalyzer.fmt(h.pph)}%)" +
                    (h.screenOnMs?.let { " 화면 ${it / 60_000}분" } ?: "") + (h.topApp?.let { " [$it]" } ?: "") +
                    (h.culprit?.let { " 뒤에서 ${it.use.pkg}: ${it.summary}" } ?: ""))
            }
        }
        insights?.wakes?.let { w ->
            appendLine("[화면 켜짐] ${w.wakes}번 (5초 안에 꺼짐 ${w.shortWakes}, 알림이 켬 ${w.notificationWakes}, 알림 ${w.notifications}개: " +
                w.topNotifiers.take(3).joinToString(", ") { "${it.first} ${it.second}" } + ")")
            w.advice().forEach { appendLine(" · $it") }
        }
        insights?.issues?.forEach { i ->
            appendLine("[알려진 문제] ${i.title} — ${i.why}")
            i.steps.forEachIndexed { n, st -> appendLine("  ${n + 1}) $st") }
        }
        insights?.nightEffect?.headline?.let { appendLine("[밤 모드 효과] $it") }
        insights?.night?.let { n ->
            appendLine("[밤사이 뒤에서] " + n.ranks.take(5).joinToString(" / ") { "${it.use.pkg}: ${it.summary}" })
            n.advice.forEach { appendLine(" · $it") }
        }
        appendLine("[소모율] 1시간 ${rateLine(a.w1)} / 8시간 ${rateLine(a.w8)} / 24시간 ${rateLine(a.w24)}")
        appendLine("[대기(화면 꺼짐) 24시간] ${rateLine(a.idle24)} — ${Thresholds.idle(a.idle24.percentPerHour).label}")
        appendLine("[밤사이] ${overnightLine(a)}")
        appendLine("[잔량 표시 이상] " + if (a.anomalies.isEmpty()) "없음" else a.anomalies.size.toString() + "회")
        a.anomalies.takeLast(10).forEach { appendLine(" · ${time(it.t)} ${it.label}: ${it.detail}") }
        appendLine("[잔량 표시 점검] " + (a.drift?.let { "${it.headline} (신뢰도 ${it.confidence}, 쉬는 표본 ${it.restSamples}개, ${String.format(Locale.US, "%.2f", it.restMv / 1000.0)}V)" } ?: "쉬는 표본이 모자라 아직 모름"))
        a.drift?.advice?.let { appendLine(" · $it") }
        a.recoveries.take(5).forEach { appendLine(" · 재시작 ${time(it.t)}: ${it.before}% → ${it.after}% (${if (it.gained >= 0) "+" else ""}${it.gained})" + (if (it.userMarked) " (직접 표시)" else "")) }
        appendLine("[용량 추정] ${capacityLine(a, live?.chargeUah != null)}")
        val ex = experimentLines(a, now)
        appendLine("[실험] " + if (ex.isEmpty()) "없음" else "")
        ex.forEach { appendLine(" · $it") }
        insights?.levers?.let { appendLine("[레버 점검] $it") }
        appendLine(metricsLine(a, insights))
        appendLine()
        appendLine("기록 ${a.samples}개, ${DrainAnalyzer.fmt(a.spanHours)}시간치")
        appendLine("기준: ${Thresholds.SOURCE_IDLE} / ${Thresholds.SOURCE_MIXED}")
    }
}
