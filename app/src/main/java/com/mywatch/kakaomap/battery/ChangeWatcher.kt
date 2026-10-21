package com.mywatch.kakaomap.battery

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.mywatch.battery.core.ChangeFinder
import com.mywatch.battery.core.ChangeKind
import com.mywatch.battery.core.ChangeNote
import com.mywatch.kakaomap.data.ErrorLog
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * '그 무렵 바뀐 것' 기록 — 15분 표본·부팅 표본을 남길 때 지난번 뒤에 바뀐 것을 표본 note 로 ([ChangeNote]):
 * 워치 소프트웨어(빌드)·앱(설치·업데이트·지움 — [android.content.pm.PackageManager.getChangedPackages])·시계 화면·
 * 사용자가 바꾼 설정(AOD·손목 올림·터치로 켜기·'Hey Google'·위치). 처음 보는 값은 기억만 한다.
 * 설정은 낮(7~23시)에, 지킴이가 무언가를 쥐고 있거나 절전·취침 모드일 땐 보지 않는다 — 우리·시스템이 잠시 바꾼 것을
 * 사용자 변화로 읽지 않게(그동안 바뀐 것은 다음 낮에 그 전 낮 값과 견준다).
 * 다 합쳐 수 ms (설정 몇 개·바인더 호출 두 번). 배터리 화면의 '최근 며칠'과 PC 툴킷이 이 표시로 꺾인 날 무렵의 변화를 짚는다.
 */
object ChangeWatcher {
    private const val P_FW = "cw_fw"
    private const val P_SEQ = "cw_seq"
    private const val P_BOOT = "cw_boot"
    private const val P_FACE = "cw_face"
    private const val P_SET = "cw_set_"
    /** 한 표본에 적는 앱 수 (나머지는 '앱 N개 더') */
    const val MAX_APPS = 8
    /** 부팅 시각이 이만큼 넘게 다르면 다른 부팅 (시계 맞춤 오차는 넘긴다) */
    private const val BOOT_SLACK_MS = 5 * 60_000L
    const val DAY_FROM_HOUR = 7
    const val DAY_UNTIL_HOUR = 23
    /** Settings.Global.Wearable.BEDTIME_MODE — 취침 모드는 AOD·손목 올림을 잠시 끈다 */
    private val BEDTIME = Levers.Key(Levers.Table.GLOBAL, "bedtime_mode")

    val DEFAULT_FIRMWARE: () -> String? = { Build.VERSION.INCREMENTAL?.takeIf { it.isNotBlank() } ?: Build.DISPLAY }
    val DEFAULT_BOOT_TIME: (Long) -> Long = { now -> now - SystemClock.elapsedRealtime() }
    val DEFAULT_CHANGED: (Context, Int) -> Pair<Int, List<String>>? = { c, seq ->
        c.packageManager.getChangedPackages(seq)?.let { it.sequenceNumber to it.packageNames }
    }

    /** 시험에선 바꿔 끼운다 */
    @VisibleForTesting @Volatile var firmware: () -> String? = DEFAULT_FIRMWARE
    @VisibleForTesting @Volatile var bootTime: (Long) -> Long = DEFAULT_BOOT_TIME
    @VisibleForTesting @Volatile var changedPackages: (Context, Int) -> Pair<Int, List<String>>? = DEFAULT_CHANGED

    @VisibleForTesting
    fun resetHooks() {
        firmware = DEFAULT_FIRMWARE
        bootTime = DEFAULT_BOOT_TIME
        changedPackages = DEFAULT_CHANGED
    }

    /** 지난번 뒤에 바뀐 것을 note 한 줄로 (없으면 "") — 기록 작업(15분·부팅)이 표본에 붙인다 */
    @Synchronized
    fun collect(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val c = context.applicationContext
        val prefs = c.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        val items = ArrayList<ChangeNote.Item>()
        fun part(name: String, block: () -> Unit) = runCatching(block).onFailure { ErrorLog.record("배터리·변화", "$name: ${it.javaClass.simpleName}") }
        part("소프트웨어") { firmware(prefs, edit)?.let { items += it } }
        part("앱") { items += apps(c, prefs, edit, now) }
        part("시계 화면") { face(c, prefs, edit)?.let { items += it } }
        part("설정") { items += settings(c, prefs, edit, now, zone) }
        edit.commit()
        return ChangeNote.encode(items)
    }

    /** 지킴이 설정을 바꿨을 때 남길 note ([id] = night_aod 등) */
    fun configNote(id: String, from: Boolean, to: Boolean): String =
        ChangeNote.encode(listOf(ChangeNote.Item(ChangeKind.CONFIG, id, if (from) "1" else "0", if (to) "1" else "0")))

    private fun firmware(prefs: SharedPreferences, edit: SharedPreferences.Editor): ChangeNote.Item? {
        val cur = firmware()?.takeIf { it.isNotBlank() } ?: return null
        val old = prefs.getString(P_FW, null)
        edit.putString(P_FW, cur)
        return if (old != null && old != cur) ChangeNote.Item(ChangeKind.FIRMWARE, "fw", old, cur) else null
    }

    private class AppEntry(val pkg: String, val version: String, val system: Boolean)

    private fun apps(c: Context, prefs: SharedPreferences, edit: SharedPreferences.Editor, now: Long): List<ChangeNote.Item> {
        val boot = bootTime(now)
        val known = prefs.contains(P_BOOT)
        val storedBoot = prefs.getLong(P_BOOT, 0L)
        val sameBoot = known && abs(boot - storedBoot) <= BOOT_SLACK_MS
        // 바뀐 앱 순번은 부팅마다 0부터 다시 센다
        val seq = if (sameBoot) prefs.getInt(P_SEQ, 0) else 0
        val res = changedPackages(c, seq)
        edit.putLong(P_BOOT, if (sameBoot) storedBoot else boot)
        edit.putInt(P_SEQ, res?.first ?: seq)
        if (!known || res == null) return emptyList()   // 처음엔 기준만
        val pm = c.packageManager
        val entries = res.second.distinct().map { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            if (info == null) AppEntry(pkg, "-", system = false)
            else {
                val ver = info.versionName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
                    ?: info.longVersionCode.toString()
                // 처음 깔린 때 = 마지막 업데이트 때 → 새로 깖
                val fresh = info.firstInstallTime > 0 && abs(info.lastUpdateTime - info.firstInstallTime) < 60_000L
                AppEntry(pkg, if (fresh) "+$ver" else ver, system = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
        }
        // Play 서비스·Samsung Health → 사용자 앱 → 시스템 앱
        val sorted = entries.sortedBy { e -> if (e.pkg == ChangeFinder.GMS || e.pkg == ChangeFinder.SHEALTH) 0 else if (!e.system) 1 else 2 }
        val shown = sorted.take(MAX_APPS).map { ChangeNote.Item(ChangeKind.APP, it.pkg, to = it.version) }
        val more = sorted.size - shown.size
        return if (more > 0) shown + ChangeNote.Item(ChangeKind.MORE_APPS, "apps", to = "$more") else shown
    }

    private fun face(c: Context, prefs: SharedPreferences, edit: SharedPreferences.Editor): ChangeNote.Item? {
        val cur = runCatching { BatteryInsights.watchFace(c) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val old = prefs.getString(P_FACE, null)
        edit.putString(P_FACE, cur)
        return if (old != null && old != cur) ChangeNote.Item(ChangeKind.FACE, "face", old, cur) else null
    }

    private fun settings(c: Context, prefs: SharedPreferences, edit: SharedPreferences.Editor, now: Long, zone: ZoneId): List<ChangeNote.Item> {
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        if (hour < DAY_FROM_HOUR || hour >= DAY_UNTIL_HOUR) return emptyList()
        val levers = Levers(c)
        if (levers.holdsAnything || levers.saverOn() || levers.read(BEDTIME) == "1") return emptyList()
        val keys = listOf(
            "aod" to levers.aodKey, "tilt" to levers.tiltKey, "touch" to levers.touchKey, "hotword" to levers.hotwordKey,
            "location" to levers.locationKey.takeIf { levers.hasLocationKey },
        )
        val out = ArrayList<ChangeNote.Item>()
        for ((id, key) in keys) {
            val raw = key?.let { levers.read(it) } ?: continue
            // 켬/끔만 본다 (위치 모드 3 = 켬)
            val v = if (raw.trim() == "0") "0" else "1"
            val old = prefs.getString(P_SET + id, null)
            if (old != null && old != v) out += ChangeNote.Item(ChangeKind.SETTING, id, old, v)
            edit.putString(P_SET + id, v)
        }
        return out
    }
}
