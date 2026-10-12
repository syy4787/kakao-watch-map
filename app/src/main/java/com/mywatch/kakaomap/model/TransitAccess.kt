package com.mywatch.kakaomap.model

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 카카오 대중교통 응답에는 '출발지 → 첫 정류장'과 '마지막 정류장 → 도착지' 도보가 단계로 들어 있지 않고
 * 총 소요 시간·거리에만 포함돼 있다 (실제 응답으로 확인: 단계 합보다 총합이 10~15분 많음).
 * 카카오맵처럼 처음과 끝에 '도보 N분'을 보여 주고 안내도 하려고, 그 두 구간을 직선으로 채워 넣는다.
 * 시간·거리는 빠진 몫(총합 − 단계 합)을 두 직선거리의 비율로 나눈다.
 */
object TransitAccess {
    /** 이보다 가까우면 도보 구간을 따로 만들지 않는다 */
    private const val MIN_ACCESS_M = 15.0
    /** 빠진 몫을 알 수 없을 때 쓰는 값: 실제 걷는 길은 직선의 약 1.3배, 걸음 1.1m/s */
    private const val DETOUR = 1.3
    private const val WALK_MPS = 1.1

    fun withAccessWalks(route: TransitRoute, origin: Coord, destination: Coord, destinationName: String): TransitRoute {
        val steps = route.steps
        if (steps.isEmpty()) return route
        val first = steps.first().points.firstOrNull() ?: return route
        val last = steps.last().points.lastOrNull() ?: return route
        val needLead = steps.first().type != TransitStepType.WALK && Geo.distanceM(origin, first) >= MIN_ACCESS_M
        val needTail = steps.last().type != TransitStepType.WALK && Geo.distanceM(last, destination) >= MIN_ACCESS_M
        if (!needLead && !needTail) return route

        val dIn = if (needLead) Geo.distanceM(origin, first) else 0.0
        val dOut = if (needTail) Geo.distanceM(last, destination) else 0.0
        val gapTime = (route.totalTimeSec - steps.sumOf { it.timeSec }).coerceAtLeast(0)
        val gapDist = (route.totalDistanceM - steps.sumOf { it.distanceM }).coerceAtLeast(0)
        val sum = dIn + dOut

        fun walk(d: Double, guidance: String, points: List<Coord>): TransitStep {
            val dist = if (gapDist > 0 && sum > 0) (gapDist * d / sum).roundToInt() else (d * DETOUR).roundToInt()
            val time = if (gapTime > 0 && sum > 0) (gapTime * d / sum).roundToInt() else (d * DETOUR / WALK_MPS).roundToInt()
            return TransitStep(
                type = TransitStepType.WALK,
                guidance = guidance,
                distanceM = max(dist, d.roundToInt()),
                timeSec = max(time, 30),
                stops = emptyList(),
                vehicles = emptyList(),
                points = points,
                approx = true,
            )
        }

        val firstRide = steps.firstOrNull { it.type == TransitStepType.BUS || it.type == TransitStepType.SUBWAY }
        val lead = if (needLead) walk(dIn, "${firstRide?.boardingStop ?: "정류장"}까지 도보", listOf(origin, first)) else null
        val tail = if (needTail) walk(dOut, "${destinationName.ifBlank { "도착지" }}까지 도보", listOf(last, destination)) else null
        return route.copy(steps = listOfNotNull(lead) + steps + listOfNotNull(tail))
    }
}
