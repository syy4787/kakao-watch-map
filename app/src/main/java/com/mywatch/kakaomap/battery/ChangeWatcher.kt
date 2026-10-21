package com.mywatch.kakaomap.battery

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
 * 워치 소프트웨어(빌드)·앱(설치·업데이트·지움)·시계 화면·사용자가 바꾼 설정(AOD·손목 올림·터치로 켜기·'Hey Google'·위치).
 *  · 처음 볼 땐 기억만 하고 '적기 시작' 표시(`on=1`)를 남긴다 — 그 앞의 변화는 '없음'이 아니라 '모름'. 기록은 30일만 남으므로
 *    표시는 2주마다 다시 남긴다.
 *  · 앱: [PackageManager.getChangedPackages] 는 구성 요소를 켜고 끄기만 해도(Play 서비스가 자주 함) 알리므로, 지난번 뒤에
 *    실제로 깔리거나 업데이트된 것(lastUpdateTime)과 지워진 것만. 이 앱 자신은 뺀다(자주 업데이트되고, 소모는 '앱별 소모'에 보인다).
 *    순번은 부팅마다 0부터라 부팅 횟수(Settings.Global.BOOT_COUNT)로 부팅을 가린다.
 *  · 설정은 낮(7~23시)에, 지킴이가 무언가를 쥐고 있거나 절전·취침·극장 모드일 땐 보지 않는다 — 우리·시스템이 잠시 바꾼 것을
 *    사용자 변화로 읽지 않게(그동안 바뀐 것은 다음 낮에 그 전 낮 값과 견준다). 그래도 잠깐 바뀌었다 되돌아간 것은 분석이 뺀다.
 *  · 표본이 실제로 저장됐을 때만 '본 값'을 기억한다 — 저장에 실패하면 다음 번에 다시 알아챈다.
 * 다 합쳐 수 ms (설정 몇 개·바인더 호출 몇 번). 배터리 화면의 '최근 며칠'과 PC 툴킷이 이 표시로 꺾인 날 무렵의 변화를 짚는다.
 */
object ChangeWatcher {
    private const val P_SINCE = "cw_since"
    /** 마지막으로 '적기 시작' 표시를 남긴 때 */
    private const val P_MARK = "cw_mark"
    /** 이미 적은 앱 업데이트 ("패키지@업데이트 시각" 을 ';' 로) — 같은 업데이트를 두 번 적지 않게 */
    private const val P_REPORTED = "cw_rep"
    private const val P_LAST = "cw_last"
    private const val P_FW = "cw_fw"
    private const val P_SEQ = "cw_seq"
    private const val P_BOOT = "cw_boot"
    private const val P_FACE = "cw_face"
    private const val P_SET = "cw_set_"
    /** 한 표본에 적는 앱 수 (나머지는 '앱 N개 더') */
    const val MAX_APPS = 8
    /**
     * '적기 시작' 표시를 다시 남기는 간격 — 기록은 30일만 남으므로 처음 표시가 정리돼도 더 늦은 표시가 남아
     * 그 뒤의 꺾임이 '모름'이 되지 않게 ([ChangeNote.trackedSince] 는 기록에 남은 가장 이른 표시)
     */
    const val MARK_EVERY_MS = 14 * 24 * 3_600_000L
    /** 기억해 둘 적은 업데이트 수 */
    private const val MAX_REPORTED = 40
    /** 부팅 횟수를 못 읽는 기기: 부팅 시각이 이만큼 넘게 다르면 다른 부팅 */
    private const val BOOT_SLACK_MS = 5 * 60_000L
    /** 지난번 뒤에 업데이트됐나를 볼 때의 여유 */
    private const val LOOK_SLACK_MS = 60_000L
    const val DAY_FROM_HOUR = 7
    const val DAY_UNTIL_HOUR = 23
    /** Settings.Global.Wearable.BEDTIME_MODE — 취침 모드는 AOD·손목 올림을 잠시 끈다 */
    private val BEDTIME = Levers.Key(Levers.Table.GLOBAL, "bedtime_mode")
    /** Settings.Global.THEATER_MODE_ON — 극장 모드도 화면 켜기를 잠시 끈다 (삼성 안내: 수면·극장 모드면 손목 올려 켜기가 꺼질 수 있음) */
    private val THEATER = Levers.Key(Levers.Table.GLOBAL, "theater_mode_on")

    val DEFAULT_FIRMWARE: () -> String? = { Build.VERSION.INCREMENTAL?.takeIf { it.isNotBlank() } ?: Build.DISPLAY }
    val DEFAULT_BOOT_TIME: (Long) -> Long = { now -> now - SystemClock.elapsedRealtime() }
    val DEFAULT_BOOT_COUNT: (Context) -> Int? = { c ->
        runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrNull()?.takeIf { it >= 0 }
    }
    val DEFAULT_CHANGED: (Context, Int) -> Pair<Int, List<String>>? = { c, seq ->
        c.packageManager.getChangedPackages(seq)?.let { it.sequenceNumber to it.packageNames }
    }

    /** 시험에선 바꿔 끼운다 */
    @VisibleForTesting @Volatile var firmware: () -> String? = DEFAULT_FIRMWARE
    @VisibleForTesting @Volatile var bootTime: (Long) -> Long = DEFAULT_BOOT_TIME
    @VisibleForTesting @Volatile var bootCount: (Context) -> Int? = DEFAULT_BOOT_COUNT
    @VisibleForTesting @Volatile var changedPackages: (Context, Int) -> Pair<Int, List<String>>? = DEFAULT_CHANGED

    @VisibleForTesting
    fun resetHooks() {
        firmware = DEFAULT_FIRMWARE
        bootTime = DEFAULT_BOOT_TIME
        bootCount = DEFAULT_BOOT_COUNT
        changedPackages = DEFAULT_CHANGED
    }

    /**
     * 지난번 뒤에 바뀐 것을 note 한 줄로 만들어 [save] 에 넘기고(표본에 붙여 저장), [save] 가 true 일 때만 본 값을 기억한다.
     * 기록 작업(15분·부팅)과 시험이 쓴다. 돌려주는 값 = 그 note (없으면 "")
     */
    @Synchronized
    fun record(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), save: (String) -> Boolean): String {
        val c = context.applicationContext
        val prefs = c.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        val items = ArrayList<ChangeNote.Item>()
        fun part(name: String, block: () -> Unit) = runCatching(block).onFailure { ErrorLog.record("배터리·변화", "$name: ${it.javaClass.simpleName}") }
        if (!prefs.contains(P_SINCE)) edit.putLong(P_SINCE, now)
        val mark = prefs.getLong(P_MARK, Long.MIN_VALUE)
        if (mark == Long.MIN_VALUE || now - mark >= MARK_EVERY_MS || now < mark) {
            items += ChangeNote.Item(ChangeKind.TRACKING, "on")
            edit.putLong(P_MARK, now)
        }
        part("소프트웨어") { firmware(prefs, edit)?.let { items += it } }
        part("앱") { items += apps(c, prefs, edit, now) }
        part("시계 화면") { face(c, prefs, edit)?.let { items += it } }
        part("설정") { items += settings(c, prefs, edit, now, zone) }
        edit.putLong(P_LAST, now)
        val note = ChangeNote.encode(items)
        if (runCatching { save(note) }.getOrDefault(false)) edit.commit()
        return note
    }

    /** 지난번 뒤에 바뀐 것 — 저장할 표본이 따로 없을 때(시험) */
    fun collect(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        record(context, now, zone) { true }

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

    /** 이 부팅을 가리는 값: 부팅 횟수(있으면) 또는 어림한 부팅 시각 */
    private fun sameBoot(c: Context, prefs: SharedPreferences, edit: SharedPreferences.Editor, now: Long): Boolean {
        val count = bootCount(c)
        val cur = if (count != null) "n$count" else "t${bootTime(now)}"
        // 예전 빌드가 숫자로 남긴 표시는 모르는 것으로 (읽다 넘어져 앱 변화 기록이 멈추지 않게)
        val old = prefs.all[P_BOOT] as? String
        val same = when {
            old == null -> false
            count != null -> old == cur
            old.startsWith("t") -> old.removePrefix("t").toLongOrNull()?.let { abs(it - bootTime(now)) <= BOOT_SLACK_MS } ?: false
            else -> false
        }
        // 같은 부팅이면 처음 본 값을 지킨다 (어림한 부팅 시각이 조금씩 흔들려도 기준이 밀리지 않게)
        edit.putString(P_BOOT, if (same) old else cur)
        return same
    }

    private fun apps(c: Context, prefs: SharedPreferences, edit: SharedPreferences.Editor, now: Long): List<ChangeNote.Item> {
        val known = prefs.all[P_BOOT] is String
        // 바뀐 앱 순번은 부팅마다 0부터 다시 센다
        val seq = if (sameBoot(c, prefs, edit, now)) prefs.getInt(P_SEQ, 0) else 0
        val res = changedPackages(c, seq)
        edit.putInt(P_SEQ, res?.first ?: seq)
        if (!known || res == null) return emptyList()   // 처음엔 기준만
        val lastLook = prefs.getLong(P_LAST, 0L)
        val pm = c.packageManager
        val reported = prefs.getString(P_REPORTED, null).orEmpty().split(';').filter { it.isNotEmpty() }.toMutableList()
        val entries = res.second.distinct().filter { it != c.packageName }.mapNotNull { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
                ?: return@mapNotNull AppEntry(pkg, "-", system = false)   // 지워짐
            // 구성 요소를 켜고 끈 것뿐(업데이트 시각 그대로)이면 바뀐 것이 아니다
            if (info.lastUpdateTime < lastLook - LOOK_SLACK_MS) return@mapNotNull null
            // 지난번 바로 앞(여유 60초 안)의 업데이트를 이미 적었으면 다시 적지 않는다
            val key = "$pkg@${info.lastUpdateTime}"
            if (key in reported) return@mapNotNull null
            reported += key
            val ver = info.versionName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: info.longVersionCode.toString()
            // 처음 깔린 때 = 마지막 업데이트 때 → 새로 깖
            val fresh = info.firstInstallTime > 0 && abs(info.lastUpdateTime - info.firstInstallTime) < 60_000L
            AppEntry(pkg, if (fresh) "+$ver" else ver, system = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
        }
        // Play 서비스·Samsung Health → 사용자 앱 → 시스템 앱
        edit.putString(P_REPORTED, reported.takeLast(MAX_REPORTED).joinToString(";"))
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
        if (levers.holdsAnything || levers.saverOn() || levers.read(BEDTIME) == "1" || levers.read(THEATER) == "1") return emptyList()
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
