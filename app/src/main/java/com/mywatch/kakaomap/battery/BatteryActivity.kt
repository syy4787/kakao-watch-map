package com.mywatch.kakaomap.battery

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.CalibrationRule
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Projection
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.Thresholds
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.WearList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 배터리 화면: 15분마다 쌓인 기록으로 소모율(%/h·실측 mAh/h)·판정·밤사이·잔량 표시 이상·용량 추정을 보여 주고,
 * 실험(비행기 모드 등) 표시와 요약 파일 쓰기를 한다. 화면이 열려 있는 동안은 2초마다 지금 값(전압·전류)을 새로 읽는다.
 */
class BatteryActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var log: BatteryLog
    private var analysis: Analysis? = null
    /** 사용 내역·알려진 문제·조치 효과 (IO 에서 함께 계산) */
    private var insights: Insights? = null
    /** "지금 페이스(시간당 4.0%)면 오후 10:05에 15%, …" */
    private var forecast: String? = null
    private var live: BatterySample? = null
    private var picking = false
    private var notice: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            runCatching { BatteryProbe.read(this@BatteryActivity, Event.APP_OPEN) }.getOrNull()?.let { live = it }
            renderLive()
            handler.postDelayed(this, LIVE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        log = BatteryLog(this)
        ui.showStatus("배터리 기록 읽는 중…")
        // 화면을 연 순간도 한 줄 (15분 사이의 변화를 더 촘촘히)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { BatteryProbe.read(this@BatteryActivity, Event.APP_OPEN)?.let { log.append(it) } } }
        reload()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun reload() {
        lifecycleScope.launch {
            val (ins, last) = withContext(Dispatchers.IO) {
                runCatching {
                    val all = log.readAll()
                    forecast = Projection.forecast(all, System.currentTimeMillis())?.label(java.time.ZoneId.systemDefault())
                    BatteryInsights.compute(this@BatteryActivity, all) to all.maxByOrNull { it.t }
                }.onFailure { ErrorLog.record("배터리·분석", it) }.getOrNull() ?: (null to null)
            }
            insights = ins
            analysis = ins?.analysis
            lastLogged = last
            render()
        }
    }

    private fun render() {
        val a = analysis
        if (a == null) { ui.showStatus("기록을 읽지 못했습니다"); return }
        if (picking) { ui.rows = pickRows(); return }
        val now = System.currentTimeMillis()
        val rows = mutableListOf<Row>()
        rows += Row.Header("배터리", BatterySummary.nowLine(live ?: lastSample(a), a.drift))
        notice?.let { rows += Row.Note(it) }
        forecast?.let { rows += Row.Note(it) }

        rows += Row.Header("소모율")
        rows += rateRow("1시간", a.w1, Thresholds.mixed24h(a.w1.percentPerHour))
        rows += rateRow("8시간", a.w8, Thresholds.mixed24h(a.w8.percentPerHour))
        rows += rateRow("24시간", a.w24, Thresholds.mixed24h(a.w24.percentPerHour))
        rows += rateRow("대기(화면 꺼짐)", a.idle24, Thresholds.idle(a.idle24.percentPerHour))

        rows += Row.Header("판정")
        val v = a.verdict
        rows += Row.Step(
            color(v.grade), if (v.grade == Grade.ABNORMAL) R.drawable.ic_warning else R.drawable.ic_battery, v.grade.label,
            v.headline, v.reasons.takeIf { it.isNotEmpty() }?.joinToString("\n"), key = "verdict",
        )

        rows += usageRows()
        rows += guardianRows()
        rows += appRows()

        rows += Row.Header("밤사이")
        rows += Row.Note(BatterySummary.overnightLine(a))

        rows += Row.Header("잔량 표시 이상 (최근)")
        if (a.anomalies.isEmpty()) rows += Row.Note("없음") else a.anomalies.takeLast(5).reversed().forEach {
            rows += Row.Step(color(Grade.CAUTION), R.drawable.ic_warning, null, it.label, "${BatterySummary.time(it.t)} · ${it.detail}", key = "anom:${it.t}:${it.kind}")
        }

        rows += driftRows(a)

        rows += Row.Header("용량 추정")
        rows += Row.Note(BatterySummary.capacityLine(a, live?.chargeUah != null))

        rows += Row.Header("실험")
        val running = a.experiments.firstOrNull { it.end == null }
        if (running != null) {
            BatterySummary.experimentLines(a, now).lastOrNull()?.let { rows += Row.Note(it) }
            rows += Row.Action(A_EXP_END, R.drawable.ic_close, "실험 끝", accent = true)
        } else {
            a.experiments.takeLast(3).let { done -> BatterySummary.experimentLines(a.copy(experiments = done), now).forEach { rows += Row.Note(it) } }
            rows += Row.Note("비행기 모드·절전 모드·시계만 사용으로 몇 시간 써 보면 원인이 소프트웨어인지 하드웨어인지 갈립니다")
            rows += Row.Action(A_EXP_START, R.drawable.ic_play, "실험 시작", accent = true)
        }

        rows += Row.Header("내보내기")
        rows += Row.Note("기록 ${a.samples}개 · ${DrainAnalyzer.fmt(a.spanHours)}시간치\n${log.file.path}")
        rows += Row.Action(A_SUMMARY, R.drawable.ic_edit, "요약 새로 쓰기")

        rows += Row.Header("도움말")
        rows += Row.Note(HELP)
        ui.rows = rows
    }

    /** 지킴이: 권한 상태, 지금 해 둔 것, 켜고 끄기 */
    private fun guardianRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row.Header("배터리 지킴이")
        val levers = Levers(this)
        val stats = HealthReader(this).available
        val st = Guardian.state(this)
        val cfg = Guardian.config(this)
        if (!levers.canWriteSecure && !stats) {
            rows += Row.Note("PC 툴킷에서 메뉴 7(권한 주기)을 한 번 실행하면 켜집니다 — 앱별 소모를 읽고, 밤과 비정상 소모 때 절전 모드·AOD 를 스스로 조절합니다")
        } else {
            val powers = listOfNotNull(if (stats) "앱별 통계" else null, if (levers.canWriteSecure) "설정 조절" else null)
            rows += Row.Note("권한: ${powers.joinToString(" · ")}" + (if (!levers.canWriteSecure) " (설정 조절 권한 없음 — 알림만)" else ""))
        }
        val now = System.currentTimeMillis()
        val status = when {
            st.nightActive -> "지금: 밤 모드 적용 중(아침·충전 때 원래대로)"
            st.abnormalActive -> "지금: 비정상 소모 대응 중(절전 모드 켬)"
            now < st.userOverrideUntil -> "지금: 사용자가 꺼서 ${BatterySummary.time(st.userOverrideUntil)}까지 쉼"
            else -> "지금: 대기 중"
        }
        rows += Row.Note(status + (Guardian.lastAction(this)?.let { "\n마지막: $it" } ?: ""))
        if (st.nightActive || st.abnormalActive || levers.holdsAnything) rows += Row.Action(A_RESTORE, R.drawable.ic_refresh, "지금 되돌리기", accent = true)
        fun toggle(id: String, on: Boolean, label: String) = Row.Action(id, if (on) R.drawable.ic_check else R.drawable.ic_close, "$label · ${if (on) "켜짐" else "꺼짐"}", accent = on)
        rows += toggle("$A_TOGGLE${T_NIGHT_SAVER}", cfg.nightSaver, "밤(00~07시) 절전 모드")
        rows += toggle("$A_TOGGLE${T_NIGHT_AOD}", cfg.nightAodOff, "밤에 AOD 끄기" + (if (levers.aodKey == null && levers.canWriteSecure) " (이 워치엔 키 없음)" else ""))
        rows += toggle("$A_TOGGLE${T_NIGHT_LOCATION}", cfg.nightLocationOff, "밤에 위치 끄기")
        rows += toggle("$A_TOGGLE${T_NIGHT_SYNC}", cfg.nightSyncOff, "밤에 계정 동기화 끄기")
        rows += toggle("$A_TOGGLE${T_ABNORMAL}", cfg.abnormalSaver, "비정상 소모면 바로 절전")
        rows += toggle("$A_TOGGLE${T_REMINDER}", cfg.chargeReminder, "잠자기 전 충전 알림")
        rows += Row.Note("밤 모드는 화면이 30분 넘게 꺼져 있을 때만 시작하고, 절전을 손수 끄면 12시간 쉽니다")
        insights?.nightEffect?.let { e ->
            rows += Row.Note(e.headline?.let { "효과: $it" }
                ?: "효과: 비교할 밤이 아직 모자람 (켠 밤 ${e.with.size} · 안 켠 밤 ${e.without.size} — 각 2밤 이상이면 밤 소모가 얼마나 줄었는지 보여 줍니다)")
        }
        return rows
    }

    /**
     * 잔량 표시 점검: 쉬는 전압으로 어림잡은 잔량과 표시 잔량의 차이(게이지 드리프트), 마지막 재시작 전후 잔량,
     * '강제 재시작' 표시 남기기, 게이지 보정 루틴
     */
    private fun driftRows(a: Analysis): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row.Header("잔량 표시 점검")
        val d = a.drift
        if (d != null) {
            val g = when {
                d.certain && d.driftPoints > 0 -> Grade.ABNORMAL      // 높게 세는 중 — 갑자기 꺼질 수 있다
                d.suspicious -> Grade.CAUTION
                else -> Grade.NORMAL
            }
            val sub = listOfNotNull(d.advice, "신뢰도 ${d.confidence} · 쉬는 표본 ${d.restSamples}개 · ${String.format(java.util.Locale.US, "%.2f", d.restMv / 1000.0)}V").joinToString("\n")
            rows += Row.Step(color(g), if (g == Grade.NORMAL) R.drawable.ic_battery else R.drawable.ic_warning, if (g == Grade.NORMAL) null else g.label, d.headline, sub, key = "drift")
        } else {
            rows += Row.Note("화면 꺼진 채 쉬는 기록이 3개(45분) 넘게 쌓이면 쉬는 전압으로 잔량 표시가 맞는지 점검합니다")
        }
        a.recoveries.firstOrNull()?.let { r ->
            val sign = if (r.gained >= 0) "+" else ""
            rows += Row.Note("마지막 재시작 ${BatterySummary.time(r.t)}: ${r.before}% → ${r.after}% ($sign${r.gained})" + (if (r.userMarked) " · 직접 표시" else ""))
        }
        rows += Row.Action(A_FORCED_RESTART, R.drawable.ic_refresh, "강제 재시작 표시 남기기")
        val cs = Calibration.state(this)
        if (cs.active) {
            val hold = cs.fullSince.takeIf { it > 0 }?.let { "\n완충 유지 ${((System.currentTimeMillis() - it) / 60_000L).coerceAtLeast(0)}분째 (60분이면 다음 단계)" } ?: ""
            rows += Row.Note("게이지 보정 ${CalibrationRule.stepLabel(cs.step)}$hold")
            rows += Row.Action(A_CAL_STOP, R.drawable.ic_close, "보정 그만두기")
        } else {
            if (cs.step == CalibrationRule.STEP_DONE) {
                val before = cs.driftBefore?.let { " · 시작 때 오차 ${it}포인트" } ?: ""
                val nowDrift = d?.let { " → 지금 ${it.driftPoints}포인트" } ?: ""
                rows += Row.Note("보정 끝 ${BatterySummary.time(cs.finishedAt)}$before$nowDrift")
            }
            rows += Row.Note("보정 루틴: 완충 + 1시간 → ${CalibrationRule.DRAIN_TO}% 아래까지 사용 → 다시 완충 + 1시간. 단계마다 알려 드립니다 (설정 > 배터리 > 배터리 보호(85% 제한)는 보정 동안 꺼 두세요 — 100%에 닿아야 합니다)")
            rows += Row.Action(A_CAL_START, R.drawable.ic_play, if (cs.step == CalibrationRule.STEP_DONE) "게이지 보정 다시" else "게이지 보정 시작")
        }
        return rows
    }

    /**
     * 배터리 사용 내역 (24시간): 화면 켜짐·꺼짐으로 나눈 몫, 가장 많이 닳은 시간과 그때 화면의 앱·뒤에서 돈 앱
     */
    private fun usageRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row.Header("배터리 사용 내역 (24시간)")
        val b = insights?.breakdown
        if (b == null) {
            rows += Row.Note("충전 없이 쓴 기록이 2시간 넘게 쌓이면 어디에 썼는지 나눠 보여 줍니다")
            return rows
        }
        rows += Row.Note(b.headline)
        b.idlePph?.let { pph ->
            val g = Thresholds.idle(pph)
            rows += Row.Step(color(g), R.drawable.ic_battery, if (g == Grade.UNKNOWN) null else g.label, "화면 꺼진 대기: 시간당 ${DrainAnalyzer.fmt(pph)}%",
                "기준 ${Thresholds.IDLE_NORMAL}% 이하 정상 · ${Thresholds.IDLE_ABNORMAL}% 넘으면 비정상 — 높으면 뒤에서 도는 앱·AOD 쪽", key = "usage:idle")
        }
        b.screenPph?.let { pph ->
            rows += Row.Note("화면 켜진 동안: 시간당 ${DrainAnalyzer.fmt(pph)}% — 화면 시간·밝기가 좌우")
        }
        if (!b.exactScreen) rows += Row.Note("PC 툴킷 메뉴 7(권한 주기) 뒤엔 화면 켜짐 시간까지 정확히 나눕니다")
        b.steepest.forEach { h ->
            val on = h.screenOnMs?.let { "화면 ${it / 60_000}분" + (h.topApp?.let { p -> "(${HealthStore.label(this, p)})" } ?: "") }
            val bg = h.culprit?.takeIf { it.score > 0 }?.let { "뒤에서: ${HealthStore.label(this, it.use.pkg)} ${it.summary}" }
            val g = Thresholds.mixed24h(h.pph)
            rows += Row.Step(color(g), R.drawable.ic_battery, if (g == Grade.NORMAL) null else g.label,
                "${hourLabel(h.start)} · ${DrainAnalyzer.fmt(h.dropPercent)}% (시간당 ${DrainAnalyzer.fmt(h.pph)}%)",
                listOfNotNull(on, bg).joinToString("\n").ifEmpty { null }, key = "hour:${h.start}")
        }
        return rows
    }

    /** "오후 7시~8시" */
    private fun hourLabel(t: Long): String {
        val h = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).hour
        fun name(x: Int) = (if (x % 24 < 12) "오전 " else "오후 ") + (if (x % 12 == 0) 12 else x % 12) + "시"
        return "${name(h)}~${name(h + 1).substringAfter(' ')}"
    }

    /** 앱별 소모 (최근 2시간) — 권한이 있을 때만. 알려진 문제(Play 서비스 과소모 등)는 맨 위에 해결 순서와 함께 */
    private fun appRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row.Header("앱별 소모 (최근 2시간)")
        if (!HealthReader(this).available) {
            rows += Row.Note("PC 툴킷 메뉴 7(권한 주기) 뒤에 보입니다")
            return rows
        }
        insights?.issues?.forEach { i ->
            rows += Row.Step(color(i.grade), R.drawable.ic_warning, i.grade.label, i.title,
                (listOf(i.why) + i.steps.mapIndexed { n, st -> "${n + 1}) $st" }).joinToString("\n"), key = "issue:${i.id}")
            rows += Row.Action("$A_APP_INFO${i.pkg}", R.drawable.ic_edit, "Play 서비스 앱 정보 열기", accent = true)
        }
        val ranked = insights?.culprits.orEmpty()
        if (ranked.isEmpty()) { rows += Row.Note("아직 두 번째 기록 전입니다 (15분 뒤부터)"); return rows }
        ranked.take(8).forEach { r ->
            val sub = (r.flags.filter { it != "이 앱" } + r.summary).joinToString(" · ")
            val name = HealthStore.label(this, r.use.pkg) + (if ("이 앱" in r.flags) " (이 앱)" else "")
            rows += Row.Step(color(r.grade), R.drawable.ic_battery, if (r.grade == Grade.NORMAL) null else r.grade.label, name, sub, key = "app:${r.use.pkg}")
        }
        return rows
    }

    private fun renderLive() {
        val a = analysis
        if (picking || a == null || ui.rows.isEmpty()) return
        val first = ui.rows.firstOrNull() as? Row.Header ?: return
        ui.adapter.replace(0, first.copy(subtitle = BatterySummary.nowLine(live ?: lastSample(a), a.drift)))
    }

    /** 배터리 상태를 못 읽는 환경(시험)에선 마지막 기록을 '지금' 으로 */
    private var lastLogged: BatterySample? = null
    private fun lastSample(a: Analysis): BatterySample? = if (a.samples > 0) lastLogged else null

    private fun rateRow(label: String, r: Rate, g: Grade) =
        Row.Step(color(g), R.drawable.ic_battery, if (g == Grade.UNKNOWN) null else g.label, "$label: ${BatterySummary.rateLine(r)}", null, key = "rate:$label")

    private fun pickRows(): List<Row> = buildList {
        add(Row.Header("어떤 실험인가요?", "고른 뒤 워치 설정에서 그 모드를 직접 켜세요"))
        MODES.forEach { add(Row.Action("$A_MODE$it", R.drawable.ic_play, it)) }
        add(Row.Action(A_CANCEL, R.drawable.ic_close, "취소"))
    }

    private fun onRow(row: Row) {
        if (row !is Row.Action) return
        when {
            row.id == A_RESTORE -> {
                lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { Guardian.restoreNow(this@BatteryActivity) } }; notice = "원래대로 되돌렸습니다 (12시간 쉼)"; render() }
            }
            row.id.startsWith(A_TOGGLE) -> {
                val cfg = Guardian.config(this)
                val next = when (row.id.removePrefix(A_TOGGLE)) {
                    T_NIGHT_SAVER -> cfg.copy(nightSaver = !cfg.nightSaver)
                    T_NIGHT_AOD -> cfg.copy(nightAodOff = !cfg.nightAodOff)
                    T_NIGHT_LOCATION -> cfg.copy(nightLocationOff = !cfg.nightLocationOff)
                    T_NIGHT_SYNC -> cfg.copy(nightSyncOff = !cfg.nightSyncOff)
                    T_ABNORMAL -> cfg.copy(abnormalSaver = !cfg.abnormalSaver)
                    T_REMINDER -> cfg.copy(chargeReminder = !cfg.chargeReminder)
                    else -> cfg
                }
                Guardian.saveConfig(this, next)
                render()
            }
            row.id.startsWith(A_APP_INFO) -> {
                val pkg = row.id.removePrefix(A_APP_INFO)
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
                }.onFailure { notice = "앱 정보를 열지 못했습니다 — 설정 > 애플리케이션에서 찾아 주세요"; render() }
            }
            row.id == A_FORCED_RESTART -> {
                notice = "표시를 남겼습니다 — 이제 홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 누르세요. 다시 켜지면 전후 잔량을 비교합니다 (이미 재시작했다면 그대로 두세요)"
                render()
                mark(Event.USER_NOTE, GaugeDriftRule.NOTE_FORCED_RESTART)
            }
            row.id == A_CAL_START -> {
                Calibration.start(this, driftPoints = analysis?.drift?.driftPoints)
                notice = "게이지 보정 시작 — 충전기에 올려 100%가 되면 1시간 더 두세요. 단계마다 알림으로 알려 드립니다"
                render()
            }
            row.id == A_CAL_STOP -> { Calibration.stop(this); notice = "게이지 보정을 그만뒀습니다"; render() }
            row.id == A_EXP_START -> { picking = true; render() }
            row.id == A_CANCEL -> { picking = false; render() }
            row.id.startsWith(A_MODE) -> {
                val mode = row.id.removePrefix(A_MODE)
                picking = false
                mark(Event.EXPERIMENT_START, mode)
                Toast.makeText(this, "이제 워치 설정에서 '$mode'를 켜세요. 끝나면 '실험 끝'", Toast.LENGTH_LONG).show()
            }
            row.id == A_EXP_END -> mark(Event.EXPERIMENT_END, analysis?.experiments?.lastOrNull { it.end == null }?.name ?: "")
            row.id == A_SUMMARY -> {
                val a = analysis ?: return
                lifecycleScope.launch {
                    val f = withContext(Dispatchers.IO) {
                        runCatching { log.writeSummary(BatterySummary.text(a, live, Build.MODEL ?: "", insights = insights)) }.getOrNull()
                    }
                    notice = if (f != null) "요약 저장됨\n${f.path}" else "요약을 쓰지 못했습니다"
                    render()
                }
            }
        }
    }

    /** 실험 표시 줄을 그 자리에서 쓰고 다시 계산. 배터리 상태를 못 읽는 환경에선 마지막 표본의 잔량으로 표시만 남긴다 */
    private fun mark(event: Event, note: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val s = BatteryProbe.read(this@BatteryActivity, event, note)
                        ?: BatterySample(System.currentTimeMillis(), live?.level ?: log.readAll().lastOrNull()?.level ?: 0, event = event, note = note)
                    log.append(s)
                }
            }
            reload()
        }
    }

    private fun color(g: Grade): Int = getColor(
        when (g) {
            Grade.NORMAL -> R.color.grade_normal
            Grade.CAUTION -> R.color.grade_caution
            Grade.ABNORMAL -> R.color.grade_abnormal
            Grade.UNKNOWN -> R.color.grade_unknown
        }
    )

    companion object {
        const val LIVE_EVERY_MS = 2_000L
        const val A_EXP_START = "exp:start"
        const val A_EXP_END = "exp:end"
        const val A_MODE = "mode:"
        const val A_CANCEL = "cancel"
        const val A_SUMMARY = "summary"
        const val A_RESTORE = "g:restore"
        const val A_TOGGLE = "g:toggle:"
        const val A_FORCED_RESTART = "drift:restart"
        const val A_APP_INFO = "appinfo:"
        const val A_CAL_START = "cal:start"
        const val A_CAL_STOP = "cal:stop"
        const val T_NIGHT_SAVER = "nightSaver"
        const val T_NIGHT_AOD = "nightAod"
        const val T_NIGHT_LOCATION = "nightLocation"
        const val T_NIGHT_SYNC = "nightSync"
        const val T_ABNORMAL = "abnormal"
        const val T_REMINDER = "reminder"
        val MODES = listOf("비행기 모드", "절전 모드", "시계만 사용", "평소대로")
        const val HELP = "• 잔량 표시 점검: '17%에서 꺼졌는데 켜니 47%'는 배터리가 아니라 잔량 게이지가 낮게 센 것. 쉬는 전압으로 그 오차를 잡아 알려 주고, 강제 재시작·보정 루틴으로 다시 맞춥니다.\n" +
            "• 강제 재시작: 홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 누르기. 효과 없으면 30초.\n" +
            "• 설정 > 배터리 > 헬스 기능 제한 / 대기 중인 앱\n" +
            "• 설정 > 버튼 및 제스처 > 손목 올려 말하기 끄기\n" +
            "• 설정 > 디스플레이 > Always On Display 끄기\n" +
            "• PC 툴킷(battery-doctor) 메뉴 8(자동 치료): 권한 주기 → 범인 앱 백그라운드 제한 → 통계 초기화를 한 번에"
    }
}
