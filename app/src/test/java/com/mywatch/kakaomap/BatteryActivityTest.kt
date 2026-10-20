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

    private fun open(): Pair<BatteryActivity, RowAdapter> {
        val a = Robolectric.buildActivity(BatteryActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val adapter = a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter
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
    }

    @Test
    fun withNothingRecordedItSaysItIsStillCollecting() {
        val (_, adapter) = open()
        val verdict = rows(adapter).filterIsInstance<Row.Step>().first { it.key == "verdict" }
        assertTrue(verdict.title, verdict.title.contains("모으는 중"))
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

    @Test
    fun guardianTogglesFlipTheConfigAndNoPermissionIsExplained() {
        val (a, adapter) = open()
        assertTrue(rows(adapter).filterIsInstance<Row.Note>().any { it.text.contains("메뉴 7") })
        val before = com.mywatch.kakaomap.battery.Guardian.config(context)
        assertTrue(before.nightSaver)
        click(a, adapter, BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_SAVER)
        assertFalse(com.mywatch.kakaomap.battery.Guardian.config(context).nightSaver)
        assertTrue(rows(adapter).filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_SAVER && it.text.endsWith("꺼짐") })
        click(a, adapter, BatteryActivity.A_TOGGLE + BatteryActivity.T_NIGHT_LOCATION)
        assertTrue(com.mywatch.kakaomap.battery.Guardian.config(context).nightLocationOff)
    }
}
