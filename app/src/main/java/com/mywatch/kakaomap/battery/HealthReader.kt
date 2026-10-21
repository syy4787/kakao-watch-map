package com.mywatch.kakaomap.battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
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
            val base = if ("android" in names) "android" else NAMED.firstOrNull { it in names } ?: names[0]
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

    /**
     * 부팅 하나 — [count] = 부팅 번호(Settings.Global.BOOT_COUNT, 부팅마다 1씩 — 모르면 null), [elapsedMs] = 켜진 뒤 흐른 시간,
     * [wallMs] = 그때의 벽시계(모르면 null). 배터리 통계는 부팅 때 0 이 되지 않고 충전 뒤부터 이어지므로(안드로이드 10+) 재시작은
     * 통계가 아니라 이것으로 알아챈다
     */
    data class Boot(val count: Int?, val elapsedMs: Long, val wallMs: Long? = null) {
        /**
         * [before] 를 남긴 뒤 다시 켜졌다: 부팅 번호가 바뀌었거나, 켜진 뒤 흐른 시간이 줄었거나(같은 부팅의 두 스냅샷이 엇갈려 적힌
         * 몇 초는 봐준다), 부팅 번호를 모르면 켜진 때(벽시계 − 켜진 뒤 시간)가 옮겨졌다 — 재시작 뒤 첫 스냅샷의 켜진 시간이 재시작 전보다
         * 길어도 알아채게. 부팅 번호를 못 읽는 워치에서 시계를 5분 넘게 바꾸면 재시작으로 보여 기준값을 새로 잡고 그 앞의 최근 기록(최대
         * 2시간)을 판단에서 뺀다 — 드물고, 1시간쯤 뒤 다시 본다
         */
        fun rebootedSince(before: Boot): Boolean {
            val counted = count != null && before.count != null
            if (counted && count != before.count) return true
            if (elapsedMs + SKEW_MS < before.elapsedMs) return true
            if (!counted && wallMs != null && before.wallMs != null &&
                kotlin.math.abs((wallMs - elapsedMs) - (before.wallMs - before.elapsedMs)) > BOOT_SHIFT_MS) return true
            return false
        }

        companion object {
            /** 같은 부팅에서 두 스냅샷이 엇갈려 적힐 때 켜진 시간이 줄어 보일 수 있는 폭 */
            const val SKEW_MS = 60_000L
            /** 켜진 때가 이만큼 넘게 옮겨졌으면 재시작 (벽시계 맞춤은 보통 몇 초) */
            const val BOOT_SHIFT_MS = 5 * 60_000L

            fun now(c: Context): Boot = Boot(
                runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1).takeIf { it >= 0 },
                SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            )
        }
    }

    /**
     * 지금 스냅샷을 기록하고 직전과의 차를 돌려준다. 빈 목록(기준값만)인 때: 첫 기록 · [boot] 로 본 재시작 뒤 첫 스냅샷 · 직전 스냅샷이
     * 부팅을 안 적은 예전 형식(업데이트 직후 — 그 사이 재시작했는지 모른다) · 시계가 뒤로 감. 재시작을 걸친 구간은 재시작 앞뒤 사용이
     * 섞이고, 강제 재시작은 저장 못 한 통계를 잃어 누적값이 줄기도 한다 — 그 줄어듦을 '충전 뒤 통계가 비워짐'으로 보면 충전 뒤 전부가
     * 한 구간 사용으로 잡혀 엉뚱한 과소모로 짚었다 (리뷰에서 찾음)
     */
    fun record(now: Long, snapshot: Map<String, UidUse>, boot: Boot? = null): List<UidUse> = synchronized(LOCK) {
        dir.mkdirs()
        val prev = readLast()
        val rebooted = boot != null && prev != null && (prev.boot == null || boot.rebootedSince(prev.boot))
        val seq = (prev?.seq ?: 0) + if (rebooted) 1 else 0
        val d = if (prev == null || rebooted || now <= prev.t) emptyList() else AppDrain.delta(prev.uses, snapshot)
        writeLast(now, snapshot, boot, seq)
        // 15분 기록이 재시작을 파일을 열지 않고 알아채게 — 그때 바로 기준값을 남긴다 ([rebootedSinceLast])
        prefs.edit().putLong(K_LAST_AT, now).putInt(K_LAST_BOOT_COUNT, boot?.count ?: -1).putLong(K_LAST_BOOT_ELAPSED, boot?.elapsedMs ?: -1L)
            .putLong(K_LAST_BOOT_WALL, boot?.wallMs ?: -1L).putInt(K_LAST_BOOT_SEQ, seq).apply()
        if (prev != null && d.isNotEmpty()) {
            val top = d.sortedByDescending { it.wakelockMs + it.cpuMs * 2 + it.sensorMs / 2 + it.gpsMs * 3 }.take(MAX_PER_ROW)
            val row = JSONObject().put("t", now).put("ms", now - prev.t).put("b", seq).put("d", JSONArray(top.map { toJson(it) }))
            deltas.appendText(row.toString() + "\n")
            pruneIfBig(now)
        }
        return d
    }

    /** 마지막으로 스냅샷을 남긴 때 (없으면 0) — 파일을 열지 않고 */
    fun lastAt(): Long = prefs.getLong(K_LAST_AT, 0L)

    /** 지금 부팅 차례 (재시작을 알아챌 때마다 1씩 — 줄의 "b") — 파일을 열지 않고 */
    fun bootSeq(): Int = prefs.getInt(K_LAST_BOOT_SEQ, 0)

    /**
     * 마지막 스냅샷 뒤에 재시작했나 (파일을 열지 않고). 스냅샷이 아직 없으면 false, 그 스냅샷이 부팅을 안 적었으면(예전 버전) true —
     * 어느 쪽이든 다음 스냅샷은 기준값이니 바로 남기는 게 낫다
     */
    fun rebootedSinceLast(boot: Boot): Boolean {
        if (lastAt() <= 0L) return false
        val elapsed = prefs.getLong(K_LAST_BOOT_ELAPSED, -1L).takeIf { it >= 0 } ?: return true
        val last = Boot(prefs.getInt(K_LAST_BOOT_COUNT, -1).takeIf { it >= 0 }, elapsed, prefs.getLong(K_LAST_BOOT_WALL, -1L).takeIf { it >= 0 })
        return boot.rebootedSince(last)
    }

    /** 최근 [hours]시간의 차를 앱별로 합친 것과 실제로 덮인 시간(ms) */
    fun recent(hours: Int, now: Long): Pair<List<UidUse>, Long> = recentWindow(hours, now).let { it.uses to it.coveredMs }

    /** 최근 차의 합 · 덮인 시간 · 마지막 줄의 때 · 줄마다 덮은 때(스냅샷 사이 — 화면 켜짐·알림을 같은 때로 세려고) */
    data class Recent(val uses: List<UidUse>, val coveredMs: Long, val endAt: Long, val spans: List<Pair<Long, Long>> = emptyList())

    /**
     * 최근 [hours]시간의 차. [boot](지금 부팅)를 주면 이번 부팅의 줄만 — 재시작 앞의 사용량이 재시작 뒤 판단에 섞이지 않게
     * (마지막 스냅샷 뒤에 또 재시작했으면 아직 이번 부팅의 줄이 없다). 주지 않으면 부팅을 따지지 않는다
     */
    fun recentWindow(hours: Int, now: Long, boot: Boot? = null): Recent = synchronized(LOCK) {
        if (!deltas.exists()) return EMPTY
        val seq: Int? = if (boot == null) null else {
            val last = readLast() ?: return EMPTY
            if (last.boot != null && boot.rebootedSince(last.boot)) return EMPTY
            last.seq
        }
        val from = now - hours * 3_600_000L
        val windows = ArrayList<List<UidUse>>()
        val spans = ArrayList<Pair<Long, Long>>()
        var covered = 0L
        var end = 0L
        deltas.forEachLine { line ->
            // 창 밖 줄은 앞의 날짜만 보고 건너뛴다 (24시간치를 15분마다 다 풀지 않게)
            fastT(line)?.let { if (it < from || it > now) return@forEachLine }
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = o.optLong("t")
            if (t < from || t > now) return@forEachLine
            // 부팅 차례가 없는 줄(이전 버전이 쓴 것)은 어느 부팅인지 모른다 — 재시작을 걸친 엉뚱한 줄일 수 있어 늘 빼고 본다
            if (!o.has("b") || (seq != null && o.optInt("b") != seq)) return@forEachLine
            val ms = o.optLong("ms")
            covered += ms
            spans += (t - ms) to t
            end = maxOf(end, t)
            val arr = o.optJSONArray("d") ?: return@forEachLine
            windows += (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }
        return Recent(AppDrain.sum(windows), covered, end, spans)
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
            // 이전 버전이 쓴 줄(부팅 차례 없음)은 재시작을 걸친 엉뚱한 줄일 수 있다 — 빼고 본다 (하루 지나면 잘려 나감)
            if (!o.has("b")) return@forEachLine
            val arr = o.optJSONArray("d") ?: return@forEachLine
            out += TimedUse(t, o.optLong("ms"), (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) })
        }
        return out
    }

    /** 마지막 스냅샷 — 때 · 앱별 누적값 · 그때의 부팅(모르면 null) · 우리가 센 부팅 차례(줄의 "b") */
    private class Last(val t: Long, val uses: Map<String, UidUse>, val boot: Boot?, val seq: Int)

    private fun readLast(): Last? {
        if (!last.exists()) return null
        return runCatching {
            val o = JSONObject(last.readText())
            val arr = o.getJSONArray("u")
            val boot = if (o.has("el")) Boot(if (o.has("bc")) o.getInt("bc") else null, o.getLong("el"), if (o.has("wl")) o.getLong("wl") else null) else null
            Last(o.getLong("t"), (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }.associateBy { it.pkg }, boot, o.optInt("bs", 0))
        }.getOrNull()
    }

    private fun writeLast(now: Long, snapshot: Map<String, UidUse>, boot: Boot?, seq: Int) {
        val tmp = File(dir, "health_last.json.tmp")
        val o = JSONObject().put("t", now).put("bs", seq)
        if (boot != null) { o.put("el", boot.elapsedMs); boot.count?.let { o.put("bc", it) }; boot.wallMs?.let { o.put("wl", it) } }
        tmp.writeText(o.put("u", JSONArray(snapshot.values.map { toJson(it) })).toString())
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
        private val EMPTY = Recent(emptyList(), 0L, 0L)
        /** 배터리 화면(IO 스레드)과 15분 기록이 같이 쓰므로 인스턴스가 아니라 파일에 하나 */
        private val LOCK = Any()
        private const val K_LAST_AT = "health_last_at"
        private const val K_LAST_BOOT_COUNT = "health_last_boot"
        private const val K_LAST_BOOT_ELAPSED = "health_last_elapsed"
        private const val K_LAST_BOOT_WALL = "health_last_wall"
        private const val K_LAST_BOOT_SEQ = "health_last_seq"
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
