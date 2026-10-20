package com.mywatch.battery.core

/**
 * 앱(uid) 하나가 쓴 자원의 누적값 — 시스템 건강 통계(HealthStats)에서 읽은 그대로.
 * 둘 사이의 차([AppDrain.delta])가 한 구간 동안 그 앱이 쓴 양이다.
 */
data class UidUse(
    val pkg: String,
    /** partial wakelock 을 쥔 시간 합(ms) — 화면 꺼진 동안 CPU 를 깨워 둔 시간 */
    val wakelockMs: Long = 0,
    /** 사용자+시스템 CPU 시간(ms) */
    val cpuMs: Long = 0,
    /** 센서(심박·가속도 등) 등록 시간 합(ms) */
    val sensorMs: Long = 0,
    val gpsMs: Long = 0,
    val syncs: Int = 0,
    val jobs: Int = 0,
    /** 화면에 보이던(top/foreground) 시간 */
    val fgMs: Long = 0,
    val bgMs: Long = 0,
    /** 모바일 무선이 켜진 시간 */
    val radioMs: Long = 0,
) {
    operator fun minus(o: UidUse) = UidUse(
        pkg, c(wakelockMs - o.wakelockMs, wakelockMs), c(cpuMs - o.cpuMs, cpuMs), c(sensorMs - o.sensorMs, sensorMs), c(gpsMs - o.gpsMs, gpsMs),
        c(syncs - o.syncs, syncs), c(jobs - o.jobs, jobs), c(fgMs - o.fgMs, fgMs), c(bgMs - o.bgMs, bgMs), c(radioMs - o.radioMs, radioMs),
    )

    operator fun plus(o: UidUse) = UidUse(
        pkg, wakelockMs + o.wakelockMs, cpuMs + o.cpuMs, sensorMs + o.sensorMs, gpsMs + o.gpsMs,
        syncs + o.syncs, jobs + o.jobs, fgMs + o.fgMs, bgMs + o.bgMs, radioMs + o.radioMs,
    )

    val isEmpty: Boolean get() = wakelockMs == 0L && cpuMs == 0L && sensorMs == 0L && gpsMs == 0L && syncs == 0 && jobs == 0 && fgMs == 0L && bgMs == 0L && radioMs == 0L

    private companion object {
        /** 누적값이 줄었으면(충전 뒤 통계가 비워짐) 지금 값이 곧 이번 구간의 사용량 */
        fun c(d: Long, now: Long): Long = if (d < 0) now else d
        fun c(d: Int, now: Int): Int = if (d < 0) now else d
    }
}

/** 한 구간에서 가장 많이 쓴 앱 순위 한 줄 */
data class AppDrainRank(val use: UidUse, val score: Double, val flags: List<String>, val grade: Grade) {
    /** "웨이크락 12분 · CPU 3분 · 센서 40분 · GPS 5분 · 동기화 3회" */
    val summary: String
        get() = listOfNotNull(
            use.wakelockMs.takeIf { it >= 1000 }?.let { "웨이크락 ${mins(it)}" },
            use.cpuMs.takeIf { it >= 1000 }?.let { "CPU ${mins(it)}" },
            use.sensorMs.takeIf { it >= 1000 }?.let { "센서 ${mins(it)}" },
            use.gpsMs.takeIf { it >= 1000 }?.let { "GPS ${mins(it)}" },
            use.syncs.takeIf { it > 0 }?.let { "동기화 ${it}회" },
            use.jobs.takeIf { it > 0 }?.let { "작업 ${it}회" },
            use.fgMs.takeIf { it >= 1000 }?.let { "화면 ${mins(it)}" },
        ).joinToString(" · ").ifEmpty { "거의 안 씀" }

    private fun mins(ms: Long): String = if (ms < 60_000) "${ms / 1000}초" else "${ms / 60_000}분"
}

object AppDrain {
    /** Android vitals: 화면 꺼진 세션에서 partial wakelock 시간당 18.5초 넘으면 과소모 기여 */
    const val WAKELOCK_CAUTION_MS_PER_H = 18_500L
    /** 구간의 절반 넘게 웨이크락을 쥐면 '멈춘(stuck) 웨이크락'으로 본다 */
    const val STUCK_RATIO = 0.5
    const val GPS_CAUTION_MS_PER_H = 10L * 60_000L
    const val SENSOR_BG_CAUTION_MS_PER_H = 30L * 60_000L

    /** 이전 스냅샷과 지금 스냅샷의 차. 이전에 없던 앱은 지금 값 전체가 이번 구간 사용량 */
    fun delta(prev: Map<String, UidUse>, now: Map<String, UidUse>): List<UidUse> =
        now.values.map { n -> prev[n.pkg]?.let { n - it } ?: n }.filter { !it.isEmpty }

    /** 여러 구간의 차를 앱별로 합친다 */
    fun sum(deltas: List<List<UidUse>>): List<UidUse> =
        deltas.flatten().groupBy { it.pkg }.map { (_, uses) -> uses.reduce { a, b -> a + b } }

    /**
     * 점수 = 웨이크락 + CPU×2 + 센서×0.5 + GPS×3 (ms) + 동기화 2초·작업 1초씩. 화면에 보이던 시간은 사용자가 쓴 것이라 점수에 넣지 않는다.
     * [windowMs] 는 이 사용량을 모은 시간 — 시간당 기준(Android vitals)으로 환산해 표시를 붙인다
     */
    fun rank(deltas: List<UidUse>, windowMs: Long, ownPkg: String? = null): List<AppDrainRank> {
        val hours = (windowMs / 3_600_000.0).coerceAtLeast(0.25)
        return deltas.map { u ->
            val score = u.wakelockMs + u.cpuMs * 2.0 + u.sensorMs * 0.5 + u.gpsMs * 3.0 + u.syncs * 2_000.0 + u.jobs * 1_000.0
            val flags = ArrayList<String>()
            var grade = Grade.NORMAL
            val wlPerH = u.wakelockMs / hours
            if (u.wakelockMs >= windowMs * STUCK_RATIO) { flags += "웨이크락을 ${(u.wakelockMs * 100 / windowMs.coerceAtLeast(1))}% 시간 동안 쥐고 있음(stuck)"; grade = Grade.ABNORMAL }
            else if (wlPerH > WAKELOCK_CAUTION_MS_PER_H) { flags += "웨이크락 시간당 ${(wlPerH / 1000).toInt()}초 (기준 18.5초)"; grade = Grade.CAUTION }
            if (u.gpsMs / hours > GPS_CAUTION_MS_PER_H) { flags += "GPS 시간당 ${(u.gpsMs / hours / 60_000).toInt()}분"; if (grade == Grade.NORMAL) grade = Grade.CAUTION }
            if (u.sensorMs / hours > SENSOR_BG_CAUTION_MS_PER_H && u.fgMs < u.sensorMs / 4) { flags += "화면 밖에서 센서 시간당 ${(u.sensorMs / hours / 60_000).toInt()}분"; if (grade == Grade.NORMAL) grade = Grade.CAUTION }
            if (ownPkg != null && u.pkg == ownPkg) flags += "이 앱"
            AppDrainRank(u, score, flags, grade)
        }.filter { it.score > 0 }.sortedByDescending { it.score }
    }
}
