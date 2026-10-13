package com.mywatch.kakaomap.model

import kotlin.math.roundToInt

/**
 * 카카오맵처럼 '정류장까지 걷는 길'을 실제 길로 (순수 계산 — 단위 테스트로 검증).
 * 카카오 대중교통 응답에 없어서 직선으로 채운 도보(출발지→첫 정류장, 마지막 정류장→도착지, 기차역 오가는 짧은 도보)를
 * 카카오 도보 길찾기 경로로 바꾼다 — 지도에 그 길이 점선으로 그려지고, 정류장까지 남은 거리도 길 따라 잰다.
 * 카카오가 준 환승 도보는 이미 길을 따라가므로 그대로.
 */
object TransitWalks {
    /** 이보다 짧은 직선은 그대로 (길찾기를 부를 만큼이 아님) */
    const val MIN_M = 40.0
    /** 길 끝과 출발점·정류장이 이만큼 안이면 그 점으로 바꾸고, 더 멀면 직선으로 이어 붙인다 */
    private const val JOIN_M = 3.0
    /** 길 끝이 이보다 멀면 엉뚱한 곳의 길 — 쓰지 않는다 */
    private const val MAX_GAP_M = 300.0
    private const val WALK_MPS = 1.1

    /** 실제 길로 바꿀 도보 구간 번호 */
    fun pending(route: TransitRoute): List<Int> = route.steps.indices.filter { i ->
        val s = route.steps[i]
        s.type == TransitStepType.WALK && s.approx && !s.pathFound && s.points.size >= 2 &&
            Geo.distanceM(s.points.first(), s.points.last()) >= MIN_M
    }

    /**
     * [index] 도보 구간을 걷는 길 [walk] 로 바꾼다. 출발점·정류장 좌표에서 정확히 시작·끝나게 잇고(다음 탈 것 구간과 이어지게),
     * 걷는 거리·시간도 실제 길 기준으로.
     *  · 시내 경로: 전체 소요 시간·거리는 카카오 값 그대로 (기다리는 시간 등이 섞여 있고, 카카오맵 목록과 같은 숫자)
     *  · 기차·버스 시간표 경로: 역까지 걷는 시간이 달라진 만큼 '늦어도 이때 출발'을, 내려서 걷는 시간이 달라진 만큼
     *    도착 시각·남은 시간을 옮긴다 (열차 시각은 그대로 — 더 걸리면 그만큼 일찍 나가야 탈 수 있다)
     */
    fun apply(route: TransitRoute, index: Int, walk: SimpleRoute): TransitRoute {
        val step = route.steps.getOrNull(index) ?: return route
        if (step.type != TransitStepType.WALK || step.points.size < 2) return route
        val path = walk.steps.flatMap { it.points }.fold(ArrayList<Coord>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
        if (path.size < 2) return route
        val from = step.points.first()
        val to = step.points.last()
        val gapStart = Geo.distanceM(from, path.first())
        val gapEnd = Geo.distanceM(path.last(), to)
        if (gapStart > MAX_GAP_M || gapEnd > MAX_GAP_M) return route
        val points = ArrayList<Coord>(path.size + 2)
        if (gapStart <= JOIN_M) points += from else { points += from; points += path.first() }
        points.addAll(path.subList(1, path.size - 1))
        if (gapEnd <= JOIN_M) points += to else { points += path.last(); points += to }
        val extra = (if (gapStart > JOIN_M) gapStart else 0.0) + (if (gapEnd > JOIN_M) gapEnd else 0.0)
        val walked = step.copy(
            points = points,
            distanceM = walk.totalDistanceM + extra.roundToInt(),
            timeSec = walk.totalTimeSec + (extra / WALK_MPS).roundToInt(),
            pathFound = true,
        )
        val steps = route.steps.toMutableList().also { it[index] = walked }
        val firstTimed = route.steps.indexOfFirst { it.type.scheduled }
        if (firstTimed < 0 || (route.leaveByMs == null && route.arriveAtMs == null)) return route.copy(steps = steps)
        val lastTimed = route.steps.indexOfLast { it.type.scheduled }
        val moreSec = walked.timeSec - step.timeSec
        val after = index > lastTimed
        return route.copy(
            steps = steps,
            totalDistanceM = route.totalDistanceM + walked.distanceM - step.distanceM,
            leaveByMs = if (index < firstTimed) route.leaveByMs?.minus(moreSec * 1000L) else route.leaveByMs,
            arriveAtMs = if (after) route.arriveAtMs?.plus(moreSec * 1000L) else route.arriveAtMs,
            totalTimeSec = if (after) (route.totalTimeSec + moreSec).coerceAtLeast(0) else route.totalTimeSec,
        )
    }
}
