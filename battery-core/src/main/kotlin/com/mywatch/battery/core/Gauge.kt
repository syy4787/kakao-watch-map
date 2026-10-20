package com.mywatch.battery.core

import kotlin.math.abs

enum class AnomalyKind {
    /** 충전기 없이 잔량이 올라감 */
    JUMP_UP,
    /** 꺼졌다 켜지며 잔량이 뜀(17% → 꺼짐 → 50% 패턴) */
    REBOOT_JUMP,
    /** 20분 안에 15포인트 넘게 떨어짐(화면 꺼진 채) */
    CLIFF,
    /** 전하량 계수기로 계산한 소모와 표시 잔량의 소모가 크게 다름 */
    COUNTER_MISMATCH,
}

data class GaugeAnomaly(val kind: AnomalyKind, val t: Long, val fromLevel: Int, val toLevel: Int, val detail: String) {
    val label: String
        get() = when (kind) {
            AnomalyKind.JUMP_UP -> "충전 없이 잔량 상승"
            AnomalyKind.REBOOT_JUMP -> "꺼졌다 켜지며 잔량 점프"
            AnomalyKind.CLIFF -> "잔량 급락"
            AnomalyKind.COUNTER_MISMATCH -> "전하량 계수기와 표시 잔량 불일치"
        }
}

/** 충전 곡선으로 추정한 만충 용량 */
data class CapacityEstimate(val estimatedMah: Double, val nominalMah: Int?, val sessions: Int) {
    val percentOfNominal: Double? get() = nominalMah?.let { estimatedMah / it * 100.0 }
}

object Gauge {
    const val JUMP_UP_MIN = 5
    const val REBOOT_JUMP_MIN = 10
    const val CLIFF_DROP = 15
    const val CLIFF_MAX_GAP_MS = 20L * 60_000L
    const val MISMATCH_POINTS = 10.0
    /** 용량 추정에 쓰는 충전 세션의 최소 잔량 상승폭 */
    const val CAPACITY_MIN_RISE = 40

    fun anomalies(samples: List<BatterySample>, nominalMah: Int?): List<GaugeAnomaly> {
        val out = ArrayList<GaugeAnomaly>()
        for ((a, b) in samples.zipWithNext()) {
            val d = b.level - a.level
            val gap = b.t - a.t
            if (b.event == Event.BOOT && d >= REBOOT_JUMP_MIN) {
                out += GaugeAnomaly(AnomalyKind.REBOOT_JUMP, b.t, a.level, b.level, "부팅 뒤 ${a.level}% → ${b.level}%")
            } else if (b.event != Event.BOOT && !a.plugged && !b.plugged && d >= JUMP_UP_MIN) {
                out += GaugeAnomaly(AnomalyKind.JUMP_UP, b.t, a.level, b.level, "충전기 없이 ${a.level}% → ${b.level}%")
            }
            if (!a.plugged && !b.plugged && gap in 1..CLIFF_MAX_GAP_MS && -d >= CLIFF_DROP && !b.interactive) {
                out += GaugeAnomaly(AnomalyKind.CLIFF, b.t, a.level, b.level, "${gap / 60_000}분 만에 ${a.level}% → ${b.level}%")
            }
            if (nominalMah != null && !a.plugged && !b.plugged && d <= 0 && a.chargeUah != null && b.chargeUah != null) {
                val counterPoints = (a.chargeUah - b.chargeUah) / (nominalMah * 10.0)   // µAh → 공칭 용량 대비 포인트
                val shown = -d.toDouble()
                if (abs(counterPoints - shown) >= MISMATCH_POINTS) {
                    out += GaugeAnomaly(
                        AnomalyKind.COUNTER_MISMATCH, b.t, a.level, b.level,
                        "표시 ${shown.toInt()}포인트 소모, 계수기 ${"%.0f".format(counterPoints)}포인트",
                    )
                }
            }
        }
        return out
    }

    /**
     * 충전기가 꽂힌 연속 표본 묶음(세션)마다 처음과 끝의 전하량·잔량 차로 100% 용량을 환산하고,
     * 40포인트 넘게 오른 세션들의 중앙값을 낸다. 계수기가 없으면 null
     */
    fun capacity(samples: List<BatterySample>, nominalMah: Int?): CapacityEstimate? {
        val estimates = ArrayList<Double>()
        var session = ArrayList<BatterySample>()
        fun close() {
            val withUah = session.filter { it.chargeUah != null }
            if (withUah.size >= 2) {
                val first = withUah.first()
                val last = withUah.last()
                val rise = last.level - first.level
                if (rise >= CAPACITY_MIN_RISE) {
                    estimates += (last.chargeUah!! - first.chargeUah!!) / 1000.0 / rise * 100.0
                }
            }
            session = ArrayList()
        }
        for (s in samples) {
            if (s.plugged) session += s else close()
        }
        close()
        if (estimates.isEmpty()) return null
        val sorted = estimates.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return CapacityEstimate(median, nominalMah, estimates.size)
    }
}
