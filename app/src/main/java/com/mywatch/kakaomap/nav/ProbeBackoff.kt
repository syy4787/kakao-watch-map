package com.mywatch.kakaomap.nav

/**
 * 저전력 위치로 쉬는 동안 몇 분마다 하는 GPS 확인(최대 30초)의 간격. 실내라 계속 못 잡으면 늘리고(4→8→16분),
 * 한 번 잡히면 다시 4분부터 — 건물 안에서 GPS 를 30초씩 자주 켜지 않게 (순수 계산 — 단위 테스트로 검증)
 */
class ProbeBackoff(private val baseMs: Long = BASE_MS, private val maxMs: Long = MAX_MS) {
    private var misses = 0

    fun nextDelayMs(): Long {
        var d = baseMs
        repeat(misses) { d = minOf(d * 2, maxMs) }
        return d
    }

    fun missed() { if (nextDelayMs() < maxMs) misses++ }

    fun found() { misses = 0 }

    /** GPS 확인 결과 — 정확도 [USABLE_M] 이내로 잡혀야 '잡힘' (기지국·Wi-Fi 로 어림한 위치·못 잡음은 '못 잡음') */
    fun record(accuracyM: Float?) { if (accuracyM != null && accuracyM <= USABLE_M) found() else missed() }

    companion object {
        const val BASE_MS = 4 * 60_000L
        const val MAX_MS = 16 * 60_000L
        const val USABLE_M = 50f
    }
}
