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
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.Thresholds
import com.mywatch.battery.core.WatchModels
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
            val a = withContext(Dispatchers.IO) {
                runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL)).analyze(log.readAll()) }
                    .onFailure { ErrorLog.record("배터리·분석", it) }.getOrNull()
            }
            analysis = a
            render()
        }
    }

    private fun render() {
        val a = analysis
        if (a == null) { ui.showStatus("기록을 읽지 못했습니다"); return }
        if (picking) { ui.rows = pickRows(); return }
        val now = System.currentTimeMillis()
        val rows = mutableListOf<Row>()
        rows += Row.Header("배터리", BatterySummary.nowLine(live))
        notice?.let { rows += Row.Note(it) }

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

        rows += Row.Header("밤사이")
        rows += Row.Note(BatterySummary.overnightLine(a))

        rows += Row.Header("잔량 표시 이상 (최근)")
        if (a.anomalies.isEmpty()) rows += Row.Note("없음") else a.anomalies.takeLast(5).reversed().forEach {
            rows += Row.Step(color(Grade.CAUTION), R.drawable.ic_warning, null, it.label, "${BatterySummary.time(it.t)} · ${it.detail}", key = "anom:${it.t}:${it.kind}")
        }

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

    private fun renderLive() {
        if (picking || analysis == null || ui.rows.isEmpty()) return
        val first = ui.rows.firstOrNull() as? Row.Header ?: return
        ui.adapter.replace(0, first.copy(subtitle = BatterySummary.nowLine(live)))
    }

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
                        runCatching { log.writeSummary(BatterySummary.text(a, live, Build.MODEL ?: "")) }.getOrNull()
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
        val MODES = listOf("비행기 모드", "절전 모드", "시계만 사용", "평소대로")
        const val HELP = "• 강제 재시작: 홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 누르기. 효과 없으면 30초.\n" +
            "• 설정 > 배터리 > 헬스 기능 제한 / 대기 중인 앱\n" +
            "• 설정 > 버튼 및 제스처 > 손목 올려 말하기 끄기\n" +
            "• 설정 > 디스플레이 > Always On Display 끄기\n" +
            "• 비정상이 계속되면 PC 툴킷(battery-doctor)으로 범인 앱을 찾고 증거 파일을 만드세요"
    }
}
