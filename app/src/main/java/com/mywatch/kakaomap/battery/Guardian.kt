package com.mywatch.kakaomap.battery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.AppDrain
import com.mywatch.battery.core.AppDrainRank
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.GuardianAction
import com.mywatch.battery.core.GuardianConfig
import com.mywatch.battery.core.GuardianRules
import com.mywatch.battery.core.GuardianState
import com.mywatch.battery.core.WatchModels
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import java.time.ZoneId

/**
 * 배터리 지킴이: 15분 표본마다 [GuardianRules] 가 정한 대로 레버([Levers])를 당기고 알린다.
 *  · 밤(00~07시)에 화면이 30분 넘게 꺼져 있으면 절전 모드·AOD 끔(설정에 따라 위치·동기화도) → 아침·충전·기상 때 원래대로
 *  · 화면 꺼진 채 시간당 5% 넘게 새면 바로 절전 모드 + 범인 앱 알림(앱 정보로 바로 가기, 되돌리기)
 *  · 잠자기 전 아침까지 못 버틸 것 같으면 "N분 충전" 알림
 * 권한(WRITE_SECURE_SETTINGS)이 없으면 설정은 건드리지 않고 알림만 한다. 사용자가 절전을 손수 끄면 12시간 물러난다.
 */
object Guardian {
    const val CHANNEL = BatteryAlerts.CHANNEL
    const val N_ABNORMAL = 7302
    const val N_CHARGE = 7303
    const val N_NIGHT = 7304
    const val ACTION_RESTORE = "com.mywatch.kakaomap.battery.RESTORE"

    private const val K_NIGHT_SAVER = "g_night_saver"
    private const val K_NIGHT_AOD = "g_night_aod"
    private const val K_NIGHT_LOCATION = "g_night_location"
    private const val K_NIGHT_SYNC = "g_night_sync"
    private const val K_ABNORMAL = "g_abnormal"
    private const val K_REMINDER = "g_reminder"
    private const val K_S_NIGHT = "gs_night"
    private const val K_S_ABNORMAL = "gs_abnormal"
    private const val K_S_OVERRIDE = "gs_override_until"
    private const val K_S_NOTIFY_AT = "gs_abnormal_notify_at"
    private const val K_S_REMINDER_DAY = "gs_reminder_day"
    private const val K_S_ABNORMAL_SINCE = "gs_abnormal_since"
    private const val K_S_NIGHT_SINCE = "gs_night_since"
    private const val K_S_SAVER_SEEN = "gs_saver_seen"
    private const val K_S_SAVER_BROKEN = "gs_saver_broken"
    private const val K_LAST_ACTION = "gs_last_action"

    private fun prefs(c: Context) = c.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)

    fun config(c: Context): GuardianConfig = prefs(c).let { p ->
        GuardianConfig(
            nightSaver = p.getBoolean(K_NIGHT_SAVER, true), nightAodOff = p.getBoolean(K_NIGHT_AOD, true),
            nightLocationOff = p.getBoolean(K_NIGHT_LOCATION, false), nightSyncOff = p.getBoolean(K_NIGHT_SYNC, false),
            abnormalSaver = p.getBoolean(K_ABNORMAL, true), chargeReminder = p.getBoolean(K_REMINDER, true),
        )
    }

    fun saveConfig(c: Context, cfg: GuardianConfig) = prefs(c).edit()
        .putBoolean(K_NIGHT_SAVER, cfg.nightSaver).putBoolean(K_NIGHT_AOD, cfg.nightAodOff)
        .putBoolean(K_NIGHT_LOCATION, cfg.nightLocationOff).putBoolean(K_NIGHT_SYNC, cfg.nightSyncOff)
        .putBoolean(K_ABNORMAL, cfg.abnormalSaver).putBoolean(K_REMINDER, cfg.chargeReminder).apply()

    fun state(c: Context): GuardianState = prefs(c).let { p ->
        GuardianState(
            nightActive = p.getBoolean(K_S_NIGHT, false), abnormalActive = p.getBoolean(K_S_ABNORMAL, false),
            userOverrideUntil = p.getLong(K_S_OVERRIDE, 0L), lastAbnormalNotifyAt = p.getLong(K_S_NOTIFY_AT, 0L),
            lastReminderDay = p.getString(K_S_REMINDER_DAY, null), abnormalSince = p.getLong(K_S_ABNORMAL_SINCE, 0L),
            nightSince = p.getLong(K_S_NIGHT_SINCE, 0L), saverSeenOn = p.getBoolean(K_S_SAVER_SEEN, false), saverBroken = p.getBoolean(K_S_SAVER_BROKEN, false),
        )
    }

    fun saveState(c: Context, s: GuardianState) = prefs(c).edit()
        .putBoolean(K_S_NIGHT, s.nightActive).putBoolean(K_S_ABNORMAL, s.abnormalActive)
        .putLong(K_S_OVERRIDE, s.userOverrideUntil).putLong(K_S_NOTIFY_AT, s.lastAbnormalNotifyAt)
        .putString(K_S_REMINDER_DAY, s.lastReminderDay).putLong(K_S_ABNORMAL_SINCE, s.abnormalSince)
        .putLong(K_S_NIGHT_SINCE, s.nightSince).putBoolean(K_S_SAVER_SEEN, s.saverSeenOn).putBoolean(K_S_SAVER_BROKEN, s.saverBroken).apply()

    /** 마지막으로 한 일(화면에 보여 줌) */
    fun lastAction(c: Context): String? = prefs(c).getString(K_LAST_ACTION, null)
    private fun noteAction(c: Context, text: String) = prefs(c).edit().putString(K_LAST_ACTION, "${BatterySummary.time(System.currentTimeMillis())} $text").apply()

    /** 15분 표본 뒤에 불린다 */
    fun tick(
        context: Context, log: BatteryLog, now: Long = System.currentTimeMillis(),
        stats: UidStatsSource? = null, zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val c = context.applicationContext
        val samples = log.readAll()
        val analysis: Analysis? = runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL), zone).analyze(samples, now) }
            .onFailure { ErrorLog.record("배터리·지킴이", it) }.getOrNull()

        // 앱별 누적 사용량 → 이번 구간 사용량 (권한이 있을 때만)
        val source = stats ?: HealthReader(c).takeIf { it.available }
        if (source != null) runCatching { HealthStore(c).record(now, source.take()) }.onFailure { ErrorLog.record("배터리·앱별 통계", it) }

        val levers = Levers(c)
        if (!levers.canWriteSecure) {
            // 설정을 못 바꾸는 동안은 알림만
            BatteryAlerts.check(c, log, now)
            return
        }
        val cfg = config(c)
        val st = state(c)
        val (actions, next) = GuardianRules.decide(now, zone, cfg, st, samples, analysis, levers.saverOn())
        // 설정으로 절전이 안 켜지는 워치면 절전 레버는 빼고 나머지만
        val effective = if (st.saverBroken || next.saverBroken) cfg.copy(nightSaver = false) else cfg
        for (a in actions) runCatching { act(c, levers, effective, a, now, next.saverBroken) }.onFailure { ErrorLog.record("배터리·지킴이", it) }
        saveState(c, next)
    }

    /**
     * 사용자가 앱을 열었다(첫 화면·배터리 화면) — 아침 2시간 안이면 일어난 것으로 보고 밤 모드를 바로 끝낸다
     * (15분 표본은 화면 켜짐을 놓치기 쉽다)
     */
    fun onUserOpenedApp(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        val c = context.applicationContext
        val st = state(c)
        if (!st.nightActive) return
        val cfg = config(c)
        val local = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val minute = local.hour * 60 + local.minute
        if (GuardianRules.minutesUntil(minute, cfg.nightEndMin) > 120 && GuardianRules.inWindow(minute, cfg.nightStartMin, cfg.nightEndMin)) return
        runCatching { Levers(c).restoreNight() }
        saveState(c, st.copy(nightActive = false, saverSeenOn = false))
        noteAction(c, "앱을 열어 밤 모드 끝")
    }

    private fun act(c: Context, levers: Levers, cfg: GuardianConfig, a: GuardianAction, now: Long, saverBroken: Boolean) {
        when (a) {
            GuardianAction.SaverNotWorking -> noteAction(c, "이 워치는 설정으로 절전 모드가 켜지지 않아 절전 레버는 건너뜁니다 (AOD 등은 계속)")
            GuardianAction.NightOn -> {
                val done = levers.applyNight(cfg)
                noteAction(c, "밤 모드 시작 — " + done.joinToString(", ").ifEmpty { "바꿀 수 있는 설정 없음" })
            }
            GuardianAction.NightOff -> {
                val done = levers.restoreNight()
                noteAction(c, "밤 모드 끝 — " + done.joinToString(", ").ifEmpty { "되돌릴 것 없음" })
            }
            is GuardianAction.AbnormalOn -> {
                val ok = !saverBroken && levers.applyAbnormal()
                val culprits = culprits(c, now)
                noteAction(c, "비정상 소모(시간당 ${DrainAnalyzer.fmt(a.pph)}%) — 절전 모드 ${if (ok) "켬" else "못 켬"}" +
                    culprits.take(2).joinToString(prefix = " · 의심: ") { HealthStore.label(c, it.use.pkg) })
                if (a.notify) notifyAbnormal(c, a.pph, ok, culprits)
            }
            GuardianAction.AbnormalOff -> { levers.restoreAbnormal(); noteAction(c, "비정상 대응 끝 — 절전 복구") }
            GuardianAction.UserOverride -> { levers.restoreAll(keepSaver = true); noteAction(c, "사용자가 절전을 꺼서 12시간 쉼") }
            is GuardianAction.ChargeReminder -> notifyCharge(c, a)
        }
    }

    /** 최근 2시간 가장 많이 쓴 앱들 (권한이 없으면 빈 목록) */
    fun culprits(c: Context, now: Long): List<AppDrainRank> {
        val (uses, covered) = runCatching { HealthStore(c).recent(2, now) }.getOrDefault(emptyList<com.mywatch.battery.core.UidUse>() to 0L)
        if (uses.isEmpty()) return emptyList()
        return AppDrain.rank(uses, covered.coerceAtLeast(15 * 60_000L), ownPkg = c.packageName)
    }

    private fun channel(c: Context): NotificationManager? {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return null
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.battery_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    private fun openScreen(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, BatteryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyAbnormal(c: Context, pph: Double, saverOn: Boolean, culprits: List<AppDrainRank>) {
        val nm = channel(c) ?: return
        val names = culprits.take(2).map { HealthStore.label(c, it.use.pkg) }
        val text = buildString {
            append("화면을 거의 안 켰는데 2시간 동안 시간당 ${DrainAnalyzer.fmt(pph)}% 닳았습니다. ")
            append(if (saverOn) "절전 모드를 켰습니다(충전하면 풀림). " else "절전 모드를 켜지 못했습니다 — 직접 켜 주세요. ")
            if (names.isNotEmpty()) append("의심 앱: ${names.joinToString(", ")}. ")
            append("계속되면 홈+뒤로 버튼을 꺼질 때까지 눌러 강제 재시작하세요.")
        }
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("배터리 시간당 ${DrainAnalyzer.fmt(pph)}% — 절전 켬")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(c))
            .setAutoCancel(true)
        culprits.firstOrNull()?.let { top ->
            val first = top.use.pkg.substringBefore('+')
            if (first != c.packageName) {
                val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$first")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                b.addAction(0, "${HealthStore.label(c, top.use.pkg)} 앱 정보", PendingIntent.getActivity(c, 1, info, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
        b.addAction(0, "되돌리기", PendingIntent.getBroadcast(c, 2, Intent(c, GuardianReceiver::class.java).setAction(ACTION_RESTORE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        runCatching { nm.notify(N_ABNORMAL, b.build()) }
    }

    private fun notifyCharge(c: Context, a: GuardianAction.ChargeReminder) {
        val nm = channel(c) ?: return
        val text = "밤사이 시간당 ${DrainAnalyzer.fmt(a.ratePph)}% 기준으로 아침 7시엔 약 ${a.predictedPercent.toInt().coerceAtLeast(0)}% 가 남습니다. 지금 ${a.minutes}분만 충전하면 아침까지 넉넉합니다."
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("자기 전에 ${a.minutes}분 충전 권장")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(c))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(N_CHARGE, n) }
    }

    /** 알림의 '되돌리기'·화면의 '지금 되돌리기': 우리가 바꾼 것을 모두 원래대로, 12시간 쉼 */
    fun restoreNow(c: Context, now: Long = System.currentTimeMillis()) {
        Levers(c).restoreAll(keepSaver = false)
        val s = state(c)
        saveState(c, s.copy(nightActive = false, abnormalActive = false, userOverrideUntil = now + GuardianRules.USER_OVERRIDE_MS))
        noteAction(c, "사용자가 되돌림 — 12시간 쉼")
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(N_ABNORMAL) }
    }
}

/** 알림의 '되돌리기' 버튼 */
class GuardianReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Guardian.ACTION_RESTORE) Guardian.restoreNow(context)
    }
}
