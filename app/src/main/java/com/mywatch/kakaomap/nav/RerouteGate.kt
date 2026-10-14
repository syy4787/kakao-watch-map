package com.mywatch.kakaomap.nav

/**
 * 경로를 벗어났을 때 다시 찾기를 언제 또 할지 (순수 계산 — 단위 테스트로 검증).
 *  · 다시 찾을수록 간격을 30초 → 1분 → 2분 → 4분으로 늘리고
 *  · 새 경로를 따라 실제로 나아가야(남은 거리 100m 줄거나 2분 넘게 경로 위) 처음(30초)으로 되돌린다 —
 *    새 경로는 늘 지금 위치에서 시작해 곧바로 '경로 위'가 되므로, 그것만으로 되돌리면 옆길로 가는 동안 30초마다
 *    '이탈' 진동 + 다시 찾기 + 1초 GPS 가 되풀이됐다
 *  · 한 여정에 [MAX_PER_TRIP] 번까지 (경로 API 무료 사용량) — 다 쓰면 [exhausted], 사용자가 누르면 [manual] 로 한 번 더
 */
class RerouteGate {
    private var count = 0
    private var withoutProgress = 0
    private var lastAt = Long.MIN_VALUE / 2
    /** 마지막으로 다시 찾은 때 (그 뒤 진척을 확인하는 중이면) */
    private var since: Long? = null
    private var remainingThen: Int? = null

    val exhausted: Boolean get() = count >= MAX_PER_TRIP

    fun newTrip() {
        count = 0
        withoutProgress = 0
        lastAt = Long.MIN_VALUE / 2
        since = null
        remainingThen = null
    }

    /** 지금 다음 다시 찾기까지 기다릴 간격 */
    fun interval(): Long = BASE_MS shl withoutProgress.coerceAtMost(3)

    fun canReroute(now: Long): Boolean = !exhausted && now - lastAt >= interval()

    fun rerouted(now: Long) {
        lastAt = now
        count++
        withoutProgress++
        since = now
        remainingThen = null
    }

    /** 경로 위에 있음 — 다시 찾은 뒤 실제로 나아갔으면 간격을 처음으로 */
    fun onRoute(s: NavState, now: Long) {
        val t = since
        if (t == null) {
            withoutProgress = 0
            return
        }
        val then = remainingThen
        if (then == null) {
            remainingThen = s.remainingM
            return
        }
        val now2 = s.remainingM
        val moved = now2 != null && then - now2 >= PROGRESS_M
        if (moved || now - t >= PROGRESS_MS) {
            withoutProgress = 0
            since = null
            remainingThen = null
        }
    }

    /** 사용자가 '다시 찾기'를 누름 — 지금 바로 한 번 더 */
    fun manual() {
        if (count >= MAX_PER_TRIP) count = MAX_PER_TRIP - 1
        lastAt = Long.MIN_VALUE / 2
        withoutProgress = 0
    }

    companion object {
        const val MAX_PER_TRIP = 10
        const val BASE_MS = 30_000L
        private const val PROGRESS_M = 100
        private const val PROGRESS_MS = 2 * 60_000L
    }
}
