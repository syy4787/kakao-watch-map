package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.BatteryManager
import android.provider.Settings
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.mywatch.battery.core.Change
import com.mywatch.battery.core.ChangeKind
import com.mywatch.battery.core.ChangeNote
import com.mywatch.battery.core.Event
import com.mywatch.kakaomap.battery.BatteryInsights
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.BatterySampleWorker
import com.mywatch.kakaomap.battery.ChangeWatcher
import com.mywatch.kakaomap.battery.Levers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * '그 무렵 바뀐 것' 기록: 소프트웨어·앱·시계 화면·설정이 바뀌면 다음 15분 표본 note 에 한 번 (처음 볼 땐 기억만),
 * 설정은 낮에만·지킴이가 쥐고 있을 땐 보지 않음, 지킴이 설정을 바꾸면 그 자리에서 한 줄
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChangeWatcherTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val gms = "com.google.android.gms"

    private var fw = "L350XXU1AZH1"
    private var seq = 5
    private var changed = emptyList<String>()
    private val asked = ArrayList<Int>()
    private var boot = 1_000L
    private var face: String? = "com.samsung.android.watch.watchface.analogmodular"

    @Before
    fun setUp() {
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ChangeWatcher.firmware = { fw }
        ChangeWatcher.bootTime = { boot }
        ChangeWatcher.changedPackages = { _, s -> asked += s; if (changed.isEmpty()) null else (seq to changed) }
        BatteryInsights.watchFace = { face }
        Settings.Global.putString(context.contentResolver, "ambient_enabled", "0")
        Settings.Global.putString(context.contentResolver, "ambient_tilt_to_wake", "1")
    }

    @After
    fun tearDown() {
        ChangeWatcher.resetHooks()
        BatteryInsights.watchFace = BatteryInsights.DEFAULT_FACE
    }

    private fun collect(now: Long = at(21, 10)): List<Change> = ChangeNote.decode(now, ChangeWatcher.collect(context, now, zone))

    private fun install(pkg: String, version: String, system: Boolean = false, firstInstall: Long = 1_000L, lastUpdate: Long = 5_000_000L) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            versionName = version
            firstInstallTime = firstInstall
            lastUpdateTime = lastUpdate
            applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
        })
    }

    @Test
    fun theFirstLookOnlyRemembers() {
        changed = listOf(gms)
        install(gms, "26.40.31")
        assertTrue(collect().isEmpty())
        // 다음엔 바뀐 것만
        changed = emptyList()
        assertTrue(collect().isEmpty())
    }

    @Test
    fun aSoftwareUpdateIsNotedOnce() {
        collect()
        fw = "L350XXU1AZI1"
        assertEquals(listOf(Change(at(21, 10), ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1")), collect())
        assertTrue(collect().isEmpty())
    }

    @Test
    fun appChangesComeWithVersionsPlayServicesFirst() {
        changed = listOf("com.example.old")
        collect()   // 처음: 순번 5만 기억
        install("com.samsung.android.wearable.sysui", "9.0", system = true)
        install("com.example.game", "2.0 (beta)", firstInstall = at(21, 9), lastUpdate = at(21, 9))
        install(gms, "26.40.31 (190400-812345678)", system = true)
        seq = 9
        changed = listOf("com.samsung.android.wearable.sysui", "com.example.game", gms, "com.example.gone")
        val c = collect()
        assertEquals(listOf(gms, "com.example.game", "com.example.gone", "com.samsung.android.wearable.sysui"), c.map { it.subject })
        assertEquals(listOf("26.40.31", "+2.0", "-", "9.0"), c.map { it.to })
        // 다음엔 9번부터 묻는다
        changed = emptyList()
        collect()
        assertEquals(listOf(0, 5, 9), asked)
    }

    @Test
    fun manyAppsAtOnceAreCutWithACount() {
        collect()
        changed = (1..11).map { "com.example.app$it" }
        changed.forEach { install(it, "1.0") }
        val c = collect()
        assertEquals(ChangeWatcher.MAX_APPS, c.count { it.kind == ChangeKind.APP })
        assertEquals(Change(at(21, 10), ChangeKind.MORE_APPS, "apps", null, "3"), c.last())
    }

    @Test
    fun aNewBootCountsAppChangesFromZero() {
        changed = listOf("com.example.old")
        collect()   // 순번 5
        boot = 1_000L + 60 * 60_000L
        seq = 2
        changed = listOf(gms)
        install(gms, "26.41.10")
        assertEquals(gms, collect().single().subject)
        assertEquals(0, asked.last())
    }

    @Test
    fun aWatchFaceChangeIsNoted() {
        collect()
        face = "com.example.neonface"
        assertEquals(Change(at(21, 10), ChangeKind.FACE, "face", "com.samsung.android.watch.watchface.analogmodular", "com.example.neonface"), collect().single())
        face = null   // 못 읽으면 기억을 지우지 않는다
        assertTrue(collect().isEmpty())
        face = "com.example.neonface"
        assertTrue(collect().isEmpty())
    }

    @Test
    fun settingsAreComparedInTheDaytimeOnly() {
        collect(at(21, 10))
        Settings.Global.putString(context.contentResolver, "ambient_enabled", "1")
        assertTrue("밤엔 보지 않음", collect(at(21, 23, 30)).isEmpty())
        assertEquals(listOf(Change(at(22, 8), ChangeKind.SETTING, "aod", "0", "1")), collect(at(22, 8)))
        Settings.Global.putString(context.contentResolver, "ambient_tilt_to_wake", "0")
        assertEquals(listOf(Change(at(22, 9), ChangeKind.SETTING, "tilt", "1", "0")), collect(at(22, 9)))
    }

    @Test
    fun settingsAreLeftAloneWhileTheGuardianHoldsOne() {
        collect(at(21, 10))
        // 지킴이가 AOD 를 끄고 쥐고 있는 동안 (비정상 소모 대응은 낮에도)
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().putString(Levers.B_AOD, "1").commit()
        Settings.Global.putString(context.contentResolver, "ambient_tilt_to_wake", "0")
        assertTrue(collect(at(21, 11)).isEmpty())
        // 놓은 뒤: 쥐기 전 낮 값과 견준다
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().remove(Levers.B_AOD).commit()
        assertEquals(listOf("tilt"), collect(at(21, 12)).map { it.subject })
    }

    @Test
    fun theSamplingWorkerPutsChangesOnPeriodicAndBootSamplesOnly() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val log = BatteryLog(context)
        log.dir.deleteRecursively()
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 57).putExtra(BatteryManager.EXTRA_SCALE, 100)
        )
        fun run(event: Event, note: String = "") {
            val w = TestWorkerBuilder<BatterySampleWorker>(context, Executors.newSingleThreadExecutor())
                .setInputData(workDataOf(BatterySampleWorker.KEY_EVENT to event.name, BatterySampleWorker.KEY_NOTE to note)).build()
            assertEquals(ListenableWorker.Result.success(), w.doWork())
            Thread.sleep(3)   // 같은 시각·같은 계기 줄은 하나로 합쳐지므로
        }
        run(Event.PERIODIC)
        fw = "L350XXU1AZI1"
        run(Event.POWER_CONNECTED)
        run(Event.BOOT)
        run(Event.PERIODIC)
        val notes = log.readAll().map { it.event to it.note }
        assertEquals(Event.POWER_CONNECTED to "", notes[1])
        assertEquals(Event.BOOT, notes[2].first)
        assertTrue(notes[2].second, ChangeNote.decode(0, notes[2].second).single().kind == ChangeKind.FIRMWARE)
        assertEquals(Event.PERIODIC to "", notes[3])
    }

    @Test
    fun aGuardianToggleBecomesAConfigChange() {
        val c = ChangeNote.decode(1, ChangeWatcher.configNote("night_aod", from = false, to = true)).single()
        assertEquals(Change(1, ChangeKind.CONFIG, "night_aod", "0", "1"), c)
    }
}
