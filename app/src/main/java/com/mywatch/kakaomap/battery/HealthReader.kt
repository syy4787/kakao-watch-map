package com.mywatch.kakaomap.battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import com.mywatch.battery.core.AppDrain
import com.mywatch.battery.core.TimedUse
import com.mywatch.battery.core.UidUse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 앱(uid)별 누적 사용량을 주는 곳 — 실제는 시스템 건강 통계, 시험에선 가짜 */
fun interface UidStatsSource {
    fun take(): Map<String, UidUse>
}

/**
 * 시스템 건강 통계(HealthStats)로 모든 앱의 웨이크락·CPU·센서·GPS·동기화 누적값을 읽는다.
 * 다른 앱의 통계를 읽으려면 BATTERY_STATS 권한이 필요 — PC 툴킷의 '권한 주기'(`pm grant`)로 한 번 받는다.
 */
class HealthReader(context: Context) : UidStatsSource {
    private val app = context.applicationContext

    val available: Boolean
        get() = app.checkSelfPermission(Manifest.permission.BATTERY_STATS) == PackageManager.PERMISSION_GRANTED

    override fun take(): Map<String, UidUse> {
        val pm = app.packageManager
        val byUid = pm.getInstalledApplications(0).groupBy({ it.uid }, { it.packageName })
        val uids = byUid.keys.toIntArray()
        val shm = app.getSystemService(SystemHealthManager::class.java) ?: return emptyMap()
        val stats = shm.takeUidSnapshots(uids)
        val out = LinkedHashMap<String, UidUse>()
        for ((i, uid) in uids.withIndex()) {
            val h = stats.getOrNull(i) ?: continue
            val names = byUid[uid]!!.sorted()
            val pkg = if (names.size == 1) names[0] else names[0] + "+" + (names.size - 1)
            out[pkg] = uidUse(pkg, h)
        }
        return out
    }

    companion object {
        fun uidUse(pkg: String, h: HealthStats): UidUse {
            fun timerSum(key: Int): Long = if (h.hasTimers(key)) h.getTimers(key).values.sumOf { it.time } else 0L
            fun timerCount(key: Int): Int = if (h.hasTimers(key)) h.getTimers(key).values.sumOf { it.count } else 0
            fun timer(key: Int): Long = if (h.hasTimer(key)) h.getTimerTime(key) else 0L
            fun meas(key: Int): Long = if (h.hasMeasurement(key)) h.getMeasurement(key) else 0L
            return UidUse(
                pkg = pkg,
                wakelockMs = timerSum(UidHealthStats.TIMERS_WAKELOCKS_PARTIAL),
                cpuMs = meas(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS) + meas(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
                sensorMs = timerSum(UidHealthStats.TIMERS_SENSORS),
                gpsMs = timer(UidHealthStats.TIMER_GPS_SENSOR),
                syncs = timerCount(UidHealthStats.TIMERS_SYNCS),
                jobs = timerCount(UidHealthStats.TIMERS_JOBS),
                fgMs = timer(UidHealthStats.TIMER_PROCESS_STATE_TOP_MS) + timer(UidHealthStats.TIMER_PROCESS_STATE_FOREGROUND_MS),
                bgMs = timer(UidHealthStats.TIMER_PROCESS_STATE_BACKGROUND_MS),
                radioMs = timer(UidHealthStats.TIMER_MOBILE_RADIO_ACTIVE),
            )
        }
    }
}

/**
 * 누적값 스냅샷을 파일에 두고, 15분마다 차(이번 구간 사용량)를 `health_deltas.jsonl` 에 쌓는다(24시간치).
 * 화면의 '앱별 소모 (최근 2시간)'과 비정상 알림의 범인 이름이 여기서 나온다.
 */
class HealthStore(context: Context) {
    private val dir = BatteryLog(context).dir
    private val last = File(dir, "health_last.json")
    private val deltas = File(dir, "health_deltas.jsonl")

    /** 지금 스냅샷을 기록하고 직전과의 차를 돌려준다 (첫 기록이면 빈 목록 — 누적값은 구간 사용량이 아니다) */
    @Synchronized
    fun record(now: Long, snapshot: Map<String, UidUse>): List<UidUse> {
        dir.mkdirs()
        val prev = readLast()
        val d = if (prev == null) emptyList() else AppDrain.delta(prev.second, snapshot)
        writeLast(now, snapshot)
        if (prev != null && d.isNotEmpty()) {
            val top = d.sortedByDescending { it.wakelockMs + it.cpuMs * 2 + it.sensorMs / 2 + it.gpsMs * 3 }.take(MAX_PER_ROW)
            val row = JSONObject().put("t", now).put("ms", now - prev.first).put("d", JSONArray(top.map { toJson(it) }))
            deltas.appendText(row.toString() + "\n")
            pruneIfBig(now)
        }
        return d
    }

    /** 최근 [hours]시간의 차를 앱별로 합친 것과 실제로 덮인 시간(ms) */
    @Synchronized
    fun recent(hours: Int, now: Long): Pair<List<UidUse>, Long> {
        if (!deltas.exists()) return emptyList<UidUse>() to 0L
        val from = now - hours * 3_600_000L
        val windows = ArrayList<List<UidUse>>()
        var covered = 0L
        deltas.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = o.optLong("t")
            if (t < from || t > now) return@forEachLine
            covered += o.optLong("ms")
            val arr = o.optJSONArray("d") ?: return@forEachLine
            windows += (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }
        return AppDrain.sum(windows) to covered
    }

    /** 최근 [hours]시간의 15분 차를 그대로 (시간대별 범인을 찾을 때) */
    @Synchronized
    fun windows(hours: Int, now: Long): List<TimedUse> {
        if (!deltas.exists()) return emptyList()
        val from = now - hours * 3_600_000L
        val out = ArrayList<TimedUse>()
        deltas.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = o.optLong("t")
            if (t < from || t > now) return@forEachLine
            val arr = o.optJSONArray("d") ?: return@forEachLine
            out += TimedUse(t, o.optLong("ms"), (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) })
        }
        return out
    }

    private fun readLast(): Pair<Long, Map<String, UidUse>>? {
        if (!last.exists()) return null
        return runCatching {
            val o = JSONObject(last.readText())
            val t = o.getLong("t")
            val arr = o.getJSONArray("u")
            t to (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }.associateBy { it.pkg }
        }.getOrNull()
    }

    private fun writeLast(now: Long, snapshot: Map<String, UidUse>) {
        val tmp = File(dir, "health_last.json.tmp")
        tmp.writeText(JSONObject().put("t", now).put("u", JSONArray(snapshot.values.map { toJson(it) })).toString())
        if (!tmp.renameTo(last)) { last.writeText(tmp.readText()); tmp.delete() }
    }

    private fun pruneIfBig(now: Long) {
        if (deltas.length() < 400_000L) return
        val keepFrom = now - 24 * 3_600_000L
        val kept = deltas.readLines().filter { l -> runCatching { JSONObject(l).optLong("t") >= keepFrom }.getOrDefault(false) }
        deltas.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    private fun toJson(u: UidUse) = JSONObject().put("p", u.pkg).put("wl", u.wakelockMs).put("cpu", u.cpuMs).put("sen", u.sensorMs)
        .put("gps", u.gpsMs).put("sy", u.syncs).put("jb", u.jobs).put("fg", u.fgMs).put("bg", u.bgMs).put("rd", u.radioMs)

    private fun fromJson(o: JSONObject) = UidUse(
        o.optString("p"), o.optLong("wl"), o.optLong("cpu"), o.optLong("sen"), o.optLong("gps"),
        o.optInt("sy"), o.optInt("jb"), o.optLong("fg"), o.optLong("bg"), o.optLong("rd"),
    )

    companion object {
        const val MAX_PER_ROW = 25

        /** 패키지 이름 → 화면에 보일 이름 (공유 uid 는 첫 패키지 기준) */
        fun label(context: Context, pkg: String): String {
            val first = pkg.substringBefore('+')
            val pm = context.packageManager
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(first, 0)).toString() }.getOrNull()
            val extra = pkg.substringAfter('+', "").takeIf { it.isNotEmpty() }?.let { " 외 $it" } ?: ""
            return (name ?: first) + extra
        }
    }
}
