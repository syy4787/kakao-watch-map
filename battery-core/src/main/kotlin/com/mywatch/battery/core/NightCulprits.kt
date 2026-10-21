package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 밤사이 뒤에서 배터리를 쓴 앱 순위와 할 일 */
data class NightReport(val start: Long, val end: Long, val coveredHours: Double, val ranks: List<AppDrainRank>, val advice: List<String>)

/**
 * 가장 최근 밤(00~07시, 지금이 밤이면 지금까지)의 15분 차를 합쳐 순위를 낸다.
 * 삼성 헬스가 밤새 센서를 시간당 [HEALTH_SENSOR_MIN_PER_H]분 넘게 켜 두면(심박·혈중 산소·피부 온도를 계속 잼) 측정 설정을 알려 준다 —
 * 삼성 공식 안내도 배터리를 아끼려면 자동 심박 측정 간격을 늘리고 상시 스트레스 측정 등을 끄라고 한다.
 */
object NightCulprits {
    const val SHEALTH = "com.samsung.android.wear.shealth"
    const val NIGHT_END_HOUR = 7
    const val MIN_COVERED_HOURS = 2.0
    /** 센서 둘이 내내 켜진 정도 */
    const val HEALTH_SENSOR_MIN_PER_H = 90

    fun lastNight(usage: List<TimedUse>, now: Long, zone: ZoneId, ownPkg: String? = null): NightReport? {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate()
        val start = today.atTime(LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
        val endOfNight = today.atTime(NIGHT_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val end = minOf(now, endOfNight)
        val windows = usage.filter { it.t > start && it.t <= end }
        val covered = windows.sumOf { it.ms } / 3_600_000.0
        if (covered < MIN_COVERED_HOURS) return null
        val ranks = AppDrain.rank(AppDrain.sum(windows.map { it.uses }), windows.sumOf { it.ms }, ownPkg)
        val advice = ArrayList<String>()
        ranks.firstOrNull { it.use.pkg.substringBefore('+') == SHEALTH }?.let { h ->
            val perH = h.use.sensorMs / 60_000.0 / covered
            if (perH > HEALTH_SENSOR_MIN_PER_H) {
                advice += "삼성 헬스가 밤새 센서를 시간당 ${perH.toInt()}분 켜 둠 — 워치 삼성 헬스 설정에서 수면 중 혈중 산소·피부 온도 측정을 끄고, " +
                    "심박수 자동 측정을 '10분마다'로, 스트레스 상시 측정을 끄면 밤 소모가 줄어듭니다 (건강 기록이 필요한 것만 남기세요)"
            }
        }
        return NightReport(start, end, covered, ranks.take(5), advice)
    }
}
