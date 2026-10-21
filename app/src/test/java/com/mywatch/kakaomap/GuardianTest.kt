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
