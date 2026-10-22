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

    /**
     * 켜진 뒤 처음 남는 줄이 그 부팅의 줄 — 부팅 방송이 건 작업이 늦게 돌면 그 사이 켜진 뒤의 15분·충전 시작 줄이 먼저 남아 꺼지기 전 기록과의
     * 잔량 점프('방전 뒤 충전기에서 켜니 48%')를 못 봤다. 시각을 앞당기지 않고 그 줄을 부팅 줄로 — 시각과 잔량이 늘 같은 때의 것 (리뷰)
     */
    @Test
    fun theFirstRowAfterARestartIsTheBootRow() {
        val cr = context.contentResolver
        fun bootCount(n: Int) = android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.BOOT_COUNT, n)
        fun run(event: Event) = TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor())
            .setInputData(workDataOf(BatterySampleWorker.KEY_EVENT to event.name)).build().doWork()
        fun events() = log.readAll().map { it.event }
        bootCount(1)
        // 이 기능 전부터 쓰던 워치 — 지난 부팅을 모르면 15분 줄은 그대로, 부팅 작업이 부팅 줄을 남긴다
        run(Event.PERIODIC)
        run(Event.BOOT)
        assertEquals(listOf(Event.PERIODIC, Event.BOOT), events())
        run(Event.PERIODIC)
        assertEquals(Event.PERIODIC, events().last())
        // 다시 켜졌다 — 15분 줄이 먼저 돌면 그 줄이 부팅 줄, 늦게 돈 부팅 작업은 남기지 않는다
        bootCount(2)
        run(Event.PERIODIC)
        run(Event.BOOT)
        assertEquals(listOf(Event.PERIODIC, Event.BOOT, Event.PERIODIC, Event.BOOT), events())
        run(Event.PERIODIC)
        assertEquals(Event.PERIODIC, events().last())
        // 방전 뒤 충전기 위에서 켜졌다 — 충전 시작 줄이 부팅 줄 (충전 중으로)
        bootCount(3)
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 48).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS))
        TestWorkerBuilder<com.mywatch.kakaomap.battery.ChargeWatchWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        val boot = log.readAll().last()
        assertEquals(Event.BOOT, boot.event)
        assertTrue(boot.plugged && boot.level == 48)
        run(Event.BOOT)
        assertEquals(1, log.readAll().count { it.event == Event.BOOT && it.level == 48 })
        // 줄은 늘 시각 순으로 덧붙는다 (앞당겨 적지 않는다)
        val rows = log.readAll()
        assertEquals(rows.sortedBy { it.t }, rows)
    }

    /**
     * 충전 중 42℃ 이상이고 80% 아래면 그 자리에서 식히는 길을 — 충전마다 한 번, 같은 안내는 사흘에 한 번(2℃ 넘게 더 뜨거우면 다시), 밤엔
     * 소리 없는 채널, 충전기에서 빼면 그 알림을 거둔다 (워치9 느린 충전: 충전 중 44℃까지 뜨거워지며 느려질 수 있음)
     */
    @Test
    fun aHotChargeIsToldOncePerChargeAndNotEveryNight() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val real = context.getSystemService(android.app.NotificationManager::class.java)
        val nm = shadowOf(real)
        val zone = java.time.ZoneId.of("Asia/Seoul")
        fun at(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        fun s(level: Int, plugged: Boolean, tempDeciC: Int) = com.mywatch.battery.core.BatterySample(0L, level, plugged = plugged, tempDeciC = tempDeciC)
        fun hot(level: Int, tempDeciC: Int, now: Long) = com.mywatch.kakaomap.battery.BatteryAlerts.hotCharge(context, s(level, true, tempDeciC), now, zone)
        fun shown() = nm.allNotifications.filter { shadowOf(it).contentTitle?.toString()?.startsWith("충전 중") == true }
        hot(50, 419, at(20, 12))
        assertTrue("41.9℃ 는 아직", shown().isEmpty())
        hot(55, 420, at(20, 12, 10))
        val n = shown().single()
        assertTrue(shadowOf(n).contentTitle.toString(), shadowOf(n).contentTitle.toString().startsWith("충전 중 42℃"))
        assertTrue(shadowOf(n).contentText.toString().contains("선풍기"))
        assertEquals(com.mywatch.kakaomap.battery.BatteryAlerts.CHANNEL, n.channelId)
        real.cancelAll()
        hot(60, 440, at(20, 12, 20))
        assertTrue("같은 충전에선 한 번", shown().isEmpty())
        com.mywatch.kakaomap.battery.BatteryAlerts.hotCharge(context, s(66, false, 350), at(20, 13), zone)
        // 다음 날 충전 — 사흘 안이고 더 뜨겁지 않으면 다시 알리지 않는다, 2℃ 넘게 더 뜨거우면 다시
        hot(50, 430, at(21, 12))
        assertTrue("밤마다 같은 알림을 내지 않게", shown().isEmpty())
        hot(55, 441, at(21, 12, 10))
        assertEquals(1, shown().size)
        com.mywatch.kakaomap.battery.BatteryAlerts.hotCharge(context, s(70, false, 350), at(21, 13), zone)
        assertTrue("빼면 거둔다", shown().isEmpty())
        // 사흘 지나면 다시 — 밤이면 소리·진동 없는 채널
        hot(50, 430, at(24, 23, 30))
        assertEquals(com.mywatch.kakaomap.battery.BatteryAlerts.CHANNEL_QUIET, shown().single().channelId)
        com.mywatch.kakaomap.battery.BatteryAlerts.hotCharge(context, s(70, false, 350), at(25, 7), zone)
        // 80% 이상이면 알리지 않는다 (곧 끝남)
        hot(80, 460, at(29, 12))
        assertTrue(shown().isEmpty())
    }

    /** 충전 시작 작업(충전 중 10분마다)과 15분 기록이 충전 중 온도를 본다 */
    @Test
    fun theChargeWatchAndThePeriodicRowCheckTheHeat() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val real = context.getSystemService(android.app.NotificationManager::class.java)
        val nm = shadowOf(real)
        fun shown() = nm.allNotifications.filter { shadowOf(it).contentTitle?.toString()?.startsWith("충전 중") == true }
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 40).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS)
            .putExtra(BatteryManager.EXTRA_TEMPERATURE, 435))
        TestWorkerBuilder<com.mywatch.kakaomap.battery.ChargeWatchWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertEquals(1, shown().size)
        // 15분 기록도 — 충전기에서 뺀 기록이면 그 알림을 거둔다
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 45).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING).putExtra(BatteryManager.EXTRA_PLUGGED, 0)
            .putExtra(BatteryManager.EXTRA_TEMPERATURE, 350))
        TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor()).build().doWork()
        assertTrue(shown().isEmpty())
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
