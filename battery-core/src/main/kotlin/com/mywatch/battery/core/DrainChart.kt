package com.mywatch.battery.core

import java.time.Instant
import java.time.ZoneId

/** 그래프 위 한 점: [x] 는 창 안의 위치 0~1, [level] 은 잔량 % */
data class ChartPoint(val x: Double, val level: Int)

/** 그래프 위 가로 구간 (0~1) */
data class ChartRange(val from: Double, val to: Double)

/** 가로축 글자 ("6시") 와 그 위치 */
data class ChartTick(val x: Double, val label: String)

/**
 * 배터리 화면의 24시간 그래프 — 그리기는 안드로이드 쪽이 하고, 여기서는 무엇을 어디에 그릴지만 정한다.
 * [lines] 기록이 끊긴 곳(40분 넘게 표본 없음)마다 나눈 잔량 선들 · [charging] 충전 구간 · [screenOn] 화면 켜짐(사용 통계가 있을 때) ·
 * [hot] 가장 많이 닳은 시간 · [description] 화면 낭독기와 접근성용 한 줄
 */
data class DrainChartModel(
    val from: Long,
    val to: Long,
    val lines: List<List<ChartPoint>>,
    val charging: List<ChartRange>,
    val screenOn: List<ChartRange>,
    val hot: List<ChartRange>,
    val minLevel: Int,
    val maxLevel: Int,
    /** 화면 켜짐이 사용 통계로 정확한가 (아니면 화면 켜짐 띠를 그리지 않는다) */
    val screenExact: Boolean,
    val description: String,
    /** 6시간마다 시각 글자 (오른쪽 끝 '지금'은 그리는 쪽이 붙인다) */
    val ticks: List<ChartTick> = emptyList(),
)

object DrainChart {
    const val WINDOW_MS = 24L * 3_600_000L
    /** 표본 사이가 이보다 길면 선을 끊는다 (기록이 빠진 곳을 이어 그리면 거짓 기울기가 생김) */
    const val GAP_MS = 40L * 60_000L

    fun x(t: Long, m: DrainChartModel): Double = ((t - m.from).toDouble() / (m.to - m.from)).coerceIn(0.0, 1.0)

    /** 창 안 표본이 2개 미만이면 null */
    fun buildOrNull(
        samples: List<BatterySample>, now: Long, zone: ZoneId,
        screen: List<Span>? = null, hot: List<Pair<Long, Long>> = emptyList(), windowMs: Long = WINDOW_MS,
    ): DrainChartModel? {
        val inWindow = samples.count { it.t in (now - windowMs)..now }
        return if (inWindow < 2) null else build(samples, now, zone, screen, hot, windowMs)
    }

    fun build(
        samples: List<BatterySample>, now: Long, zone: ZoneId,
        screen: List<Span>? = null, hot: List<Pair<Long, Long>> = emptyList(), windowMs: Long = WINDOW_MS,
    ): DrainChartModel {
        val from = now - windowMs
        val s = samples.filter { it.t in from..now }.sortedBy { it.t }
        fun xs(t: Long) = ((t - from).toDouble() / windowMs).coerceIn(0.0, 1.0)

        val lines = ArrayList<List<ChartPoint>>()
        var cur = ArrayList<ChartPoint>()
        var prevT: Long? = null
        for (p in s) {
            if (prevT != null && p.t - prevT > GAP_MS && cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            cur += ChartPoint(xs(p.t), p.level.coerceIn(0, 100))
            prevT = p.t
        }
        if (cur.isNotEmpty()) lines += cur

        // 충전: 꽂힌 표본부터 다음 빠진 표본(없으면 지금)까지
        val charging = ArrayList<ChartRange>()
        var start: Long? = null
        for (p in s) {
            if (p.plugged && start == null) start = p.t
            if (!p.plugged && start != null) { charging += ChartRange(xs(start), xs(p.t)); start = null }
        }
        start?.let { charging += ChartRange(xs(it), xs(now)) }

        val screenOn = screen.orEmpty().filter { it.end > from && it.start < now }.sortedBy { it.start }
            .map { ChartRange(xs(maxOf(it.start, from)), xs(minOf(it.end, now))) }
        val hotRanges = hot.filter { it.second > from && it.first < now }.map { ChartRange(xs(maxOf(it.first, from)), xs(minOf(it.second, now))) }

        val levels = s.map { it.level.coerceIn(0, 100) }
        val minL = levels.minOrNull() ?: 0
        val maxL = levels.maxOrNull() ?: 100
        val description = buildString {
            if (s.isEmpty()) { append("기록 없음"); return@buildString }
            append("${hoursLabel(s.last().t - s.first().t)} 동안 ${s.first().level}% → ${s.last().level}%")
            if (minL < minOf(s.first().level, s.last().level)) append(" (가장 낮을 때 $minL%)")
            if (charging.isNotEmpty()) append(" · 충전 ${charging.size}번")
            hot.firstOrNull()?.let { append(" · 가장 많이 닳은 때 ${hourName(it.first, zone)}") }
        }
        return DrainChartModel(from, now, lines, charging, screenOn, hotRanges, minL, maxL, screen != null, description, ticks(from, now, zone))
    }

    /** 창 안의 0·6·12·18시 — 지금에서 1시간 반 안은 '지금' 글자와 겹치니 뺀다 */
    private fun ticks(from: Long, now: Long, zone: ZoneId): List<ChartTick> {
        val out = ArrayList<ChartTick>()
        var t = Instant.ofEpochMilli(from).atZone(zone).withMinute(0).withSecond(0).withNano(0)
        while (t.hour % TICK_HOURS != 0) t = t.plusHours(1)
        while (true) {
            val ms = t.toInstant().toEpochMilli()
            if (ms > now - TICK_GAP_MS) break
            if (ms >= from) out += ChartTick((ms - from).toDouble() / (now - from), "${t.hour}시")
            t = t.plusHours(TICK_HOURS.toLong())
        }
        return out
    }

    private const val TICK_HOURS = 6
    private const val TICK_GAP_MS = 90L * 60_000L

    /** "오전 9시" / "오후 12시" */
    fun hourName(t: Long, zone: ZoneId): String {
        val h = Instant.ofEpochMilli(t).atZone(zone).hour
        return (if (h < 12) "오전 " else "오후 ") + (if (h % 12 == 0) 12 else h % 12) + "시"
    }

    private fun hoursLabel(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        return if (h >= 1) "${h}시간" + (if (m >= 30 && h < 6) " ${m}분" else "") else "${m}분"
    }
}
