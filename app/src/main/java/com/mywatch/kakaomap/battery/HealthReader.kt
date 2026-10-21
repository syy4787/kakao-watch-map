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
            val pkg = groupKey(byUid[uid]!!)
            out[pkg] = uidUse(pkg, h)
        }
        return out
    }

    companion object {
        /** 알려진 문제를 보는 패키지 — 공유 uid 묶음에 있으면 그 이름으로 부른다 */
        private val NAMED = listOf(com.mywatch.battery.core.KnownIssues.GMS, com.mywatch.battery.core.KnownIssues.HOME)

        /**
         * 공유 uid 묶음의 이름: 알려진 문제를 보는 패키지(Play 서비스·One UI Watch 홈)가 있으면 그 이름, 아니면 알파벳 첫 이름. 둘 이상이면
         * '+N'. 'android' 가 낀 시스템 uid(system_server 포함)는 그대로 — 홈이 우연히 시스템 uid 에 있어도 시스템 전체를 홈 탓으로 하지 않게
         */
        fun groupKey(packages: List<String>): String {
            val names = packages.sorted()
            if (names.size == 1) return names[0]
            val base = if ("android" in names) names[0] else NAMED.firstOrNull { it in names } ?: names[0]
            return base + "+" + (names.size - 1)
        }

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
    private val prefs = context.applicationContext.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
    private val last = File(dir, "health_last.json")
    private val deltas = File(dir, "health_deltas.jsonl")

    /** 지금 스냅샷을 기록하고 직전과의 차를 돌려준다 (첫 기록이면 빈 목록 — 누적값은 구간 사용량이 아니다) */
    fun record(now: Long, snapshot: Map<String, UidUse>): List<UidUse> = synchronized(LOCK) {
        dir.mkdirs()
        val prev = readLast()
        val d = if (prev == null) emptyList() else AppDrain.delta(prev.second, snapshot)
        writeLast(now, snapshot)
        prefs.edit().putLong(K_LAST_AT, now).apply()
        if (prev != null && d.isNotEmpty()) {
            val top = d.sortedByDescending { it.wakelockMs + it.cpuMs * 2 + it.sensorMs / 2 + it.gpsMs * 3 }.take(MAX_PER_ROW)
            val row = JSONObject().put("t", now).put("ms", now - prev.first).put("d", JSONArray(top.map { toJson(it) }))
            deltas.appendText(row.toString() + "\n")
            pruneIfBig(now)
        }
        return d
    }

    /** 마지막으로 스냅샷을 남긴 때 (없으면 0) — 파일을 열지 않고 */
    fun lastAt(): Long = prefs.getLong(K_LAST_AT, 0L)

    /** 최근 [hours]시간의 차를 앱별로 합친 것과 실제로 덮인 시간(ms) */
    fun recent(hours: Int, now: Long): Pair<List<UidUse>, Long> = recentWindow(hours, now).let { it.uses to it.coveredMs }

    /** 최근 차의 합 · 덮인 시간 · 마지막 줄의 때 */
    data class Recent(val uses: List<UidUse>, val coveredMs: Long, val endAt: Long)

    /**
     * 최근 [hours]시간의 차 — [after](부팅한 때) 뒤의 줄만: 재시작 앞의 사용량이 재시작 뒤 판단에 섞이지 않게. 부팅을 걸친 줄은 통계가
     * 부팅 때 0 부터라 덮인 시간도 부팅 뒤만 센다
     */
    fun recentWindow(hours: Int, now: Long, after: Long = 0L): Recent = synchronized(LOCK) {
        if (!deltas.exists()) return Recent(emptyList(), 0L, 0L)
        val from = now - hours * 3_600_000L
        val windows = ArrayList<List<UidUse>>()
        var covered = 0L
        var end = 0L
        deltas.forEachLine { line ->
            // 창 밖 줄은 앞의 날짜만 보고 건너뛴다 (24시간치를 15분마다 다 풀지 않게)
            fastT(line)?.let { if (it < from || it > now || it <= after) return@forEachLine }
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = o.optLong("t")
            if (t < from || t > now || t <= after) return@forEachLine
            val ms = o.optLong("ms")
            covered += if (after > 0) minOf(ms, t - after) else ms
            end = maxOf(end, t)
            val arr = o.optJSONArray("d") ?: return@forEachLine
            windows += (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }
        return Recent(AppDrain.sum(windows), covered, end)
    }

    /** 최근 [hours]시간의 15분 차를 그대로 (시간대별 범인을 찾을 때) */
    fun windows(hours: Int, now: Long): List<TimedUse> = synchronized(LOCK) {
        if (!deltas.exists()) return emptyList()
        val from = now - hours * 3_600_000L
        val out = ArrayList<TimedUse>()
        deltas.forEachLine { line ->
            fastT(line)?.let { if (it < from || it > now) return@forEachLine }
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
        // 1시간에 한 줄(소모가 높으면 15분)이라 하루치는 수십 KB — 화면·지킴이가 읽는 파일이 커지지 않게 일찍 자른다
        if (deltas.length() < PRUNE_BYTES) return
        val keepFrom = now - 24 * 3_600_000L
        // 맨 앞 줄이 26시간 안이면 아직 자르지 않는다 — 15분마다 파일을 통째로 다시 쓰지 않게(2시간에 한 번쯤)
        val first = deltas.bufferedReader().use { it.readLine() }
        if (first != null && (fastT(first) ?: Long.MIN_VALUE) >= now - 26 * 3_600_000L) return
        val kept = deltas.readLines().filter { l -> (fastT(l) ?: runCatching { JSONObject(l).optLong("t") }.getOrDefault(0L)) >= keepFrom }
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
        /** 배터리 화면(IO 스레드)과 15분 기록이 같이 쓰므로 인스턴스가 아니라 파일에 하나 */
        private val LOCK = Any()
        private const val K_LAST_AT = "health_last_at"
        private const val PRUNE_BYTES = 120_000L
        private val T_PREFIX = Regex("^\\{\"t\":(-?\\d+)[,}]")

        /** 줄 맨 앞 '{"t":숫자' 의 시각 — 우리가 쓴 줄은 늘 t 가 먼저다. 모양이 다르면 null (그땐 통째로 푼다) */
        internal fun fastT(line: String): Long? = T_PREFIX.find(line)?.groupValues?.get(1)?.toLongOrNull()

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
