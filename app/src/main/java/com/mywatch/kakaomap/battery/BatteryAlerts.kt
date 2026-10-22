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
import com.mywatch.battery.core.DayTrend
import com.mywatch.kakaomap.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 비정상 소모 알림: 최근 2시간 내내 화면이 꺼져 있다시피 했는데 시간당 5% 넘게 닳았으면 한 번 알린다(6시간에 1번까지).
 * '언제부터 더 닳음' 알림: 최근 며칠 대기 소모가 늘어 꺾였으면 꺾임마다 한 번 (하루 요약 때, 밤이면 아침에).
 * 충전 중 뜨거움 알림: 충전 중 42℃ 이상이고 80% 아래면 식히는 길을 충전마다 한 번까지(같은 안내는 사흘에 한 번 — 더 뜨거우면 다시).
 * 밤(23~7시) 알림은 소리·진동 없는 채널로 — 화면을 깨워 배터리를 더 쓰지 않게.
 * 누르면 배터리 화면. 알림 권한이 없으면 조용히 넘어간다.
 */
object BatteryAlerts {
    /** 울리는 채널 (낮의 급한 알림·잠자기 전 충전 알림) */
    const val CHANNEL = "battery-watch"
    /** 소리·진동 없이 목록에만 (밤 알림·참고 알림) */
    const val CHANNEL_QUIET = "battery-quiet"
    const val QUIET_FROM_HOUR = 23
    const val QUIET_UNTIL_HOUR = 7
    const val NOTIFICATION_ID = 7301
    /** '언제부터 더 닳음' 알림 (꺾임마다 한 번) */
    const val TREND_ID = 7308
    /** 충전 중 뜨거움 알림 (충전마다 한 번까지) */
    const val HOT_CHARGE_ID = 7312
    /** 이 잔량 아래에서 뜨거우면 알린다 — 그 위는 원래 느리게 차고 곧 끝난다 */
    const val HOT_CHARGE_BELOW = 80
    /** 같은 안내는 이만큼에 한 번 — 워치9는 충전 때마다 뜨거워질 수 있어 밤마다 같은 알림을 내지 않게 (더 뜨거우면 다시) */
    const val HOT_CHARGE_COOLDOWN_MS = 3L * 24 * 3_600_000L
    /** 지난 알림보다 이만큼 더 뜨거우면 쉬는 동안에도 다시 */
    const val HOT_CHARGE_HOTTER_C = 2.0
    private const val KEY_HOT_CHARGE_SENT = "hot_charge_sent"
    private const val KEY_HOT_CHARGE_AT = "hot_charge_at"
    private const val KEY_HOT_CHARGE_C = "hot_charge_c"
    /** 꺾인 날이 이만큼 안에서 옮겨 간 것은 같은 꺾임 */
    const val SAME_SHIFT_DAYS = 3L
    /** 새 꺾임이라도 지난 알림에서 이만큼은 지나야 다시 알린다 (훨씬 더 닳으면 바로) */
    const val REALERT_MS = 7L * 24 * 3_600_000L
    private const val KEY_ALERT_AT = "alert_at"
    private const val KEY_TREND_FOR = "trend_notified_for"
    private const val KEY_TREND_AT = "trend_notified_at"
    private const val KEY_TREND_AFTER = "trend_notified_after"
    private const val KEY_PENDING_TITLE = "trend_pending_title"
    private const val KEY_PENDING_TEXT = "trend_pending_text"
    /** 타일에 보일 '언제부터' 한 줄과 그 줄을 보일 기한 (epoch ms) */
    const val KEY_TREND_LINE = "trend_line"
    const val KEY_TREND_UNTIL = "trend_until"

    fun check(context: Context, log: BatteryLog, now: Long = System.currentTimeMillis()) =
        check(context, log.readRecent(1, now), now, null)

    /**
     * [samples] 최근 표본, [screenOnMs] 최근 2시간 화면 켜짐(사용 통계로 알 때), [freshFrom] 기록을 새로 재기 시작한 때(재시작·PC 치료 —
     * 그 앞의 표본은 섞지 않는다)
     */
    fun check(
        context: Context, samples: List<com.mywatch.battery.core.BatterySample>, now: Long, screenOnMs: Long?,
        zone: ZoneId = ZoneId.systemDefault(), freshFrom: Long? = null,
    ) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_ALERT_AT, 0L).takeIf { it > 0 }
        val alert = AlertRule.check(samples, now, last, screenOnMs, freshFrom) ?: return
        if (runCatching { notify(context, alert, quiet(now, zone)) }.isSuccess) prefs.edit().putLong(KEY_ALERT_AT, now).apply()
    }

    /**
     * 충전 중 뜨거움 — 워치9는 충전 중 44℃까지 뜨거워지며 충전이 느려질 수 있다는 보고가 많다(삼성 공식 완충 80~90분인데 2~4시간, 2026년
     * 10월 기준 삼성은 고침을 알리지 않음 — Android Authority·Notebookcheck 2026-08-12, Sammy Fans 2026-10-13). 충전 중 [ChargeHabits.HOT_C]℃
     * 이상이고 아직 [HOT_CHARGE_BELOW]% 아래면 그 자리에서 식히는 길을 알린다 — 충전마다 한 번까지, 같은 안내는 [HOT_CHARGE_COOLDOWN_MS]에
     * 한 번(그보다 [HOT_CHARGE_HOTTER_C]℃ 넘게 더 뜨거우면 다시). 밤엔 소리·진동 없이. 충전기에서 뺐으면 그 알림을 거두고 다음 충전에 다시 셈
     */
    fun hotCharge(context: Context, s: com.mywatch.battery.core.BatterySample, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        if (!s.plugged) {
            chargeStarted(context)
            return
        }
        val temp = s.tempDeciC?.div(10.0) ?: return
        if (temp < com.mywatch.battery.core.ChargeHabits.HOT_C || s.level >= HOT_CHARGE_BELOW || prefs.getBoolean(KEY_HOT_CHARGE_SENT, false)) return
        val lastAt = prefs.getLong(KEY_HOT_CHARGE_AT, 0L)
        val lastC = prefs.getFloat(KEY_HOT_CHARGE_C, 0f).toDouble()
        // 시계가 뒤로 가 미래가 된 지난 알림은 쉼으로 치지 않는다
        if (lastAt > 0 && now - lastAt in 0 until HOT_CHARGE_COOLDOWN_MS && temp < lastC + HOT_CHARGE_HOTTER_C) return
        val t = DrainAnalyzer.fmt(temp).removeSuffix(".0")
        val text = "뜨거우면 배터리를 지키려 충전이 느려질 수 있습니다(워치9에서 많이 보고됨). 케이스를 빼고 서늘한 곳에서, 작은 선풍기 바람을 워치와 " +
            "충전기 쪽으로 — 한 사용자: 15→100% 2시간 10분 → 1시간 15분. 얼음은 습기 때문에 쓰지 마세요"
        if (runCatching { notifyHotCharge(context, "충전 중 ${t}℃ — 충전이 느려질 수 있음", text, quiet(now, zone)) }.isSuccess) {
            prefs.edit().putBoolean(KEY_HOT_CHARGE_SENT, true).putLong(KEY_HOT_CHARGE_AT, now).putFloat(KEY_HOT_CHARGE_C, temp.toFloat()).apply()
        }
    }

    /** 새 충전(또는 충전기에서 뺌) — 지난 충전의 뜨거움 알림을 거두고 이번 충전에서 다시 셈 */
    fun chargeStarted(context: Context) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HOT_CHARGE_SENT, false)) return
        prefs.edit().putBoolean(KEY_HOT_CHARGE_SENT, false).apply()
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(HOT_CHARGE_ID) }
    }

    private fun notifyHotCharge(context: Context, title: String, text: String, quiet: Boolean) {
        val (nm, channel) = manager(context, quiet) ?: return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openBattery(context))
            .setAutoCancel(true)
            .build()
        nm.notify(HOT_CHARGE_ID, n)
    }

    /** 밤(23~7시)인가 — 알림이 소리·진동으로 화면을 깨우지 않게 */
    fun quiet(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val h = Instant.ofEpochMilli(now).atZone(zone).hour
        return h >= QUIET_FROM_HOUR || h < QUIET_UNTIL_HOUR
    }

    /** 알림 관리자와 낼 채널 — [quiet] 면 소리·진동 없는 채널 (없으면 만든다) */
    fun manager(context: Context, quiet: Boolean): Pair<NotificationManager, String>? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        val id = if (quiet) CHANNEL_QUIET else CHANNEL
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                if (quiet) NotificationChannel(id, context.getString(R.string.battery_channel_quiet), NotificationManager.IMPORTANCE_LOW)
                else NotificationChannel(id, context.getString(R.string.battery_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        return nm to id
    }

    /**
     * 최근 7일 안에 대기 소모가 늘어 꺾였으면(그 무렵 바뀐 것에 맞는 할 일과 함께) 꺾인 날마다 한 번 알리고, 타일 한 줄을 정해 둔다.
     * 하루 요약을 쓸 때(하루 한 번) 부른다 — 사람들은 배터리 화면을 잘 열지 않으므로. 꺾임이 없거나 지났으면 타일 줄을 지운다
     */
    fun trend(
        context: Context, trend: TrendReport?, names: (String) -> String?, zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val t = trend?.takeIf { it.fresh }
        val shift = t?.shift
        val advice = t?.advice(names)
        if (t == null || shift == null || advice == null) {
            prefs.edit().remove(KEY_TREND_LINE).remove(KEY_TREND_UNTIL).remove(KEY_PENDING_TITLE).remove(KEY_PENDING_TEXT).apply()
            return
        }
        val until = shift.firstAfter.plusDays(TrendReport.FRESH_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        prefs.edit().putString(KEY_TREND_LINE, advice.first).putLong(KEY_TREND_UNTIL, until).apply()
        // 같은 꺾임(날이 쌓이며 꺾인 날이 며칠 앞뒤로 옮겨 감)이거나 지난 알림이 일주일 안이면 다시 알리지 않는다 — 훨씬 더 닳으면 다시
        val lastFor = prefs.getString(KEY_TREND_FOR, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val lastAt = prefs.getLong(KEY_TREND_AT, 0L)
        val lastAfter = prefs.getFloat(KEY_TREND_AFTER, 0f).toDouble()
        val same = lastFor != null && abs(ChronoUnit.DAYS.between(lastFor, shift.firstAfter)) <= SAME_SHIFT_DAYS
        val tooSoon = lastAt > 0 && now - lastAt in 0 until REALERT_MS
        val muchWorse = lastAfter > 0 && shift.after >= lastAfter * DayTrend.RATIO
        if ((same || tooSoon) && !muchWorse) return
        prefs.edit()
            .putString(KEY_TREND_FOR, shift.firstAfter.toString()).putLong(KEY_TREND_AT, now).putFloat(KEY_TREND_AFTER, shift.after.toFloat())
            .putString(KEY_PENDING_TITLE, advice.first)
            .putString(KEY_PENDING_TEXT, "${ChangeFinder.title(shift)} · ${ChangeFinder.numbers(shift)}\n할 일: ${advice.second}")
            .apply()
        flush(context, now, zone)
    }

    /** 밤에 찾아 미뤄 둔 '언제부터 더 닳음' 알림을 낮이 되면 낸다 — 15분 기록마다 (설정값 몇 개만 읽는다) */
    fun flush(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_PENDING_TITLE, null) ?: return
        if (quiet(now, zone)) return
        val text = prefs.getString(KEY_PENDING_TEXT, null).orEmpty()
        if (runCatching { notifyTrend(context, title, text) }.isSuccess) prefs.edit().remove(KEY_PENDING_TITLE).remove(KEY_PENDING_TEXT).apply()
    }

    /** 타일에 보일 '언제부터' 한 줄 (기한이 지났으면 null) */
    fun trendLine(context: Context, now: Long): String? {
        val prefs = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TREND_LINE, null)?.takeIf { prefs.getLong(KEY_TREND_UNTIL, 0L) > now }
    }

    private fun notifyTrend(context: Context, title: String, text: String) {
        val (nm, channel) = manager(context, quiet = false) ?: return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openBattery(context))
            .setAutoCancel(true)
            .build()
        nm.notify(TREND_ID, n)
    }

    private fun openBattery(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, BatteryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(context: Context, alert: AlertRule.Alert, quiet: Boolean) {
        val (nm, channel) = manager(context, quiet) ?: return
        val open = openBattery(context)
        val pph = DrainAnalyzer.fmt(alert.pph)
        val n = NotificationCompat.Builder(context, channel)
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
