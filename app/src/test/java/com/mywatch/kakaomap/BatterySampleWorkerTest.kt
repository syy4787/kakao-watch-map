package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.Status
import com.mywatch.kakaomap.battery.BatteryEventsReceiver
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.BatterySampleWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/** 15분 기록 작업: 예약되고, 돌면 시스템 배터리 값 그대로 한 줄, 충전기 꽂음 같은 계기도 한 줄 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatterySampleWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var log: BatteryLog

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        log = BatteryLog(context)
        log.dir.deleteRecursively()
        // 시스템이 들고 있는 마지막 배터리 상태
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 57).putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                .putExtra(BatteryManager.EXTRA_VOLTAGE, 3912).putExtra(BatteryManager.EXTRA_TEMPERATURE, 331)
        )
        val bm = context.getSystemService(BatteryManager::class.java)
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 250_000)
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -84_000)
    }

    @Test
    fun schedulingKeepsOnePeriodicJob() {
        BatterySampleWorker.schedule(context)
        BatterySampleWorker.schedule(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BatterySampleWorker.UNIQUE).get()
        assertEquals(1, infos.size)
        assertTrue(infos[0].periodicityInfo != null)
        assertEquals(15 * 60_000L, infos[0].periodicityInfo!!.repeatIntervalMillis)
        assertFalse(infos[0].state == WorkInfo.State.CANCELLED)
    }

    @Test
    fun aRunRecordsTheSystemBatteryValues() {
        val worker = TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val s = log.readAll().single()
        assertEquals(57, s.level)
        assertEquals(250_000, s.chargeUah)
        assertEquals(3912, s.voltageMv)
        assertEquals(331, s.tempDeciC)
        assertEquals(-84_000, s.currentUa)
        assertEquals(Status.DISCHARGING, s.status)
        assertFalse(s.plugged)
        assertEquals(Event.PERIODIC, s.event)
    }

    @Test
    fun unsupportedCounterIsLeftUnknown() {
        val bm = context.getSystemService(BatteryManager::class.java)
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, Int.MIN_VALUE)
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 0)
        TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        val s = log.readAll().single()
        assertNull(s.chargeUah)
        assertNull(s.currentUa)
    }

    private fun plugIn(level: Int) = context.sendStickyBroadcast(
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS)
    )

    /**
     * 충전기 꽂음 방송은 targetSdk 26+ 앱에 오지 않는다 — 그래서 '충전 중' 조건의 작업이 충전이 시작되면 한 줄 남기고(이미 충전 중
     * 줄이 있으면 안 씀), 다시 '충전 중' 조건으로 걸어 둔다. 15분 표본 사이의 짧은 충전이 '충전 없이 잔량 상승'으로 읽히지 않게 (점검에서 찾음)
     */
    @Test
    fun aChargingJobLogsTheStartOfChargingOnceAndRearms() {
        plugIn(57)
        fun run() = TestWorkerBuilder<com.mywatch.kakaomap.battery.ChargeWatchWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertEquals(ListenableWorker.Result.success(), run())
        val s = log.readAll().single()
        assertEquals(Event.POWER_CONNECTED, s.event)
        assertTrue(s.plugged)
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals("이미 충전 중 줄이 있으면 쓰지 않는다", 1, log.readAll().size)
        val next = WorkManager.getInstance(context).getWorkInfosForUniqueWork(com.mywatch.kakaomap.battery.ChargeWatchWorker.UNIQUE).get()
        assertTrue(next.isNotEmpty())
        assertTrue(next.all { it.constraints.requiresCharging() })
    }

    /**
     * 실제 WorkManager 사슬로: 걸어 두면 '충전 중' 조건을 기다리고, 조건이 맞으면 돌아 한 줄 남기고 자기 뒤에 다음 것을 잇는다
     * (앞 작업이 끝나야 다음 것이 기다림 — 돌던 작업을 갈아 끼우지 않는다). 앱이 다시 시작돼 걸어도 사슬은 하나
     */
    @Test
    fun theChargingJobRunsWhenChargingAndRearmsInItsChain() {
        plugIn(57)
        com.mywatch.kakaomap.battery.ChargeWatchWorker.arm(context)
        val wm = WorkManager.getInstance(context)
        val first = wm.getWorkInfosForUniqueWork(com.mywatch.kakaomap.battery.ChargeWatchWorker.UNIQUE).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        assertEquals(Event.POWER_CONNECTED, log.readAll().single().event)
        val after = wm.getWorkInfosForUniqueWork(com.mywatch.kakaomap.battery.ChargeWatchWorker.UNIQUE).get()
        assertEquals(WorkInfo.State.SUCCEEDED, after.single { it.id == first.id }.state)
        val next = after.single { it.id != first.id }
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
        assertTrue(next.constraints.requiresCharging())
        com.mywatch.kakaomap.battery.ChargeWatchWorker.arm(context)
        assertEquals(1, wm.getWorkInfosForUniqueWork(com.mywatch.kakaomap.battery.ChargeWatchWorker.UNIQUE).get().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun bootWritesARowAndReschedules() {
        BatteryEventsReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        // 시험용 WorkManager 는 주기 작업도 곧바로 한 번 돌리므로 BOOT 줄 외에 PERIODIC 줄이 있을 수 있다
        assertTrue(log.readAll().any { it.event == Event.BOOT })
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(BatterySampleWorker.UNIQUE).get().size)
        // 충전 시작을 잡는 작업도 걸려 있다
        val charge = WorkManager.getInstance(context).getWorkInfosForUniqueWork(com.mywatch.kakaomap.battery.ChargeWatchWorker.UNIQUE).get()
        assertTrue(charge.single().constraints.requiresCharging())
    }

    @Test
    fun aNoteTravelsWithTheRow() {
        val worker = TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor())
            .setInputData(workDataOf(BatterySampleWorker.KEY_EVENT to Event.EXPERIMENT_START.name, BatterySampleWorker.KEY_NOTE to "비행기 모드"))
            .build()
        worker.doWork()
        val s = log.readAll().single()
        assertEquals(Event.EXPERIMENT_START, s.event)
        assertEquals("비행기 모드", s.note)
    }

    /** 하루에 한 번 요약 파일을 저절로 쓴다 (PC 툴킷이 가져가 보고서에 넣음) */
    @Test
    fun aDailySummaryIsWrittenOnce() {
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        // 3시간치 기록 (시간당 2%)
        val now = System.currentTimeMillis()
        val q = 15 * 60_000L
        for (i in 0..12) log.append(com.mywatch.battery.core.BatterySample(t = now - (13 - i) * q, level = 63 - i / 2, interactive = false))
        TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertTrue(log.summaryFile.exists())
        assertTrue(log.summaryFile.readText().contains("[판정]"))
        // 이 워치에서 어느 레버가 듣는지 (PC 툴킷이 수집해 보고서에 싣는다)
        assertTrue(log.summaryFile.readText().contains("[레버 점검] perm="))
        // PC 툴킷이 읽는 숫자 한 줄
        val metrics = com.mywatch.battery.core.SummaryMetrics.parse(log.summaryFile.readText())
        assertTrue(metrics.toString(), metrics.containsKey("day_pph"))
        // 언제 쓴 요약인지 — PC 툴킷이 지난 수집보다 새 요약일 때만 비교에 쓴다
        assertTrue(metrics.toString(), (metrics["at"] ?: 0.0) > now - 60_000)
        val first = log.summaryFile.lastModified()
        log.summaryFile.writeText("손대지 않음")
        TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertEquals("손대지 않음", log.summaryFile.readText())     // 하루가 안 지났으면 다시 쓰지 않는다
        assertTrue(first > 0)
    }
}
