package com.mywatch.kakaomap.battery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mywatch.battery.core.AlertRule
import com.mywatch.battery.core.ChangeFinder
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.TrendReport
import com.mywatch.kakaomap.R
import java.time.ZoneId

/**
 * 비정상 소모 알림: 최근 2시간 내내 화면이 꺼져 있다시피 했는데 시간당 5% 넘게 닳았으면 한 번 알린다(6시간에 1번까지).
 * '언제부터 더 닳음' 알림: 최근 며칠 대기 소모가 늘어 꺾였으면 꺾인 날마다 한 번 (하루 요약 때).
 * 누르면 배터리 화면. 알림 권한이 없으면 조용히 넘어간다.
 */
object BatteryAlerts {
    const val CHANNEL = "battery-watch"
    const val NOTIFICATION_ID = 7301
    /** '언제부터 더 닳음' 알림 (꺾인 날마다 한 번) */
    const val TREND_ID = 7308
    private const val KEY_ALERT_AT = "alert_at"
    private const val KEY_TREND_FOR = "trend_notified_for"
    /** 타일에 보일 '언제부터' 한 줄과 그 줄을 보일 기한 (epoch ms) */
    const val KEY_TREND_LINE = "trend_line"
    const val KEY_TREND_UNTIL = "trend_until"

    fun check(context: Context, log: BatteryLog, now: Long = System.currentTimeMillis()) =
        check(context, log.readRecent(1, now), now, null)

    /** [samples] 최근 표본, [screenOnMs] 최근 2시간 화면 켜짐(사용 통계로 알 때) */
    fun check(context: Context, samples: List<com.mywatch.battery.core.BatterySample>, now: Long, screenOnMs: Long?) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_ALERT_AT, 0L).takeIf { it > 0 }
        val alert = AlertRule.check(samples, now, last, screenOnMs) ?: return
        if (runCatching { notify(context, alert) }.isSuccess) prefs.edit().putLong(KEY_ALERT_AT, now).apply()
    }

    /**
     * 최근 7일 안에 대기 소모가 늘어 꺾였으면(그 무렵 바뀐 것에 맞는 할 일과 함께) 꺾인 날마다 한 번 알리고, 타일 한 줄을 정해 둔다.
     * 하루 요약을 쓸 때(하루 한 번) 부른다 — 사람들은 배터리 화면을 잘 열지 않으므로. 꺾임이 없거나 지났으면 타일 줄을 지운다
     */
    fun trend(context: Context, trend: TrendReport?, names: (String) -> String?, zone: ZoneId = ZoneId.systemDefault()) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val t = trend?.takeIf { it.fresh }
        val shift = t?.shift
        val advice = t?.advice(names)
        if (t == null || shift == null || advice == null) {
            prefs.edit().remove(KEY_TREND_LINE).remove(KEY_TREND_UNTIL).apply()
            return
        }
        val until = shift.firstAfter.plusDays(TrendReport.FRESH_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        prefs.edit().putString(KEY_TREND_LINE, advice.first).putLong(KEY_TREND_UNTIL, until).apply()
        val key = shift.firstAfter.toString()
        if (prefs.getString(KEY_TREND_FOR, null) == key) return
        if (runCatching { notifyTrend(context, advice.first, "${ChangeFinder.title(shift)} · ${ChangeFinder.numbers(shift)}\n할 일: ${advice.second}") }.isSuccess) {
            prefs.edit().putString(KEY_TREND_FOR, key).apply()
        }
    }

    /** 타일에 보일 '언제부터' 한 줄 (기한이 지났으면 null) */
    fun trendLine(context: Context, now: Long): String? {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TREND_LINE, null)?.takeIf { prefs.getLong(KEY_TREND_UNTIL, 0L) > now }
    }

    private fun notifyTrend(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        channel(context, nm)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openBattery(context))
            .setAutoCancel(true)
            .build()
        nm.notify(TREND_ID, n)
    }

    private fun channel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.battery_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openBattery(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, BatteryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(context: Context, alert: AlertRule.Alert) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        channel(context, nm)
        val open = openBattery(context)
        val pph = DrainAnalyzer.fmt(alert.pph)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("배터리 시간당 ${pph}% — 비정상")
            .setContentText("화면을 거의 안 켰는데 ${DrainAnalyzer.fmt(alert.hours)}시간 동안 시간당 ${pph}% 닳았습니다. 홈+뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작해 보세요.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}
