package com.mywatch.kakaomap.nav

/**
 * 경로 밖에 있는 시간 두 가지 (순수 계산 — 단위 테스트로 검증):
 *  · lost: 경로 위가 아닌 모든 때(출발 전 아직 경로에 못 오른 때 포함) — 위치를 덜 자주 받는 데 쓴다
 *  · off: 경로를 '벗어난' 때부터(이탈·정류장 지나침). 재탐색한 새 경로에 아직 못 올라도 이어서 센다 — 30분이면 안내를 끝냄
 * 경로 위로 돌아오거나 도착하면 둘 다 처음부터.
 */
class LostClock {
    private var lostSince: Long? = null
    private var offSince: Long? = null

    /** [now] = 부팅 후 ms (SystemClock.elapsedRealtime) */
    fun update(s: NavState, now: Long) {
        val onRoute = s.arrived || (s.matched && !s.offRoute && !s.passedStop)
        if (onRoute) {
            reset()
            return
        }
        if (lostSince == null) lostSince = now
        if ((s.offRoute || s.passedStop) && offSince == null) offSince = now
    }

    fun reset() {
        lostSince = null
        offSince = null
    }

    fun sinceLost(now: Long): Long = lostSince?.let { now - it } ?: 0L

    /** 경로를 벗어난 채 30분 → 안내를 스스로 끝낸다 (버스·지하철 타는 중, 도착 뒤는 제외) */
    fun shouldGiveUp(s: NavState, now: Long): Boolean {
        val off = offSince ?: return false
        return !s.arrived && !s.onRide && now - off >= LocationPolicy.GIVE_UP_MS
    }

    /**
     * 위치가 안 들어와도 다시 판단해야 하는 다음 시각(저전력 위치로 바꿀 때, 안내를 끝낼 때). 없으면 null.
     * 멈춰 있으면 위치가 거의 안 들어오므로 서비스가 이 시각에 타이머로 다시 판단한다.
     */
    fun nextDeadline(now: Long): Long? = listOfNotNull(
        lostSince?.let { it + LocationPolicy.LOST_LONG_MS + 1_000 },
        offSince?.let { it + LocationPolicy.GIVE_UP_MS },
    ).filter { it > now }.minOrNull()
}
