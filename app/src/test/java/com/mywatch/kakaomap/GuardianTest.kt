package com.mywatch.kakaomap

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.GuardianConfig
import com.mywatch.battery.core.Status
import com.mywatch.battery.core.UidUse
import com.mywatch.kakaomap.battery.BatteryAlerts
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.Calibration
import com.mywatch.kakaomap.battery.Guardian
import com.mywatch.kakaomap.battery.HealthStore
import com.mywatch.kakaomap.battery.Levers
import com.mywatch.kakaomap.battery.UidStatsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/**
 * 배터리 지킴이: 밤에 화면이 꺼져 있으면 절전·AOD 를 끄고 아침에 되돌린다, 비정상 소모면 절전 + 범인 앱 알림,
 * 잠자기 전 충전 알림, 권한이 없으면 설정은 건드리지 않고 알림만
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GuardianTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private lateinit var log: BatteryLog
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val cr get() = context.contentResolver

    private fun at(h: Int, m: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun fill(end: Long, count: Int, startLevel: Double, pph: Double, interactive: Boolean = false, plugged: Boolean = false) {
        log.file.delete()   // 표본만 새로 (앱별 통계 파일은 그대로)
        for (i in 0 until count) log.append(BatterySample(t = end - (count - 1 - i) * q, level = (startLevel - pph * i / 4).toInt(), interactive = interactive, plugged = plugged))
    }

    private fun grant() = shadowOf(context as Application).grantPermissions("android.permission.WRITE_SECURE_SETTINGS", "android.permission.BATTERY_STATS")

    private fun lowPower() = Settings.Global.getInt(cr, "low_power", 0)

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(cr, "low_power", 0)
        Settings.Global.putString(cr, "ambient_enabled", "1")
        Settings.Secure.putString(cr, "location_mode", "3")
    }

    @Test
    fun withoutThePermissionOnlyTheAlertFires() {
        fill(at(14, 0), 9, 80.0, 6.0)
        Guardian.tick(context, log, at(14, 0), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals(0, lowPower())
        assertEquals(listOf(BatteryAlerts.NOTIFICATION_ID), shadowOf(nm).allNotifications.map { shadowOf(nm).activeNotifications.first().id })
    }

    @Test
    fun nightTurnsSaverAndAodOffThenMorningRestores() {
        grant()
        fill(at(0, 30), 8, 60.0, 2.0)   // 자정 반, 화면은 두 시간째 꺼짐
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals(1, lowPower())
        assertEquals("0", Settings.Global.getString(cr, "ambient_enabled"))
        assertEquals("3", Settings.Secure.getString(cr, "location_mode"))   // 기본 설정에선 위치는 그대로
        assertTrue(Guardian.state(context).nightActive)
        assertTrue(Guardian.lastAction(context)!!, Guardian.lastAction(context)!!.contains("밤 모드 시작"))

        // 아침 7시 5분 — 시스템은 절전 중이라고 말한다(우리가 켠 것)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        fill(at(7, 5), 8, 50.0, 1.0)
        Guardian.tick(context, log, at(7, 5), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals(0, lowPower())
        assertEquals("1", Settings.Global.getString(cr, "ambient_enabled"))
        assertFalse(Guardian.state(context).nightActive)
    }

    @Test
    fun openingTheAppInTheMorningEndsNightModeAtOnce() {
        grant()
        fill(at(0, 30), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals(1, lowPower())
        Guardian.onUserOpenedApp(context, at(3, 0), zone)      // 한밤중 — 그대로
        assertEquals(1, lowPower())
        Guardian.onUserOpenedApp(context, at(6, 20), zone)     // 아침 — 끝
        assertEquals(0, lowPower())
        assertEquals("1", Settings.Global.getString(cr, "ambient_enabled"))
        assertFalse(Guardian.state(context).nightActive)
    }

    @Test
    fun nightLocationOffIsOptIn() {
        grant()
        Guardian.saveConfig(context, GuardianConfig(nightLocationOff = true))
        fill(at(1, 0), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(1, 0), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("0", Settings.Secure.getString(cr, "location_mode"))
        Guardian.restoreNow(context, at(1, 10))
        assertEquals("3", Settings.Secure.getString(cr, "location_mode"))
        assertEquals(0, lowPower())
        assertTrue(Guardian.state(context).userOverrideUntil > at(1, 10))
    }

    @Test
    fun abnormalDrainTurnsSaverOnAndNamesTheCulprit() {
        grant()
        val stats = object : UidStatsSource {
            var wl = 0L
            override fun take() = mapOf(
                "com.example.drain" to UidUse("com.example.drain", wakelockMs = wl),
                "com.example.quiet" to UidUse("com.example.quiet", cpuMs = 1_000),
            )
        }
        // 첫 기록(기준선): 비정상 아님
        fill(at(13, 45), 9, 80.0, 1.0)
        Guardian.tick(context, log, at(13, 45), stats = stats, zone = zone)
        // 15분 뒤: 2시간째 6%/h, drain 앱이 14분 동안 웨이크락
        stats.wl = 14 * 60_000L
        fill(at(14, 0), 9, 80.0, 6.0)
        Guardian.tick(context, log, at(14, 0), stats = stats, zone = zone)
        assertEquals(1, lowPower())
        assertTrue(Guardian.state(context).abnormalActive)
        val n = shadowOf(nm).getNotification(Guardian.N_ABNORMAL)
        assertNotNull(n)
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text, text.contains("시간당 6.0%"))
        assertTrue(text, text.contains("com.example.drain"))
        assertTrue(n.actions.map { it.title.toString() }.toString(), n.actions.any { it.title.toString() == "되돌리기" })
        assertTrue(n.actions.any { it.title.toString().contains("앱 정보") })
        // 범인 순위에도
        val culprits = Guardian.culprits(context, at(14, 0))
        assertEquals("com.example.drain", culprits[0].use.pkg)
        assertTrue(culprits[0].flags.toString(), culprits[0].flags.any { it.contains("stuck") })
    }

    /** 밤(23~7시)의 비정상 소모 알림은 소리·진동 없는 채널로 — 화면을 깨우지 않게 (점검에서 찾음). 낮엔 울린다 */
    @Test
    fun lateNightAlertsArePostedQuietly() {
        grant()
        val stats = object : UidStatsSource {
            var wl = 0L
            override fun take() = mapOf("com.example.drain" to UidUse("com.example.drain", wakelockMs = wl))
        }
        fill(at(23, 15), 9, 80.0, 1.0)
        Guardian.tick(context, log, at(23, 15), stats = stats, zone = zone)
        stats.wl = 14 * 60_000L
        fill(at(23, 30), 9, 80.0, 6.0)
        Guardian.tick(context, log, at(23, 30), stats = stats, zone = zone)
        assertEquals(BatteryAlerts.CHANNEL_QUIET, shadowOf(nm).getNotification(Guardian.N_ABNORMAL).channelId)
    }

    /** 하루 요약(꺾임 알림 포함)은 날짜마다 한 번, 아침 7시 넘어 첫 기록 때 — 20시간 간격으로 새벽에 밀려가지 않게 (점검에서 찾음) */
    @Test
    fun theDailySummaryRunsOncePerDayAfterSeven() {
        fill(at(8, 0, d = 13), 9, 80.0, 1.0)
        val p = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        fun run(t: Long) = com.mywatch.kakaomap.battery.BatterySummary.writeDailyIfDue(context, log, t, zone)
        run(at(8, 0, d = 13))
        assertEquals(at(8, 0, d = 13), p.getLong("summary_written_at", 0L))
        run(at(2, 0, d = 14))
        assertEquals("다음 날 새벽엔 아직", at(8, 0, d = 13), p.getLong("summary_written_at", 0L))
        run(at(7, 15, d = 14))
        assertEquals(at(7, 15, d = 14), p.getLong("summary_written_at", 0L))
        run(at(21, 0, d = 14))
        assertEquals("같은 날 두 번 안 씀", at(7, 15, d = 14), p.getLong("summary_written_at", 0L))
        // 30시간 넘게 못 썼으면(아침마다 꺼져 있었음 등) 시각과 상관없이
        run(at(3, 0, d = 15))
        assertEquals(at(7, 15, d = 14), p.getLong("summary_written_at", 0L))
        run(at(2, 0, d = 16))
        assertEquals(at(2, 0, d = 16), p.getLong("summary_written_at", 0L))
    }

    /** 되돌리다 쓰기에 실패하면(권한 없음 등) 기억을 지우지 않는다 — 다음 기록(밤 모드 아님)에서 다시 되돌린다 (점검에서 찾음) */
    @Test
    fun aFailedRestoreKeepsTheMemoryAndRetriesLater() {
        grant()
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        val levers = Levers(context)
        levers.applyNight(GuardianConfig(nightSaver = false, nightAodOff = false, nightWakeOff = true))
        assertEquals("0", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        Levers.writeHook = { _, _ -> false }
        try { levers.restoreNight() } finally { Levers.writeHook = null }
        assertTrue("기억이 남음", levers.holdsAnything)
        fill(at(14, 0), 9, 80.0, 1.0)
        Guardian.tick(context, log, at(14, 0), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("1", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        assertFalse(levers.holdsAnything)
    }

    /**
     * 권한이 없어졌는데(PC '되돌리기 all' 이 거둠) 밤 모드로 바꿔 둔 설정이 남아 있으면: 직접 되돌릴 것을 한 번 알리고 손을 뗀다 —
     * 타일이 '밤 모드 중'에 영영 머물거나 설정 변화 기록이 멈추지 않게 (점검에서 찾음)
     */
    @Test
    fun losingThePermissionWhileHoldingSettingsTellsWhatToTurnBackOn() {
        grant()
        Settings.Global.putString(cr, "ambient_enabled", "1")
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        fill(at(0, 30), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertTrue(Guardian.state(context).nightActive)
        assertEquals("0", Settings.Global.getString(cr, "ambient_enabled"))
        shadowOf(context as Application).denyPermissions("android.permission.WRITE_SECURE_SETTINGS")
        Guardian.tick(context, log, at(0, 45), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertFalse(Guardian.state(context).nightActive)
        assertFalse(Levers(context).holdsAnything)
        val text = shadowOf(nm).getNotification(Guardian.N_RELEASE).extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text, text.contains("AOD 켜기") && text.contains("손목 올려 켜기"))
        // 권한만 다시 주는 것은 메뉴 7 (메뉴 8 자동 치료는 앱 제한·통계 초기화까지 한다 — 리뷰에서 찾음)
        assertTrue(text, text.contains("메뉴 7"))
    }

    /** 알림의 '되돌리기'에서 권한이 없어 못 되돌린 것이 있으면 무엇을 직접 켤지 알린다 (조용히 내려놓지 않게 — 리뷰에서 찾음) */
    @Test
    fun theNotificationsRestoreTellsWhatItCouldNotUndo() {
        grant()
        Settings.Global.putInt(cr, "low_power", 0)
        assertTrue(Levers(context).applyAbnormal())
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        Levers.writeHook = { _, _ -> false }
        try {
            com.mywatch.kakaomap.battery.GuardianReceiver().onReceive(context, android.content.Intent(Guardian.ACTION_RESTORE))
        } finally { Levers.writeHook = null }
        val n = shadowOf(nm).getNotification(Guardian.N_RELEASE)
        assertNotNull(n)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("절전 모드 끄기"))
    }

    /** 설정으로 켠 절전은 '끈 채 유지(sticky)' 표시가 남아 충전 뒤 다시 켜진다 — 원래 꺼져 있었으면 되돌릴 때 그 표시도 지운다 (점검에서 찾음) */
    @Test
    fun restoringTheSaverClearsItsStickyFlag() {
        grant()
        Settings.Global.putInt(cr, "low_power", 0)
        val levers = Levers(context)
        assertTrue(levers.applyAbnormal())
        Settings.Global.putInt(cr, "low_power_sticky", 1)
        assertTrue(levers.restoreAbnormal())
        assertEquals("0", Settings.Global.getString(cr, "low_power"))
        assertEquals("0", Settings.Global.getString(cr, "low_power_sticky"))
        // sticky 설정이 없는 워치엔 만들지 않는다
        Settings.Global.putString(cr, "low_power_sticky", null)
        assertTrue(levers.applyAbnormal())
        assertTrue(levers.restoreAbnormal())
        assertNull(Settings.Global.getString(cr, "low_power_sticky"))
    }

    /** PC 툴킷이 권한을 거두기 전에 보내는 '모두 되돌리기' — 셸만 보낼 수 있다(DUMP 권한) */
    @Test
    fun theShellCanAskForEverythingBackBeforeThePermissionGoes() {
        grant()
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        Levers(context).applyNight(GuardianConfig(nightSaver = false, nightAodOff = false, nightWakeOff = true))
        com.mywatch.kakaomap.battery.ShellRestoreReceiver().onReceive(context, android.content.Intent(Guardian.ACTION_SHELL_RESTORE))
        assertEquals("1", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        assertFalse(Levers(context).holdsAnything)
        val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context, com.mywatch.kakaomap.battery.ShellRestoreReceiver::class.java), 0)
        assertEquals("android.permission.DUMP", info.permission)
        assertTrue(info.exported)
    }

    /** 길 안내를 시작하면 밤 모드로 바꿔 둔 것을 바로 되돌리고, 안내 중·직후엔 밤 모드를 켜지 않는다 (점검에서 찾음) */
    @Test
    fun navigationEndsNightModeAndKeepsItOff() {
        grant()
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        fill(at(0, 30), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertTrue(Guardian.state(context).nightActive)
        Guardian.onNavigationStarted(context, at(0, 35))
        assertFalse(Guardian.state(context).nightActive)
        assertEquals("1", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        fill(at(0, 50), 8, 58.0, 2.0)
        Guardian.tick(context, log, at(0, 50), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertFalse("안내 직후엔 다시 켜지 않음", Guardian.state(context).nightActive)
    }

    /**
     * 앱별 통계(시스템 전체 통계 동기화 — 무선·모뎀까지 부름)는 1시간에 한 번만. 최근 2시간 대기 소모가 높으면(범인을 바로 알아야)
     * 15분마다 (점검에서 찾음: 15분마다 모든 앱 스냅샷 + 파일 두 번 읽기가 이 앱의 배터리 소모)
     */
    @Test
    fun appStatsAreSnapshottedHourlyUnlessDrainingFast() {
        var takes = 0
        val stats = UidStatsSource { takes++; emptyMap() }
        fill(at(10, 0), 9, 80.0, 1.0)
        Guardian.tick(context, log, at(10, 0), stats = stats, zone = zone)
        Guardian.tick(context, log, at(10, 15), stats = stats, zone = zone)
        Guardian.tick(context, log, at(10, 45), stats = stats, zone = zone)
        assertEquals(1, takes)
        Guardian.tick(context, log, at(11, 0), stats = stats, zone = zone)
        assertEquals(2, takes)
        // 대기 소모가 시간당 4% 면 15분 뒤에도
        log.file.delete()
        fill(at(11, 15), 9, 80.0, 4.0)
        Guardian.tick(context, log, at(11, 15), stats = stats, zone = zone)
        assertEquals(3, takes)
    }

    /**
     * 앱별 통계 파일은 120KB 를 넘어도 맨 앞 줄이 26시간 안이면 아직 자르지 않는다 — 소모가 높아 15분마다 스냅샷을 남길 때
     * 15분마다 파일을 통째로 읽고 다시 쓰지 않게(2시간에 한 번쯤). 26시간이 넘으면 24시간치만 남긴다 (리뷰에서 찾음)
     */
    @Test
    fun theStatsFileIsNotRewrittenEveryQuarterHour() {
        val store = HealthStore(context)
        val file = java.io.File(BatteryLog(context).dir, "health_deltas.jsonl")
        file.delete()
        val head = at(0, 0, d = 19)
        var cpu = 0L
        fun snap(t: Long) { cpu += 1_000L; store.record(t, (1..25).associate { k -> "com.example.app$k" to UidUse("com.example.app$k", cpuMs = cpu + k) }) }
        snap(head - q)
        snap(head)                               // 맨 앞 줄
        // 16시간 뒤부터 10분마다 — 130KB 가 될 때 맨 앞 줄은 24~26시간 전 (그 전 코드는 24시간 넘은 줄을 그때마다 잘랐다)
        var t = head + 16 * 3_600_000L
        while (file.length() < 130_000L) { snap(t); t += 10 * 60_000L }
        val last = t - 10 * 60_000L
        assertTrue("전제: 맨 앞 줄이 24~26시간 전", last - head in 24 * 3_600_000L until 26 * 3_600_000L)
        assertTrue("맨 앞 줄이 26시간 안 — 아직 안 자름", store.windows(30, last).any { it.t == head })
        // 26시간이 넘으면 24시간치만 남긴다
        snap(head + 27 * 3_600_000L)
        assertTrue(store.windows(30, head + 27 * 3_600_000L).none { it.t == head })
    }

    @Test
    fun bedtimeChargeReminder() {
        grant()
        fill(at(22, 45), 8, 42.0, 3.0)
        Guardian.tick(context, log, at(22, 45), stats = UidStatsSource { emptyMap() }, zone = zone)
        val n = shadowOf(nm).getNotification(Guardian.N_CHARGE)
        assertNotNull(n)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(title, title.contains("분 충전"))
        assertNull(shadowOf(nm).getNotification(Guardian.N_ABNORMAL))
    }

    /** 게이지가 20포인트 넘게 낮게 세는 것이 확실하고 표시 잔량이 낮으면 권한 없이도 알린다 — 12시간에 한 번 */
    @Test
    fun certainLowCountingDriftAtALowLevelNotifiesOnceInTwelveHours() {
        val t = at(14, 0)
        log.file.delete()
        for (i in 0..7) log.append(BatterySample(t = t - (7 - i) * q, level = 22 - i / 3, voltageMv = 3950, currentUa = -20_000, interactive = false))
        Guardian.tick(context, log, t, stats = UidStatsSource { emptyMap() }, zone = zone)
        val n = shadowOf(nm).getNotification(Guardian.N_DRIFT)
        assertNotNull(n)
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text, text.contains("낮게 세는 중"))
        assertTrue(text, text.contains("강제 재시작"))
        // 알림의 '보정 루틴 시작' 버튼 → 보정 1단계, 시작 때 오차 기억
        assertTrue(n.actions.map { it.title.toString() }.toString(), n.actions.any { it.title.toString() == "보정 루틴 시작" })
        com.mywatch.kakaomap.battery.GuardianReceiver().onReceive(context, android.content.Intent(Guardian.ACTION_CAL_START))
        val cs = Calibration.state(context)
        assertEquals(1, cs.step)
        assertTrue(cs.driftBefore.toString(), (cs.driftBefore ?: 0) <= -20)
        assertTrue(shadowOf(nm).getNotification(Guardian.N_CAL).extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("보정 시작"))
        nm.cancel(Guardian.N_DRIFT)
        log.append(BatterySample(t = t + 4 * q, level = 19, voltageMv = 3950, currentUa = -20_000))
        Guardian.tick(context, log, t + 4 * q, stats = UidStatsSource { emptyMap() }, zone = zone)
        assertNull(shadowOf(nm).getNotification(Guardian.N_DRIFT))
    }

    /** 게이지 보정 루틴: 완충 1시간 → 빼서 쓰기 → 10% 아래 → 다시 완충 1시간, 단계마다 알림 */
    @Test
    fun calibrationStepsNotifyAsTheWatchChargesAndDrains() {
        fun title() = shadowOf(nm).getNotification(Guardian.N_CAL)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        fun step(t: Long, s: BatterySample) { log.append(s); Guardian.tick(context, log, t, stats = UidStatsSource { emptyMap() }, zone = zone) }
        val t0 = at(9, 0)
        log.file.delete()
        log.append(BatterySample(t0 - q, 70))
        Calibration.start(context, t0, driftPoints = -24)
        assertEquals(1, Calibration.state(context).step)
        step(t0, BatterySample(t0, 100, status = Status.FULL, plugged = true))
        assertTrue(title(), title().contains("1시간"))
        step(t0 + 4 * q, BatterySample(t0 + 4 * q, 100, status = Status.FULL, plugged = true))
        assertTrue(title(), title().contains("1단계 끝"))
        assertEquals(2, Calibration.state(context).step)
        step(t0 + 20 * q, BatterySample(t0 + 20 * q, 9, status = Status.DISCHARGING))
        assertTrue(title(), title().contains("2단계 끝"))
        step(t0 + 24 * q, BatterySample(t0 + 24 * q, 100, status = Status.FULL, plugged = true))
        step(t0 + 28 * q, BatterySample(t0 + 28 * q, 100, status = Status.FULL, plugged = true))
        assertTrue(title(), title().contains("보정 끝"))
        val st = Calibration.state(context)
        assertEquals(com.mywatch.battery.core.CalibrationRule.STEP_DONE, st.step)
        assertEquals(-24, st.driftBefore)
    }

    @Test
    fun healthStoreKeepsDeltasAndSumsTheRecentWindow() {
        val store = HealthStore(context)
        val t0 = at(10, 0)
        assertTrue(store.record(t0, mapOf("a" to UidUse("a", wakelockMs = 1_000))).isEmpty())   // 기준선
        val d1 = store.record(t0 + q, mapOf("a" to UidUse("a", wakelockMs = 4_000), "b" to UidUse("b", syncs = 2)))
        assertEquals(3_000L, d1.first { it.pkg == "a" }.wakelockMs)
        store.record(t0 + 2 * q, mapOf("a" to UidUse("a", wakelockMs = 9_000), "b" to UidUse("b", syncs = 3)))
        val (sum, covered) = store.recent(2, t0 + 2 * q)
        assertEquals(8_000L, sum.first { it.pkg == "a" }.wakelockMs)
        assertEquals(3, sum.first { it.pkg == "b" }.syncs)
        assertEquals(2 * q, covered)
        // 창 밖(3시간 전)은 빠진다
        assertTrue(store.recent(2, t0 + 5 * 3_600_000L).first.isEmpty())
        assertEquals("com.example.x", HealthStore.label(context, "com.example.x"))
        assertEquals("com.example.x 외 2", HealthStore.label(context, "com.example.x+2"))
    }

    /**
     * 15분마다 최근 2시간만 필요하다 — 24시간치 줄은 날짜만 보고 건너뛴다(JSON 을 다 풀지 않음). 그래서 옛 줄이 깨져 있어도 상관없고,
     * 날짜를 앞에서 못 읽는 줄은 예전처럼 통째로 풀어 본다
     */
    @Test
    fun recentHealthSkipsOldLinesByTheirTimestamp() {
        val store = HealthStore(context)
        val dir = BatteryLog(context).dir.apply { mkdirs() }
        val f = java.io.File(dir, "health_deltas.jsonl")
        val t = at(10, 0)
        f.writeText(
            "{\"t\":${t - 5 * 3_600_000L},\"ms\":900000,\"d\":[{\"p\":\"old\",\"wl\":5 BROKEN\n" +
                "{\"t\":${t - q},\"ms\":900000,\"d\":[{\"p\":\"a\",\"wl\":1000}]}\n" +
                "{\"ms\":900000,\"t\":$t,\"d\":[{\"p\":\"a\",\"wl\":500}]}\n"
        )
        val (sum, covered) = store.recent(2, t)
        assertEquals(1_500L, sum.single { it.pkg == "a" }.wakelockMs)
        assertEquals(2 * q, covered)
        assertEquals(listOf(t - q, t), store.windows(2, t).map { it.t })
    }

    /** 요약을 못 쓴 날도 15분마다 다시 30일치를 읽고 분석하지 않는다 — 다음 날 다시 */
    @Test
    fun aFailedDailySummaryIsNotRetriedEveryQuarterHour() {
        fill(at(14, 0), 9, 80.0, 1.0)
        log.summaryFile.parentFile?.mkdirs()
        log.summaryFile.mkdirs()      // 요약 파일 자리에 폴더 → 쓰기 실패
        runCatching { com.mywatch.kakaomap.battery.BatterySummary.writeDailyIfDue(context, log, at(14, 0)) }
        val p = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        assertEquals(at(14, 0), p.getLong("summary_written_at", 0L))
        log.summaryFile.delete()
    }

    /** 밤 모드: 손목 올림·화면 터치로 켜지는 것도 끄고(이 워치에 그 설정이 있을 때) 아침에 원래대로. 'Hey Google' 은 기본으로 그대로 */
    @Test
    fun nightTurnsWakeGesturesOffAndMorningRestoresThem() {
        grant()
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        Settings.Global.putString(cr, "ambient_touch_to_wake", "1")
        Settings.Global.putString(cr, "hotword_detection_enabled", "1")
        fill(at(0, 30), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("0", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        assertEquals("0", Settings.Global.getString(cr, "ambient_touch_to_wake"))
        assertEquals("1", Settings.Global.getString(cr, "hotword_detection_enabled"))
        assertTrue(Guardian.lastAction(context)!!, Guardian.lastAction(context)!!.contains("손목 올림"))

        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        fill(at(7, 5), 8, 50.0, 1.0)
        Guardian.tick(context, log, at(7, 5), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("1", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        assertEquals("1", Settings.Global.getString(cr, "ambient_touch_to_wake"))
        assertFalse(Levers(context).holdsAnything)
    }

    /**
     * 삼성 취침 모드처럼 다른 것이 밤사이 바꾼 값은 아침에 덮어쓰지 않는다 — 우리가 둔 값(0)이 그대로일 때만 되돌린다.
     * (취침 모드가 이미 AOD 를 꺼 둔 밤: 우리는 바꾸지 않았으니, 아침에 취침 모드가 켠 AOD 를 다시 끄면 안 됨)
     */
    @Test
    fun valuesChangedByBedtimeModeOvernightAreLeftAloneInTheMorning() {
        grant()
        Settings.Global.putString(cr, "ambient_enabled", "0")        // 취침 모드가 이미 끔
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        fill(at(0, 30), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("0", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        // 이미 꺼져 있던 AOD 는 바꾸지 않았으니 기억도 하지 않는다 ('지금 되돌리기'에 걸리지 않게)
        assertFalse(context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).contains(Levers.B_AOD))
        // 아침: 취침 모드가 끝나며 AOD 를 켜고, 사용자가 밤에 손목 올림을 손수 다시 켜 둠
        Settings.Global.putString(cr, "ambient_enabled", "1")
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        fill(at(7, 5), 8, 50.0, 1.0)
        Guardian.tick(context, log, at(7, 5), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("아침에 켜진 AOD 를 다시 끄지 않는다", "1", Settings.Global.getString(cr, "ambient_enabled"))
        assertEquals("1", Settings.Global.getString(cr, "ambient_tilt_to_wake"))
        assertFalse(Levers(context).holdsAnything)
    }

    private fun event(pkg: String, t: Long, type: Int) =
        shadowOf(context.getSystemService(android.app.usage.UsageStatsManager::class.java)).addEvent(pkg, t, type)

    /**
     * One UI Watch 홈(시스템 UI)이 화면이 거의 꺼져 있던 2시간 동안 CPU 를 30분 썼다 — 알려진 문제로 알리고 타일에 '강제 재시작'.
     * 홈은 늘 '보이는' 프로세스라 앱별 등급으로는 안 잡힌다 (2026년 9~10월 워치9·One UI 9 보고: '배터리 1위가 One UI Watch 홈')
     */
    @Test
    fun theWatchHomeBurningCpuWithTheScreenOffIsCaught() {
        grant()
        val home = com.mywatch.battery.core.KnownIssues.HOME
        event("android", at(10, 30), android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE)
        event("android", at(10, 36), android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE)   // 2시간 중 화면 6분
        var use = com.mywatch.battery.core.UidUse(home)
        val stats = UidStatsSource { mapOf(home to use) }
        fill(at(10, 0), 9, 80.0, 1.0)
        Guardian.tick(context, log, at(10, 0), stats = stats, zone = zone)
        use = com.mywatch.battery.core.UidUse(home, cpuMs = 30 * 60_000L, fgMs = 2 * 3_600_000L)
        fill(at(12, 0), 9, 78.0, 1.0)
        Guardian.tick(context, log, at(12, 0), stats = stats, zone = zone)
        assertEquals("One UI Watch 홈 과소모 — 강제 재시작", Guardian.tileIssue(context, at(12, 0)))
        val n = shadowOf(nm).allNotifications.firstOrNull { it.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty().contains("One UI Watch 홈") }
        assertTrue(shadowOf(nm).allNotifications.map { it.extras.getString(android.app.Notification.EXTRA_TITLE) }.toString(), n != null)
        assertTrue(n!!.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("강제 재시작"))
    }

    /** 화면을 오래 켜고 썼으면(2시간 중 50분) 홈의 CPU(40분)는 홈 화면·알림·타일을 그린 것 — 짚지 않는다 */
    @Test
    fun theWatchHomeWorkingWhileTheScreenIsOnIsNotAnIssue() {
        grant()
        val home = com.mywatch.battery.core.KnownIssues.HOME
        event("android", at(10, 30), android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE)
        event("android", at(11, 20), android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        var use = com.mywatch.battery.core.UidUse(home)
        val stats = UidStatsSource { mapOf(home to use) }
        fill(at(10, 0), 9, 80.0, 1.0)
        Guardian.tick(context, log, at(10, 0), stats = stats, zone = zone)
        use = com.mywatch.battery.core.UidUse(home, cpuMs = 40 * 60_000L, fgMs = 2 * 3_600_000L)
        fill(at(12, 0), 9, 78.0, 1.0)
        Guardian.tick(context, log, at(12, 0), stats = stats, zone = zone)
        assertEquals(null, Guardian.tileIssue(context, at(12, 0)))
    }

    /** 새벽 알림이 화면을 잠깐 켠 것은 '일어남'이 아니다 — 밤 모드를 끝내지 않는다. 사람이 40초 보면 끝낸다 */
    @Test
    fun aNotificationFlashAtDawnDoesNotEndTheNight() {
        grant()
        val on = android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE
        val off = android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE
        event("android", at(23, 30, d = 19), on)
        event("android", at(23, 35, d = 19), off)
        fill(at(0, 30), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertTrue(Guardian.state(context).nightActive)
        // 05:40 메신저 알림이 화면을 8초 켬
        event("com.kakao.talk", at(5, 40), com.mywatch.kakaomap.battery.ScreenUsage.NOTIFICATION_INTERRUPTION)
        event("android", at(5, 40) + 1_000, on)
        event("android", at(5, 40) + 9_000, off)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        fill(at(5, 45), 8, 55.0, 1.0)
        Guardian.tick(context, log, at(5, 45), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertTrue("알림이 켠 화면으로는 끝내지 않는다", Guardian.state(context).nightActive)
        // 06:10 사람이 40초 봄 → 일어남
        event("android", at(6, 10), on)
        event("android", at(6, 10) + 40_000, off)
        fill(at(6, 15), 8, 54.0, 1.0)
        Guardian.tick(context, log, at(6, 15), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertFalse(Guardian.state(context).nightActive)
    }

    /** 'Hey Google' 끄기는 고른 사람만 — 되돌리기로 원래대로 */
    @Test
    fun nightHotwordOffIsOptInAndRestored() {
        grant()
        Settings.Global.putString(cr, "hotword_detection_enabled", "1")
        Guardian.saveConfig(context, GuardianConfig(nightHotwordOff = true))
        assertTrue(Guardian.config(context).nightHotwordOff)
        fill(at(1, 0), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(1, 0), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("0", Settings.Global.getString(cr, "hotword_detection_enabled"))
        Guardian.restoreNow(context, at(1, 10))
        assertEquals("1", Settings.Global.getString(cr, "hotword_detection_enabled"))
    }

    @Test
    fun nightHotwordOffComesBackInTheMorning() {
        grant()
        Settings.Global.putString(cr, "hotword_detection_enabled", "1")
        Guardian.saveConfig(context, GuardianConfig(nightHotwordOff = true))
        fill(at(1, 0), 8, 60.0, 2.0)
        Guardian.tick(context, log, at(1, 0), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("0", Settings.Global.getString(cr, "hotword_detection_enabled"))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        fill(at(7, 5), 8, 50.0, 1.0)
        Guardian.tick(context, log, at(7, 5), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals("1", Settings.Global.getString(cr, "hotword_detection_enabled"))
        assertTrue(Guardian.lastAction(context)!!, Guardian.lastAction(context)!!.contains("'Hey Google' 복구"))
    }

    /** 레버 점검: 이 워치에 있는 설정과 권한을 하나씩 보여 준다 (실제 워치에서 어느 레버가 듣는지 확인용) */
    @Test
    fun leverCheckShowsWhatThisWatchSupports() {
        Settings.Global.putString(cr, "ambient_tilt_to_wake", "1")
        val before = Levers(context).check()
        assertTrue(before.joinToString { it.toString() }, before.none { it.usable })
        assertTrue(before.all { it.state.isNotBlank() })
        grant()
        val after = Levers(context).check().associateBy { it.id }
        assertTrue(after.keys.toString(), after.keys.containsAll(listOf("saver", "aod", "tilt", "touch", "hotword", "location", "sync")))
        assertTrue(after["aod"]!!.usable)
        assertEquals("global/ambient_enabled", after["aod"]!!.key)
        assertTrue(after["tilt"]!!.usable)
        assertFalse(after["touch"]!!.usable)
        assertEquals("이 워치엔 없음", after["touch"]!!.state)
        assertTrue(after["sync"]!!.usable)
        // 요약 파일·PC 툴킷이 읽는 한 줄
        val line = Levers(context).checkLine()
        assertTrue(line, line.contains("tilt=global/ambient_tilt_to_wake") && line.contains("touch=-"))
    }

    @Test
    fun leversRememberTheFirstValueOnly() {
        grant()
        val levers = Levers(context)
        assertTrue(levers.canWriteSecure)
        levers.applyAbnormal()                      // before_saver = 0
        Settings.Global.putInt(cr, "low_power", 1)
        levers.applyNight(GuardianConfig())         // 다시 기억하지 않는다 (0 그대로)
        levers.restoreNight()
        assertEquals(0, lowPower())
        assertFalse(levers.holdsAnything)
    }
}
