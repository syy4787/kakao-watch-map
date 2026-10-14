package com.mywatch.kakaomap.nav

import com.mywatch.kakaomap.model.Action
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.LiveBus
import com.mywatch.kakaomap.model.LongRide
import com.mywatch.kakaomap.model.Maneuver
import com.mywatch.kakaomap.model.Maneuvers
import com.mywatch.kakaomap.model.RouteProgress
import com.mywatch.kakaomap.model.TransitGuide
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
    /** 타려는 시내 버스가 2분 안에 옴 (실시간 도착 — 정류장에 있거나 150m 안일 때 한 번) */
    BUS_ARRIVING,
    /** 내릴 곳이 가까움 */
    ALIGHT_SOON,
    /** 내릴 정류장을 지나침 */
    PASSED_STOP,
    /** 타려던 기차·고속버스가 떠남 (출발 3분이 지나도록 타지 않음) */
    MISSED_RIDE,
    OFF_ROUTE,
    ARRIVED,
    REROUTED,
}

/** 배너 앞 아이콘 */
enum class NavIcon { ACTION, WALK, BUS, SUBWAY, TRAIN, FLAG, WARNING }

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
    /** 기차·고속·시외버스를 타고 가는 중 (내릴 역이 멀면 저전력 위치로) */
    val onLongRide: Boolean = false,
    /** 타려던 기차·고속버스를 놓침 → 다음 편으로 다시 찾기 */
    val missedRide: Boolean = false,
    /** 기차·고속버스 역(터미널)에서 기다리는 중 */
    val waitingLongRide: Boolean = false,
    /** 기다리는 중이면 출발 시각, 타는 중이면 도착 시각 (시간표, epoch ms) — 위치 간격을 시각으로도 정한다 */
    val longRideAtMs: Long? = null,
    /** 내릴 정류장을 지나침 → 다시 찾기 */
    val passedStop: Boolean = false,
    /** 도착하고 5분이 지났거나 300m 넘게 떠남 → 안내를 끝내도 됨 */
    val finished: Boolean = false,
    /** GPS 신호를 한동안 못 받음 */
    val signalLost: Boolean = false,
    /** 이번 여정의 다시 찾기를 다 씀 — 벗어나 있으면 배너로 알리고, 누르면 한 번 더 */
    val rerouteStopped: Boolean = false,
    /** 지도 배너: 오른쪽 굵은 글자 — 짧은 '할 일' (좌회전, 617번 승차, 곧 하차 …) */
    val bannerTitle: String? = null,
    /** 지도 배너: 오른쪽 둘째 줄 — 장소 하나 (역전약국 앞, 시청역 …) */
    val bannerSub: String? = null,
    /** 지도 배너: 왼쪽 아이콘 아래 거리 (없으면 숨김) */
    val bannerDistM: Int? = null,
    /** 타러 가거나 기다리는 탈 것 구간 (실시간 버스 도착을 물을 곳). 타는 중·도착 뒤엔 null */
    val boardingStep: Int? = null,
    /** 시내 버스 정류장에서 기다리는 중 (위치는 5초 간격이면 충분 — 탔는지는 노선을 따라 60m 넘게 가야 안다) */
    val waitingBus: Boolean = false,
)

/**
 * 경로 안내 판단 — 안드로이드와 무관한 순수 계산이라 시뮬레이션 테스트(가짜 GPS 재생)로 검증한다.
 * 위치가 들어올 때마다 현재 구간·다음에 할 일·남은 거리·이탈·도착·승하차 알림을 정한다.
 *
 * GPS 는 서 있어도 몇 m 씩 흔들리고, 빌딩 사이에선 수십 m 튀므로 판단마다 '되돌림 방지'를 둔다:
 *  · 경로를 따라 갑자기 너무 멀리 앞서면(걷는 속도로 못 갈 거리) 두 번 연속일 때만 인정 — 튄 위치로 구간을 건너뛰지 않게
 *  · 구간은 앞으로만 넘어가며, 뒤로 가는 것은 경로를 따라 20m 넘게 되돌아간 게 두 번 연속일 때만 인정
 *  · 버스·지하철은 정류장에서 노선을 따라 60m 넘게 간 위치가 두 번 연속일 때 '탔다'고 본다 (기다리는 동안 흔들림 무시).
 *    시내 버스는 차 속도(8초 넘는 구간의 평균 3m/s 넘게)일 때만 — 버스 길을 따라 걸어서 정류장을 지나쳐도 탄 것이 아니다
 *  · 시내 버스에서 내린 것은 내릴 곳에서 40초 넘게 머물거나 버스가 온 길을 걸어서 되돌아갈 때 — 차 속도로 계속 가면
 *    내려서 걸을 길(같은 길)을 따라가도 버스 구간에 붙여 두어 '정류장 지남'을 알리고 '도착'으로 오인하지 않는다.
 *    내렸다고 본 뒤 정류장 300m 안에서 다시 차 속도로 멀어지면 되돌린다 (정류장에 오래 선 버스에서 안 내림)
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
    /** 지금 시각(epoch ms) — 기차·버스 시간표와 비교해 'n분 후 하차'·'놓침'을 정한다 (테스트는 가짜 시계) */
    private val clock: () -> Long = System::currentTimeMillis,
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
    /** 기차·버스 구간마다 내릴 역에 가장 가까웠던 직선거리 (지나침 판단) */
    private val closestToEnd = HashMap<Int, Double>()
    private var missedCount = 0

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

    // 시내 버스 타고 내리기 — 위치만으로는 버스 길과 걸을 길이 겹칠 때 헷갈려서 속도·머문 시간도 본다
    /** 최근 위치 자취 (부팅 후 ms, 좌표) — 8초 넘는 구간의 평균 속도로 차/걷기를 가른다 (1초 GPS 잡음에 덜 흔들림) */
    private val trail = ArrayDeque<Pair<Long, Coord>>()
    /** 이미 내린 시내 버스 구간 — 그 구간과 그 앞으로는 다시 찾지 않는다 */
    private var rideDone = -1
    /** '내렸다'고 본 버스 구간과 그 전의 [rideDone] (정류장 근처에서 차 속도로 다시 멀어지면 되돌린다) */
    private var gotOff = -1
    private var gotOffPrev = -1
    private var undoCount = 0
    /** 탄 버스의 내릴 곳 30m 안에 온 뒤 가장 가까웠던 남은 거리 / 거기서 거의 멈춰 있기 시작한 시각 */
    private var endClosest = Int.MAX_VALUE
    private var endSlowSince: Long? = null
    /** 실시간 버스 도착 (탈 것 구간 → 문구)과 2분 안 도착 진동을 한 구간 */
    private val live = HashMap<Int, LiveBus>()
    private val liveAlerted = HashSet<Int>()
    /** 마지막으로 보인 승차 안내 (탈 것 구간, 정류장까지 m) — 실시간 도착이 오면 그 문구만 다시 만든다 */
    private var lastBoard: Pair<Int, Int>? = null

    private var arrivedAt: Long? = null
    private var farAwayCount = 0
    /** 탈 것 둘 이상 앞 구간으로 건너뛴 위치가 이어지는지 (튄 위치 거르기) */
    private var farJumpStep = -1
    private var farJumpCount = 0

    var state = initialSay().let {
        NavState(
            routeVersion = routeVersion, guide = it?.guide, icon = initialIcon(), bannerTitle = it?.title, bannerSub = it?.sub,
            bannerDistM = it?.distM, boardingStep = initialBoarding(),
        )
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
        // 기차·고속버스를 타고 가는 중이거나 그 역(터미널)에서 기다리는 중이면 그 구간 번호
        val longIdx = longRideContext()
        // 역에서 기다리다 출발 2분 전부터는 기차 안의 거친 위치(기지국 1~2km)도 받는다 — 그래야 GPS 가 안 잡혀도 '탔다'를 안다
        val boarding = longIdx != null && !state.onRide && steps[longIdx].departAtMs?.let { clock() >= it - BOARD_EARLY_MS } == true
        val maxAcc = when {
            (state.onLongRide || boarding) && longIdx != null -> longMaxAccuracy(steps[longIdx])
            onSubway || longIdx != null -> SUBWAY_MAX_USABLE_ACCURACY_M   // 지하·큰 역 지붕 아래
            else -> MAX_USABLE_ACCURACY_M
        }
        if (acc > maxAcc) return emptyList()
        val precise = known && acc <= DECISION_ACCURACY_M
        updateSpeed(c, elapsedMs)
        // 놓쳤다고 알렸으면 새 경로(다음 편)가 올 때까지 그대로
        if (state.missedRide) return emptyList()

        val coarse = acc > MAX_USABLE_ACCURACY_M
        /** 8초 넘는 구간의 평균 속도 (모르면 null — 시각 없는 위치) */
        val v = steadySpeed()
        var p: RouteProgress
        if (state.onLongRide) {
            // ── 기차·고속버스 타는 중: 그 구간에 붙여 둔다. 선로가 역과 역을 이은 직선에서 수 km 벗어나 걸어온 길·내려서 갈 길 쪽이
            //    더 가깝게 잡혀도 내리지 않은 것 — 다음 구간은 내릴 역 1km 안에서 걷는 속도(또는 멈춤)일 때만
            val ri = state.stepIndex
            p = rideProgress(c, ri)
            val nearEnd = Geo.distanceM(c, steps[ri].points.last()) <= LONG_EXIT_M + min(acc, LONG_EXIT_M.toFloat())
            if (ri < steps.lastIndex && nearEnd && (speedMps ?: 0.0) < WALKING_MAX_MPS) {
                RouteProgress.locate(c, steps, fromSeg = segStart[ri])?.let { p = it }
            }
        } else {
            // 버스는 거꾸로 가지 않는다: 이미 내린 버스 구간과 그 앞, 지금 탄 버스 구간 앞(걸어온 길)으로는 다시 찾지 않는다
            val floor = max(if (rideDone >= 0) segStart[rideDone + 1] else 0, cityRide()?.let { segStart[it] } ?: 0)
            var searchFrom = if (state.offRoute) floor else max((lastSeg - BACK_SEGS).coerceAtLeast(0), floor)
            // 정류장에서 기다리다 차 속도로 움직임 → 버스가 떠남: 걸어온 길(버스가 되짚어 가는 길 옆)이 아니라 그 버스 구간으로
            waitingCityRide()?.let { k -> if (v != null && v > VEHICLE_MPS) searchFrom = max(searchFrom, segStart[k]) }
            p = RouteProgress.locate(c, steps, fromSeg = searchFrom) ?: return emptyList()
            // 지하철·기차 안과 승강장의 거친 위치(기지국)로는 지금 구간(또는 기다리는 탈 것) 밖으로 옮겨 가지 않는다
            if ((onSubway || longIdx != null) && coarse && p.stepIndex != state.stepIndex &&
                !(steps[p.stepIndex].ride && p.stepIndex in atStop)
            ) return emptyList()
            // ── 기차·버스 역에서 기다리다 탐: 역에서 차 속도로 1.5km(버스 0.8km) 넘게 멀어짐 — 선로가 직선과 달라
            //    역까지 걸어온 길 쪽이 더 가깝게 잡혀도 그 탈 것 구간으로 본다
            if (longIdx != null && (speedMps ?: 0.0) > LONG_BOARD_MPS &&
                Geo.distanceM(c, steps[longIdx].points.first()) >= boardProgress(steps[longIdx]) + (if (coarse) acc else 0f)
            ) {
                p = rideProgress(c, longIdx)
            }
            // ── 시내 버스를 타고 가다 내릴 곳 너머 '도보' 구간 위로 잡혀도 아직 차 속도면 내리지 않은 것 — 버스 구간에 붙여 둔다
            //    (그래야 정류장 지남을 알고, 내려서 걸을 길을 따라 버스가 계속 가도 '도착'으로 오인하지 않는다)
            //    같은 정류장에서 갈아탈 버스 길의 첫머리(60m 안)에 잡혀도 아직 이 버스 — 내렸다고 볼 때까지(머묾·되돌아감) 여기에 둔다
            //    (정류장에서 흔들리는 위치로 '514번 환승'과 '514번 승차'가 번갈아 바뀌지 않게)
            cityRide()?.let { r ->
                val q = p
                val fastOnWalk = q.stepIndex > r && !steps[q.stepIndex].ride && v != null && v > VEHICLE_MPS
                val nextBusStart = q.stepIndex == r + 1 && steps[q.stepIndex].ride && stepLen[q.stepIndex] - q.toStepEndM < BOARD_PROGRESS_M
                if (fastOnWalk || nextBusStart) cityRideProgress(c, r)?.let { p = it }
            }
            // ── '내렸다'고 본 뒤 정류장 300m 안에서 차 속도로 멀어짐(다음 버스 길 위가 아니면) → 안 내린 것: 그 버스 구간으로 되돌린다
            val off = gotOff
            if (off >= 0 && rideDone == off && v != null && v > VEHICLE_MPS && known && !coarse &&
                Geo.distanceM(c, steps[off].points.last()) <= UNDO_M &&
                (!steps[p.stepIndex].ride || p.offRouteM > UNDO_OFF_M)
            ) {
                if (++undoCount >= CONFIRM_FIXES) {
                    rideDone = gotOffPrev
                    gotOff = -1
                    undoCount = 0
                    cityRideProgress(c, off)?.let { p = it }
                    maxStep = off
                    bestRemaining = p.remainingM
                    lastSeg = p.segIndex
                }
            } else {
                undoCount = 0
            }
        }

        // ── 타려던 기차·고속버스가 떠났는데 안 탐 → 놓침 (다음 편으로 다시 찾게). '탄 것 같은' 위치면 아래 승차 판단에 맡긴다
        missedCheck(c, p, acc)?.let { return it }

        // ── 이미 '정류장 지남'을 알린 탈 것의 끝 너머에 아직 있음 → 새 경로가 올 때까지 그 상태 유지 (신호 대기로 멈춰도)
        if (p.stepIndex in passedAlerted && p.toStepEndM <= PASSED_END_M && p.offRouteM > ALIGHTED_OFF_M) return emptyList()
        // ── 버스·지하철 끝(내릴 곳)에 와서 걷는 속도로 조금 벗어남 → 내려서 걷는 중: 다음 도보 구간 기준으로 다시 본다
        //    (정확한 위치일 때만, 100m 안에서만 — 정류장을 지나 신호 대기 중인 버스·지하의 기지국 위치와 구별)
        if (steps[p.stepIndex].ride && maxStep >= p.stepIndex && p.stepIndex < steps.lastIndex && known && !coarse &&
            p.toStepEndM <= PASSED_END_M && p.offRouteM in (ALIGHTED_OFF_M + 1)..ALIGHTED_MAX_OFF_M &&
            (speedMps ?: 0.0) < WALKING_MAX_MPS
        ) {
            if (isCityBus(p.stepIndex)) markGotOff(p.stepIndex)
            RouteProgress.locate(c, steps, fromSeg = segStart[p.stepIndex + 1])?.let { p = it }
        }
        // ── 시내 버스에서 내림: 내릴 곳 30m 안에 왔다가 (가) 40초 넘게 거의 멈춰 있거나 (나) 버스가 온 길을 걸어서 되돌아감
        //    → 그 버스 구간은 끝. 같은 정류장에서 갈아탈 버스면 그 버스 '승차' 안내로, 도보면 도보 구간으로
        cityRide()?.let { r ->
            if (p.stepIndex == r && r < steps.lastIndex && known && !coarse) {
                if (p.toStepEndM <= PASSED_END_M) endClosest = min(endClosest, p.toStepEndM)
                val reached = endClosest <= PASSED_END_M
                endSlowSince = if (reached && v != null && v < STAND_MPS && p.toStepEndM <= PASSED_END_M) endSlowSince ?: elapsedMs else null
                val dwell = elapsedMs != null && endSlowSince?.let { elapsedMs - it >= ALIGHT_DWELL_MS } == true
                val back = reached && v != null && v < WALKING_MAX_MPS && p.toStepEndM >= endClosest + BACK_ALIGHT_M
                if (dwell || back) {
                    markGotOff(r)
                    lastSeg = segStart[r + 1]
                    RouteProgress.locate(c, steps, fromSeg = segStart[r + 1])?.let { p = it }
                }
            }
            // 내릴 곳 근처에서 위치가 다음 구간으로 넘어갔으면(내려서 걷기 시작 등) 그 버스 구간은 끝 —
            // 차 속도로 다시 멀어지면 되돌릴 수 있게 기억 (멀리서 튄 위치 하나로는 끝내지 않음)
            if (p.stepIndex > r && Geo.distanceM(c, steps[r].points.last()) <= GOT_OFF_NEAR_M) markGotOff(r)
        }
        val step = steps[p.stepIndex]

        // ── 도착: 마지막 구간(또는 80m 이내 남음)에서 목적지 근처이거나, 경로 끝에 다다름 — 두 번 연속 확인
        val nearDestination = Geo.distanceM(c, destination) <= ARRIVE_M + min(acc, 15f) &&
            (p.stepIndex == steps.lastIndex || p.remainingM <= ARRIVE_REMAINING_M)
        val atRouteEnd = p.remainingM <= ARRIVE_M && p.offRouteM <= ARRIVE_END_OFF_M
        // 버스를 타고 차 속도로 지나가는 중엔 '도착'이 아니다 (내릴 곳을 지나쳐 목적지 앞을 지나는 버스)
        val ridingFast = v != null && v > VEHICLE_MPS &&
            (cityRide() != null || (gotOff >= 0 && Geo.distanceM(c, steps[gotOff].points.last()) <= UNDO_M))
        if (precise && (nearDestination || atRouteEnd) && !ridingFast) {
            if (++arriveCount >= CONFIRM_FIXES) {
                arrivedAt = elapsedMs
                state = state.copy(
                    stepIndex = p.stepIndex, remainingM = 0, arrived = true, offRoute = false, rerouting = false,
                    toStepEndM = 0, toActionM = 0, onRide = false, onSubway = false, onLongRide = false, action = null,
                    icon = NavIcon.FLAG, passedStop = false, waitingLongRide = false, longRideAtMs = null,
                    boardingStep = null, waitingBus = false,
                ).with(Say(ARRIVED_TEXT, "도착했어요", destinationName, null))
                return listOf(NavEvent.ARRIVED)
            }
        } else {
            arriveCount = 0
        }

        if (state.onRide && state.onLongRide) {
            // ── 기차·고속버스: 내릴 역을 지나쳐 계속 감 — 역에 가장 가까웠던 때보다 기차 4km·버스 2km 넘게 차 속도로 멀어짐.
            //    선로·도로가 직선과 달라 '구간 끝 너머'로는 판단하지 않는다 (굽은 선로를 지나침으로 오인)
            longPassedCheck(c, acc)?.let { return it }
        } else {
            // ── 내릴 정류장 지나침: 탄 구간의 끝을 지나 차 속도로 계속 멀어짐
            val alightAcc = if (step.subway) SUBWAY_DECISION_ACCURACY_M else DECISION_ACCURACY_M
            if (step.ride && step.longRide == null && maxStep >= p.stepIndex && p.toStepEndM <= PASSED_END_M &&
                p.offRouteM > PASSED_STOP_M + acc && acc <= alightAcc && (speedMps ?: 0.0) > VEHICLE_MPS
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
            if (step.longRide != null) {
                // 기차·버스: 역에서 직선으로 1.5km(버스 0.8km) 넘게 가야 '탔다' (큰 역 안을 걸어 다니는 것과 구별),
                // 그리고 출발 2분 전부터만. 그 전에 그 노선(직선) 쪽에서 위치가 잡히면 아직 역으로 오는 중 — 역 200m 안이면 '역에 닿음'
                val d = Geo.distanceM(c, step.points.first())
                val departed = step.departAtMs?.let { clock() >= it - BOARD_EARLY_MS } ?: true
                val atStation = d <= LONG_AT_STATION_M + min(acc, LONG_AT_STATION_M.toFloat())
                if (!departed || d < boardProgress(step) + (if (coarse) acc else 0f)) {
                    boardCount = 0
                    // 역에 한 번 닿았으면 승강장이 역 좌표에서 수백 m 떨어져 있어도 그대로 '기다리는 중'
                    return if (atStation || p.stepIndex in atStop) holdAtStop(p) else approachStation(p.stepIndex, c)
                }
            } else {
                val progress = stepLen[p.stepIndex] - p.toStepEndM
                // 걷는 속도로 버스 길을 따라 정류장을 지나쳐 가는 것은 탄 것이 아니다 (400m 넘게 갔으면 그냥 인정)
                val walkingPast = isCityBus(p.stepIndex) && v != null && v < BOARD_MIN_MPS && progress < BOARD_WALK_LIMIT_M
                if (progress < BOARD_PROGRESS_M + (if (coarse) acc else 0f) || walkingPast) {
                    boardCount = 0
                    return holdAtStop(p)
                }
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

        return guideAndEvents(p, acc, c)
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
            onSubway = ride.subway, onLongRide = false,
            // 기차·고속버스 역에서 기다리는 중: 출발 3분 전까지는 저전력 위치로
            waitingLongRide = ride.longRide != null, longRideAtMs = if (ride.longRide != null) ride.departAtMs else null,
            action = null, icon = vehicleIcon(ride), passedStop = false,
            boardingStep = rideIdx, waitingBus = isCityBus(rideIdx),
        ).with(boardSay(rideIdx, 0))
        return events
    }

    private fun guideAndEvents(p: RouteProgress, acc: Float, c: Coord): List<NavEvent> {
        val events = ArrayList<NavEvent>(1)
        val cur = steps[p.stepIndex]
        val waitingLong = !cur.ride && steps.getOrNull(p.stepIndex + 1)?.let { it.longRide != null } == true &&
            (p.stepIndex + 1) in atStop
        var action: Action? = null
        var toAction = p.toStepEndM
        val icon: NavIcon
        val say: Say
        val long = cur.longRide
        if (cur.ride && long != null) {
            // ── 기차·고속버스 타는 중: 하차 알림은 내릴 역까지 '직선거리'로 (기차 4km·버스 2km 안, 두 번 연속 확인되면 한 번).
            //    선로를 몰라 역과 역을 직선으로 이었으므로 노선을 따라 남은 거리는 쓰지 않는다
            val train = long == LongRide.TRAIN
            val d = Geo.distanceM(c, cur.points.last()).toInt()
            val fromStart = Geo.distanceM(c, cur.points.first())
            if (fromStart >= min(LONG_ARM_M, stepLen[p.stepIndex] * 0.3)) alightArmed += p.stepIndex
            toAction = d
            if (p.stepIndex in alightArmed && d <= (if (train) TRAIN_ALIGHT_M else COACH_ALIGHT_M)) {
                say = Say(
                    (cur.alight?.let { "곧 하차 · $it" } ?: "곧 하차") + "\n${Format.distance(d)} 남음",
                    "곧 하차", cur.alight, d,
                )
                val alightAcc = if (train) TRAIN_DECISION_ACCURACY_M else COACH_DECISION_ACCURACY_M
                if (acc <= alightAcc && ++alightCount >= CONFIRM_FIXES && alightAlerted.add(p.stepIndex)) {
                    events += NavEvent.ALIGHT_SOON
                }
            } else {
                alightCount = 0
                say = longRideSay(cur, d)
            }
            icon = vehicleIcon(cur)
        } else if (cur.ride) {
            // ── 하차 알림: 탄 뒤 어느 정도 가고 나서, 다음 정류장이 내릴 곳이면(정류장 정보가 없으면 남은 거리로)
            //    두 번 연속 확인되면 한 번
            val len = stepLen[p.stepIndex]
            val progress = len - p.toStepEndM
            val limit = min((if (cur.subway) SUBWAY_ALIGHT_M else BUS_ALIGHT_M).toDouble(), len * 0.5)
            if (progress >= min(ARM_PROGRESS_M, len * 0.3)) alightArmed += p.stepIndex
            val left = stopAlong[p.stepIndex]?.count { it > progress + STOP_AHEAD_M }
            val soon = p.stepIndex in alightArmed && (if (left != null) left <= 1 else p.toStepEndM <= limit)
            if (soon) {
                // 같은 정류장에서 갈아탈 버스가 있으면 내릴 곳에서 카카오맵처럼 "514번 환승"
                val transfer = steps.getOrNull(p.stepIndex + 1)?.takeIf { it.ride }?.line
                val atStopNow = if (left != null) left == 0 else p.toStepEndM <= PASSED_END_M
                val title = when {
                    transfer != null && atStopNow -> "$transfer 환승"
                    left == null -> "곧 하차"
                    left == 1 -> "다음 정류장 하차"
                    else -> "이번 정류장 하차"
                }
                val first = if (transfer != null && atStopNow) "$transfer 환승" + (cur.alight?.let { " · $it" } ?: "")
                else cur.alight?.let { "곧 하차 · $it" } ?: "곧 하차"
                say = Say(first + "\n${Format.distance(p.toStepEndM)} 남음", title, cur.alight, p.toStepEndM)
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
                // ── 정류장·역으로 걷는 중 (기차역·터미널은 커서 200m 안이면 닿은 것)
                if (p.toStepEndM <= (if (next.longRide != null) LONG_AT_STATION_M.toInt() else nowM)) atStop += p.stepIndex + 1
                if (p.toStepEndM <= BOARD_SOON_M && boardAlerted.add(p.stepIndex + 1)) events += NavEvent.BOARD_SOON
                // 정류장·역에 닿은 뒤엔 흔들리는 위치(지하 기지국 위치 포함)로 '120m 후 승차'처럼 바뀌지 않게
                val coarse = acc > MAX_USABLE_ACCURACY_M
                val d = if (p.stepIndex + 1 in atStop && (p.toStepEndM <= BOARD_SOON_M || coarse)) 0 else p.toStepEndM
                say = if (next.line != null) boardSay(p.stepIndex + 1, d) else legacySay(p)
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
            onLongRide = cur.ride && long != null,
            waitingLongRide = waitingLong,
            longRideAtMs = when {
                cur.ride && long != null -> cur.arriveAtMs
                waitingLong -> steps[p.stepIndex + 1].departAtMs
                else -> null
            },
            action = action,
            icon = if (state.rerouting) NavIcon.WARNING else icon,
            passedStop = false,
            boardingStep = if (!cur.ride && steps.getOrNull(p.stepIndex + 1)?.ride == true) p.stepIndex + 1 else null,
            waitingBus = false,
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

    /** 다시 찾기를 다 씀 / 한 번 더 함 */
    fun setRerouteStopped(stopped: Boolean) {
        if (state.rerouteStopped != stopped) state = state.copy(rerouteStopped = stopped)
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

    private fun boardSay(idx: Int, d: Int): Say {
        val ride = steps[idx]
        lastBoard = idx to d
        val name = ride.line ?: (if (ride.subway) "지하철" else "버스")
        val near = d <= nowM
        val departs = ride.departAtMs
        if (ride.longRide != null && departs != null) {
            // 기차·고속버스: 출발 시각이 가장 중요 — "KTX 105 승차 / 09:58 출발", 알림엔 "(12분 후)"까지
            val time = "${TransitGuide.clock(departs)} 출발"
            val left = ceil((departs - clock()) / 60_000.0).toInt()
            val wait = if (left > 0) " (${left}분 후)" else ""
            // 알림·워치페이스엔 첫 줄만 보이므로: 역에선 "KTX 105 09:58 출발 (12분 후)", 가는 중엔 "1.2km 후 KTX 105 승차"
            val main = if (near) "$name $time$wait" + (ride.board?.let { "\n${it}에서 승차" } ?: "")
            else "${Format.distance(d)} 후 $name 승차\n" + listOfNotNull(ride.board, time + wait).joinToString(" ")
            return Say(main, "$name 승차", if (near) time else ride.board ?: time, if (near) null else d)
        }
        // 시내 버스: 실시간 도착을 붙인다 (카카오맵 길안내처럼) — 정류장에선 둘째 줄에 도착만, 가는 중엔 정류장 이름과 함께
        val bus = live[idx]?.short
        val main = (if (near) "$name 승차" else "${Format.distance(d)} 후 $name 승차") + (bus?.let { " · $it" } ?: "")
        val sub = if (near) bus ?: ride.board else listOfNotNull(ride.board, bus).joinToString(" · ").ifEmpty { null }
        return Say(main + (ride.board?.let { "\n$it" } ?: ""), "$name 승차", sub, if (near) null else d)
    }

    /**
     * 타려는 시내 버스 구간 [stepIndex] 의 실시간 도착 (안내 중 45초마다 받아 넣는다, 못 받았으면 null).
     * 지금 그 버스 승차 안내 중이면 문구를 바로 고치고, 2분 안에 오는데 정류장에 있거나 150m 안이면 한 번 진동
     */
    fun setLiveArrival(stepIndex: Int, bus: LiveBus?): List<NavEvent> {
        if (!isCityBus(stepIndex)) return emptyList()
        if (bus?.short == null) live.remove(stepIndex) else live[stepIndex] = bus
        val shown = lastBoard
        val showing = shown != null && shown.first == stepIndex && state.boardingStep == stepIndex && !state.onRide &&
            !state.arrived && !state.offRoute && !state.rerouting && !state.passedStop && !state.missedRide
        if (!showing) return emptyList()
        state = state.with(boardSay(stepIndex, shown!!.second))
        val sec = bus?.takeIf { it.short != null }?.soonestSec ?: return emptyList()
        val close = stepIndex in atStop || shown.second <= BUS_SOON_NEAR_M
        return if (sec in 0..BUS_SOON_SEC && close && liveAlerted.add(stepIndex)) listOf(NavEvent.BUS_ARRIVING) else emptyList()
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
        if (s0.ride) return if (s0.line != null) boardSay(0, 0) else s0.text.ifBlank { null }?.let { Say(it, it, null, null) }
        val next = steps.getOrNull(1)
        if (next != null && next.ride && next.line != null) return boardSay(1, stepLen[0].toInt())
        return s0.text.ifBlank { null }?.let { Say(it, it, null, null) }
    }

    /** 처음 타러 갈 탈 것 구간 (첫 구간이 탈 것이거나, 첫 도보 다음이 탈 것일 때) */
    private fun initialBoarding(): Int? {
        val s0 = steps.firstOrNull() ?: return null
        if (s0.ride) return 0
        return if (steps.getOrNull(1)?.ride == true) 1 else null
    }

    private fun initialIcon(): NavIcon {
        val s0 = steps.firstOrNull() ?: return NavIcon.WALK
        if (s0.ride) return vehicleIcon(s0)
        val next = steps.getOrNull(1)
        if (next != null && next.ride) return vehicleIcon(next)
        return NavIcon.WALK
    }

    private fun vehicleIcon(s: GuideStep) = when {
        s.subway -> NavIcon.SUBWAY
        s.longRide == LongRide.TRAIN -> NavIcon.TRAIN
        else -> NavIcon.BUS
    }

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
        step.longRide == LongRide.TRAIN -> TRAIN_MAX_MPS
        step.longRide == LongRide.COACH -> COACH_MAX_MPS
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
        trail.addLast(elapsedMs to c)
        while (trail.size > 1 && elapsedMs - trail.first().first > TRAIL_MS) trail.removeFirst()
    }

    /** 8초 넘게 떨어진 두 위치로 잰 평균 속도 m/s (모르면 null) */
    private fun steadySpeed(): Double? {
        val last = trail.lastOrNull() ?: return null
        val ref = trail.lastOrNull { last.first - it.first >= STEADY_WINDOW_MS } ?: return null
        return Geo.distanceM(ref.second, last.second) / ((last.first - ref.first) / 1000.0)
    }

    /** 시내 버스 구간인지 (지하철·기차·고속버스 제외) */
    private fun isCityBus(i: Int): Boolean = i in steps.indices && steps[i].ride && steps[i].longRide == null && !steps[i].subway

    /** 지금 타고 있는 시내 버스 구간 */
    private fun cityRide(): Int? = state.stepIndex.takeIf { state.onRide && isCityBus(it) }

    /** 정류장에 닿아 기다리던(아직 안 탄) 시내 버스 구간 */
    private fun waitingCityRide(): Int? =
        ((maxStep + 1).coerceAtLeast(0) until steps.size).firstOrNull { steps[it].ride }?.takeIf { it in atStop && isCityBus(it) }

    /** 버스 구간 [r] 위로만 본 진행 (그 뒤 구간은 남은 거리에만 더함) */
    private fun cityRideProgress(c: Coord, r: Int): RouteProgress? {
        val p = RouteProgress.locate(c, steps.take(r + 1), fromSeg = segStart[r]) ?: return null
        val rest = ((r + 1) until steps.size).sumOf { stepLen[it] }.toInt()
        return p.copy(remainingM = p.remainingM + rest)
    }

    /** 버스 구간 [r] 에서 내렸다 — 그 구간으로는 다시 찾지 않는다 (차 속도로 다시 멀어지면 되돌림) */
    private fun markGotOff(r: Int) {
        if (rideDone == r) return
        gotOffPrev = rideDone
        rideDone = r
        gotOff = r
        undoCount = 0
        endClosest = Int.MAX_VALUE
        endSlowSince = null
    }

    // ───────── 기차·고속버스 ─────────

    /** 기차·버스를 타고 가는 중이면 그 구간, 그 역(터미널)에 닿아 기다리는 중이면 그 구간 번호 (경로가 기차부터 시작해도) */
    private fun longRideContext(): Int? {
        if (state.onRide) return state.stepIndex.takeIf { it >= 0 && steps[it].longRide != null }
        return nextLongRide()?.takeIf { it in atStop }
    }

    /** 아직 타지 않은 다음 기차·버스 구간 (지나온 구간 뒤에서 처음) */
    private fun nextLongRide(): Int? = ((maxStep + 1).coerceAtLeast(0) until steps.size).firstOrNull { steps[it].longRide != null }

    /** '탔다'고 볼 만큼 탈 것 노선을 따라(기차·버스는 역에서 직선으로) 간 거리 */
    private fun boardProgress(step: GuideStep): Double = when (step.longRide) {
        LongRide.TRAIN -> TRAIN_BOARD_M
        LongRide.COACH -> COACH_BOARD_M
        null -> BOARD_PROGRESS_M
    }

    private fun longMaxAccuracy(step: GuideStep): Float =
        if (step.longRide == LongRide.TRAIN) TRAIN_MAX_USABLE_ACCURACY_M else COACH_MAX_USABLE_ACCURACY_M

    /** 위치를 기차·버스 구간 [i] (역과 역을 이은 직선) 위로만 본 진행 상황 */
    private fun rideProgress(c: Coord, i: Int): RouteProgress {
        val pts = steps[i].points
        val (off, t) = Geo.distanceToSegment(c, pts.first(), pts.last())
        val toEnd = stepLen[i] * (1 - t)
        var remaining = toEnd
        for (j in i + 1 until steps.size) remaining += stepLen[j]
        return RouteProgress(i, toEnd.toInt(), remaining.toInt(), off.toInt(), segStart[i])
    }

    /**
     * 아직 안 탄 다음 기차·버스가 떠났는데 안 탐 → 한 번만 알린다 (두 번 연속 확인).
     *  · 역(터미널)에 닿았던 적이 있거나 지금 역 400m 안이면 늦게 떠나는 수도 있어 기차 10분·버스 15분 기다린다
     *    (막혀서 터미널 앞을 기어가는 버스, 역 안에서 튀는 기지국 위치도 이 경우)
     *  · 아직 역에 닿지 못한 채 가는 중이면 3분 (늦어서 못 탐) — 단, 위치가 거칠면(400m 넘게) 단정하지 않는다
     *  · 그 노선 쪽에서 역과 멀어지고 있으면 탄 것 — 아래 승차 판단에 맡긴다
     */
    private fun missedCheck(c: Coord, p: RouteProgress, acc: Float): List<NavEvent>? {
        if (state.onLongRide) return null
        val k = nextLongRide() ?: return null
        val ride = steps[k]
        val departs = ride.departAtMs ?: return null
        val d = Geo.distanceM(c, ride.points.first())
        val near = d <= boardProgress(ride) + min(acc, MISSED_NEAR_SLACK_M)
        if (!near && p.stepIndex >= k) {
            missedCount = 0
            return null
        }
        val atStation = k in atStop || d <= 2 * LONG_AT_STATION_M + min(acc, LONG_AT_STATION_M.toFloat())
        if (!atStation && acc > SUBWAY_MAX_USABLE_ACCURACY_M) return null
        val grace = when {
            !atStation -> MISSED_AWAY_MS
            ride.longRide == LongRide.TRAIN -> TRAIN_MISSED_AT_STATION_MS
            else -> COACH_MISSED_AT_STATION_MS
        }
        if (clock() < departs + grace || ++missedCount < CONFIRM_FIXES) {
            if (clock() < departs + grace) missedCount = 0
            return null
        }
        val name = ride.line ?: (if (ride.longRide == LongRide.TRAIN) "기차" else "버스")
        state = state.copy(
            missedRide = true, icon = NavIcon.WARNING, action = null, onRide = false, onLongRide = false,
            waitingLongRide = false, longRideAtMs = null,
        ).with(Say("타려던 $name 이(가) 떠났어요\n다음 편을 찾아요", "$name 떠남", "다음 편 찾는 중", null))
        return listOf(NavEvent.MISSED_RIDE)
    }

    /** 기차·버스에서 내릴 역을 지나침: 역에 가장 가까웠던 거리보다 기차 4km·버스 2km 넘게 차 속도로 멀어짐 (두 번 확인) */
    private fun longPassedCheck(c: Coord, acc: Float): List<NavEvent>? {
        val i = state.stepIndex
        val ride = steps[i]
        val train = ride.longRide == LongRide.TRAIN
        val d = Geo.distanceM(c, ride.points.last())
        val best = min(closestToEnd[i] ?: Double.MAX_VALUE, d)
        closestToEnd[i] = best
        if (i in passedAlerted) return emptyList()
        val passed = best <= (if (train) TRAIN_NEAR_END_M else COACH_NEAR_END_M) &&
            d >= best + (if (train) TRAIN_PASSED_M else COACH_PASSED_M) &&
            acc <= (if (train) TRAIN_DECISION_ACCURACY_M else COACH_DECISION_ACCURACY_M) &&
            (speedMps ?: 0.0) > VEHICLE_MPS
        if (!passed) {
            passedCount = 0
            return null
        }
        if (++passedCount < CONFIRM_FIXES || !passedAlerted.add(i)) return emptyList()
        val text = if (train) "내릴 역을 지났어요\n다음 역에서 내려 주세요" else "내릴 터미널을 지났어요\n다음에 서는 곳에서 내려 주세요"
        state = state.copy(passedStop = true, icon = NavIcon.WARNING, action = null)
            .with(Say(text, if (train) "역 지남" else "터미널 지남", ride.alight?.let { "$it 지남" }, null))
        return listOf(NavEvent.PASSED_STOP)
    }

    /** 기차·버스 역으로 가는 중인데 위치가 그 노선(직선) 쪽에 잡힘 — 역까지 직선거리로 승차 안내 */
    private fun approachStation(k: Int, c: Coord): List<NavEvent> {
        val ride = steps[k]
        val d = Geo.distanceM(c, ride.points.first()).toInt()
        state = state.copy(
            stepIndex = (k - 1).coerceAtLeast(0), offRoute = false, matched = true, toStepEndM = d, toActionM = d,
            onRide = false, onSubway = false, onLongRide = false, waitingLongRide = false, longRideAtMs = null,
            action = null, icon = vehicleIcon(ride), passedStop = false, boardingStep = k, waitingBus = false,
        ).with(boardSay(k, d))
        return emptyList()
    }

    /**
     * 기차·고속버스 타는 중: "48분 후 하차 / 서울역 10:51" — 시간표 도착 시각 기준 (늦어지면 남은 거리·속도로).
     */
    private fun longRideSay(cur: GuideStep, d: Int): Say {
        val scheduled = cur.arriveAtMs?.let { ceil((it - clock()) / 60_000.0).toInt() }
        val bySpeed = speedMps?.takeIf { it > VEHICLE_MPS }?.let { ceil(d / it / 60.0).toInt() }
        val minutes = (scheduled?.takeIf { it > 0 } ?: bySpeed)?.coerceAtLeast(1)
        val name = cur.line ?: (if (cur.longRide == LongRide.TRAIN) "기차" else "버스")
        val title = minutes?.let { "${it}분 후 하차" } ?: "$name 타는 중"
        val sub = listOfNotNull(cur.alight, cur.arriveAtMs?.let { TransitGuide.clock(it) }).joinToString(" ").ifEmpty { null }
        // 알림·워치페이스의 첫 줄: "48분 후 하차 · 서울역"
        val first = listOfNotNull(minutes?.let { "${it}분 후 하차" } ?: "${Format.distance(d)} 후 하차", cur.alight).joinToString(" · ")
        val main = "$first\n${Format.distance(d)} 남음" + (cur.line?.let { " · $it" } ?: "")
        return Say(main, title, sub, d)
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
        /** 속도는 이만큼 넘게 떨어진 두 위치로 잰다 (1초 간격 GPS 잡음 σ 4m 면 바로 잰 속도는 ±5m/s 흔들림) */
        private const val STEADY_WINDOW_MS = 8_000L
        private const val TRAIL_MS = 40_000L
        /** 내릴 곳에서 이보다 느리게 이만큼 머물면 내린 것 (버스는 정류장에 보통 10~30초 선다) */
        private const val STAND_MPS = 2.0
        private const val ALIGHT_DWELL_MS = 40_000L
        /** 내릴 곳에서 버스가 온 길로 이만큼 걸어서 되돌아가면 내린 것 */
        private const val BACK_ALIGHT_M = 20
        /** 시내 버스는 이 속도(8초 평균, 시속 11km)를 넘어야 '탔다' — 버스 길을 따라 걷는 것과 구별 */
        private const val BOARD_MIN_MPS = 3.0
        private const val BOARD_WALK_LIMIT_M = 400.0
        /** '내렸다'고 본 뒤 정류장에서 이만큼 안에서 차 속도로 멀어지면 되돌린다 (다음 버스 길에서 이만큼 넘게 떨어져 있을 때) */
        private const val UNDO_M = 300.0
        /** 내릴 곳에서 이 안에서만 '다음 구간으로 넘어감 = 내림'으로 본다 */
        private const val GOT_OFF_NEAR_M = 150.0
        private const val UNDO_OFF_M = 40
        /** 실시간 도착: 이 안(초)에 오면 한 번 진동 — 정류장에 있거나 이만큼(m) 안일 때 */
        const val BUS_SOON_SEC = 120
        private const val BUS_SOON_NEAR_M = 150
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
        // ── 기차·고속버스 (역과 역을 직선으로 이은 구간)
        /** 기차 안에선 GPS 대신 기지국 위치(1~2km)도 받는다 */
        private const val TRAIN_MAX_USABLE_ACCURACY_M = 3_000f
        private const val COACH_MAX_USABLE_ACCURACY_M = 1_000f
        private const val TRAIN_DECISION_ACCURACY_M = 1_500f
        private const val COACH_DECISION_ACCURACY_M = 300f
        /** 하차 알림: 내릴 역까지 직선거리 (KTX 는 1분에 5km 가까이 가므로 넉넉히) */
        const val TRAIN_ALIGHT_M = 4_000
        const val COACH_ALIGHT_M = 2_000
        /** 역에서 직선으로 이만큼 가야 '탔다' (큰 역·터미널 안을 걸어 다니는 것과 구별) */
        private const val TRAIN_BOARD_M = 1_500.0
        private const val COACH_BOARD_M = 800.0
        /** 탄 뒤 이만큼(또는 구간의 30%) 가야 하차 알림 준비 */
        private const val LONG_ARM_M = 3_000.0
        /** 역에 이만큼 가까이 왔다가 */
        private const val TRAIN_NEAR_END_M = 2_500.0
        private const val COACH_NEAR_END_M = 1_000.0
        /** 이만큼 더 멀어지면 지나침 */
        private const val TRAIN_PASSED_M = 4_000.0
        private const val COACH_PASSED_M = 2_000.0
        /** 역에서 기다리다 이 속도(시속 29km)로 멀어지면 탄 것 */
        private const val LONG_BOARD_MPS = 8.0
        private const val TRAIN_MAX_MPS = 100.0
        private const val COACH_MAX_MPS = 40.0
        /** 출발 2분 전부터만 '탔다'로 본다 */
        private const val BOARD_EARLY_MS = 2 * 60_000L
        /** 놓침: 역(터미널)에서 떨어져 아직 가는 중이면 출발 3분 뒤 */
        const val MISSED_AWAY_MS = 3 * 60_000L
        /** 놓침: 역 근처에 있으면 늦게 떠날 수도 있어 기차는 10분, 버스는 15분 (막힌 길을 기어 나가는 버스) 뒤 */
        const val TRAIN_MISSED_AT_STATION_MS = 10 * 60_000L
        const val COACH_MISSED_AT_STATION_MS = 15 * 60_000L
        private const val MISSED_NEAR_SLACK_M = 500f
        /** 기차역·터미널은 커서 이 안이면 '닿음' */
        private const val LONG_AT_STATION_M = 200.0
        /** 내릴 역에서 이 안이어야 내려서 다음 구간으로 */
        private const val LONG_EXIT_M = 1_000.0
        const val ARRIVED_TEXT = "목적지에 도착했어요"
        const val NEAR_TEXT = "목적지 부근이에요"
        const val REROUTING_TEXT = "경로를 다시 찾는 중…"
        const val PASSED_TEXT = "정류장을 지났어요\n다음 정류장에서 내려 주세요"
        private const val REROUTING_TITLE = "새 경로 찾는 중"
    }
}
