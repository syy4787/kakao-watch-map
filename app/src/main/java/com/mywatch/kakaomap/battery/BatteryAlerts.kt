package com.mywatch.kakaomap.battery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mywatch.battery.core.AlertRule
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.kakaomap.R

/**
 * 비정상 소모 알림: 최근 2시간 내내 화면이 꺼져 있다시피 했는데 시간당 5% 넘게 닳았으면 한 번 알린다(6시간에 1번까지).
 * 누르면 배터리 화면. 알림 권한이 없으면 조용히 넘어간다.
 */
object BatteryAlerts {
    const val CHANNEL = "battery-watch"
    const val NOTIFICATION_ID = 7301
    private const val KEY_ALERT_AT = "alert_at"

    fun check(context: Context, log: BatteryLog, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_ALERT_AT, 0L).takeIf { it > 0 }
        val alert = AlertRule.check(log.readAll(), now, last) ?: return
        if (runCatching { notify(context, alert) }.isSuccess) prefs.edit().putLong(KEY_ALERT_AT, now).apply()
    }

    private fun notify(context: Context, alert: AlertRule.Alert) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.battery_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, BatteryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
