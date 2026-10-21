package com.mywatch.kakaomap

import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.battery.core.AppSpan
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.Span
import com.mywatch.battery.core.UidUse
import com.mywatch.kakaomap.battery.BatteryActivity
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.Guardian
import com.mywatch.kakaomap.battery.GuardianJournal
import com.mywatch.kakaomap.battery.HealthStore
import com.mywatch.kakaomap.battery.ScreenUsage
import com.mywatch.kakaomap.battery.UidStatsSource
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 배터리 사용 내역(화면 켜짐·꺼짐, 가장 많이 닳은 시간), 알려진 문제(Play 서비스 과소모) 알림, 밤 모드 효과 일지.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryInsightsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private lateinit var log: BatteryLog
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun at(h: Int, m: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, "low_power", 0)
    }

    private fun allowUsage() = shadowOf(context.getSystemService(AppOpsManager::class.java))
        .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)

    private fun event(pkg: String, t: Long, type: Int) = shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(pkg, t, type)

    @Test
    fun screenUsageNeedsThePermissionThenReadsScreenAndApps() {
        val su = ScreenUsage(context)
        // 권한을 막아 둔 상태 (Robolectric 의 기본값은 허용이라 직접 막는다)
        shadowOf(context.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(su.available)
        assertNull(su.read(at(18), at(21, 30)))
        allowUsage()
        assertTrue(su.available)
        event("android", at(19), UsageEvents.Event.SCREEN_INTERACTIVE)
        event("com.google.android.youtube", at(19, 1), UsageEvents.Event.ACTIVITY_RESUMED)
        event("com.google.android.youtube", at(19, 35), UsageEvents.Event.ACTIVITY_PAUSED)
        event("android", at(19, 40), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event("android", at(21), UsageEvents.Event.SCREEN_INTERACTIVE)        // 지금도 켜져 있음
        val (screen, apps) = su.read(at(18), at(21, 30))!!
        assertEquals(listOf(Span(at(19), at(19, 40)), Span(at(21), at(21, 30))), screen)
        assertEquals(listOf(AppSpan("com.google.android.youtube", at(19, 1), at(19, 35))), apps)
    }

    @Test
    fun aScreenAlreadyOnWhenTheWindowStartsCountsFromTheStart() {
        allowUsage()
        event("android", at(18, 10), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val (screen, _) = ScreenUsage(context).read(at(18), at(19))!!
        assertEquals(listOf(Span(at(18), at(18, 10))), screen)
    }

    @Test
    fun journalPairsNightOnAndOff() {
        val j = GuardianJournal(context)
        j.add(GuardianJournal.NIGHT_ON, at(0, 30, 19))
        j.add(GuardianJournal.NIGHT_OFF, at(7, 0, 19))
        j.add(GuardianJournal.NIGHT_ON, at(0, 40, 20))
        assertEquals(listOf(Span(at(0, 30, 19), at(7, 0, 19)), Span(at(0, 40, 20), at(3, 0, 20))), j.nightSessions(at(3, 0, 20)))
        // 끝 표시 없이 오래 지난 것은 10시간까지만
        assertEquals(Span(at(0, 40, 20), at(10, 40, 20)), j.nightSessions(at(23, 0, 20)).last())
    }

    @Test
    fun guardianNightModeIsWrittenToTheJournal() {
        shadowOf(context as Application).grantPermissions("android.permission.WRITE_SECURE_SETTINGS")
        fun fill(end: Long, start: Double, pph: Double) {
            log.file.delete()
            for (i in 0 until 8) log.append(BatterySample(t = end - (7 - i) * q, level = (start - pph * i / 4).toInt()))
        }
        fill(at(0, 30), 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        fill(at(7, 5), 50.0, 1.0)
        Guardian.tick(context, log, at(7, 5), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals(listOf(Span(at(0, 30), at(7, 5))), GuardianJournal(context).nightSessions(at(8)))
    }

    /** 2시간째 화면 꺼진 채 Play 서비스가 15분마다 CPU 4분(시간당 16분)을 쓰는 워치 */
    private fun playServicesLoop(start: Long, ticks: Int) {
        val stats = object : UidStatsSource {
            var cpu = 0L
            override fun take() = mapOf(
                "com.google.android.gms+2" to UidUse("com.google.android.gms+2", cpuMs = cpu, wakelockMs = cpu),
                "com.samsung.android.wear.shealth" to UidUse("com.samsung.android.wear.shealth", sensorMs = cpu / 4),
            )
        }
        for (k in 0 until ticks) {
            val t = start + k * q
            log.append(BatterySample(t, 80 - k))
            Guardian.tick(context, log, t, stats = stats, zone = zone)
            stats.cpu += 4 * 60_000L
        }
    }

    @Test
    fun aPlayServicesLoopIsNotifiedWithTheFixAndTheAppInfoButton() {
        playServicesLoop(at(14), 3)
        val n = shadowOf(nm).getNotification(Guardian.N_ISSUE)
        assertNotNull(n)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(title, title.contains("Play 서비스"))
        assertTrue(text, text.contains("캐시 지우기"))
        assertTrue(n.actions.map { it.title.toString() }.toString(), n.actions.any { it.title.toString() == "Play 서비스 앱 정보" })
        // 12시간에 한 번
        nm.cancel(Guardian.N_ISSUE)
        playServicesLoop(at(14, 45), 2)
        assertNull(shadowOf(nm).getNotification(Guardian.N_ISSUE))
    }

    private fun open(): Pair<BatteryActivity, RowAdapter> {
        val a = Robolectric.buildActivity(BatteryActivity::class.java).setup().get()
        val adapter = a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && adapter.rows.none { it is Row.Header && it.title == "배터리 사용 내역 (24시간)" }) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        return a to adapter
    }

    @Test
    fun theBatteryScreenShowsWhereTheBatteryWentAndTheKnownIssue() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        allowUsage()
        val now = System.currentTimeMillis()
        // 6시간: 화면 꺼진 동안 시간당 2%, 지금부터 3시간 전 40분 동안 화면 켜짐(시간당 30%)
        val on = Span(now - 3 * 3_600_000L, now - 3 * 3_600_000L + 40 * 60_000L)
        event("android", on.start, UsageEvents.Event.SCREEN_INTERACTIVE)
        event("com.google.android.youtube", on.start + 60_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        event("com.google.android.youtube", on.end - 60_000L, UsageEvents.Event.ACTIVITY_PAUSED)
        event("android", on.end, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        var level = 90.0
        var t = now - 6 * 3_600_000L
        while (t <= now) {
            log.append(BatterySample(t, level.toInt()))
            for (k in 0 until 15) level -= if (t + k * 60_000L in on.start until on.end) 0.5 else 2.0 / 60
            t += q
        }
        // 앱별 통계: Play 서비스가 멈춘 채 도는 중 (최근 2시간)
        val store = HealthStore(context)
        store.record(now - 2 * 3_600_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms")))
        store.record(now - 3_600_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = 20 * 60_000L)))
        store.record(now - 60_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = 40 * 60_000L)))

        val (_, adapter) = open()
        val rows = adapter.rows
        assertTrue(rows.filterIsInstance<Row.Note>().any { it.text.contains("화면(") && it.text.contains("대기(") })
        val idle = rows.filterIsInstance<Row.Step>().first { it.key == "usage:idle" }
        val idlePph = Regex("시간당 ([0-9.]+)%").find(idle.title)!!.groupValues[1].toDouble()
        assertTrue(idle.title, idlePph in 1.5..2.6)
        val hour = rows.filterIsInstance<Row.Step>().first { it.key.startsWith("hour:") }
        assertTrue(hour.sub ?: "", (hour.sub ?: "").contains("화면"))
        val issue = rows.filterIsInstance<Row.Step>().first { it.key == "issue:play-services-loop" }
        assertTrue(issue.sub ?: "", (issue.sub ?: "").contains("캐시 지우기"))
        assertTrue(rows.filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_APP_INFO + "com.google.android.gms" })
    }

    /** 화면이 하루 수백 번 켜졌다 금방 꺼지면(손목 올려 켜기 오작동) 그 횟수와 끌 설정을 알려 준다. 알림이 켠 것은 앱별로 */
    @Test
    fun manyShortWakesAndNotificationWakesGetAdvice() {
        allowUsage()
        val now = System.currentTimeMillis()
        var t = now - 20 * 3_600_000L
        repeat(120) {          // 손목을 들 때 켜졌다 2초 만에 꺼짐
            event("android", t, UsageEvents.Event.SCREEN_INTERACTIVE)
            event("android", t + 2_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
            t += 5 * 60_000L
        }
        repeat(60) {           // 카카오톡 알림이 화면을 켬
            event("com.kakao.talk", t, ScreenUsage.NOTIFICATION_INTERRUPTION)
            event("android", t + 400L, UsageEvents.Event.SCREEN_INTERACTIVE)
            event("android", t + 8_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
            t += 3 * 60_000L
        }
        val u = ScreenUsage(context).readAll(now - 24 * 3_600_000L, now)!!
        assertEquals(60, u.notifications.size)
        assertEquals(180, u.screen.size)
        // 표본: 화면 기록과 상관없이 하루치
        var level = 95.0
        var s = now - 22 * 3_600_000L
        while (s <= now) { log.append(BatterySample(s, level.toInt())); level -= 0.6; s += q }
        val (_, adapter) = open()
        val rows = adapter.rows
        assertTrue(rows.filterIsInstance<Row.Note>().any { it.text.startsWith("화면 켜짐 180번") })
        val todo = rows.filterIsInstance<Row.Step>().filter { it.key.startsWith("wake:") }
        assertEquals(2, todo.size)
        assertTrue(todo[0].title, todo[0].title.contains("120번"))
        assertTrue(todo[0].sub ?: "", (todo[0].sub ?: "").contains("손목 올려 화면 켜기"))
        assertTrue(todo[1].title, todo[1].title.contains("알림이 화면을 60번"))
    }
}
