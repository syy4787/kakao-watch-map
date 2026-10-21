package doctor

import doctor.fix.JournalEntry
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

    fun of(before: Facts, after: Facts, journal: List<JournalEntry>): Comparison? {
        val rows = ArrayList<CompareRow>()
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
        val bs = before.stats?.takeIf { (it.onBatteryHours ?: 0.0) >= MIN_HOURS }
        val `as` = after.stats?.takeIf { (it.onBatteryHours ?: 0.0) >= MIN_HOURS }
        row(TOTAL, bs?.percentPerHour, `as`?.percentPerHour, 0.2, ::pph)
        row(SCREEN_OFF, bs?.let(::screenOffPph), `as`?.let(::screenOffPph), 0.2, ::pph)
        row(AWAKE, bs?.screenOffAwakeRatio?.times(100), `as`?.screenOffAwakeRatio?.times(100), 3.0, ::pct)
        row(GMS, bs?.let(::gmsShare), `as`?.let(::gmsShare), 3.0, ::pct)
        row(IDLE, before.watchMetrics["idle_pph"] ?: before.watch?.idle24?.percentPerHour, after.watchMetrics["idle_pph"] ?: after.watch?.idle24?.percentPerHour, 0.2, ::pph)
        row(NIGHT, before.watchMetrics["night_pph"] ?: before.watch?.overnight?.percentPerHour, after.watchMetrics["night_pph"] ?: after.watch?.overnight?.percentPerHour, 0.2, ::pph)
        row(GESTURE, before.watchMetrics["gesture_wakes"], after.watchMetrics["gesture_wakes"], 10.0) { "${it.toInt()}번" }
        if (rows.isEmpty()) return null

        val from = before.collectedAt ?: 0L
        val to = after.collectedAt ?: Long.MAX_VALUE
        val actions = journal.filter { it.time in (from + 1)..to && it.ok && !it.undone }.map { it.id }
        return Comparison(from, to, actions, rows, headline(rows, actions.isNotEmpty()))
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
