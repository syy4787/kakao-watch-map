package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.Event
import com.mywatch.kakaomap.battery.BatteryActivity
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 배터리 화면: 기록으로 소모율·판정을 보여 주고, 실험 표시와 요약 쓰기를 한다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryActivityTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var log: BatteryLog
    private val q = 15 * 60_000L

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
    }

    /** 화면을 열고 기록 분석(다른 스레드)이 끝나 줄이 그려질 때까지 기다린다 (기록이 많으면 한 번 돌리는 것으로는 모자람) */
    private fun open(): Pair<BatteryActivity, RowAdapter> {
        val a = Robolectric.buildActivity(BatteryActivity::class.java).setup().get()
        val adapter = a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter
        val until = System.currentTimeMillis() + 10_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (adapter.rows.any { it is Row.Header && it.title == "소모율" }) break
            Thread.sleep(20)
        } while (System.currentTimeMillis() < until)
        return a to adapter
    }

    private fun rows(adapter: RowAdapter) = adapter.rows

    private fun click(a: BatteryActivity, adapter: RowAdapter, id: String) {
        val row = rows(adapter).filterIsInstance<Row.Action>().first { it.id == id }
        // 어댑터가 줄을 누를 때 하는 일을 그대로 (화면을 실제로 그리지 않고)
        val m = BatteryActivity::class.java.getDeclaredMethod("onRow", Row::class.java).apply { isAccessible = true }
        m.invoke(a, row)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 파일 쓰기·다시 계산은 다른 스레드에서 — 줄이 바뀔 때까지 잠깐 기다린다 */
    private fun awaitAction(adapter: RowAdapter, id: String) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (rows(adapter).filterIsInstance<Row.Action>().any { it.id == id }) return
            Thread.sleep(20)
        }
        throw AssertionError("'$id' 줄이 나타나지 않음: " + rows(adapter).filterIsInstance<Row.Action>().map { it.id })
    }

    @Test
    fun twelveHoursOfHeavyIdleDrainIsCalledAbnormal() {
        val now = System.currentTimeMillis()
        // 12시간 동안 시간당 6% (100% → 28%), 내내 화면 꺼짐
        for (i in 0..48) log.append(BatterySample(t = now - (48 - i) * q, level = (100 - 6.0 * (i / 4.0)).toInt(), interactive = false))
        val (_, adapter) = open()
        val steps = rows(adapter).filterIsInstance<Row.Step>()
        val day = steps.first { it.title.startsWith("24시간") }
        assertTrue(day.title, day.title.contains("6.0%"))
        assertEquals("비정상", day.chip)
        val verdict = steps.first { it.key == "verdict" }
        assertEquals("비정상", verdict.chip)
        assertTrue(verdict.title, verdict.title.contains("시간당"))
        // 맨 위 '지금 할 일': 권한이 없으면 PC 툴킷 자동 치료부터
        val todo = steps.first { it.key == "todo:0" }
        assertTrue(todo.sub ?: "", (todo.sub ?: "").contains("메뉴 8"))
        // 12시간 기록 = 날마다 견줄 날이 모자람 — 몇 시간 넘는 날이 며칠 필요한지 (6.0시간이 아니라 6시간)
        val need = rows(adapter).filterIsInstance<Row.Note>().first { it.text.startsWith("날마다 견주려면") }
        assertTrue(need.text, need.text.contains("6시간 넘는 날이 4일"))
    }

    @Test
    fun withNothingRecordedItSaysItIsStillCollecting() {
        val (_, adapter) = open()
        val verdict = rows(adapter).filterIsInstance<Row.Step>().first { it.key == "verdict" }
        assertTrue(verdict.title, verdict.title.contains("모으는 중"))
        assertTrue(rows(adapter).none { it is Row.Chart })
    }

    /** 24시간 그래프: 기록이 있으면 맨 위 쪽에, 충전 구간과 설명(화면 낭독기용)과 함께 */
    @Test
    fun theDayGraphShowsUpWithChargingAndADescription() {
        val now = System.currentTimeMillis()
        for (i in 0..40) log.append(BatterySample(t = now - (60 - i) * q, level = 90 - i, interactive = false))
        for (i in 41..44) log.append(BatterySample(t = now - (60 - i) * q, level = 50 + (i - 40) * 8, plugged = true))
        for (i in 45..60) log.append(BatterySample(t = now - (60 - i) * q, level = 85 - (i - 45) / 2, interactive = false))
        val (_, adapter) = open()
        val all = rows(adapter)
        val chart = all.filterIsInstance<Row.Chart>().single()
        assertEquals(1, chart.model.charging.size)
        assertTrue(chart.model.description, chart.model.description.contains("충전 1번"))
        // 소모율 표보다 위 (한눈에 먼저)
        val rateHeader = all.indexOfFirst { it is Row.Header && it.title == "소모율" }
        assertTrue(all.indexOf(chart) < rateHeader)
        // 그래프 뷰가 실제로 그려진다 (빈 화면이 아니게)
        val view = com.mywatch.kakaomap.ui.DrainChartView(context).apply { model = chart.model }
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.measuredHeight > 60)
        assertEquals(chart.model.description, view.contentDescription)
        view.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)))
    }

    /** 충전 습관: 밤마다 충전기에 100%로 몇 시간씩 있으면 '배터리 보호(90%)'를 권한다 */
    @Test
    fun chargingHabitsSuggestBatteryProtection() {
        val now = System.currentTimeMillis()
        var t = now - 3 * 24 * 3_600_000L
        var level = 90
        while (t < now) {
            val hour = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).hour
            val plugged = hour >= 22 || hour < 7
            level = if (plugged) minOf(100, level + 10) else maxOf(20, level - 1)
            log.append(BatterySample(t = t, level = level, plugged = plugged, tempDeciC = 320))
            t += q
        }
        val (_, adapter) = open()
        assertTrue(rows(adapter).any { it is Row.Header && it.title == "충전 습관" })
        val step = rows(adapter).filterIsInstance<Row.Step>().first { it.key.startsWith("charge:") }
        assertTrue(step.title + step.sub, (step.title + (step.sub ?: "")).contains("배터리 보호"))
    }

    /**
     * '최근 며칠': 일주일 기록 — 사흘 전 새벽 소프트웨어 업데이트 뒤로 대기 소모가 1.0 → 2.5%/h 로 늘었으면 막대 그래프·꺾인 날·그 무렵 바뀐 것,
     * 맨 위 '지금 할 일'에도 (업데이트 뒤 며칠 기다림 → 캐시·재시작)
     */
    @Test
    fun aRiseAfterASoftwareUpdateIsShownWithWhatChangedThen() {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val rates = listOf(1.0, 1.0, 1.1, 1.0, 2.6, 2.5, 2.4)
        rates.forEachIndexed { i, r ->
            val start = today.minusDays((7 - i).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            var t = start
            while (t <= start + 20 * 3_600_000L) {
                val note = if (i == 4 && t == start + 3 * 3_600_000L) com.mywatch.battery.core.ChangeNote.encode(listOf(
                    com.mywatch.battery.core.ChangeNote.Item(com.mywatch.battery.core.ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1"))) else ""
                log.append(BatterySample(t = t, level = (100 - r * (t - start) / 3_600_000.0).toInt(), note = note))
                t += q
            }
            t = start + 20 * 3_600_000L + q
            while (t < start + 24 * 3_600_000L) { log.append(BatterySample(t = t, level = 100, plugged = true)); t += q }
        }
        val (a, adapter) = open()
        val all = rows(adapter)
        val header = all.indexOfFirst { it is Row.Header && it.title == "최근 며칠" }
        assertTrue("최근 며칠 칸", header >= 0)
        val bars = all.filterIsInstance<Row.Trend>().single()
        assertTrue(bars.model.description, bars.model.description.contains("부터 늘어남"))
        val step = all.filterIsInstance<Row.Step>().first { it.key == "trend" }
        assertTrue(step.title, step.title.endsWith("부터 대기 소모가 늘었습니다"))
        assertEquals("시간당 1.0% → 2.5%", step.sub)
        assertTrue(all.filterIsInstance<Row.Note>().any { it.text.startsWith("그 무렵 바뀐 것:") && it.text.contains("워치 소프트웨어 업데이트 (L350XXU1AZI1)") })
        val todo = all.filterIsInstance<Row.Step>().first { it.key == "todo:0" }
        assertTrue(todo.title, todo.title.contains("소프트웨어 업데이트 뒤 더 닳음"))
        // 할 일 아래 버튼: 그 뒤 할 일(Play 서비스 캐시)을 위한 앱 정보 화면
        click(a, adapter, BatteryActivity.A_TODO + "0")
        val started = shadowOf(a).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:com.google.android.gms", started.dataString)
    }

    @Test
    fun startingAndEndingAnExperimentWritesMarkers() {
        val (a, adapter) = open()
        click(a, adapter, BatteryActivity.A_EXP_START)
        assertTrue(rows(adapter).filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_MODE + "비행기 모드" })
        click(a, adapter, BatteryActivity.A_MODE + "비행기 모드")
        awaitAction(adapter, BatteryActivity.A_EXP_END)
        val start = log.readAll().last { it.event == Event.EXPERIMENT_START }
        assertEquals("비행기 모드", start.note)
        click(a, adapter, BatteryActivity.A_EXP_END)
        awaitAction(adapter, BatteryActivity.A_EXP_START)
        assertEquals("비행기 모드", log.readAll().last { it.event == Event.EXPERIMENT_END }.note)
    }

    @Test
    fun writingTheSummaryCreatesTheFile() {
        val (a, adapter) = open()
        click(a, adapter, BatteryActivity.A_SUMMARY)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && rows(adapter).filterIsInstance<Row.Note>().none { it.text.contains("요약 저장됨") }) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        assertTrue(log.summaryFile.exists())
        assertTrue(log.summaryFile.readText().contains("[판정]"))
        assertTrue(rows(adapter).filterIsInstance<Row.Note>().any { it.text.contains("요약 저장됨") })
    }

    /** 잔량 표시 점검: 쉬는 전압과 표시 잔량이 크게 어긋나면 드리프트를 보여 주고, '강제 재시작' 표시 줄을 남길 수 있다 */
    @Test
    fun restingVoltageFarAboveTheShownLevelIsFlaggedAndTheRestartMarkerIsWritten() {
        val now = System.currentTimeMillis()
        // 3.95V(고전압 셀 기준 약 44%)에서 쉬는데 표시는 20% 안팎 (2시간 넘게 — 판정이 나오도록)
        for (i in 0..11) log.append(BatterySample(t = now - (11 - i) * q, level = 22 - i / 4, voltageMv = 3950, currentUa = -20_000, interactive = false))
        val (a, adapter) = open()
        val drift = rows(adapter).filterIsInstance<Row.Step>().first { it.key == "drift" }
        assertTrue(drift.title, drift.title.contains("낮게 세는 중"))
        assertTrue(drift.sub ?: "", (drift.sub ?: "").contains("강제 재시작"))
        val verdict = rows(adapter).filterIsInstance<Row.Step>().first { it.key == "verdict" }
        assertTrue(verdict.title, verdict.title.contains("잔량 표시 오차 의심"))
        // 머리글의 '지금' 줄에도 전압 기준 실제 잔량 (배터리 상태를 못 읽는 시험 환경에선 마지막 기록으로)
        val header = rows(adapter).filterIsInstance<Row.Header>().first()
        assertTrue(header.subtitle ?: "", (header.subtitle ?: "").contains("전압 기준 약"))
        click(a, adapter, BatteryActivity.A_FORCED_RESTART)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && log.readAll().none { it.event == Event.USER_NOTE }) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        val note = log.readAll().last { it.event == Event.USER_NOTE }
        assertEquals("강제 재시작", note.note)
        assertTrue(rows(adapter).filterIsInstance<Row.Note>().any { it.text.contains("표시를 남겼습니다") })
    }

    @Test
    fun calibrationCanBeStartedAndStopped() {
        val (a, adapter) = open()
        assertEquals(0, com.mywatch.kakaomap.battery.Calibration.state(context).step)
        click(a, adapter, BatteryActivity.A_CAL_START)
        assertEquals(1, com.mywatch.kakaomap.battery.Calibration.state(context).step)
        assertTrue(rows(adapter).filterIsInstance<Row.Note>().any { it.text.contains("1단계") })
        click(a, adapter, BatteryActivity.A_CAL_STOP)
        assertEquals(0, com.mywatch.kakaomap.battery.Calibration.state(context).step)
        assertTrue(rows(adapter).filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_CAL_START })
    }

    @Test
    fun guardianTogglesFlipTheConfigAndNoPermissionIsExplained() {
        val (a, adapter) = open()
        assertTrue(rows(adapter).filterIsInstance<Row.Note>().any { it.text.contains("메뉴 7") })
        // PC 가 없을 때의 길: 폰 ADB 앱으로 같은 권한
        assertTrue(rows(adapter).filterIsInstance<Row.Note>().any { it.text.contains("pm grant com.mywatch.kakaomap android.permission.WRITE_SECURE_SETTINGS") })
        val before = com.mywatch.kakaomap.battery.Guardian.config(context)
        assertTrue(before.nightSaver)
        click(a, adapter, BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_SAVER)
        assertFalse(com.mywatch.kakaomap.battery.Guardian.config(context).nightSaver)
        assertTrue(rows(adapter).filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_SAVER && it.text.endsWith("꺼짐") })
        // 바꾼 것은 기록에 한 줄 — '최근 며칠'이 그 무렵 바뀐 것으로 보여 준다
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && log.readAll().none { it.event == Event.USER_NOTE }) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        val cfgChange = com.mywatch.battery.core.ChangeNote.all(log.readAll()).single()
        assertEquals(com.mywatch.battery.core.ChangeKind.CONFIG, cfgChange.kind)
        assertEquals("night_saver", cfgChange.subject)
        assertEquals("1" to "0", cfgChange.from to cfgChange.to)
        click(a, adapter, BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_LOCATION)
        assertTrue(com.mywatch.kakaomap.battery.Guardian.config(context).nightLocationOff)
        // 손목 올림·터치로 켜기 끄기(기본 켬)와 'Hey Google' 끄기(기본 끔)
        assertTrue(com.mywatch.kakaomap.battery.Guardian.config(context).nightWakeOff)
        click(a, adapter, BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_WAKE)
        assertFalse(com.mywatch.kakaomap.battery.Guardian.config(context).nightWakeOff)
        click(a, adapter, BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_HOTWORD)
        assertTrue(com.mywatch.kakaomap.battery.Guardian.config(context).nightHotwordOff)
        // 레버 점검: 무엇이 이 워치에서 듣는지 (권한 없을 땐 '권한 필요')
        val check = rows(adapter).filterIsInstance<Row.Note>().first { it.text.startsWith("레버 점검") }
        assertTrue(check.text, check.text.contains("권한 필요"))
    }

    /** 사용 정보 접근은 PC 없이 워치 설정에서 허용할 수 있게 그 화면을 연다 */
    @Test
    fun usageAccessCanBeOpenedFromTheWatch() {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        shadowOf(ops).setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName, android.app.AppOpsManager.MODE_IGNORED)
        val (a, adapter) = open()
        assertTrue(rows(adapter).filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_USAGE_ACCESS })
        click(a, adapter, BatteryActivity.A_USAGE_ACCESS)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, started.action)
        // 허용되면 버튼이 사라진다
        shadowOf(ops).setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName, android.app.AppOpsManager.MODE_ALLOWED)
        val (_, adapter2) = open()
        assertFalse(rows(adapter2).filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_USAGE_ACCESS })
    }
}
