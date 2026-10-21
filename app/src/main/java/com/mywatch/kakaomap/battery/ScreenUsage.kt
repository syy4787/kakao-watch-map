package com.mywatch.kakaomap.battery

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.mywatch.battery.core.AppSpan
import com.mywatch.battery.core.ScreenCover
import com.mywatch.battery.core.Span

/**
 * 화면이 켜져 있던 구간과 화면에 떠 있던 앱 — 안드로이드 사용 통계(UsageStatsManager) 의 이벤트로.
 * 사용 통계는 PC 툴킷의 '권한 주기'(appops GET_USAGE_STATS)로 한 번 허용받아야 읽을 수 있다. 워치 밖으로 보내지 않는다.
 */
class ScreenUsage(context: Context) {
    private val app = context.applicationContext

    val available: Boolean
        get() {
            val ops = app.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = runCatching { ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) }
                .getOrDefault(AppOpsManager.MODE_ERRORED)
            return mode == AppOpsManager.MODE_ALLOWED ||
                (mode == AppOpsManager.MODE_DEFAULT && app.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
        }

    /** [from, to) 사이 화면 켜짐 구간과 앱 구간 — 권한이 없거나 못 읽으면 null */
    fun read(from: Long, to: Long): Pair<List<Span>, List<AppSpan>>? = readAll(from, to)?.let { it.screen to it.apps }

    companion object {
        /**
         * 화면을 켤 수 있는(소리·진동·화면) 알림이 왔다 — UsageEvents.Event.NOTIFICATION_INTERRUPTION(12). SDK 에는 숨겨진 상수지만
         * 사용 통계 권한이 있는 앱에도 (알림 채널 이름만 가린 채) 그대로 온다
         */
        const val NOTIFICATION_INTERRUPTION = 12
    }

    /** 화면 켜짐 구간 · 화면에 떠 있던 앱 구간 · 화면을 켤 수 있는 알림(앱, 때) */
    data class Usage(val screen: List<Span>, val apps: List<AppSpan>, val notifications: List<Pair<String, Long>>)

    /**
     * 며칠 단위 추세용 화면 켜짐 구간 — 화면 이벤트만 모은다. 시스템은 이벤트를 며칠만 보관하므로, 가장 이른 이벤트(종류 무관)
     * 때부터 믿는다([ScreenCover.from]). 이벤트가 하나도 없으면 null
     */
    fun readScreen(from: Long, to: Long): ScreenCover? {
        if (!available || to <= from) return null
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        val screen = ArrayList<Span>()
        var first: Long? = null
        var screenStart: Long? = null
        var sawScreenEvent = false
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            val t = e.timeStamp.coerceIn(from, to)
            if (first == null) first = t
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (screenStart == null) screenStart = t
                    sawScreenEvent = true
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // 기록이 시작될 때 이미 켜져 있었으면 기록 처음부터 (창 처음이 아니라 — 그 앞은 기록이 없다)
                    val s = screenStart ?: if (!sawScreenEvent) first else null
                    if (s != null && t > s) screen += Span(s, t)
                    screenStart = null
                    sawScreenEvent = true
                }
            }
        }
        screenStart?.let { if (to > it) screen += Span(it, to) }
        return first?.let { ScreenCover(screen, it) }
    }

    fun readAll(from: Long, to: Long): Usage? {
        if (!available || to <= from) return null
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        val screen = ArrayList<Span>()
        var screenStart: Long? = null
        var sawScreenEvent = false
        val apps = ArrayList<AppSpan>()
        val notes = ArrayList<Pair<String, Long>>()
        val open = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            val t = e.timeStamp.coerceIn(from, to)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (screenStart == null) screenStart = t
                    sawScreenEvent = true
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // 창이 시작될 때 이미 켜져 있었으면(첫 화면 이벤트가 '꺼짐') 창 처음부터 켜져 있던 것
                    val s = screenStart ?: if (!sawScreenEvent) from else null
                    if (s != null && t > s) screen += Span(s, t)
                    screenStart = null
                    sawScreenEvent = true
                }
                NOTIFICATION_INTERRUPTION -> notes += e.packageName to t
                UsageEvents.Event.ACTIVITY_RESUMED -> open.putIfAbsent(e.packageName, t)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(e.packageName)?.let { s -> if (t > s) apps += AppSpan(e.packageName, s, t) }
            }
        }
        screenStart?.let { if (to > it) screen += Span(it, to) }
        open.forEach { (p, s) -> if (to > s) apps += AppSpan(p, s, to) }
        return Usage(screen, apps.sortedBy { it.start }, notes)
    }
}
