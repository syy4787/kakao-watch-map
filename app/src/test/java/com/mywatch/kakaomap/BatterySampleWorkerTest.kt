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

    @Test
    fun pluggingInTheChargerWritesAnEventRow() {
        BatteryEventsReceiver().onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        val s = log.readAll().single()
        assertEquals(Event.POWER_CONNECTED, s.event)
        assertEquals(57, s.level)
    }

    @Test
    fun bootWritesARowAndReschedules() {
        BatteryEventsReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        // 시험용 WorkManager 는 주기 작업도 곧바로 한 번 돌리므로 BOOT 줄 외에 PERIODIC 줄이 있을 수 있다
        assertTrue(log.readAll().any { it.event == Event.BOOT })
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(BatterySampleWorker.UNIQUE).get().size)
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
        TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertTrue(log.summaryFile.exists())
        assertTrue(log.summaryFile.readText().contains("[판정]"))
        val first = log.summaryFile.lastModified()
        log.summaryFile.writeText("손대지 않음")
        TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertEquals("손대지 않음", log.summaryFile.readText())     // 하루가 안 지났으면 다시 쓰지 않는다
        assertTrue(first > 0)
    }
}
