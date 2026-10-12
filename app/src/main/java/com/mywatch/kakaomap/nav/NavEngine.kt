package com.mywatch.kakaomap.nav

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.RouteProgress
import kotlin.math.min

/** 안내 중 손목에 알려 줄 일 (진동 종류) */
enum class NavEvent { STEP_CHANGED, ALIGHT_SOON, OFF_ROUTE, ARRIVED, REROUTED }

/** 지도 배너·저전력 화면·알림에 보여 줄 안내 상태 */
data class NavState(
    val routeVersion: Int = -1,
    val stepIndex: Int = -1,
    val guide: String? = null,
    val remainingM: Int? = null,
    val arrived: Boolean = false,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    /** 이 경로 위에 한 번이라도 올라섰는지 (재탐색 반복 방지용) */
    val matched: Boolean = false,
    /** 지금 구간 끝(다음 갈림길·내릴 곳)까지 남은 거리 — 위치 받는 간격을 정할 때 씀 */
    val toStepEndM: Int? = null,
    /** 버스·지하철을 타고 가는 구간인지 */
    val onRide: Boolean = false,
)

/**
 * 경로 안내 판단 — 안드로이드와 무관한 순수 계산이라 단위 테스트로 검증한다.
 * 위치가 들어올 때마다 현재 구간·남은 거리·경로 이탈·도착·하차 알림을 정한다.
 *
 * GPS 는 서 있어도 몇 m 씩 흔들리므로 판단마다 '되돌림 방지'를 둔다:
 *  · 구간은 앞으로만 넘어가며 그때만 진동. 뒤로 가는 것은 경로를 따라 20m 넘게 되돌아간 게 두 번 연속일 때만 인정
 *  · 이탈·도착·하차 알림은 연속 두 번(경로에 오르기 전 이탈은 세 번) 확인
 *  · 정확도가 나쁜 위치(50m 초과)는 버리고, 도착·하차 판단은 30m 이내 정확도에서만
 */
class NavEngine(
    private val steps: List<GuideStep>,
    private val destination: Coord,
    val routeVersion: Int,
) {
    private val stepLen = DoubleArray(steps.size) { i ->
        val pts = steps[i].points
        (0 until (pts.size - 1).coerceAtLeast(0)).sumOf { Geo.distanceM(pts[it], pts[it + 1]) }
    }

    private var lastSeg = -1
    private var maxStep = -1
    private var bestRemaining = Int.MAX_VALUE
    private var seenOnRoute = false
    private var backCount = 0
    private var offCount = 0
    private var arriveCount = 0
    private var alightCount = 0
    private val alightArmed = HashSet<Int>()
    private val alightAlerted = HashSet<Int>()

    var state = NavState(routeVersion = routeVersion, guide = steps.firstOrNull()?.text?.ifBlank { null })
        private set

    /** 새 위치 반영. 진동으로 알려야 할 일을 돌려준다 */
    fun update(c: Coord, accuracyM: Float): List<NavEvent> {
        if (state.arrived) {
            // 도착 뒤에도 안내를 끄지 않고 목적지까지 직선거리만 계속 보여 준다 (입구 찾기용)
            if (accuracyM.isNaN() || accuracyM <= 0f || accuracyM > MAX_USABLE_ACCURACY_M) return emptyList()
            val d = Geo.distanceM(c, destination).toInt()
            state = state.copy(guide = if (d <= ARRIVE_M) ARRIVED_TEXT else "$NEAR_TEXT\n목적지까지 ${Format.distance(d)}", remainingM = d)
            return emptyList()
        }
        // 정확도를 모르면(NaN·0) 보통 수준으로 보되 도착·하차 판단에는 쓰지 않는다
        val acc = if (accuracyM.isNaN() || accuracyM <= 0f) UNKNOWN_ACCURACY_M else accuracyM
        if (acc > MAX_USABLE_ACCURACY_M) return emptyList()
        val precise = acc <= DECISION_ACCURACY_M
        val p = RouteProgress.locate(c, steps, fromSeg = (lastSeg - BACK_SEGS).coerceAtLeast(0)) ?: return emptyList()

        // ── 도착: 마지막 구간(또는 80m 이내 남음)에서 목적지 근처이거나, 경로 끝에 다다름 — 두 번 연속 확인
        val nearDestination = Geo.distanceM(c, destination) <= ARRIVE_M + min(acc, 15f) &&
            (p.stepIndex == steps.lastIndex || p.remainingM <= ARRIVE_REMAINING_M)
        val atRouteEnd = p.remainingM <= ARRIVE_M && p.offRouteM <= ARRIVE_END_OFF_M
        if (precise && (nearDestination || atRouteEnd)) {
            if (++arriveCount >= CONFIRM_FIXES) {
                state = state.copy(
                    stepIndex = p.stepIndex, guide = ARRIVED_TEXT, remainingM = 0, arrived = true, offRoute = false,
                    toStepEndM = 0, onRide = false,
                )
                return listOf(NavEvent.ARRIVED)
            }
        } else {
            arriveCount = 0
        }

        // ── 이탈: 버스·지하철 타는 중에는 판단 안 함 (노선 좌표가 성글고 지하에선 GPS 가 튐)
        val step = steps[p.stepIndex]
        val onRouteLimit = OFF_ROUTE_M + min(acc, 40f)
        if (p.offRouteM <= onRouteLimit || step.ride || step.approx) seenOnRoute = true
        // 경로에 한 번도 오르지 않았으면(실내에서 출발 등) 훨씬 멀어야 이탈로 본다
        val offLimit = if (seenOnRoute) onRouteLimit else maxOf(OFF_ROUTE_M + acc, NOT_MATCHED_OFF_ROUTE_M)
        // 직선으로 채운 도보(정류장까지·도착지까지)도 실제 길과 달라서 판단 안 함
        val farOff = !step.ride && !step.approx && p.offRouteM > offLimit
        offCount = if (farOff) offCount + 1 else 0
        if (offCount >= (if (seenOnRoute) CONFIRM_FIXES else NOT_MATCHED_CONFIRM_FIXES)) {
            val events = if (state.offRoute) emptyList() else listOf(NavEvent.OFF_ROUTE)
            state = state.copy(guide = shown("경로를 벗어났어요\n(${Format.distance(p.offRouteM)})"), offRoute = true)
            return events
        }
        if (farOff) return emptyList()   // 한 번 튄 위치는 판단 보류
        if (!seenOnRoute) {
            // 아직 경로 밖 (가깝지만 경로 위는 아님): 진동 없이 경로 쪽으로
            state = state.copy(guide = shown("경로 쪽으로 이동하세요\n(경로까지 ${Format.distance(p.offRouteM)})"))
            return emptyList()
        }

        // ── 구간: 앞으로만 넘어간다
        if (state.offRoute) {
            // 이탈했다가 돌아옴 → 지금 위치를 새 기준으로
            maxStep = p.stepIndex
            bestRemaining = p.remainingM
            backCount = 0
        } else if (maxStep >= 0 && p.stepIndex < maxStep) {
            if (p.remainingM - bestRemaining > REGRESS_M) {
                if (++backCount < CONFIRM_FIXES) return emptyList()
                // 정말로 경로를 거슬러 되돌아감 → 앞 구간 안내로
                maxStep = p.stepIndex
                bestRemaining = p.remainingM
                backCount = 0
            } else {
                // 갈림길·정류장에서 GPS 가 흔들린 것 → 지금 안내 유지 (진동·문구 깜빡임 없음)
                backCount = 0
                return emptyList()
            }
        } else {
            backCount = 0
        }
        val stepChanged = maxStep >= 0 && p.stepIndex > maxStep
        if (p.stepIndex > maxStep) maxStep = p.stepIndex
        lastSeg = p.segIndex
        bestRemaining = min(bestRemaining, p.remainingM)

        // ── 하차 알림: 탄 뒤 어느 정도 가고 나서, 내릴 곳까지 기준 거리 안에 두 번 연속 들어오면 한 번
        val events = ArrayList<NavEvent>(1)
        var guide = guideText(p)
        if (step.ride && step.alight != null) {
            val len = stepLen[p.stepIndex]
            val limit = min((if (step.subway) SUBWAY_ALIGHT_M else BUS_ALIGHT_M).toDouble(), len * 0.5)
            if (len - p.toStepEndM >= min(ARM_PROGRESS_M, len * 0.3)) alightArmed += p.stepIndex
            if (p.stepIndex in alightArmed && p.toStepEndM <= limit) {
                guide = "곧 하차 · ${step.alight}\n${Format.distance(p.toStepEndM)} 남음"
                if (precise && ++alightCount >= CONFIRM_FIXES && alightAlerted.add(p.stepIndex)) events += NavEvent.ALIGHT_SOON
            } else {
                alightCount = 0
            }
        } else {
            alightCount = 0
        }
        if (stepChanged && events.isEmpty()) events += NavEvent.STEP_CHANGED
        state = state.copy(
            stepIndex = p.stepIndex,
            guide = shown(guide),
            remainingM = p.remainingM,
            offRoute = false,
            matched = true,
            toStepEndM = p.toStepEndM,
            onRide = step.ride,
        )
        return events
    }

    fun setRerouting(on: Boolean) {
        state = state.copy(rerouting = on, guide = if (on) REROUTING_TEXT else state.guide)
    }

    /** 경로를 다시 찾는 동안에는 그 문구를 유지 */
    private fun shown(guide: String) = if (state.rerouting) REROUTING_TEXT else guide

    private fun guideText(p: RouteProgress): String {
        val cur = steps[p.stepIndex]
        if (cur.ride) {
            return buildString {
                append(cur.text)
                cur.alight?.let { append("\n").append(it).append("까지 ").append(Format.distance(p.toStepEndM)) }
            }
        }
        val next = steps.getOrNull(p.stepIndex + 1)?.text?.ifBlank { null }
        // 카카오맵처럼 '다음에 할 일'을 먼저: "120m 후 우회전"
        return buildString {
            append(Format.distance(p.toStepEndM)).append(" 후 ")
            append(next ?: "도착")
            if (cur.text.isNotBlank()) append("\n").append(cur.text)
        }
    }

    companion object {
        const val ARRIVE_M = 25
        private const val ARRIVE_END_OFF_M = 30
        /** 목적지 좌표 근처라도 경로상 이만큼 넘게 남았으면 도착 아님 (길 건너편 등) */
        private const val ARRIVE_REMAINING_M = 80
        const val OFF_ROUTE_M = 50
        /** 경로에 한 번도 오르지 않았을 때(실내에서 출발 등)는 훨씬 멀어야 이탈로 본다 */
        private const val NOT_MATCHED_OFF_ROUTE_M = 150f
        private const val CONFIRM_FIXES = 2
        private const val NOT_MATCHED_CONFIRM_FIXES = 3
        /** 경로를 거슬러 이만큼 넘게 되돌아가야 앞 구간으로 인정 */
        private const val REGRESS_M = 20
        /** 지나온 선분 중 몇 개까지 다시 볼지 */
        private const val BACK_SEGS = 5
        const val BUS_ALIGHT_M = 500
        const val SUBWAY_ALIGHT_M = 1000
        /** 탄 뒤 이만큼(또는 구간의 30%) 가야 하차 알림 준비 — 타기 전·직후 오알림 방지 */
        private const val ARM_PROGRESS_M = 150.0
        private const val UNKNOWN_ACCURACY_M = 45f
        private const val MAX_USABLE_ACCURACY_M = 50f
        private const val DECISION_ACCURACY_M = 30f
        const val ARRIVED_TEXT = "목적지에 도착했어요"
        const val NEAR_TEXT = "목적지 부근이에요"
        const val REROUTING_TEXT = "경로를 다시 찾는 중…"
    }
}
