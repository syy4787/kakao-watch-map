package com.mywatch.kakaomap.nav

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo

/**
 * 안내가 '멈춘' 시간 (순수 계산 — 단위 테스트로 검증). 경로 위에서 더 나아가지 않거나(목적지 건물에 옆문으로 들어감·
 * 경로 위 카페) 처음부터 경로에 못 오르거나, 걷는 안내에서 위치를 아예 못 받으면(위치 꺼짐·실내) GPS 를 계속 켜 둔 채
 * 끝나지 않았다(445mAh 에서 시간당 5~8%).
 *  · 진척(다음 구간으로 넘어감, 남은 거리가 30m 넘게 줄어듦) 없이 10분 → 멈춰 있으면 Wi-Fi·기지국 위치 ([LocationPolicy])
 *  · 진척 없이 1시간, 또는 경로에 오른 걷는 안내에서 쓸 만한 위치(50m 이내)를 30분 넘게 못 받음 → 안내를 스스로 끝냄
 *  · 목적지 60m 안에서 3분 넘게 진척이 없음 → 도착으로 보고 끝냄 (도착 판단은 정확도 30m 이내 위치 두 번이 필요해 실내에선 안 됨)
 * 버스·지하철·기차를 타는 동안은 오래 걸려도 정상이라 진척으로 본다. 타려고 기다리는 동안([NavState.waitingToBoard] —
 * 지하철 승강장(지하)·정류장 30m 안 포함)도 멈춤이 아니지만, 정류장 옆에서 '안내 시작'만 누르고 잊은 경우를 위해
 * 2시간 넘게 기다리기만 하면 끝낸다 (시간표가 있는 기차·고속버스는 빼고 — 놓치면 엔진이 알아챈다).
 * 벗어남·지나침·놓침은 [LostClock] 몫
 */
class StallClock {
    private var progressAt = 0L
    private var fixAt = 0L
    private var bestRemaining: Int? = null
    private var step = -1
    private var last: NavState? = null
    /** 타려고 기다리기 시작한 시각 (기다리는 중이 아니면 null) */
    private var waitingSince: Long? = null
    /** 기다리다 정류장을 벗어난 시각 — [WAIT_AWAY_MS] 넘게 계속 벗어나 있어야 기다림을 처음부터 센다 */
    private var awaySince: Long? = null

    /** 안내(새 여정)를 시작함. [now] = 부팅 후 ms */
    fun start(now: Long) {
        progressAt = now
        fixAt = now
        bestRemaining = null
        step = -1
        last = null
        waitingSince = null
        awaySince = null
    }

    /** 쓸 만한(정확도 50m 이내) 위치를 받음 */
    fun onUsableFix(now: Long) {
        fixAt = now
    }

    fun update(s: NavState, now: Long) {
        // 시작 뒤 처음이거나 다시 찾아 경로가 바뀜(구간 0 부터, 남은 거리도 새로) — 지난 경로의 기준과 비교하지 않는다
        val first = last?.routeVersion != s.routeVersion
        last = s
        trackWaiting(s, now, first)
        if (riding(s) || outOfRoute(s) || s.arrived) {
            progressAt = now
            bestRemaining = s.remainingM
            step = s.stepIndex
            return
        }
        // 시작 뒤 처음 받은 상태는 기준만 잡는다 (진척은 시작 시각부터 센다)
        if (first) {
            step = s.stepIndex
            bestRemaining = s.remainingM
            return
        }
        val remaining = s.remainingM
        val best = bestRemaining
        val progressed = s.stepIndex > step || (remaining != null && best != null && remaining <= best - PROGRESS_M)
        if (s.stepIndex > step) step = s.stepIndex
        if (progressed) progressAt = now
        if (remaining != null && (best == null || progressed)) bestRemaining = remaining
    }

    /** 언제부터 기다렸는지 — 타거나 도착하거나 경로가 바뀌면 끝, 정류장에서 GPS 가 잠깐 튄 것은 이어서 센다 */
    private fun trackWaiting(s: NavState, now: Long, first: Boolean) {
        when {
            s.onRide || s.arrived || first -> {
                waitingSince = if (s.waitingToBoard) now else null
                awaySince = null
            }
            s.waitingToBoard -> {
                if (waitingSince == null) waitingSince = now
                awaySince = null
            }
            waitingSince != null -> {
                // 5분 넘게 계속 정류장을 벗어나 있으면 정말로 떠난 것 — 다시 와서 기다리면 그때부터
                val away = awaySince ?: now.also { awaySince = it }
                if (now - away >= WAIT_AWAY_MS) {
                    waitingSince = null
                    awaySince = null
                }
            }
        }
    }

    /** 오래 기다리면 끝내는지 — 시간표가 있는 기차·고속버스는 끝내지 않는다 (놓치면 엔진이 알아채 다음 편으로 다시 찾음) */
    private fun waitCapped(s: NavState) = !(s.waitingLongRide && s.longRideAtMs != null)

    fun stalledMs(now: Long): Long {
        val s = last ?: return 0L
        if (riding(s) || outOfRoute(s) || s.arrived) return 0L
        return (now - progressAt).coerceAtLeast(0L)
    }

    /** 안내를 스스로 끝낼 때 */
    fun shouldGiveUp(s: NavState, now: Long): Boolean {
        if (s.waitingToBoard) return waitCapped(s) && waitingSince?.let { now - it >= WAIT_GIVE_UP_MS } == true
        if (riding(s) || outOfRoute(s) || s.arrived) return false
        if (now - progressAt >= GIVE_UP_MS) return true
        return s.matched && now - fixAt >= NO_FIX_MS
    }

    /** [shouldGiveUp] 이 참일 때 그 까닭이 '쓸 만한 위치를 30분 못 받음'인지 (아니면 진척 없음·오래 기다림) — 알림 문구용 */
    fun noFix(s: NavState, now: Long): Boolean =
        !riding(s) && !outOfRoute(s) && !s.arrived && s.matched && now - progressAt < GIVE_UP_MS

    /** 목적지 바로 옆에서 멈춰 있음 → 도착으로 본다 */
    fun arrivedNearby(s: NavState, lastFix: Coord?, destination: Coord, now: Long): Boolean {
        if (lastFix == null || riding(s) || outOfRoute(s) || s.arrived) return false
        return now - progressAt >= NEAR_STOP_MS && Geo.distanceM(lastFix, destination) <= NEAR_M
    }

    /** 위치가 안 들어와도 다시 판단할 다음 시각 (부팅 후 ms), 없으면 null */
    fun nextDeadline(now: Long): Long? {
        val s = last ?: return null
        if (s.waitingToBoard) {
            if (!waitCapped(s)) return null
            return waitingSince?.let { it + WAIT_GIVE_UP_MS + 1_000 }?.takeIf { it > now }
        }
        if (riding(s) || outOfRoute(s) || s.arrived) return null
        return listOfNotNull(
            (progressAt + NEAR_STOP_MS + 1_000).takeIf { (s.remainingM ?: Int.MAX_VALUE) <= NEAR_CHECK_REMAINING_M },
            progressAt + LocationPolicy.STALL_LOW_POWER_MS + 1_000,
            (fixAt + NO_FIX_MS + 1_000).takeIf { s.matched },
            progressAt + GIVE_UP_MS + 1_000,
        ).filter { it > now }.minOrNull()
    }

    /** 타고 가거나 타려고 기다리는 중(또는 다시 찾는 중) — 진척이 없어도 멈춤이 아니다 */
    private fun riding(s: NavState) = s.onRide || s.waitingToBoard || s.rerouting

    private fun outOfRoute(s: NavState) = s.offRoute || s.passedStop || s.missedRide

    companion object {
        /** 이만큼 넘게 남은 거리가 줄어야 진척 (GPS 는 서 있어도 몇 m 씩 흔들림) */
        const val PROGRESS_M = 30
        const val GIVE_UP_MS = 60 * 60_000L
        /** 타지 않고 이만큼 기다리기만 하면 끝냄 (버스 간격이 길어도·지하철을 탄 걸 지하에서 못 알아채도 충분히) */
        const val WAIT_GIVE_UP_MS = 2 * 60 * 60_000L
        /** 기다리다 이만큼 넘게 계속 정류장을 벗어나 있어야 정말로 떠난 것 (GPS 가 한 번 튄 것과 구별) */
        private const val WAIT_AWAY_MS = 5 * 60_000L
        const val NO_FIX_MS = 30 * 60_000L
        const val NEAR_STOP_MS = 3 * 60_000L
        const val NEAR_M = 60.0
        /** 남은 거리가 이 안일 때만 '도착인지' 보러 깨운다 */
        private const val NEAR_CHECK_REMAINING_M = 200
    }
}
