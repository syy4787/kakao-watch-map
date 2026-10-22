package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.BatteryManager
import android.os.PowerManager
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
 * '그 무렵 바뀐 것' 기록: 소프트웨어·앱·시계 화면·설정이 바뀌면 다음 15분 표본 note 에 한 번 (처음 볼 땐 기억만 + '적기 시작'),
 * 앱은 실제 업데이트·설치·지움만(구성 요소 켜고 끄기·이 앱 자신은 빼고), 설정은 낮에만·지킴이가 쥐고 있거나 절전·취침·극장
 * 모드일 땐 보지 않음, 표본 저장에 실패하면 기억하지 않아 다음에 다시, 지킴이 설정을 바꾸면 그 자리에서 한 줄
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
    private var bootCount: Int? = null
    private var face: String? = "com.samsung.android.watch.watchface.analogmodular"

    @Before
    fun setUp() {
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ChangeWatcher.firmware = { fw }
        ChangeWatcher.bootTime = { boot }
        ChangeWatcher.bootCount = { bootCount }
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

    /** 바뀐 것만 ('적기 시작' 표시는 따로 본다) */
    private fun collect(now: Long = at(21, 10)): List<Change> =
        ChangeNote.decode(now, ChangeWatcher.collect(context, now, zone)).filter { it.kind != ChangeKind.TRACKING }

    private fun install(pkg: String, version: String, system: Boolean = false, firstInstall: Long = 1_000L, lastUpdate: Long = at(21, 9)) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            versionName = version
            firstInstallTime = firstInstall
            lastUpdateTime = lastUpdate
            applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
        })
    }

    @Test
    fun theFirstLookOnlyRemembersAndMarksTheStart() {
        changed = listOf(gms)
        install(gms, "26.40.31")
        val first = ChangeWatcher.collect(context, at(21, 8), zone)
        assertEquals("chg:on=1", first)
        // 다음엔 바뀐 것만, '적기 시작'은 한 번
        changed = emptyList()
        assertEquals("", ChangeWatcher.collect(context, at(21, 10), zone))
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
        collect(at(21, 8))   // 처음: 순번 5만 기억
        install("com.samsung.android.wearable.sysui", "9.0", system = true)
        install("com.example.game", "2.0 (beta)", firstInstall = at(21, 9), lastUpdate = at(21, 9))
        install(gms, "26.40.31 (190400-812345678)", system = true)
        seq = 9
        changed = listOf("com.samsung.android.wearable.sysui", "com.example.game", gms, "com.example.gone")
        val c = collect(at(21, 10))
        assertEquals(listOf(gms, "com.example.game", "com.example.gone", "com.samsung.android.wearable.sysui"), c.map { it.subject })
        assertEquals(listOf("26.40.31", "+2.0", "-", "9.0"), c.map { it.to })
        // 다음엔 9번부터 묻는다
        changed = emptyList()
        collect(at(21, 11))
        assertEquals(listOf(0, 5, 9), asked)
    }

    /** Play 서비스는 구성 요소를 자주 켜고 끈다 — 순번은 바뀌어도 업데이트 시각이 그대로면 바뀐 것이 아니다 (리뷰에서 찾음) */
    @Test
    fun aComponentToggleOrThisAppItselfIsNotAnUpdate() {
        install(gms, "26.40.31", system = true, lastUpdate = at(15, 9))      // 엿새 전 업데이트
        install(context.packageName, "2.40", lastUpdate = at(21, 9))        // 이 앱 자신
        collect(at(21, 8))
        changed = listOf(gms, context.packageName)
        assertTrue(collect(at(21, 10)).isEmpty())
    }

    @Test
    fun manyAppsAtOnceAreCutWithACount() {
        collect(at(21, 8))
        changed = (1..11).map { "com.example.app$it" }
        changed.forEach { install(it, "1.0") }
        val c = collect(at(21, 10))
        assertEquals(ChangeWatcher.MAX_APPS, c.count { it.kind == ChangeKind.APP })
        assertEquals(Change(at(21, 10), ChangeKind.MORE_APPS, "apps", null, "3"), c.last())
    }

    @Test
    fun aNewBootCountsAppChangesFromZero() {
        changed = listOf("com.example.old")
        collect(at(21, 8))   // 순번 5
        boot = 1_000L + 60 * 60_000L
        seq = 2
        changed = listOf(gms)
        install(gms, "26.41.10")
        assertEquals(gms, collect(at(21, 10)).single().subject)
        assertEquals(0, asked.last())
    }

    /** 부팅 횟수를 읽을 수 있으면 그것으로 — 시계를 크게 맞춰도(부팅 시각 어림이 흔들려도) 같은 부팅의 순번을 지킨다 */
    @Test
    fun theBootCountKeepsTheSequenceAcrossAClockJump() {
        bootCount = 7
        changed = listOf("com.example.old")
        collect(at(21, 8))   // 순번 5
        boot = 1_000L - 3 * 3_600_000L   // 시계를 세 시간 되돌림
        changed = emptyList()
        collect(at(21, 10))
        assertEquals(5, asked.last())
        bootCount = 8   // 정말 다시 켜짐
        collect(at(21, 11))
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

    /** 절전·취침·극장 모드는 AOD·손목 올림을 잠시 바꾼다 — 그동안은 보지 않고, 끝난 뒤 그 전 값과 견준다 */
    @Test
    fun settingsAreLeftAloneInSaverBedtimeAndTheaterModes() {
        collect(at(21, 10))
        val pm = shadowOf(context.getSystemService(PowerManager::class.java))
        pm.setIsPowerSaveMode(true)
        Settings.Global.putString(context.contentResolver, "ambient_tilt_to_wake", "0")
        assertTrue("절전 중", collect(at(21, 11)).isEmpty())
        pm.setIsPowerSaveMode(false)
        Settings.Global.putString(context.contentResolver, "bedtime_mode", "1")
        assertTrue("취침 모드", collect(at(21, 12)).isEmpty())
        Settings.Global.putString(context.contentResolver, "bedtime_mode", "0")
        Settings.Global.putString(context.contentResolver, "theater_mode_on", "1")
        assertTrue("극장 모드", collect(at(21, 13)).isEmpty())
        Settings.Global.putString(context.contentResolver, "theater_mode_on", "0")
        // 모드가 끝나고도 꺼져 있으면 그때 사용자 변화로
        assertEquals(listOf("tilt"), collect(at(21, 14)).map { it.subject })
        // 모드가 잠깐 끄고 되돌렸으면 아무것도 남지 않는다
        Settings.Global.putString(context.contentResolver, "bedtime_mode", "1")
        Settings.Global.putString(context.contentResolver, "ambient_enabled", "1")
        assertTrue(collect(at(21, 15)).isEmpty())
        Settings.Global.putString(context.contentResolver, "ambient_enabled", "0")
        Settings.Global.putString(context.contentResolver, "bedtime_mode", "0")
        assertTrue(collect(at(21, 16)).isEmpty())
    }

    /** 표본을 저장하지 못했으면(배터리 상태를 못 읽음·파일 오류) 본 값을 기억하지 않는다 — 다음 표본에 다시 */
    @Test
    fun aChangeIsRememberedOnlyWhenTheSampleWasSaved() {
        collect()
        fw = "L350XXU1AZI1"
        ChangeWatcher.record(context, at(21, 11), zone) { false }
        assertEquals(ChangeKind.FIRMWARE, collect(at(21, 12)).single().kind)
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
        // 부팅 줄은 켜진 때로 앞당겨 적으므로(시험의 '켜진 뒤 시간'은 처음부터 흐름) 시각 순서가 아니라 계기로 찾는다
        val rows = log.readAll()
        assertEquals(listOf("chg:on=1", ""), rows.filter { it.event == Event.PERIODIC }.map { it.note })
        assertEquals("", rows.single { it.event == Event.POWER_CONNECTED }.note)
        val boot = rows.single { it.event == Event.BOOT }.note
        assertTrue(boot, ChangeNote.decode(0, boot).single().kind == ChangeKind.FIRMWARE)
    }

    /**
     * 기록은 30일만 남는다 — 처음 남긴 '적기 시작' 표시가 정리돼 지워지면 그 뒤 꺾임이 모두 '모름'이 된다.
     * 그래서 2주마다 다시 남긴다 (가장 이른 표시가 늘 기록 안에 있게 — 리뷰에서 찾음)
     */
    @Test
    fun theStartMarkIsRenewedBeforeTheLogForgetsIt() {
        assertEquals("chg:on=1", ChangeWatcher.collect(context, at(1, 8), zone))
        assertEquals("", ChangeWatcher.collect(context, at(15, 7), zone))
        assertEquals("chg:on=1", ChangeWatcher.collect(context, at(15, 9), zone))
        assertEquals("", ChangeWatcher.collect(context, at(15, 10), zone))
    }

    /** 예전 빌드가 부팅 표시를 숫자로 남겼어도 넘어지지 않고(앱 변화 기록이 멈추지 않고) 새 표시로 바꾼다 */
    @Test
    fun anOldNumberBootMarkIsReplaced() {
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().putLong("cw_boot", 123_456L).commit()
        collect(at(21, 8))
        install(gms, "26.41.10", system = true, lastUpdate = at(21, 9))
        changed = listOf(gms)
        assertEquals(gms, collect(at(21, 10)).single().subject)
    }

    /**
     * 기준 시각 바로 앞(60초 안)에 업데이트된 앱이 15분 뒤 구성 요소를 켜고 꺼 또 바뀐 목록에 오르면 두 번 적던 것 —
     * 앱마다 적은 업데이트 시각을 기억해 한 번만 (리뷰에서 찾음)
     */
    @Test
    fun anUpdateIsNotedOnceEvenIfThePackageShowsUpAgain() {
        collect(at(21, 8))
        install(gms, "26.41.10", system = true, lastUpdate = at(21, 9, 59))
        changed = listOf(gms)
        assertEquals(gms, collect(at(21, 10)).single().subject)
        seq = 6
        assertTrue(collect(at(21, 10, 15)).isEmpty())
        // 정말 다시 업데이트되면 또 적는다
        install(gms, "26.42.10", system = true, lastUpdate = at(21, 10, 20))
        seq = 7
        assertEquals("26.42.10", collect(at(21, 10, 30)).single().to)
    }

    @Test
    fun aGuardianToggleBecomesAConfigChange() {
        val c = ChangeNote.decode(1, ChangeWatcher.configNote("night_aod", from = false, to = true)).single()
        assertEquals(Change(1, ChangeKind.CONFIG, "night_aod", "0", "1"), c)
    }
}
