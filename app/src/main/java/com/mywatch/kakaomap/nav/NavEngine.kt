package com.mywatch.kakaomap.nav

import com.mywatch.kakaomap.model.Action
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.Maneuver
import com.mywatch.kakaomap.model.Maneuvers
import com.mywatch.kakaomap.model.RouteProgress
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** 안내 중 손목에 알려 줄 일 (진동 종류) */
enum class NavEvent {
    /** 도보·자전거: 곧 왼쪽으로 꺾음 (짧게 2번) */
    TURN_SOON_LEFT,
    /** 곧 오른쪽으로 꺾음 (짧게 3번) */
    TURN_SOON_RIGHT,
    /** 곧 유턴 (짧게 4번) */
    UTURN_SOON,
    /** 곧 횡단보도·계단·육교 등 (길게 1번) */
    ACTION_SOON,
    /** 탈 정류장·역이 30m 앞 */
    BOARD_SOON,
    /** 내릴 곳이 가까움 */
    ALIGHT_SOON,
    /** 내릴 정류장을 지나침 */
    PASSED_STOP,
    OFF_ROUTE,
    ARRIVED,
    REROUTED,
}

/** 배너 앞 아이콘 */
enum class NavIcon { ACTION, WALK, BUS, SUBWAY, FLAG, WARNING }

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
    /** 지금 구간 끝(다음 갈림길·내릴 곳)까지 남은 거리 */
    val toStepEndM: Int? = null,
    /** 버스·지하철을 타고 가는 구간인지 */
    val onRide: Boolean = false,
    /** 다음에 할 일(회전·횡단보도·승차·하차·도착)까지 거리 — 직진으로 이어진 구간은 합쳐서. 위치 간격 정할 때 씀 */
    val toActionM: Int? = null,
    /** 다음에 할 일 (배너 화살표) */
    val action: Action? = null,
    val icon: NavIcon = NavIcon.WALK,
    /** 지금 지하철을 타고 가는 중 (지하라 GPS 가 거의 안 잡힘) */
    val onSubway: Boolean = false,
    /** 내릴 정류장을 지나침 → 다시 찾기 */
    val passedStop: Boolean = false,
    /** 도착하고 5분이 지났거나 300m 넘게 떠남 → 안내를 끝내도 됨 */
    val finished: Boolean = false,
    /** GPS 신호를 한동안 못 받음 */
    val signalLost: Boolean = false,
    /** 지도 배너: 오른쪽 굵은 글자 — 짧은 '할 일' (좌회전, 617번 승차, 곧 하차 …) */
    val bannerTitle: String? = null,
    /** 지도 배너: 오른쪽 둘째 줄 — 장소 하나 (역전약국 앞, 시청역 …) */
    val bannerSub: String? = null,
    /** 지도 배너: 왼쪽 아이콘 아래 거리 (없으면 숨김) */
    val bannerDistM: Int? = null,
)

/**
 * 경로 안내 판단 — 안드로이드와 무관한 순수 계산이라 시뮬레이션 테스트(가짜 GPS 재생)로 검증한다.
 * 위치가 들어올 때마다 현재 구간·다음에 할 일·남은 거리·이탈·도착·승하차 알림을 정한다.
 *
 * GPS 는 서 있어도 몇 m 씩 흔들리고, 빌딩 사이에선 수십 m 튀므로 판단마다 '되돌림 방지'를 둔다:
 *  · 경로를 따라 갑자기 너무 멀리 앞서면(걷는 속도로 못 갈 거리) 두 번 연속일 때만 인정 — 튄 위치로 구간을 건너뛰지 않게
 *  · 구간은 앞으로만 넘어가며, 뒤로 가는 것은 경로를 따라 20m 넘게 되돌아간 게 두 번 연속일 때만 인정
 *  · 버스·지하철은 정류장에서 노선을 따라 60m 넘게 간 위치가 두 번 연속일 때 '탔다'고 본다 (기다리는 동안 흔들림 무시)
 *  · 이탈·도착·하차 알림은 연속 두 번(경로에 오르기 전 이탈은 세 번) 확인, 갈림길을 지나쳐 계속 가면 30m 쯤에서 이탈
 *  · 정확도가 나쁜 위치(50m 초과, 지하철 안은 400m 초과)는 버리고, 도착 판단은 30m 이내 정확도에서만
 * 도보·자전거는 카카오 안내 문구와 경로 모양으로 '다음에 할 일'(좌회전·횡단보도 …)을 정해 미리(도보 35m·자전거 70m 전) 알리고,
 * 직진으로 이어지는 구간 경계는 알리지 않는다.
 */
class NavEngine(
    private val steps: List<GuideStep>,
    private val destination: Coord,
    val routeVersion: Int,
    private val bike: Boolean = false,
    private val destinationName: String? = null,
) {
    /** 한 번에 정하는 안내 문구: 알림·저전력 화면용 전체 문구 + 지도 배너용 칸들 */
    private data class Say(val guide: String, val title: String, val sub: String?, val distM: Int?)

    private val stepLen = DoubleArray(steps.size) { i ->
        val pts = steps[i].points
        (0 until (pts.size - 1).coerceAtLeast(0)).sumOf { Geo.distanceM(pts[it], pts[it + 1]) }
    }
    private val totalLen = stepLen.sum()
    /** 구간마다 첫 선분 번호 */
    private val segStart = IntArray(steps.size).also { arr ->
        var n = 0
        steps.forEachIndexed { i, s -> arr[i] = n; n += (s.points.size - 1).coerceAtLeast(0) }
    }
    private val maneuvers: List<Maneuver?> = Maneuvers.forSteps(steps)
    private val turnByTurn = steps.any { it.turnGuidance }
    private val soonM = if (bike) BIKE_SOON_M else WALK_SOON_M
    private val nowM = if (bike) BIKE_NOW_M else WALK_NOW_M

    private var lastSeg = -1
    private var maxStep = -1
    private var bestRemaining = Int.MAX_VALUE
    private var seenOnRoute = false
    private var backCount = 0
    private var offCount = 0
    private var arriveCount = 0
    private var alightCount = 0
    private var boardCount = 0
    private var passedCount = 0
    private val alightArmed = HashSet<Int>()
    private val alightAlerted = HashSet<Int>()
    /** 미리 알린 '할 일' (구간 번호) */
    private val announced = HashSet<Int>()
    /** 승차 알림을 한 탈 것 구간 */
    private val boardAlerted = HashSet<Int>()
    /** 정류장에 도착한 적 있는 탈 것 구간 (기다리는 동안 문구 고정) */
    private val atStop = HashSet<Int>()
    private val passedAlerted = HashSet<Int>()
    /** 탈 것 구간마다 '탄 뒤 지날 정류장들'의 노선상 거리(m, 마지막 = 내릴 곳) — 있으면 '정류장 n개 남음' */
    private val stopAlong = HashMap<Int, DoubleArray>()

    // 튄 위치 거르기: 마지막으로 인정한 '경로상 위치'(출발점부터 m)와 시각
    private var confirmedPos = -1.0
    private var confirmedAt: Long? = null
    private var pendingPos = -1.0
    private var pendingAt: Long? = null
    private var pendingCount = 0

    // 속도 (버스에서 정류장을 지나쳤는지, 내려서 걷는지 가르는 데 씀)
    private var lastRaw: Coord? = null
    private var lastRawAt: Long? = null
    private var speedMps: Double? = null

    private var arrivedAt: Long? = null
    private var farAwayCount = 0
    /** 탈 것 둘 이상 앞 구간으로 건너뛴 위치가 이어지는지 (튄 위치 거르기) */
    private var farJumpStep = -1
    private var farJumpCount = 0

    var state = initialSay().let {
        NavState(routeVersion = routeVersion, guide = it?.guide, icon = initialIcon(), bannerTitle = it?.title, bannerSub = it?.sub, bannerDistM = it?.distM)
    }
        private set

    /** 재탐색 중이면 그 문구를 유지 */
    private fun NavState.with(say: Say): NavState =
        if (rerouting) copy(guide = REROUTING_TEXT, bannerTitle = REROUTING_TITLE, bannerSub = null, bannerDistM = null)
        else copy(guide = say.guide, bannerTitle = say.title, bannerSub = say.sub, bannerDistM = say.distM)

    /** 새 위치 반영. 진동으로 알려야 할 일을 돌려준다. [elapsedMs] = 위치를 잰 시각(부팅 후 ms) */
    fun update(c: Coord, accuracyM: Float, elapsedMs: Long? = null): List<NavEvent> {
        if (state.arrived) return afterArrival(c, accuracyM, elapsedMs)
        // 정확도를 모르면(NaN·0) 보통 수준으로 보되 도착·하차 판단에는 쓰지 않는다
        val known = !accuracyM.isNaN() && accuracyM > 0f
        val acc = if (known) accuracyM else UNKNOWN_ACCURACY_M
        val onSubway = state.onSubway
        if (acc > (if (onSubway) SUBWAY_MAX_USABLE_ACCURACY_M else MAX_USABLE_ACCURACY_M)) return emptyList()
        val precise = known && acc <= DECISION_ACCURACY_M
        updateSpeed(c, elapsedMs)

        val searchFrom = if (state.offRoute) 0 else (lastSeg - BACK_SEGS).coerceAtLeast(0)
        var p = RouteProgress.locate(c, steps, fromSeg = searchFrom) ?: return emptyList()
        // 지하철 안·승강장의 거친 위치(기지국)로는 지금 구간(또는 기다리는 지하철) 밖으로 옮겨 가지 않는다
        val coarse = acc > MAX_USABLE_ACCURACY_M
        if (onSubway && coarse && p.stepIndex != state.stepIndex && !(steps[p.stepIndex].ride && p.stepIndex in atStop)) return emptyList()

        // ── 이미 '정류장 지남'을 알린 탈 것의 끝 너머에 아직 있음 → 새 경로가 올 때까지 그 상태 유지 (신호 대기로 멈춰도)
        if (p.stepIndex in passedAlerted && p.toStepEndM <= PASSED_END_M && p.offRouteM > ALIGHTED_OFF_M) return emptyList()
        // ── 버스·지하철 끝(내릴 곳)에 와서 걷는 속도로 조금 벗어남 → 내려서 걷는 중: 다음 도보 구간 기준으로 다시 본다
        //    (정확한 위치일 때만, 100m 안에서만 — 정류장을 지나 신호 대기 중인 버스·지하의 기지국 위치와 구별)
        if (steps[p.stepIndex].ride && maxStep >= p.stepIndex && p.stepIndex < steps.lastIndex && known && !coarse &&
            p.toStepEndM <= PASSED_END_M && p.offRouteM in (ALIGHTED_OFF_M + 1)..ALIGHTED_MAX_OFF_M &&
            (speedMps ?: 0.0) < WALKING_MAX_MPS
        ) {
            RouteProgress.locate(c, steps, fromSeg = segStart[p.stepIndex + 1])?.let { p = it }
        }
        val step = steps[p.stepIndex]

        // ── 도착: 마지막 구간(또는 80m 이내 남음)에서 목적지 근처이거나, 경로 끝에 다다름 — 두 번 연속 확인
        val nearDestination = Geo.distanceM(c, destination) <= ARRIVE_M + min(acc, 15f) &&
            (p.stepIndex == steps.lastIndex || p.remainingM <= ARRIVE_REMAINING_M)
        val atRouteEnd = p.remainingM <= ARRIVE_M && p.offRouteM <= ARRIVE_END_OFF_M
        if (precise && (nearDestination || atRouteEnd)) {
            if (++arriveCount >= CONFIRM_FIXES) {
                arrivedAt = elapsedMs
                state = state.copy(
                    stepIndex = p.stepIndex, remainingM = 0, arrived = true, offRoute = false, rerouting = false,
                    toStepEndM = 0, toActionM = 0, onRide = false, onSubway = false, action = null, icon = NavIcon.FLAG,
                    passedStop = false,
                ).with(Say(ARRIVED_TEXT, "도착했어요", destinationName, null))
                return listOf(NavEvent.ARRIVED)
            }
        } else {
            arriveCount = 0
        }

        // ── 내릴 정류장 지나침: 탄 구간의 끝을 지나 차 속도로 계속 멀어짐
        val alightAcc = if (step.subway) SUBWAY_DECISION_ACCURACY_M else DECISION_ACCURACY_M
        if (step.ride && maxStep >= p.stepIndex && p.toStepEndM <= PASSED_END_M && p.offRouteM > PASSED_STOP_M + acc &&
            acc <= alightAcc && (speedMps ?: 0.0) > VEHICLE_MPS
        ) {
            if (++passedCount >= CONFIRM_FIXES && passedAlerted.add(p.stepIndex)) {
                state = state.copy(passedStop = true, icon = NavIcon.WARNING, action = null)
                    .with(Say(PASSED_TEXT, "정류장 지남", "다음 정류장 하차", null))
                return listOf(NavEvent.PASSED_STOP)
            }
            if (p.stepIndex in passedAlerted) return emptyList()
        } else {
            passedCount = 0
        }

        // ── 이탈: 버스·지하철 타는 중과 직선으로 채운 도보는 판단 안 함 (노선 좌표가 성글고, 직선은 실제 길과 다름)
        val onRouteLimit = OFF_ROUTE_M + min(acc, 40f)
        if (p.offRouteM <= onRouteLimit || step.ride || step.approx) seenOnRoute = true
        // 경로에 한 번도 오르지 않았으면(실내에서 출발 등) 훨씬 멀어야 이탈로 본다
        val offLimit = if (seenOnRoute) onRouteLimit else maxOf(OFF_ROUTE_M + acc, NOT_MATCHED_OFF_ROUTE_M)
        // 갈림길에서 꺾지 않고 계속 직진함 → 30m 쯤에서 바로 이탈 (넓은 길 기준 50m 를 기다리지 않음)
        val overshoot = known && acc <= OVERSHOOT_ACCURACY_M && step.turnGuidance && p.stepIndex < steps.lastIndex &&
            p.toStepEndM <= 3 && p.offRouteM > OVERSHOOT_M + min(acc, 15f) && beyondStepEnd(c, p.stepIndex)
        val farOff = !step.ride && !step.approx && (p.offRouteM > offLimit || overshoot)
        offCount = if (farOff) offCount + 1 else 0
        if (offCount >= (if (seenOnRoute) CONFIRM_FIXES else NOT_MATCHED_CONFIRM_FIXES)) {
            val events = if (state.offRoute) emptyList() else listOf(NavEvent.OFF_ROUTE)
            val off = Format.distance(p.offRouteM)
            state = state.copy(offRoute = true, icon = NavIcon.WARNING, action = null)
                .with(Say("경로를 벗어났어요\n($off)", "경로 이탈", "경로까지 $off", null))
            return events
        }
        if (farOff) return emptyList()   // 한 번 튄 위치는 판단 보류
        if (!seenOnRoute) {
            // 아직 경로 밖 (가깝지만 경로 위는 아님): 진동 없이 경로 쪽으로
            val off = Format.distance(p.offRouteM)
            state = state.copy(icon = NavIcon.WALK).with(Say("경로 쪽으로 이동하세요\n(경로까지 $off)", "경로로 가세요", "경로까지 $off", null))
            return emptyList()
        }

        // ── 튄 위치 거르기: 걷는(타는) 속도로 갈 수 없을 만큼 앞서면 두 번 연속일 때만 인정
        val pos = totalLen - p.remainingM
        val now = elapsedMs
        val last = confirmedAt
        if (now != null && last != null && confirmedPos >= 0 && !state.offRoute) {
            // 속도 한계는 지금 구간 기준 (바로 다음 구간으로 넘어가는 경우에만 그 구간 것도 허용 — 버스가 떠날 때 등)
            val curStep = steps[state.stepIndex.coerceIn(0, steps.lastIndex)]
            val vmax = if (p.stepIndex <= state.stepIndex + 1) max(maxSpeed(curStep), maxSpeed(step)) else maxSpeed(curStep)
            val slack = max(acc, 10f) + JUMP_SLACK_M
            val dt = ((now - last) / 1000.0).coerceAtLeast(0.5)
            if (pos - confirmedPos > vmax * dt + slack) {
                val pa = pendingAt
                val consistent = pa != null && pendingPos >= 0 &&
                    abs(pos - pendingPos) <= vmax * ((now - pa) / 1000.0).coerceAtLeast(0.5) + slack
                pendingCount = if (consistent) pendingCount + 1 else 1
                pendingPos = pos
                pendingAt = now
                if (pendingCount < CONFIRM_FIXES) return emptyList()
            }
        }
        pendingCount = 0
        pendingPos = -1.0
        pendingAt = null

        // ── 버스·지하철 타기: 노선을 따라 60m(거친 위치면 +정확도) 넘게 간 위치가 두 번 연속이어야 '탔다'
        if (step.ride && maxStep < p.stepIndex) {
            // 탈 것 둘 이상 앞 구간으로 건너뛴 위치(다른 노선 길 위로 튐)는 같은 곳이 세 번 이어져야 인정
            if (p.stepIndex - 1 > maxStep + 1) {
                farJumpCount = if (farJumpStep == p.stepIndex) farJumpCount + 1 else 1
                farJumpStep = p.stepIndex
                if (farJumpCount < FAR_JUMP_CONFIRM) return emptyList()
            }
            val progress = stepLen[p.stepIndex] - p.toStepEndM
            if (progress < BOARD_PROGRESS_M + (if (coarse) acc else 0f)) {
                boardCount = 0
                return holdAtStop(p)
            }
            if (++boardCount < CONFIRM_FIXES) return holdAtStop(p)
        }
        boardCount = 0
        farJumpCount = 0
        farJumpStep = -1

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
        if (p.stepIndex > maxStep) maxStep = p.stepIndex
        lastSeg = p.segIndex
        bestRemaining = min(bestRemaining, p.remainingM)
        confirmedPos = pos
        if (now != null) confirmedAt = now

        return guideAndEvents(p, acc)
    }

    /** 정류장에서 기다리는 중 (또는 막 탄 직후 확인 중): 승차 안내를 보여 준다 */
    private fun holdAtStop(p: RouteProgress): List<NavEvent> {
        val rideIdx = p.stepIndex
        val ride = steps[rideIdx]
        val walkIdx = rideIdx - 1
        val remainingAtStop = p.remainingM + (stepLen[rideIdx] - p.toStepEndM).toInt()
        if (walkIdx >= 0 && walkIdx > maxStep) {
            maxStep = walkIdx
            lastSeg = (segStart[rideIdx] - 1).coerceAtLeast(0)
        }
        // 되돌림 판단의 기준점도 정류장으로 (안 그러면 앞 구간 위치를 영영 '흔들림'으로 봄)
        bestRemaining = min(bestRemaining, remainingAtStop)
        atStop += rideIdx
        val events = if (boardAlerted.add(rideIdx)) listOf(NavEvent.BOARD_SOON) else emptyList()
        state = state.copy(
            stepIndex = max(walkIdx, 0),
            remainingM = remainingAtStop,
            offRoute = false, matched = true, toStepEndM = 0, toActionM = 0, onRide = false,
            // 지하철 승강장(지하)에서 기다리는 중이면 기지국 위치도 받고, 위치는 저전력으로
            onSubway = ride.subway,
            action = null, icon = vehicleIcon(ride), passedStop = false,
        ).with(boardSay(ride, 0))
        return events
    }

    private fun guideAndEvents(p: RouteProgress, acc: Float): List<NavEvent> {
        val events = ArrayList<NavEvent>(1)
        val cur = steps[p.stepIndex]
        var action: Action? = null
        var toAction = p.toStepEndM
        val icon: NavIcon
        val say: Say
        if (cur.ride) {
            // ── 하차 알림: 탄 뒤 어느 정도 가고 나서, 다음 정류장이 내릴 곳이면(정류장 정보가 없으면 남은 거리로)
            //    두 번 연속 확인되면 한 번
            val len = stepLen[p.stepIndex]
            val progress = len - p.toStepEndM
            val limit = min((if (cur.subway) SUBWAY_ALIGHT_M else BUS_ALIGHT_M).toDouble(), len * 0.5)
            if (progress >= min(ARM_PROGRESS_M, len * 0.3)) alightArmed += p.stepIndex
            val left = stopAlong[p.stepIndex]?.count { it > progress + STOP_AHEAD_M }
            val soon = p.stepIndex in alightArmed && (if (left != null) left <= 1 else p.toStepEndM <= limit)
            if (soon) {
                val title = when (left) {
                    null -> "곧 하차"
                    1 -> "다음 정류장 하차"
                    else -> "이번 정류장 하차"
                }
                say = Say(
                    (cur.alight?.let { "곧 하차 · $it" } ?: "곧 하차") + "\n${Format.distance(p.toStepEndM)} 남음",
                    title, cur.alight, p.toStepEndM,
                )
                val alightAcc = if (cur.subway) SUBWAY_DECISION_ACCURACY_M else DECISION_ACCURACY_M
                if (acc <= alightAcc && ++alightCount >= CONFIRM_FIXES && alightAlerted.add(p.stepIndex)) {
                    events += NavEvent.ALIGHT_SOON
                }
            } else {
                alightCount = 0
                say = rideSay(cur, p.toStepEndM, left, len)
            }
            icon = vehicleIcon(cur)
        } else {
            alightCount = 0
            val next = steps.getOrNull(p.stepIndex + 1)
            if (next != null && next.ride) {
                // ── 정류장·역으로 걷는 중
                if (p.toStepEndM <= nowM) atStop += p.stepIndex + 1
                if (p.toStepEndM <= BOARD_SOON_M && boardAlerted.add(p.stepIndex + 1)) events += NavEvent.BOARD_SOON
                // 정류장·역에 닿은 뒤엔 흔들리는 위치(지하 기지국 위치 포함)로 '120m 후 승차'처럼 바뀌지 않게
                val coarse = acc > MAX_USABLE_ACCURACY_M
                val d = if (p.stepIndex + 1 in atStop && (p.toStepEndM <= BOARD_SOON_M || coarse)) 0 else p.toStepEndM
                say = if (next.line != null) boardSay(next, d) else legacySay(p)
                icon = vehicleIcon(next)
            } else if (turnByTurn) {
                // ── 도보·자전거: 직진 구간은 건너뛰고 다음에 할 일
                val k = nextAction(p.stepIndex)
                val dK = distanceTo(p, k)
                toAction = dK
                if (k >= 0) {
                    val m = maneuvers[k]!!
                    action = m.action
                    icon = NavIcon.ACTION
                    if (dK <= soonM && acc <= ALERT_ACCURACY_M && announced.add(k)) events += eventFor(m.action)
                    say = turnSay(m, k, dK)
                } else {
                    icon = NavIcon.FLAG
                    val near = dK <= nowM
                    say = Say(
                        if (near) "곧 도착" else "${Format.distance(dK)} 후 도착",
                        if (near) "곧 도착" else "도착", destinationName, if (near) null else dK,
                    )
                }
            } else {
                say = legacySay(p)
                icon = NavIcon.WALK
            }
        }
        state = state.copy(
            stepIndex = p.stepIndex,
            remainingM = p.remainingM,
            offRoute = false,
            matched = true,
            toStepEndM = p.toStepEndM,
            toActionM = toAction,
            onRide = cur.ride,
            onSubway = (cur.ride && cur.subway) || waitingForSubway(p.stepIndex),
            action = action,
            icon = if (state.rerouting) NavIcon.WARNING else icon,
            passedStop = false,
        ).with(say)
        return events
    }

    /** 도착 뒤에도 바로 끄지 않고 목적지까지 직선거리를 보여 준다 (입구 찾기용). 5분 지나거나 300m 넘게 떠나면 끝 */
    private fun afterArrival(c: Coord, accuracyM: Float, elapsedMs: Long?): List<NavEvent> {
        if (accuracyM.isNaN() || accuracyM <= 0f || accuracyM > MAX_USABLE_ACCURACY_M) return emptyList()
        val d = Geo.distanceM(c, destination).toInt()
        val since = if (elapsedMs != null && arrivedAt != null) elapsedMs - arrivedAt!! else 0L
        // 300m 넘게 떠남은 두 번 연속 확인 (튄 위치 한 번으로 끝내지 않게)
        farAwayCount = if (d > FINISH_AWAY_M) farAwayCount + 1 else 0
        val done = farAwayCount >= CONFIRM_FIXES || since >= FINISH_AFTER_MS
        state = state.copy(remainingM = d, finished = state.finished || done).with(
            if (d <= ARRIVE_M) Say(ARRIVED_TEXT, "도착했어요", destinationName, null)
            else Say("$NEAR_TEXT\n목적지까지 ${Format.distance(d)}", "목적지 부근", destinationName ?: "목적지까지", d)
        )
        return emptyList()
    }

    fun setRerouting(on: Boolean) {
        state = if (on) {
            state.copy(rerouting = true, guide = REROUTING_TEXT, icon = NavIcon.WARNING, bannerTitle = REROUTING_TITLE, bannerSub = null, bannerDistM = null)
        } else {
            state.copy(rerouting = false)
        }
    }

    /** GPS 신호를 한동안 못 받음 / 다시 받음 */
    fun setSignalLost(lost: Boolean) {
        if (state.signalLost != lost) state = state.copy(signalLost = lost)
    }

    // ───────── 문구 ─────────

    private fun turnSay(m: Maneuver, k: Int, dK: Int): Say {
        val phrase = Maneuvers.phrase(m.action, m.count)
        val near = dK <= nowM
        val main = if (near) "지금 $phrase" else "${Format.distance(dK)} 후 $phrase"
        // 바로 이어지는 다음 할 일(25m 안)은 가까워졌을 때 '이어서 …'로
        val k2 = nextAction(k)
        val gap = if (k2 >= 0) (k until k2).sumOf { stepLen[it] } else Double.MAX_VALUE
        val then = if (dK <= THEN_WINDOW_M && k2 >= 0 && gap <= THEN_GAP_M) {
            "이어서 " + maneuvers[k2]!!.let { Maneuvers.phrase(it.action, it.count) }
        } else {
            null
        }
        val sub = then ?: listOfNotNull(m.at, m.road).joinToString(" · ").ifEmpty { m.to?.let { "$it 방향" } ?: "" }
        // 배너 둘째 줄은 좁으니 장소 하나만 (…앞 > 길 이름 > …방향)
        val bannerSub = then ?: m.at ?: m.road ?: m.to?.let { "$it 방향" }
        return Say(if (sub.isEmpty()) main else "$main\n$sub", if (near) "지금 $phrase" else phrase, bannerSub, if (near) null else dK)
    }

    private fun boardSay(ride: GuideStep, d: Int): Say {
        val name = ride.line ?: (if (ride.subway) "지하철" else "버스")
        val near = d <= nowM
        val main = if (near) "$name 승차" else "${Format.distance(d)} 후 $name 승차"
        return Say(main + (ride.board?.let { "\n$it" } ?: ""), "$name 승차", ride.board, if (near) null else d)
    }

    /**
     * 타고 가는 중: "1.2km 후 하차" / "은하수네거리 · 617번".
     * 배너는 카카오맵 알림("정류장 3개(7분)")처럼 [버스 1.2km] 3정거장 · 7분 / 은하수네거리 하차.
     * 정류장 위치를 모르면 '7분 후 하차', 소요 시간도 모르면 '617번 타는 중'.
     */
    private fun rideSay(cur: GuideStep, d: Int, left: Int?, len: Double): Say {
        val where = listOfNotNull(cur.alight, cur.line).joinToString(" · ")
        val main = if (left != null) "${left}정거장 남음 · ${Format.distance(d)}" else "${Format.distance(d)} 후 하차"
        val name = cur.line ?: (if (cur.subway) "지하철" else "버스")
        // 남은 시간 ≈ 구간 소요 시간 × 남은 거리 비율 (올림, 최소 1분)
        val minutes = if (cur.timeSec > 0 && len > 1.0) ceil(cur.timeSec * (d / len) / 60.0).toInt().coerceAtLeast(1) else null
        // 정류장 이름이 길면 '하차'는 빼서 이름이 잘리지 않게
        val stopSub = cur.alight?.let { if (it.length <= 6) "$it 하차" else it }
        val (title, sub) = when {
            left != null && minutes != null -> "${left}정거장 · ${minutes}분" to stopSub
            left != null -> "${left}정거장 남음" to stopSub
            minutes != null -> "${minutes}분 후 하차" to cur.alight
            else -> "$name 타는 중" to stopSub
        }
        return Say(if (where.isEmpty()) main else "$main\n$where", title, sub, d)
    }

    /**
     * 탈 것 구간 [stepIndex] 의 정류장 위치 (탄 정류장 다음부터 내릴 정류장까지, 노선 시작부터의 거리 m).
     * 네트워크로 나중에 받아 오므로 안내 도중에 넣을 수 있다.
     */
    fun setStops(stepIndex: Int, along: List<Double>) {
        if (stepIndex !in steps.indices || !steps[stepIndex].ride || along.isEmpty()) return
        stopAlong[stepIndex] = along.sorted().toDoubleArray()
    }

    /** 노선 이름 같은 정보가 없는 예전 형식 구간 */
    private fun legacySay(p: RouteProgress): Say {
        val cur = steps[p.stepIndex]
        val next = steps.getOrNull(p.stepIndex + 1)?.text?.ifBlank { null } ?: "도착"
        val guide = buildString {
            append(Format.distance(p.toStepEndM)).append(" 후 ").append(next)
            if (cur.text.isNotBlank()) append("\n").append(cur.text)
        }
        return Say(guide, next, cur.text.ifBlank { null }, p.toStepEndM)
    }

    private fun initialSay(): Say? {
        val s0 = steps.firstOrNull() ?: return null
        if (s0.turnGuidance) {
            val p = Maneuvers.parse(s0.text)
            val sub = p.to?.let { "$it 방향" } ?: p.road
            return Say(if (sub == null) "출발" else "출발\n$sub", "출발", sub, null)
        }
        if (s0.ride) return if (s0.line != null) boardSay(s0, 0) else s0.text.ifBlank { null }?.let { Say(it, it, null, null) }
        val next = steps.getOrNull(1)
        if (next != null && next.ride && next.line != null) return boardSay(next, stepLen[0].toInt())
        return s0.text.ifBlank { null }?.let { Say(it, it, null, null) }
    }

    private fun initialIcon(): NavIcon {
        val s0 = steps.firstOrNull() ?: return NavIcon.WALK
        if (s0.ride) return vehicleIcon(s0)
        val next = steps.getOrNull(1)
        if (next != null && next.ride) return vehicleIcon(next)
        return NavIcon.WALK
    }

    private fun vehicleIcon(s: GuideStep) = if (s.subway) NavIcon.SUBWAY else NavIcon.BUS

    // ───────── 계산 도우미 ─────────

    /** from 다음으로 처음 나오는 '할 일'(직진이 아닌 것)의 구간 번호, 없으면 -1 (도착) */
    private fun nextAction(from: Int): Int {
        for (k in from + 1 until steps.size) {
            val m = maneuvers[k] ?: return -1     // 회전 안내가 없는 구간(대중교통)에서 멈춤
            if (m.action != Action.STRAIGHT) return k
        }
        return -1
    }

    /** 지금 위치에서 구간 k 시작점까지 경로 거리 (k = -1 이면 도착까지) */
    private fun distanceTo(p: RouteProgress, k: Int): Int {
        if (k < 0) return p.remainingM
        var d = p.toStepEndM.toDouble()
        for (i in p.stepIndex + 1 until k) d += stepLen[i]
        return d.toInt()
    }

    /** 구간 끝(갈림길)을 들어오던 방향으로 지나쳐 앞에 있는지 */
    private fun beyondStepEnd(c: Coord, i: Int): Boolean {
        val pts = steps[i].points
        if (pts.size < 2) return false
        val end = pts.last()
        val before = pts[pts.size - 2]
        val dir = Geo.bearingDeg(before, end)
        val toMe = Geo.bearingDeg(end, c)
        val along = Geo.distanceM(end, c) * cos(Math.toRadians(toMe - dir))
        return along > OVERSHOOT_ALONG_M
    }

    /** 지하철역에 도착해 기다리는 중인지 (다음 구간이 지하철이고 역에 닿은 적 있음) */
    private fun waitingForSubway(i: Int): Boolean {
        val next = steps.getOrNull(i + 1) ?: return false
        return !steps[i].ride && next.ride && next.subway && (i + 1) in atStop
    }

    private fun maxSpeed(step: GuideStep): Double = when {
        step.ride -> RIDE_MAX_MPS
        bike -> BIKE_MAX_MPS
        else -> WALK_MAX_MPS
    }

    private fun updateSpeed(c: Coord, elapsedMs: Long?) {
        if (elapsedMs == null) return
        val pc = lastRaw
        val pt = lastRawAt
        if (pc != null && pt != null && elapsedMs > pt) {
            val dt = (elapsedMs - pt) / 1000.0
            if (dt in 0.5..120.0) speedMps = Geo.distanceM(pc, c) / dt
        }
        lastRaw = c
        lastRawAt = elapsedMs
    }

    private fun eventFor(a: Action): NavEvent = when {
        a.isLeft -> NavEvent.TURN_SOON_LEFT
        a.isRight -> NavEvent.TURN_SOON_RIGHT
        a == Action.UTURN -> NavEvent.UTURN_SOON
        else -> NavEvent.ACTION_SOON
    }

    companion object {
        const val ARRIVE_M = 25
        private const val ARRIVE_END_OFF_M = 30
        /** 목적지 좌표 근처라도 경로상 이만큼 넘게 남았으면 도착 아님 (길 건너편 등) */
        private const val ARRIVE_REMAINING_M = 80
        const val OFF_ROUTE_M = 50
        /** 경로에 한 번도 오르지 않았을 때(실내에서 출발 등)는 훨씬 멀어야 이탈로 본다 */
        private const val NOT_MATCHED_OFF_ROUTE_M = 150f
        /** 갈림길에서 꺾지 않고 이만큼(+정확도) 지나치면 이탈 */
        private const val OVERSHOOT_M = 25f
        private const val OVERSHOOT_ALONG_M = 15.0
        private const val OVERSHOOT_ACCURACY_M = 20f
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
        /** 정류장에서 노선을 따라 이만큼 가야 '탔다' */
        private const val BOARD_PROGRESS_M = 60.0
        const val BOARD_SOON_M = 30
        /** 탄 구간 끝을 지나 이만큼 멀어지면 '정류장을 지남' */
        private const val PASSED_STOP_M = 150
        private const val PASSED_END_M = 30
        private const val ALIGHTED_OFF_M = 30
        private const val ALIGHTED_MAX_OFF_M = 100
        /** 이만큼 앞에 있는 정류장부터 '남은 정류장'으로 센다 (정류장에 서 있을 땐 그 정류장은 뺌) */
        private const val STOP_AHEAD_M = 15.0
        private const val FAR_JUMP_CONFIRM = 3
        private const val VEHICLE_MPS = 3.0
        private const val WALKING_MAX_MPS = 2.5
        private const val WALK_MAX_MPS = 4.0
        private const val BIKE_MAX_MPS = 15.0
        private const val RIDE_MAX_MPS = 35.0
        private const val JUMP_SLACK_M = 15f
        const val WALK_SOON_M = 35
        const val BIKE_SOON_M = 70
        private const val WALK_NOW_M = 12
        private const val BIKE_NOW_M = 20
        private const val THEN_WINDOW_M = 50
        private const val THEN_GAP_M = 25.0
        private const val UNKNOWN_ACCURACY_M = 45f
        private const val MAX_USABLE_ACCURACY_M = 50f
        private const val SUBWAY_MAX_USABLE_ACCURACY_M = 400f
        private const val DECISION_ACCURACY_M = 30f
        private const val SUBWAY_DECISION_ACCURACY_M = 300f
        private const val ALERT_ACCURACY_M = 40f
        private const val FINISH_AWAY_M = 300
        const val FINISH_AFTER_MS = 5 * 60_000L
        const val ARRIVED_TEXT = "목적지에 도착했어요"
        const val NEAR_TEXT = "목적지 부근이에요"
        const val REROUTING_TEXT = "경로를 다시 찾는 중…"
        const val PASSED_TEXT = "정류장을 지났어요\n다음 정류장에서 내려 주세요"
        private const val REROUTING_TITLE = "새 경로 찾는 중"
    }
}
