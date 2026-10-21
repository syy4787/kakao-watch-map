package com.mywatch.kakaomap.battery

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
import com.mywatch.battery.core.AlertRule
import com.mywatch.battery.core.AppDrainRank
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.KnownIssues
import com.mywatch.battery.core.Span
import com.mywatch.battery.core.GuardianAction
import com.mywatch.battery.core.GuardianConfig
import com.mywatch.battery.core.GuardianRules
import com.mywatch.battery.core.GuardianState
import com.mywatch.battery.core.Wakes
import com.mywatch.battery.core.WatchModels
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import java.time.ZoneId

/**
 * 배터리 지킴이: 15분 표본마다 [GuardianRules] 가 정한 대로 레버([Levers])를 당기고 알린다.
 *  · 밤(00~07시)에 화면이 30분 넘게 꺼져 있으면 절전 모드·AOD·손목 올림/터치로 켜기 끔(설정에 따라 'Hey Google'·위치·동기화도)
 *    → 아침·충전·기상 때 원래대로
 *  · 화면 꺼진 채 시간당 5% 넘게 새면 바로 절전 모드 + 범인 앱 알림(앱 정보로 바로 가기, 되돌리기)
 *  · 잠자기 전 아침까지 못 버틸 것 같으면 "N분 충전" 알림
 * 권한(WRITE_SECURE_SETTINGS)이 없으면 설정은 건드리지 않고 알림만 한다. 사용자가 절전을 손수 끄면 12시간 물러난다.
 */
object Guardian {
    const val CHANNEL = BatteryAlerts.CHANNEL
    const val N_ABNORMAL = 7302
    const val N_CHARGE = 7303
    const val N_NIGHT = 7304
    /** 잔량 게이지가 실제보다 낮게 세는 중(가짜 소모) */
    const val N_DRIFT = 7305
    /** 게이지 보정 루틴 단계 */
    const val N_CAL = 7306
    /** 알려진 문제(Play 서비스 과소모 등) */
    const val N_ISSUE = 7307
    /** 권한이 없어져 바꿔 둔 설정을 되돌리지 못함 — 직접 되돌릴 것 */
    const val N_RELEASE = 7309
    /** 길 안내가 시작·끝난 때 (안내 뒤 2시간은 그 소모를 '비정상'으로 읽지 않게) */
    const val K_NAV_STARTED = "nav_started_at"
    const val K_NAV_STOPPED = "nav_stopped_at"
    const val ACTION_RESTORE = "com.mywatch.kakaomap.battery.RESTORE"
    /** PC 툴킷이 지킴이 권한을 거두기 전에 보내는 '모두 되돌리기' ([ShellRestoreReceiver]) */
    const val ACTION_SHELL_RESTORE = "com.mywatch.kakaomap.battery.SHELL_RESTORE"
    /** 잔량 표시 오차 알림의 '보정 루틴 시작' 버튼 */
    const val ACTION_CAL_START = "com.mywatch.kakaomap.battery.CAL_START"

    private const val K_NIGHT_SAVER = "g_night_saver"
    private const val K_NIGHT_AOD = "g_night_aod"
    private const val K_NIGHT_LOCATION = "g_night_location"
    private const val K_NIGHT_SYNC = "g_night_sync"
    private const val K_NIGHT_WAKE = "g_night_wake"
    private const val K_NIGHT_HOTWORD = "g_night_hotword"
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
    private const val K_S_DRIFT_NOTIFY_AT = "gs_drift_notify_at"
    private const val K_S_ISSUE_AT = "gs_issue_notify_at"
    /** 알려진 문제 알림은 12시간에 한 번 */
    const val ISSUE_COOLDOWN_MS = 12L * 3_600_000L
    /** 지킴이 판단에 읽는 기록 (일) */
    const val RECENT_DAYS = 3
    /**
     * 화면 켜짐을 볼 창 — 이 안에 기록이 있어야 사용 통계를 믿는다. 15분마다 읽으니 짧게(6시간): 그보다 오래 꺼져 있었으면
     * 표본으로 어림해도 '밤 모드 시작'(30분)·'최근 2시간 화면' 판단은 같다
     */
    const val SCREEN_LOOKBACK_MS = 6L * 3_600_000L

    /** 화면 기록이 최근 6시간에 없을 때 '켜진 적 없음'인지 보려고 더 길게 보는 창 (밤새 안 켠 아침) */
    const val SCREEN_FALLBACK_MS = 24L * 3_600_000L

    /** 마지막으로 알린 드리프트(포인트) — 알림에서 보정을 시작할 때 '시작 때 오차'로 */
    internal const val K_S_LAST_DRIFT = "gs_last_drift"
    private const val K_LAST_ACTION = "gs_last_action"

    private fun prefs(c: Context) = c.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)

    fun config(c: Context): GuardianConfig = prefs(c).let { p ->
        GuardianConfig(
            nightSaver = p.getBoolean(K_NIGHT_SAVER, true), nightAodOff = p.getBoolean(K_NIGHT_AOD, true),
            nightLocationOff = p.getBoolean(K_NIGHT_LOCATION, false), nightSyncOff = p.getBoolean(K_NIGHT_SYNC, false),
            nightWakeOff = p.getBoolean(K_NIGHT_WAKE, true), nightHotwordOff = p.getBoolean(K_NIGHT_HOTWORD, false),
            abnormalSaver = p.getBoolean(K_ABNORMAL, true), chargeReminder = p.getBoolean(K_REMINDER, true),
        )
    }

    /** 지킴이 설정 하나의 값 — [id] 는 [BatteryActivity.CONFIG_IDS] 의 이름 (모르면 null) */
    fun flag(cfg: GuardianConfig, id: String): Boolean? = when (id) {
        "night_saver" -> cfg.nightSaver
        "night_aod" -> cfg.nightAodOff
        "night_location" -> cfg.nightLocationOff
        "night_sync" -> cfg.nightSyncOff
        "night_wake" -> cfg.nightWakeOff
        "night_hotword" -> cfg.nightHotwordOff
        "abnormal" -> cfg.abnormalSaver
        "reminder" -> cfg.chargeReminder
        else -> null
    }

    fun saveConfig(c: Context, cfg: GuardianConfig) = prefs(c).edit()
        .putBoolean(K_NIGHT_SAVER, cfg.nightSaver).putBoolean(K_NIGHT_AOD, cfg.nightAodOff)
        .putBoolean(K_NIGHT_LOCATION, cfg.nightLocationOff).putBoolean(K_NIGHT_SYNC, cfg.nightSyncOff)
        .putBoolean(K_NIGHT_WAKE, cfg.nightWakeOff).putBoolean(K_NIGHT_HOTWORD, cfg.nightHotwordOff)
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
        // 판단에는 최근 며칠이면 충분하다 — 15분마다 30일 파일 전체를 읽고 분석하지 않는다(이 앱이 배터리를 덜 쓰게)
        val samples = log.readRecent(RECENT_DAYS, now)
        val analysis: Analysis? = runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL), zone).analyze(samples, now) }
            .onFailure { ErrorLog.record("배터리·지킴이", it) }.getOrNull()
        // 화면 켜짐을 사용 통계로 정확히 (권한이 있을 때) — 15분 표본 사이에 켰다 끈 것도 안다.
        // 창 안에 화면 기록이 하나도 없으면 이 기기가 기록을 안 남기는 것으로 보고 표본으로 어림한다
        val raw = runCatching { ScreenUsage(c).readAll(now - SCREEN_LOOKBACK_MS, now) }.getOrNull()
        val usage = raw?.takeIf { it.screen.isNotEmpty() }
        val screen = usage?.screen
        // '사람이 봤다'(아침 기상)는 알림이 잠깐 켠 화면을 빼고 본다 — 새벽 알림 한 번에 밤 모드가 끝나지 않게
        val looked = usage?.let { u -> Wakes.withoutNotificationFlashes(u.screen, u.notifications.map { it.second }) }

        // 앱별 누적 사용량 → 이번 구간 사용량 (권한이 있을 때만). 시스템 통계 동기화(무선·모뎀까지)를 부르므로 1시간에 한 번 —
        // 최근 2시간 대기 소모가 높을 때만 15분마다(범인을 바로 알아야 하므로)
        val source = stats ?: HealthReader(c).takeIf { it.available }
        if (source != null && snapshotDue(c, now, samples)) {
            runCatching { HealthStore(c).record(now, source.take(), HealthStore.Boot.now(c)) }.onFailure { ErrorLog.record("배터리·앱별 통계", it) }
            // 화면 켜짐(홈 과소모 판단용): 최근 6시간에 화면 기록이 없으면(밤새 안 켬) 하루치로 '켜진 적 없음'인지 본다.
            // 받은 알림은 화면 기록과 따로 — 밤새 화면은 안 켰어도 알림은 받았고, 그걸 보여 준 몫은 홈 탓이 아니다
            val issueScreen = screen ?: runCatching { ScreenUsage(c).readScreen(now - SCREEN_FALLBACK_MS, now)?.spans }.getOrNull()
            val notes = raw?.notifications?.map { it.second }
            runCatching { checkKnownIssues(c, now, zone, issueScreen, notes) }.onFailure { ErrorLog.record("배터리·알려진 문제", it) }
        }

        // 권한이 필요 없는 것들: 게이지 보정 루틴 단계, 잔량 표시 오차 알림
        runCatching { Calibration.tick(c, samples, now, zone) }.onFailure { ErrorLog.record("배터리·보정", it) }
        runCatching { checkDrift(c, analysis, now) }.onFailure { ErrorLog.record("배터리·게이지", it) }

        val levers = Levers(c)
        val screenOn2h = screen?.sumOf { it.overlap(now - AlertRule.WINDOW_MS, now) }
        val navigating = navigatingRecently(c, now)
        if (!levers.canWriteSecure) {
            // 권한이 없어졌는데(PC 에서 거둠 등) 바꿔 둔 설정이 남아 있으면 되돌릴 수 없다 — 무엇을 직접 되돌릴지 한 번 알리고 손을 뗀다
            runCatching { releaseWithoutPermission(c, levers, now, zone) }.onFailure { ErrorLog.record("배터리·지킴이", it) }
            // 설정을 못 바꾸는 동안은 알림만 (길 안내 중·직후엔 그 소모라 알리지 않는다)
            if (!navigating) BatteryAlerts.check(c, samples, now, screenOn2h, zone)
            return
        }
        val cfg = config(c)
        val st = state(c)
        val screenOff = looked?.let { spans -> screenOffFor(spans, now) }
        val (actions, next) = GuardianRules.decide(now, zone, cfg, st, samples, analysis, levers.saverOn(), screenOff, screenOn2h, navigating)
        // 설정으로 절전이 안 켜지는 워치면 절전 레버는 빼고 나머지만
        val effective = if (st.saverBroken || next.saverBroken) cfg.copy(nightSaver = false) else cfg
        val phantom = analysis?.verdict?.tags?.let { "phantom" in it || "drift" in it } ?: false
        for (a in actions) runCatching { act(c, levers, effective, a, now, next.saverBroken, phantom, zone) }.onFailure { ErrorLog.record("배터리·지킴이", it) }
        // 밤·비정상 모드가 아닌데 기억이 남아 있으면(되돌리다 쓰기에 실패함) 다시 되돌려 본다 — 바꾼 채 하루를 보내지 않게
        if (!next.nightActive && !next.abnormalActive && levers.holdsAnything) runCatching { levers.restoreAll() }
        saveState(c, next)
    }

    /** 이 앱의 길 안내 중이거나 최근 2시간 안에 안내했다 */
    fun navigatingRecently(c: Context, now: Long): Boolean {
        if (com.mywatch.kakaomap.nav.NavigationService.running) return true
        val p = prefs(c)
        val last = maxOf(p.getLong(K_NAV_STARTED, 0L), p.getLong(K_NAV_STOPPED, 0L))
        return last > 0 && now - last in 0 until AlertRule.WINDOW_MS
    }

    /**
     * 길 안내가 시작됐다 — 밤·비정상 모드로 바꿔 둔 것(손목 올려 켜기·AOD·절전)을 바로 되돌린다. 안내 중엔 지킴이가 손대지 않는다
     * (다음 15분 기록까지 기다리면 밤 안내 첫 갈림길에서 손목을 들어도 화면이 안 켜진다)
     */
    fun onNavigationStarted(context: Context, now: Long = System.currentTimeMillis()) {
        val c = context.applicationContext
        prefs(c).edit().putLong(K_NAV_STARTED, now).apply()
        val st = state(c)
        if (!st.nightActive && !st.abnormalActive) return
        val levers = Levers(c)
        if (st.nightActive) { runCatching { levers.restoreNight() }; GuardianJournal(c).add(GuardianJournal.NIGHT_OFF, now) }
        if (st.abnormalActive) runCatching { levers.restoreAbnormal() }
        saveState(c, st.copy(nightActive = false, abnormalActive = false, saverSeenOn = false))
        noteAction(c, "길 안내를 시작해 밤·비정상 모드를 풂")
    }

    fun onNavigationStopped(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context.applicationContext).edit().putLong(K_NAV_STOPPED, now).apply()
    }

    /** 권한이 없는데 바꿔 둔 설정·모드가 남아 있으면: 직접 되돌릴 것을 알리고(있으면) 모두 내려놓는다 */
    private fun releaseWithoutPermission(c: Context, levers: Levers, now: Long, zone: ZoneId) {
        val st = state(c)
        if (!levers.holdsAnything && !st.nightActive && !st.abnormalActive) return
        val todo = levers.releaseWithoutPermission()
        if (st.nightActive) GuardianJournal(c).add(GuardianJournal.NIGHT_OFF, now)
        saveState(c, st.copy(nightActive = false, abnormalActive = false, saverSeenOn = false))
        noteAction(c, "권한이 없어 손 뗌" + if (todo.isEmpty()) "" else " — 직접 되돌릴 것: ${todo.joinToString(", ")}")
        if (todo.isEmpty()) return
        notifyRelease(c, todo, now, zone)
    }

    /** 바꿔 둔 설정을 되돌리지 못했다 — 직접 되돌릴 것 (권한만 다시 주는 것은 메뉴 7, 메뉴 8 은 앱 제한·통계 초기화까지 한다) */
    private fun notifyRelease(c: Context, todo: List<String>, now: Long, zone: ZoneId) {
        val (nm, channel) = BatteryAlerts.manager(c, BatteryAlerts.quiet(now, zone)) ?: return
        val text = "직접 되돌려 주세요: ${todo.joinToString(" · ")}. PC 툴킷 메뉴 7(권한 주기)로 권한을 다시 주면 지킴이가 다시 맡습니다."
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("지킴이 권한이 없어 설정을 못 되돌림")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(c))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(N_RELEASE, n) }
    }

    /** 앱별 통계 스냅샷 간격 (15분 기록 넷에 한 번, 시각이 조금 밀려도) */
    const val SNAPSHOT_EVERY_MS = 55L * 60_000L

    /**
     * 재시작한 뒤 이만큼 지나야 기준값을 남긴다 — 켜진 직후엔 동기화·업데이트가 몰려, 그게 첫 판단 창에 들어가면 시킨 대로 재시작한
     * 사람에게 엉뚱한 'Play 서비스 과소모'를 보인다 (3차 리뷰)
     */
    const val POST_BOOT_SETTLE_MS = 10L * 60_000L

    /**
     * 앱별 통계를 지금 남길 때인가: 1시간이 지났거나, 최근 2시간 대기 소모가 대기 기준(시간당 3.2%)을 넘을 때. 마지막 스냅샷 뒤에
     * 재시작했으면 켜진 지 [POST_BOOT_SETTLE_MS] 가 지난 첫 기록에서 바로(그 전엔 남기지 않음) — 1시간을 기다리지 않고 기준값을 남겨
     * 1시간 뒤 다시 볼 수 있게
     */
    private fun snapshotDue(c: Context, now: Long, samples: List<com.mywatch.battery.core.BatterySample>): Boolean {
        val store = HealthStore(c)
        val last = store.lastAt()
        if (last <= 0L) return true
        val boot = HealthStore.Boot.now(c)
        if (store.rebootedSinceLast(boot)) return boot.elapsedMs >= POST_BOOT_SETTLE_MS
        if (now < last || now - last >= SNAPSHOT_EVERY_MS) return true
        val idle = com.mywatch.battery.core.Rates.idleWindow(samples, now, 2).percentPerHour
        return idle != null && idle >= com.mywatch.battery.core.Thresholds.IDLE_NORMAL
    }

    /**
     * 배터리 화면을 열 때: 앱별 통계가 [maxAgeMs] 보다 묵었으면 지금 하나 남기고 알려진 문제(타일 줄)도 다시 본다
     * (1시간 간격이라 '최근 2시간 앱별 소모'가 늦지 않게, 화면을 자주 열어도 타일 줄이 밀려 사라지지 않게). 권한이 없으면 아무것도 안 한다
     */
    fun snapshotIfStale(context: Context, now: Long = System.currentTimeMillis(), maxAgeMs: Long = 15L * 60_000L, source: UidStatsSource? = null) {
        val c = context.applicationContext
        val reader = source ?: HealthReader(c).takeIf { it.available } ?: return
        val store = HealthStore(c)
        if (now - store.lastAt() < maxAgeMs && now >= store.lastAt()) return
        // 재시작 직후엔 기준값을 남기지 않는다 (15분 기록과 같이 — 켜진 지 10분 뒤부터)
        val boot = HealthStore.Boot.now(c)
        if (store.rebootedSinceLast(boot) && boot.elapsedMs < POST_BOOT_SETTLE_MS) return
        runCatching { store.record(now, reader.take(), boot) }.onFailure { ErrorLog.record("배터리·앱별 통계", it) }
        // 화면 켜짐·알림(One UI Watch 홈 과소모 판단용) — 15분 기록과 같은 읽기로 (화면 기록이 없으면 하루치로 '켜진 적 없음'인지)
        val raw = runCatching { ScreenUsage(c).readAll(now - SCREEN_LOOKBACK_MS, now) }.getOrNull()
        val screen = raw?.screen?.takeIf { it.isNotEmpty() } ?: runCatching { ScreenUsage(c).readScreen(now - SCREEN_FALLBACK_MS, now)?.spans }.getOrNull()
        runCatching { knownIssues(c, now, screen, raw?.notifications?.map { it.second }) }.onFailure { ErrorLog.record("배터리·알려진 문제", it) }
    }

    /** 타일에 보일 알려진 문제 (한 줄, 등급) — 15분 기록·배터리 화면이 찾아 적어 둔 것 (3시간 넘게 다시 확인 못 했으면 null) */
    fun tileIssue(c: Context, now: Long): String? = tileIssueGraded(c, now)?.first

    fun tileIssueGraded(c: Context, now: Long): Pair<String, Grade>? {
        val p = prefs(c)
        val at = p.getLong(K_TILE_ISSUE_AT, 0L)
        if (at <= 0L || now - at > TILE_ISSUE_MAX_AGE_MS || now < at) return null
        // 그 뒤에 재시작했으면(할 일대로 강제 재시작) 앞의 판단은 바로 내린다 — 재시작 뒤 기록이 1시간쯤 쌓이면 다시 본다.
        // 이전 버전이 적은 줄(부팅을 안 적음)은 켜진 때(벽시계 − 켜진 뒤 시간)보다 앞이면
        val seen = p.getLong(K_TILE_ISSUE_ELAPSED, -1L).takeIf { it >= 0 }?.let {
            HealthStore.Boot(p.getInt(K_TILE_ISSUE_BOOT, -1).takeIf { n -> n >= 0 }, it, p.getLong(K_TILE_ISSUE_WALL, -1L).takeIf { w -> w >= 0 })
        }
        if (seen == null && at < now - android.os.SystemClock.elapsedRealtime()) return null
        if (seen != null && HealthStore.Boot.now(c).rebootedSince(seen)) return null
        val line = p.getString(K_TILE_ISSUE, null) ?: return null
        val grade = runCatching { Grade.valueOf(p.getString(K_TILE_ISSUE_GRADE, null) ?: "CAUTION") }.getOrDefault(Grade.CAUTION)
        return line to grade
    }

    /**
     * 최근 2시간 앱별 순위로 알려진 문제를 찾고 타일 줄을 적어 둔다 (알림은 [checkKnownIssues]).
     * [screen] = 최근 화면 켜짐 구간(사용 통계 — 모르면 null): 그 창 안에서 화면이 켜져 있던 시간으로 One UI Watch 홈 과소모를 가린다
     */
    private fun knownIssues(c: Context, now: Long, screen: List<Span>?, notifications: List<Long>? = null): List<com.mywatch.battery.core.KnownIssue> {
        val boot = HealthStore.Boot.now(c)
        val w = culpritWindow(c, now, boot)
        val face = runCatching { BatteryInsights.watchFace(c) }.getOrNull()
        // 화면 켜짐·알림은 6시간치만 읽었다 — 창이 그보다 앞서면 화면 켜짐을 모른다
        val found = issuesIn(w, face, screen, notifications, now - SCREEN_LOOKBACK_MS, BatteryInsights.appName(c))
        // 타일은 이 결과를 읽는다 (타일이 통계 파일을 읽고 분석하지 않게) — 문제마다 그 할 일로, 비정상 먼저. 어느 부팅에서 본 것인지도
        val top = found.firstOrNull { it.grade == Grade.ABNORMAL } ?: found.firstOrNull()
        prefs(c).edit().putString(K_TILE_ISSUE, top?.let { it.tile ?: it.title }).putString(K_TILE_ISSUE_GRADE, top?.grade?.name)
            .putLong(K_TILE_ISSUE_AT, now).putInt(K_TILE_ISSUE_BOOT, boot.count ?: -1).putLong(K_TILE_ISSUE_ELAPSED, boot.elapsedMs)
            .putLong(K_TILE_ISSUE_WALL, boot.wallMs ?: -1L).apply()
        return found
    }

    /**
     * [w] 의 순위로 알려진 문제를 찾는다 — 화면 켜짐·받은 알림은 그 창의 스냅샷 구간들과 겹친 만큼만 센다 (재시작으로 빠진 때는 빼고).
     * 구간이 [recordedFrom](화면·알림 기록을 읽기 시작한 때)보다 앞서면 화면 켜짐을 모르는 것으로 — One UI Watch 홈은 그땐 짚지 않는다.
     * 15분 기록과 배터리 화면이 같은 판단을 쓴다
     */
    internal fun issuesIn(
        w: CulpritWindow, face: String?, screen: List<Span>?, notifications: List<Long>?, recordedFrom: Long, label: (String) -> String?,
    ): List<com.mywatch.battery.core.KnownIssue> {
        val known = w.spans.isNotEmpty() && w.spans.all { it.first >= recordedFrom }
        val screenOn = screen?.takeIf { known }?.sumOf { s -> w.spans.sumOf { (a, b) -> s.overlap(a, b) } }
        val notes = notifications?.count { t -> w.spans.any { (a, b) -> t >= a && t < b } } ?: 0
        return KnownIssues.detect(w.ranks, face, w.windowMs.takeIf { it > 0 }, screenOn, notes, label)
    }

    private const val K_TILE_ISSUE = "gs_tile_issue"
    private const val K_TILE_ISSUE_GRADE = "gs_tile_issue_grade"
    private const val K_TILE_ISSUE_AT = "gs_tile_issue_at"
    private const val K_TILE_ISSUE_BOOT = "gs_tile_issue_boot"
    private const val K_TILE_ISSUE_ELAPSED = "gs_tile_issue_elapsed"
    private const val K_TILE_ISSUE_WALL = "gs_tile_issue_wall"
    private const val TILE_ISSUE_MAX_AGE_MS = 3L * 3_600_000L

    /** 화면 켜짐 구간으로 본 '지금까지 꺼져 있던 시간' — 지금 켜져 있으면 0, 창 안에 켜진 적이 없으면 창 길이 */
    internal fun screenOffFor(spans: List<Span>, now: Long): Long {
        val last = spans.maxByOrNull { it.end } ?: return SCREEN_LOOKBACK_MS
        return (now - last.end).coerceAtLeast(0L)
    }

    /**
     * 알려진 문제(Play 서비스·One UI Watch 홈이 멈춘 채 돌며 배터리를 먹는 것 등)를 최근 2시간 앱별 사용량에서 찾으면 해결 순서와
     * 그 앱 정보 바로 가기를 알린다 (문제마다 12시간에 한 번 — 한 문제의 알림이 다른 문제를 막지 않게). 권한(BATTERY_STATS)이 있어야
     * 앱별 사용량을 안다
     */
    private fun checkKnownIssues(c: Context, now: Long, zone: ZoneId, screen: List<Span>?, notifications: List<Long>?) {
        val p = prefs(c)
        // 예전(문제 구분 없는) 시각은 업데이트 뒤 12시간까지만 같이 본다 — 따로 따져서, 시계가 바뀌어 미래가 된 예전 시각이 문제별 쉼을 가리지 않게
        val legacy = p.getLong(K_S_ISSUE_AT, 0L)
        fun cooling(at: Long) = at > 0 && now - at in 0 until ISSUE_COOLDOWN_MS
        fun resting(id: String): Boolean = cooling(p.getLong("${K_S_ISSUE_AT}_$id", 0L)) || cooling(legacy)
        val issue = knownIssues(c, now, screen, notifications).firstOrNull { it.grade == Grade.ABNORMAL && !resting(it.id) } ?: return
        val (nm, channel) = BatteryAlerts.manager(c, BatteryAlerts.quiet(now, zone)) ?: return
        val text = "${issue.why}. 해결: " + issue.steps.mapIndexed { i, s -> "${i + 1}) $s" }.joinToString(" ")
        val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${issue.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(issue.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(c))
            .setAutoCancel(true)
            .addAction(0, if (issue.pkg == KnownIssues.GMS) "Play 서비스 앱 정보" else "${HealthStore.label(c, issue.pkg)} 앱 정보",
                PendingIntent.getActivity(c, 4, info, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        nm.notify(N_ISSUE, n)
        p.edit().putLong("${K_S_ISSUE_AT}_${issue.id}", now).apply()
        noteAction(c, "알려진 문제 알림: ${issue.title}")
    }

    /** 게이지가 20포인트 넘게 낮게 세는 것이 확실하고 표시 잔량이 35% 아래면 알린다 (12시간에 한 번) — 표시보다 실제가 많으니 당황할 일이 아니다 */
    private fun checkDrift(c: Context, analysis: Analysis?, now: Long) {
        val d = analysis?.drift ?: return
        val p = prefs(c)
        val last = p.getLong(K_S_DRIFT_NOTIFY_AT, 0L).takeIf { it > 0 }
        if (!GaugeDriftRule.shouldNotify(d, now, last)) return
        // 참고 알림 — 표시보다 실제가 많으니 급하지 않다: 언제나 소리·진동 없이
        val (nm, channel) = BatteryAlerts.manager(c, quiet = true) ?: return
        val text = "${d.headline}. ${d.advice ?: ""}".trim()
        val calibrate = PendingIntent.getBroadcast(
            c, 3, Intent(c, GuardianReceiver::class.java).setAction(ACTION_CAL_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("잔량 표시가 ${-d.driftPoints}포인트 낮습니다")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(c))
            .setAutoCancel(true)
            .addAction(0, "보정 루틴 시작", calibrate)
            .build()
        nm.notify(N_DRIFT, n)
        p.edit().putLong(K_S_DRIFT_NOTIFY_AT, now).putInt(K_S_LAST_DRIFT, d.driftPoints).apply()
        noteAction(c, "잔량 표시 오차 알림 (${d.driftPoints}포인트, 신뢰도 ${d.confidence})")
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
        GuardianJournal(c).add(GuardianJournal.NIGHT_OFF, now)
        saveState(c, st.copy(nightActive = false, saverSeenOn = false))
        noteAction(c, "앱을 열어 밤 모드 끝")
    }

    private fun act(c: Context, levers: Levers, cfg: GuardianConfig, a: GuardianAction, now: Long, saverBroken: Boolean, phantom: Boolean, zone: ZoneId) {
        when (a) {
            GuardianAction.SaverNotWorking -> noteAction(c, "이 워치는 설정으로 절전 모드가 켜지지 않아 절전 레버는 건너뜁니다 (AOD 등은 계속)")
            GuardianAction.NightOn -> {
                GuardianJournal(c).add(GuardianJournal.NIGHT_ON, now)
                val done = levers.applyNight(cfg)
                noteAction(c, "밤 모드 시작 — " + done.joinToString(", ").ifEmpty { "바꿀 수 있는 설정 없음" })
            }
            GuardianAction.NightOff -> {
                GuardianJournal(c).add(GuardianJournal.NIGHT_OFF, now)
                val done = levers.restoreNight()
                noteAction(c, "밤 모드 끝 — " + done.joinToString(", ").ifEmpty { "되돌릴 것 없음" })
            }
            is GuardianAction.AbnormalOn -> {
                val ok = !saverBroken && levers.applyAbnormal()
                // 이 앱 자신은 의심 앱으로 꼽지 않는다 (길 안내 등은 사용자가 쓴 것)
                val culprits = culprits(c, now).filter { it.use.pkg.substringBefore('+') != c.packageName }
                noteAction(c, "비정상 소모(시간당 ${DrainAnalyzer.fmt(a.pph)}%) — 절전 모드 ${if (ok) "켬" else "못 켬"}" +
                    culprits.take(2).joinToString(prefix = " · 의심: ") { HealthStore.label(c, it.use.pkg) })
                if (a.notify) notifyAbnormal(c, a.pph, ok, culprits, phantom, BatteryAlerts.quiet(now, zone))
            }
            GuardianAction.AbnormalOff -> { levers.restoreAbnormal(); noteAction(c, "비정상 대응 끝 — 절전 복구") }
            GuardianAction.UserOverride -> { levers.restoreAll(keepSaver = true); noteAction(c, "사용자가 절전을 꺼서 12시간 쉼") }
            is GuardianAction.ChargeReminder -> notifyCharge(c, a)
        }
    }

    /** 최근 2시간 가장 많이 쓴 앱들 (권한이 없으면 빈 목록) */
    fun culprits(c: Context, now: Long): List<AppDrainRank> = culpritsInWindow(c, now).first

    /** [culprits] 와 그 순위를 모은 시간(ms) */
    fun culpritsInWindow(c: Context, now: Long): Pair<List<AppDrainRank>, Long> = culpritWindow(c, now).let { it.ranks to it.windowMs }

    /** 최근 2시간 앱별 순위 · 그 창의 길이 · 창 끝(마지막 스냅샷 때) · 기록 구간들(스냅샷 사이) */
    data class CulpritWindow(val ranks: List<AppDrainRank>, val windowMs: Long, val endAt: Long, val spans: List<Pair<Long, Long>> = emptyList())

    /** 최근 2시간 앱별 순위 — 이번 부팅의 기록만 (재시작 앞의 사용량이 재시작 뒤 판단에 섞이지 않게) */
    fun culpritWindow(c: Context, now: Long, boot: HealthStore.Boot = HealthStore.Boot.now(c)): CulpritWindow {
        val r = runCatching { HealthStore(c).recentWindow(2, now, boot) }.getOrNull()
        if (r == null || r.uses.isEmpty()) return CulpritWindow(emptyList(), 0L, now)
        val window = r.coveredMs.coerceAtLeast(15 * 60_000L)
        return CulpritWindow(AppDrain.rank(r.uses, window, ownPkg = c.packageName), window, r.endAt.takeIf { it > 0 } ?: now, r.spans)
    }

    internal fun openScreen(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, BatteryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyAbnormal(c: Context, pph: Double, saverOn: Boolean, culprits: List<AppDrainRank>, phantom: Boolean, quiet: Boolean) {
        val (nm, channel) = BatteryAlerts.manager(c, quiet) ?: return
        val names = culprits.take(2).map { HealthStore.label(c, it.use.pkg) }
        val text = buildString {
            append("화면을 거의 안 켰는데 2시간 동안 시간당 ${DrainAnalyzer.fmt(pph)}% 닳았습니다. ")
            append(if (saverOn) "절전 모드를 켰습니다(충전하면 풀림). " else "절전 모드를 켜지 못했습니다 — 직접 켜 주세요. ")
            if (names.isNotEmpty()) append("의심 앱: ${names.joinToString(", ")}. ")
            if (phantom) append("표시 소모의 일부는 잔량 게이지 오차(가짜 소모)로 보입니다 — 배터리 화면 '잔량 표시 점검'을 보세요. ")
            append("계속되면 홈+뒤로 버튼을 꺼질 때까지 눌러 강제 재시작하세요.")
        }
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_warning)
            // 절전을 못 켰으면 '절전 켬'이라 하지 않는다 (본문은 '직접 켜 주세요')
            .setContentTitle("배터리 시간당 ${DrainAnalyzer.fmt(pph)}% — ${if (saverOn) "절전 켬" else "비정상"}")
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
        // 잠자기 전에 일부러 울리는 알림 (사용자가 켠 것) — 밤이어도 울린다
        val (nm, channel) = BatteryAlerts.manager(c, quiet = false) ?: return
        val text = "밤사이 시간당 ${DrainAnalyzer.fmt(a.ratePph)}% 기준으로 아침 7시엔 약 ${a.predictedPercent.toInt().coerceAtLeast(0)}% 가 남습니다. 지금 ${a.minutes}분만 충전하면 아침까지 넉넉합니다."
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("자기 전에 ${a.minutes}분 충전 권장")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(c))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(N_CHARGE, n) }
    }

    /**
     * 알림의 '되돌리기'·화면의 '지금 되돌리기'·PC 툴킷이 권한을 거두기 전: 우리가 바꾼 것을 모두 원래대로, 12시간 쉼.
     * 권한이 없어 되돌리지 못한 것은 직접 되돌릴 일로 돌려준다 (없으면 빈 목록)
     */
    fun restoreNow(c: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val levers = Levers(c)
        levers.restoreAll(keepSaver = false)
        // 쓰기에 실패해 남은 것(권한 없음) — 사용자가 직접 되돌릴 것으로 알리고 내려놓는다
        val left = if (levers.holdsAnything) levers.releaseWithoutPermission() else emptyList()
        val s = state(c)
        if (s.nightActive) GuardianJournal(c).add(GuardianJournal.NIGHT_OFF, now)
        saveState(c, s.copy(nightActive = false, abnormalActive = false, userOverrideUntil = now + GuardianRules.USER_OVERRIDE_MS))
        noteAction(c, "사용자가 되돌림 — 12시간 쉼" + if (left.isEmpty()) "" else " (직접 되돌릴 것: ${left.joinToString(", ")})")
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(N_ABNORMAL) }
        // 알림의 '되돌리기'처럼 화면이 없을 때도 무엇이 남았는지 알게 (기억은 이미 내려놓았으니 다음 기록은 다시 알리지 못한다)
        if (left.isNotEmpty()) runCatching { notifyRelease(c, left, now, zone) }
        return left
    }
}

/** 알림의 '되돌리기'·'보정 루틴 시작' 버튼 */
class GuardianReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Guardian.ACTION_RESTORE -> Guardian.restoreNow(context)
            Guardian.ACTION_CAL_START -> {
                val p = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
                val drift = if (p.contains(Guardian.K_S_LAST_DRIFT)) p.getInt(Guardian.K_S_LAST_DRIFT, 0) else null
                Calibration.start(context, driftPoints = drift)
                runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(Guardian.N_DRIFT) }
                Calibration.notifyStarted(context)
            }
        }
    }
}

/**
 * PC 툴킷이 지킴이 권한(WRITE_SECURE_SETTINGS)을 거두기 전에 부른다 — `am broadcast -n com.mywatch.kakaomap/.battery.ShellRestoreReceiver`.
 * 바꿔 둔 설정(밤 모드의 AOD·손목 올려 켜기·절전 등)을 권한이 있을 때 되돌려 놓게. 셸(adb)만 보낼 수 있게 매니페스트에서
 * android.permission.DUMP 로 막는다(다른 앱은 이 권한을 얻지 못한다). 결과: "restored" 또는 "left:직접 되돌릴 것들"
 */
class ShellRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Guardian.ACTION_SHELL_RESTORE) return
        val left = runCatching { Guardian.restoreNow(context) }.getOrDefault(emptyList())
        if (isOrderedBroadcast) resultData = if (left.isEmpty()) "restored" else "left:" + left.joinToString(",")
    }
}
