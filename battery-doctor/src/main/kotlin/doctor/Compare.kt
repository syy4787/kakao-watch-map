package doctor

import com.mywatch.battery.core.DrainAnalyzer
import doctor.fix.JournalEntry
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

enum class Change {
    BETTER, SAME, WORSE;

    val label: String get() = when (this) { BETTER -> "좋아짐"; SAME -> "비슷"; WORSE -> "나빠짐" }
}

/** 비교 한 줄: [before]·[after] 는 보여 줄 글 */
data class CompareRow(val label: String, val before: String, val after: String, val change: Change)

/**
 * 지난 수집(조치 전)과 이번 수집(조치 후)의 비교. [actions] = 두 수집 사이에 적용해 아직 되돌리지 않은 조치 id.
 * [Compare.of] 의 actionsBetween = 그 '사이'를 PC 시계로 (조치 기록과 같은 시계 — 모르면 수집 시각으로)
 */
data class Comparison(val beforeAt: Long, val afterAt: Long, val actions: List<String>, val rows: List<CompareRow>, val headline: String)

/**
 * 치료 전후 비교 — 자동 치료(메뉴 8)·차단 조치(메뉴 4) 뒤에 하루쯤 쓰고 다시 수집하면 지난 수집과 나란히 놓고 정말 나아졌는지 본다.
 * 값은 낮을수록 좋다(소모율·깨어 있던 비율·Play 서비스 몫·금방 꺼진 화면). 15% 넘게, 그리고 항목마다 정한 만큼 넘게 달라져야 변화로 본다
 * (한두 번 수집의 흔들림을 변화로 읽지 않게). 측정 구간이 2시간이 안 되는 dumpsys 값은 비교하지 않는다.
 */
object Compare {
    const val TOTAL = "전체 소모 (dumpsys)"
    const val SCREEN_OFF = "화면 꺼짐 소모 (dumpsys)"
    const val AWAKE = "화면 꺼진 채 깨어 있던 비율"
    const val GMS = "Play 서비스 몫"
    const val IDLE = "대기 소모 (워치 기록)"
    const val NIGHT = "밤사이 소모 (워치 기록)"
    const val GESTURE = "금방 꺼진 화면 (하루)"

    const val REL = 0.15
    const val MIN_HOURS = 2.0

    fun of(before: Facts, after: Facts, journal: List<JournalEntry>, zone: ZoneId = ZoneId.systemDefault(), actionsBetween: LongRange? = null): Comparison? {
        val rows = ArrayList<CompareRow>()
        val beforeAt = before.collectedAt ?: return null
        val afterAt = after.collectedAt ?: return null
        if (afterAt <= beforeAt) return null
        fun pph(v: Double) = String.format(Locale.US, "시간당 %.1f%%", v)
        fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v)
        fun row(label: String, b: Double?, a: Double?, minAbs: Double, fmt: (Double) -> String) {
            if (b == null || a == null || b.isNaN() || a.isNaN()) return
            val d = a - b
            val change = when {
                abs(d) < minAbs || abs(d) <= abs(b) * REL -> Change.SAME
                d < 0 -> Change.BETTER
                else -> Change.WORSE
            }
            rows += CompareRow(label, fmt(b), fmt(a), change)
        }
        // dumpsys: '마지막 충전 뒤' 통계라, 이번 창이 지난 수집 때보다 길면(충전·초기화 없이 이어짐) 지난 구간을 품고 있어 비교하지 않는다
        val bs = before.stats?.takeIf { (it.onBatteryHours ?: 0.0) >= MIN_HOURS }
        val `as` = after.stats?.takeIf { (it.onBatteryHours ?: 0.0) >= MIN_HOURS && (it.onBatteryMs ?: Long.MAX_VALUE) <= afterAt - beforeAt }
        row(TOTAL, bs?.percentPerHour, `as`?.percentPerHour, 0.2, ::pph)
        row(SCREEN_OFF, bs?.let(::screenOffPph), `as`?.let(::screenOffPph), 0.2, ::pph)
        row(AWAKE, bs?.screenOffAwakeRatio?.times(100), `as`?.screenOffAwakeRatio?.times(100), 3.0, ::pct)
        row(GMS, bs?.let(::gmsShare), `as`?.let(::gmsShare), 3.0, ::pct)

        // 워치 쪽: 요약 지표가 양쪽 다 있고 이번 것이 지난 수집 뒤에 새로 쓰였을 때만 그것으로, 아니면 워치 기록(CSV)으로 — 섞지 않는다
        val bAt = before.watchMetrics["at"]
        val aAt = after.watchMetrics["at"]
        // 요약의 숫자는 하루치(24시간) — 지난 수집(치료) 뒤 하루가 지나 쓴 것이어야 치료 전 시간을 품지 않는다 (워치 앱 v2.43 부터 수집 때마다 새로 씀)
        val freshSummary = bAt != null && aAt != null && aAt > bAt && aAt - beforeAt >= 24 * 3_600_000L
        if (freshSummary) {
            row(IDLE, before.watchMetrics["idle_pph"], after.watchMetrics["idle_pph"], 0.2, ::pph)
            row(NIGHT, before.watchMetrics["night_pph"], after.watchMetrics["night_pph"], 0.2, ::pph)
            row(GESTURE, before.watchMetrics["gesture_wakes"], after.watchMetrics["gesture_wakes"], 10.0) { "${it.toInt()}번" }
        } else {
            // 양쪽 다 워치 기록(CSV)으로 — 이번 쪽은 지난 수집 뒤의 기록만으로 다시 계산 (앞의 하루와 겹치지 않게)
            val since = after.watchSamples.filter { it.t > beforeAt }
            val afterLog = if (since.size >= 2) runCatching { DrainAnalyzer(after.nominalMah, zone).analyze(since, afterAt) }.getOrNull() else null
            row(IDLE, before.watch?.idle24?.percentPerHour, afterLog?.idle24?.percentPerHour, 0.2, ::pph)
            row(NIGHT, before.watch?.overnight?.percentPerHour, afterLog?.overnight?.percentPerHour, 0.2, ::pph)
        }
        if (rows.isEmpty()) return null

        // 조치 기록은 PC 시계 — 수집 시각(워치 시계)과 몇 분 어긋날 수 있어 알면 PC 시계 창으로 고른다
        val actions = journal.filter { it.time in (actionsBetween ?: (beforeAt + 1)..afterAt) && it.ok && !it.undone }.map { it.id }
        return Comparison(beforeAt, afterAt, actions, rows, headline(rows, actions.isNotEmpty()))
    }

    /** 화면 꺼진 동안 시간당 % (dumpsys 의 화면 꺼짐 소모 ÷ 화면 꺼진 시간) */
    private fun screenOffPph(s: doctor.parse.BatteryStats): Double? {
        val off = s.screenOffMs?.takeIf { it >= MIN_HOURS * 3_600_000L } ?: return null
        val drop = s.dischargeScreenOff ?: return null
        return drop / (off / 3_600_000.0)
    }

    /** 추정 전력 중 Play 서비스 몫 (%) */
    private fun gmsShare(s: doctor.parse.BatteryStats): Double? {
        val total = s.powerItems.sumOf { it.mah }.takeIf { it > 0 } ?: return null
        val gms = s.powerItems.filter { it.pkg == Analyzer.GMS }.sumOf { it.mah }
        return gms / total * 100
    }

    /** 가장 중요한 줄(화면 꺼짐 → 대기 → 밤사이 → 전체) 하나로 한 문장 */
    private fun headline(rows: List<CompareRow>, treated: Boolean): String {
        val order = listOf(SCREEN_OFF, IDLE, NIGHT, TOTAL, AWAKE, GMS, GESTURE)
        fun pick(c: Change) = order.mapNotNull { l -> rows.firstOrNull { it.label == l && it.change == c } }.firstOrNull()
        fun say(r: CompareRow): String {
            val b = number(r.before)
            val a = number(r.after)
            val pct = if (b != null && a != null && b > 0) " (${abs(((a - b) / b * 100)).toInt()}% ${if (a < b) "줄어듦" else "늘어남"})" else ""
            return "${r.label} ${r.before} → ${r.after}$pct"
        }
        val prefix = if (treated) "조치 뒤 " else "지난 수집보다 "
        val better = pick(Change.BETTER)
        val worse = pick(Change.WORSE)
        return when {
            better != null && worse != null -> "${prefix}좋아졌습니다: ${say(better)}. 다만 나빠진 것도 있습니다: ${say(worse)}"
            better != null -> "${prefix}좋아졌습니다: ${say(better)}"
            worse != null -> "${prefix}나빠졌습니다: ${say(worse)} — 조치를 되돌리거나(메뉴 5) 다른 원인을 찾아보세요"
            else -> "${prefix}차이가 크지 않습니다 — 원인이 다른 곳(하드웨어·잔량 표시·사용 습관)일 수 있습니다"
        }
    }

    private fun number(s: String): Double? = Regex("([0-9]+(?:\\.[0-9]+)?)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
}
