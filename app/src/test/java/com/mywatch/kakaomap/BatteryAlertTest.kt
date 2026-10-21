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

    private val zone = java.time.ZoneId.of("Asia/Seoul")
    private fun oct(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val shift = com.mywatch.battery.core.Shift(java.time.LocalDate.of(2026, 10, 17), java.time.LocalDate.of(2026, 10, 18), 1.0, 2.5, 4, 3)
    private val fw = com.mywatch.battery.core.Change(1L, com.mywatch.battery.core.ChangeKind.FIRMWARE, "fw", "A", "L350XXU1AZI1")
    private val trend = com.mywatch.battery.core.TrendReport(emptyList(), shift, listOf(fw), emptyList(), null, fresh = true)
    private fun posted() = shadowOf(nm).allNotifications.size

    /** 최근 며칠 대기 소모가 늘어 꺾였으면 꺾임마다 한 번 알린다 — 할 일과 함께, 같은 꺾임은 다시 안 알림, 타일 한 줄도 정해 둔다 */
    @Test
    fun aFreshRiseIsNotifiedOncePerShiftAndPutOnTheTile() {
        BatteryAlerts.trend(context, trend, { null }, zone, oct(19, 9))
        val all = shadowOf(nm).allNotifications
        assertEquals(1, all.size)
        assertEquals("소프트웨어 업데이트 뒤 더 닳음", all[0].extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        val text = all[0].extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("10/18부터 대기 소모가 늘었습니다") && text.contains("1.0% → 2.5%") && text.contains("할 일"))
        // 다음 날 요약에서 같은 꺾임 — 다시 알리지 않는다
        nm.cancelAll()
        BatteryAlerts.trend(context, trend, { null }, zone, oct(20, 9))
        assertEquals(0, posted())
        // 타일 줄: 꺾인 첫날부터 7일 뒤까지
        assertEquals("소프트웨어 업데이트 뒤 더 닳음", BatteryAlerts.trendLine(context, oct(20, 9)))
        assertEquals(null, BatteryAlerts.trendLine(context, oct(31, 9)))
        // 꺾임이 사라지면 타일 줄도 지운다
        BatteryAlerts.trend(context, trend.copy(fresh = false), { null }, zone, oct(20, 9))
        assertEquals(null, BatteryAlerts.trendLine(context, oct(20, 9)))
        // 일주일 뒤 새 꺾임이면 다시 알린다
        BatteryAlerts.trend(context, trend.copy(shift = shift.copy(lastBefore = java.time.LocalDate.of(2026, 10, 25), firstAfter = java.time.LocalDate.of(2026, 10, 26))), { null }, zone, oct(27, 9))
        assertEquals(1, posted())
    }

    /**
     * 날이 쌓이며 꺾인 날이 하루이틀 앞뒤로 옮겨 가도 같은 꺾임 — 다시 알리지 않는다. 다만 그 뒤 훨씬(1.5배) 더 닳으면 다시 (리뷰에서 찾음)
     */
    @Test
    fun aShiftWhoseDayMovesALittleIsTheSameShift() {
        BatteryAlerts.trend(context, trend, { null }, zone, oct(19, 9))
        nm.cancelAll()
        BatteryAlerts.trend(context, trend.copy(shift = shift.copy(lastBefore = java.time.LocalDate.of(2026, 10, 16), firstAfter = java.time.LocalDate.of(2026, 10, 17))), { null }, zone, oct(20, 9))
        BatteryAlerts.trend(context, trend.copy(shift = shift.copy(lastBefore = java.time.LocalDate.of(2026, 10, 19), firstAfter = java.time.LocalDate.of(2026, 10, 20))), { null }, zone, oct(21, 9))
        assertEquals(0, posted())
        // 일주일 뒤에도 꺾인 날이 사흘 안에서 옮겨 간 것이면 같은 꺾임
        BatteryAlerts.trend(context, trend.copy(shift = shift.copy(lastBefore = java.time.LocalDate.of(2026, 10, 19), firstAfter = java.time.LocalDate.of(2026, 10, 20))), { null }, zone, oct(26, 9))
        assertEquals(0, posted())
        BatteryAlerts.trend(context, trend.copy(shift = shift.copy(after = 4.0)), { null }, zone, oct(22, 9))
        assertEquals(1, posted())
    }

    /** 밤(22~7시)에 찾은 꺾임은 화면을 깨우지 않고 아침 첫 기록 때 알린다 (타일 줄은 바로) */
    @Test
    fun aRiseFoundAtNightWaitsForTheMorning() {
        BatteryAlerts.trend(context, trend, { null }, zone, oct(19, 2))
        assertEquals(0, posted())
        assertEquals("소프트웨어 업데이트 뒤 더 닳음", BatteryAlerts.trendLine(context, oct(19, 2)))
        BatteryAlerts.flush(context, oct(19, 6, 45), zone)
        assertEquals(0, posted())
        BatteryAlerts.flush(context, oct(19, 7, 15), zone)
        assertEquals(1, posted())
        assertEquals(BatteryAlerts.CHANNEL, shadowOf(nm).allNotifications[0].channelId)
        nm.cancelAll()
        BatteryAlerts.flush(context, oct(19, 7, 30), zone)
        assertEquals(0, posted())
    }

    /** 밤의 비정상 소모 알림은 소리·진동 없는 채널로 (아침에 목록에서 본다) */
    @Test
    fun anAbnormalDrainAtNightIsPostedQuietly() {
        val night = oct(19, 3)
        for (i in 0..8) log.append(BatterySample(t = night - 2 * 3_600_000L + i * q, level = (100 - 6.0 * i / 4).toInt()))
        BatteryAlerts.check(context, log.readAll(), night, null, zone)
        assertEquals(BatteryAlerts.CHANNEL_QUIET, shadowOf(nm).allNotifications.single().channelId)
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(BatteryAlerts.CHANNEL_QUIET).importance)
    }
}
