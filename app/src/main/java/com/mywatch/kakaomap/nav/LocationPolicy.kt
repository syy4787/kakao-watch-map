package com.mywatch.kakaomap.nav

/** 위치를 얼마나 정밀하게 받을지 */
enum class LocPower {
    /** GPS (안내용) */
    HIGH,
    /** Wi-Fi·기지국 위주, 전력 적음 */
    BALANCED,
    /** 스스로는 위치를 켜지 않고, 다른 앱이 받은 위치만 얻어 씀 — 추가 전력 0 */
    PASSIVE,
}

/** 위치 요청 계획 */
data class LocPlan(
    val power: LocPower,
    val intervalMs: Long,
    /** 이 시간까지 위치를 모아 한 번에 받음 (0 = 바로바로). CPU 를 덜 깨워 배터리를 아낀다 */
    val maxDelayMs: Long = 0,
    /** 이만큼 움직여야 새 위치를 줌 (서 있을 때 쓸데없는 처리 방지) */
    val minDistanceM: Float = 0f,
)

/**
 * 배터리 우회법의 핵심: 기능을 끄는 대신 '필요한 순간에만 촘촘하게' 위치를 받는다.
 *  · 화면을 보고 있을 때는 1초 (화면 전력이 훨씬 커서 GPS 를 아껴도 이득이 작고, 지도는 부드러워야 하므로)
 *  · 손목을 내리면(화면 꺼짐·저전력 화면) 상황별로:
 *      갈림길 150m 안·경로 이탈·출발 직후 → 1초 / 갈림길 접근(400m 안) → 2초 / 긴 직진 → 4초(8초까지 모아 받기)
 *      멈춰 서 있음(신호·정류장) → 5초 / 버스·지하철에서 내릴 곳이 1.5km 넘게 남음 → 10초(20초까지 모아 받기)
 *      도착 → 2분간 저전력(Wi-Fi 위주) 10초, 그 뒤엔 추가 전력 0 인 '얻어 쓰기'
 *  · 워치가 폰과 연결돼 있으면 시스템이 폰·워치 중 전력이 덜 드는 쪽 위치를 쓴다 (Wear OS 기본 동작)
 * 순수 계산이라 단위 테스트로 검증한다.
 */
object LocationPolicy {

    /** 화면을 보고 있을 때 */
    val INTERACTIVE = LocPlan(LocPower.HIGH, 1_000)

    /** 저전력 화면에서 '지도에서 보기'만 한 경로를 띄워 둔 경우 (안내 서비스 없음) */
    val AMBIENT_VIEW_ONLY = LocPlan(LocPower.HIGH, 10_000, maxDelayMs = 20_000, minDistanceM = 5f)

    const val STILL_MPS = 0.4f
    const val AFTER_ARRIVAL_ACTIVE_MS = 2 * 60_000L

    /**
     * 안내 서비스의 위치 계획.
     * @param bike 자전거 안내인지 (빠르게 움직이므로 더 촘촘히)
     * @param speedMps 최근 속도 m/s (모르면 null)
     * @param sinceArrivalMs 도착한 뒤 지난 시간 (도착 전이면 무시)
     */
    fun navigation(s: NavState, bike: Boolean, speedMps: Float?, sinceArrivalMs: Long): LocPlan {
        if (s.arrived) {
            return if (sinceArrivalMs < AFTER_ARRIVAL_ACTIVE_MS) LocPlan(LocPower.BALANCED, 10_000, minDistanceM = 5f)
            else LocPlan(LocPower.PASSIVE, 60_000)
        }
        // 이탈·재탐색 중이거나 아직 경로에 오르기 전에는 정확히 따라간다
        if (s.offRoute || s.rerouting || !s.matched) return LocPlan(LocPower.HIGH, 1_000)
        val toNext = s.toStepEndM ?: Int.MAX_VALUE
        val still = speedMps != null && speedMps < STILL_MPS
        if (s.onRide) {
            return when {
                still -> LocPlan(LocPower.HIGH, 5_000, minDistanceM = 3f)          // 정류장에서 기다리는 중
                toNext > 1_500 -> LocPlan(LocPower.HIGH, 10_000, maxDelayMs = 20_000) // 내릴 곳이 멀다
                else -> LocPlan(LocPower.HIGH, 3_000)                              // 하차 알림 준비
            }
        }
        if (bike) {
            return when {
                toNext <= 300 -> LocPlan(LocPower.HIGH, 1_000)
                still -> LocPlan(LocPower.HIGH, 5_000, minDistanceM = 3f)
                else -> LocPlan(LocPower.HIGH, 2_000)
            }
        }
        return when {
            toNext <= 150 -> LocPlan(LocPower.HIGH, 1_000)                       // 갈림길 바로 앞
            still -> LocPlan(LocPower.HIGH, 5_000, minDistanceM = 3f)            // 신호 대기 등
            toNext <= 400 -> LocPlan(LocPower.HIGH, 2_000)                       // 갈림길 접근
            else -> LocPlan(LocPower.HIGH, 4_000, maxDelayMs = 8_000)            // 긴 직진
        }
    }
}
