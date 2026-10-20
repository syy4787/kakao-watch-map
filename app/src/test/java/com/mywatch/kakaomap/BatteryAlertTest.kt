package com.mywatch.kakaomap

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.kakaomap.battery.BatteryAlerts
import com.mywatch.kakaomap.battery.BatteryLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 비정상 소모 알림: 2시간 화면 꺼진 채 시간당 5% 넘게 → 알림 한 번, 6시간 안엔 다시 안 함 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryAlertTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private lateinit var log: BatteryLog
    private val now = 1_760_000_000_000L
    private val q = 15 * 60_000L

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun drain(pph: Double, interactive: Boolean = false) {
        for (i in 0..8) log.append(BatterySample(t = now - 2 * 3_600_000L + i * q, level = (100 - pph * i / 4).toInt(), interactive = interactive))
    }

    @Test
    fun abnormalDrainNotifiesOnceWithTheRate() {
        drain(6.0)
        BatteryAlerts.check(context, log, now)
        val all = shadowOf(nm).allNotifications
        assertEquals(1, all.size)
        val title = all[0].extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        assertTrue(title, title.contains("6.0%"))
        val text = all[0].extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("7초"))
        assertEquals(BatteryAlerts.CHANNEL, all[0].channelId)

        // 바로 다시 검사해도 또 알리지 않는다
        BatteryAlerts.check(context, log, now + 60_000L)
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun afterSixHoursItMayAlertAgain() {
        drain(6.0)
        BatteryAlerts.check(context, log, now)
        nm.cancelAll()
        for (i in 0..8) log.append(BatterySample(t = now + 7 * 3_600_000L - 2 * 3_600_000L + i * q, level = (60 - 6.0 * i / 4).toInt()))
        BatteryAlerts.check(context, log, now + 7 * 3_600_000L)
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun screenOnDrainIsNotAnAlert() {
        drain(6.0, interactive = true)
        BatteryAlerts.check(context, log, now)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
