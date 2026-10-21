package com.mywatch.battery.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 잔량 게이지 드리프트 — 워치9 사용자들이 겪는 "17%에서 꺼졌다 켜니 47%" 는 배터리가 아니라 **잔량을 세는 게이지가 실제보다 낮게
 * 세고 있다가 하드 리셋(홈+뒤로 7초 이상)으로 전압 기준 값으로 다시 맞춰진 것**이다. 그래서 '빨리 닳는' 것의 일부는 가짜(표시 오차)다.
 *
 * 쉬는 전압(화면 꺼짐·충전 안 함·전류 작음)으로 잔량을 어림잡아(OCV 표) 표시 잔량과 비교한다. 전압 기반 잔량은 ±10% 쯤 틀릴 수 있어
 * 큰 차이만 말한다: 12포인트 이상 '의심', 20포인트 이상 '확실'. 전하량 계수기 불일치나 최근의 재부팅 점프가 있으면 신뢰도를 올린다.
 */
data class GaugeDrift(
    /** 표시 잔량 */
    val reported: Int,
    /** 전압으로 어림잡은 잔량 */
    val voltageSoc: Int,
    /** reported − voltageSoc. 음수 = 게이지가 낮게 세는 중(가짜 소모), 양수 = 높게 세는 중(갑자기 꺼질 위험) */
    val driftPoints: Int,
    /** "낮음" / "중간" / "높음" */
    val confidence: String,
    /** 계산에 쓴 쉬는 표본 수 */
    val restSamples: Int,
    /** 쉬는 전압의 중앙값(mV) */
    val restMv: Int,
) {
    val suspicious: Boolean get() = abs(driftPoints) >= GaugeDriftRule.SUSPECT_POINTS
    val certain: Boolean get() = abs(driftPoints) >= GaugeDriftRule.CERTAIN_POINTS

    val headline: String
        get() = when {
            driftPoints <= -GaugeDriftRule.SUSPECT_POINTS -> "표시 ${reported}% · 전압 기준 약 ${voltageSoc}% — 게이지가 ${-driftPoints}포인트 낮게 세는 중"
            driftPoints >= GaugeDriftRule.SUSPECT_POINTS -> "표시 ${reported}% · 전압 기준 약 ${voltageSoc}% — 게이지가 ${driftPoints}포인트 높게 세는 중(갑자기 꺼질 수 있음)"
            else -> "표시 ${reported}% · 전압 기준 약 ${voltageSoc}% — 잔량 표시 정상 범위"
        }

    /** '지금 할 일' 줄에 맞춘 짧은 제목·설명 — 확실할 때만 할 일에 오른다 */
    val todo: Pair<String, String>?
        get() = when {
            driftPoints < 0 -> "잔량 표시가 ${-driftPoints}포인트 낮음" to "실제로는 더 남음 — 충전기 위에서 강제 재시작하면 맞춰짐"
            driftPoints > 0 -> "잔량 표시가 ${driftPoints}포인트 높음" to "갑자기 꺼질 수 있어 미리 충전 — 강제 재시작하면 맞춰짐"
            else -> null
        }

    /**
     * 타일 상태 줄 (의심일 때만) — 어느 쪽으로 틀렸는지와 할 일을 짧게. [headline] 뒷부분('게이지가 12포인트 높게 세는 중(갑자기
     * 꺼질 수 있음)')은 작은 워치 타일 두 줄을 넘었다
     */
    val tile: String?
        get() = when {
            driftPoints <= -GaugeDriftRule.SUSPECT_POINTS -> "잔량 표시가 ${-driftPoints}포인트 낮음 — 실제론 더 남음"
            driftPoints >= GaugeDriftRule.SUSPECT_POINTS -> "잔량 표시가 ${driftPoints}포인트 높음 — 미리 충전"
            else -> null
        }

    val advice: String?
        get() = when {
            driftPoints <= -GaugeDriftRule.SUSPECT_POINTS -> "홈 + 뒤로 가기 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작하면 게이지가 전압 기준으로 다시 맞춰집니다 — 충전기 위에서 하면 안전합니다. 그 전까지 표시보다 실제 잔량이 더 많다고 보면 됩니다"
            driftPoints >= GaugeDriftRule.SUSPECT_POINTS -> "표시보다 실제 잔량이 적을 수 있으니 미리 충전하세요. 강제 재시작으로 게이지를 다시 맞출 수 있습니다"
            else -> null
        }
}

/** 하드 리셋(또는 꺼졌다 켜짐) 전후의 잔량 — 드리프트가 있었다는 가장 확실한 증거 */
data class RestartRecovery(val t: Long, val before: Int, val after: Int, val userMarked: Boolean) {
    val gained: Int get() = after - before
}

object GaugeDriftRule {
    const val SUSPECT_POINTS = 12
    const val CERTAIN_POINTS = 20
    /** 쉬는 전압으로 볼 전류 한계(µA) — 워치 셀 내부저항(~0.1Ω)에서 150mA 는 전압 15mV, 잔량 2~3포인트 차이라 허용. 모르면 화면 꺼짐만 본다 */
    const val REST_CURRENT_UA = 150_000
    const val WINDOW_MS = 3L * 3_600_000L
    const val MIN_REST_SAMPLES = 3
    /** 알림: 낮게 세는 드리프트가 확실하고 표시 잔량이 이 아래면 */
    const val NOTIFY_LEVEL = 35
    const val NOTIFY_COOLDOWN_MS = 12L * 3_600_000L

    /**
     * 쉬는 전압(OCV) → 잔량 표. 두 종류: 4.2V 까지 충전하는 보통 셀과 4.35~4.4V 까지 충전하는 고전압 셀(최근 삼성 웨어러블).
     * 어느 쪽인지는 완충(FULL) 때 본 전압으로 고른다. 값은 일반적인 리튬폴리머 곡선의 근사치 — 그래서 큰 차이만 쓴다
     */
    val OCV_42: List<Pair<Int, Int>> = listOf(
        4200 to 100, 4150 to 94, 4100 to 88, 4050 to 80, 4000 to 72, 3950 to 63, 3900 to 54, 3850 to 45, 3820 to 38,
        3800 to 32, 3780 to 26, 3760 to 21, 3740 to 16, 3720 to 12, 3700 to 9, 3650 to 5, 3600 to 2, 3500 to 0,
    )
    val OCV_435: List<Pair<Int, Int>> = listOf(
        4350 to 100, 4300 to 94, 4250 to 88, 4200 to 81, 4150 to 74, 4100 to 67, 4050 to 60, 4000 to 52, 3950 to 44,
        3900 to 37, 3860 to 31, 3830 to 26, 3800 to 21, 3780 to 17, 3760 to 13, 3740 to 10, 3700 to 6, 3650 to 3, 3550 to 0,
    )

    /** 완충 때 본 최고 전압으로 셀 종류를 고른다. 모르면 고전압 셀(최근 갤럭시 워치) */
    fun table(fullMv: Int?): List<Pair<Int, Int>> = if (fullMv != null && fullMv < 4280) OCV_42 else OCV_435

    fun socOf(mv: Int, table: List<Pair<Int, Int>>): Int {
        if (mv >= table.first().first) return 100
        if (mv <= table.last().first) return 0
        for (i in 0 until table.size - 1) {
            val (v1, s1) = table[i]
            val (v2, s2) = table[i + 1]
            if (mv <= v1 && mv >= v2) {
                val f = (mv - v2).toDouble() / (v1 - v2)
                return (s2 + f * (s1 - s2)).roundToInt()
            }
        }
        return 0
    }

    /** 충전기에 꽂힌 채 FULL 이거나 잔량 100 일 때 본 전압의 최대 */
    fun fullVoltage(samples: List<BatterySample>): Int? =
        samples.filter { it.plugged && (it.status == Status.FULL || it.level >= 100) }.mapNotNull { it.voltageMv }.maxOrNull()

    fun isResting(s: BatterySample): Boolean =
        !s.plugged && !s.interactive && s.voltageMv != null && (s.currentUa == null || abs(s.currentUa) <= REST_CURRENT_UA)

    /**
     * 최근 3시간 쉬는 표본으로 드리프트 추정. 표본이 모자라면 null.
     * 완충 전압을 아직 못 봐서 셀 종류를 모르면 두 표 가운데 **차이가 작게 나오는 쪽**을 쓴다 — 표를 잘못 골라 생기는 20포인트짜리 헛소리를 막는다
     */
    fun estimate(samples: List<BatterySample>, now: Long, anomalies: List<GaugeAnomaly> = emptyList()): GaugeDrift? {
        val rest = samples.filter { it.t >= now - WINDOW_MS && it.t <= now && isResting(it) }.sortedBy { it.t }
        if (rest.size < MIN_REST_SAMPLES) return null
        val full = fullVoltage(samples)
        val tables = if (full != null) listOf(table(full)) else listOf(OCV_435, OCV_42)
        val drift = tables.map { table ->
            val diffs = rest.map { it.level - socOf(it.voltageMv!!, table) }.sorted()
            diffs[diffs.size / 2]
        }.minByOrNull { abs(it) }!!
        val latest = rest.last()
        val restMv = rest.map { it.voltageMv!! }.sorted().let { it[it.size / 2] }
        val voltageSoc = (latest.level - drift).coerceIn(0, 100)
        val recentAnomaly = anomalies.any { it.t >= now - 7 * 86_400_000L && (it.kind == AnomalyKind.REBOOT_JUMP || it.kind == AnomalyKind.COUNTER_MISMATCH) }
        val confidence = when {
            rest.size >= 6 && recentAnomaly -> "높음"
            rest.size >= 6 || recentAnomaly -> "중간"
            else -> "낮음"
        }
        return GaugeDrift(latest.level, voltageSoc, drift, confidence, rest.size, restMv)
    }

    /** 재부팅 점프(또는 사용자가 '강제 재시작함'으로 표시한 것) 전후 잔량 — 최근 것부터 */
    fun recoveries(samples: List<BatterySample>, anomalies: List<GaugeAnomaly>): List<RestartRecovery> {
        val out = ArrayList<RestartRecovery>()
        anomalies.filter { it.kind == AnomalyKind.REBOOT_JUMP }.forEach { out += RestartRecovery(it.t, it.fromLevel, it.toLevel, userMarked = false) }
        val sorted = samples.sortedBy { it.t }
        sorted.forEachIndexed { i, s ->
            if (s.event == Event.USER_NOTE && s.note == NOTE_FORCED_RESTART) {
                // 재시작 뒤에 눌렀으면(30분 안에 부팅 표본이 있으면) 부팅 직전 표본이 '전', 지금이 '후'
                val bootIdx = (i - 1 downTo 0).firstOrNull { j -> sorted[j].event == Event.BOOT && s.t - sorted[j].t <= MARKER_WINDOW_MS }
                val (before, after) = if (bootIdx != null) {
                    val b = sorted.getOrNull(bootIdx - 1)?.level ?: return@forEachIndexed
                    b to s.level
                } else {
                    // 재시작 전에 눌렀으면 직전 표본이 '전', 재시작 뒤 첫 표본(20분 안)이 '후'
                    val b = sorted.getOrNull(i - 1)?.level ?: return@forEachIndexed
                    b to (sorted.drop(i + 1).firstOrNull { it.t - s.t <= 20 * 60_000L }?.level ?: s.level)
                }
                if (out.none { abs(it.t - s.t) < MARKER_WINDOW_MS }) out += RestartRecovery(s.t, before, after, userMarked = true)
            }
        }
        return out.sortedByDescending { it.t }
    }

    /** 알림이 필요한가: 낮게 세는 드리프트가 확실하고(신뢰도 중간 이상) 잔량 표시가 낮을 때, 12시간에 한 번 */
    fun shouldNotify(d: GaugeDrift?, now: Long, lastAt: Long?): Boolean {
        if (d == null || d.confidence == "낮음") return false
        // 시계가 뒤로 가 미래가 된 마지막 알림 시각은 쉼으로 치지 않는다
        if (lastAt != null && now - lastAt in 0 until NOTIFY_COOLDOWN_MS) return false
        return d.driftPoints <= -CERTAIN_POINTS && d.reported <= NOTIFY_LEVEL
    }

    const val MARKER_WINDOW_MS = 30L * 60_000L

    const val NOTE_FORCED_RESTART = "강제 재시작"
}
