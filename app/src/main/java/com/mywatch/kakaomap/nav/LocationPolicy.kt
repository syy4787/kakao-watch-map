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
 *  · 손목을 내리면(화면 꺼짐·저전력 화면) 상황별로 — 기준은 '다음에 할 일'(회전·횡단보도·승차·하차)까지 거리:
 *      할 일 60m 안·경로 이탈·출발 직후 → 1초 / 250m 안 → 2초 / 긴 직진 → 4초(8초까지 모아 받기)
 *      멈춰 서 있음(신호·정류장 대기) → 5초 (갈림길 바로 앞이어도. 움직이면 바로 촘촘히)
 *      버스에서 내릴 곳이 3km 넘게 → 15초, 1.5km 넘게 → 10초(모아 받기), 그 안 → 3초
 *      기차·고속버스에서 내릴 역이 10km 넘게(도착 10분 넘게 남음) → GPS 끄고 기지국·Wi-Fi 위치 1분, 4km 넘게 → 10초, 그 안 → 3초
 *      기차·고속버스 역에서 기다리는 중 → 출발 3분 전까지 Wi-Fi·기지국 위치 30초
 *      지하철(지하) → GPS 대신 기지국·Wi-Fi 위치 10초 (지하에선 GPS 가 위성을 찾느라 전기만 씀)
 *      실내 출발 등으로 1분 넘게 경로에 못 오름 → 3초 / 도착 → 2분간 저전력 10초, 그 뒤엔 추가 전력 0 인 '얻어 쓰기'
 *      경로 밖에 멈춰 있음 → 5초, 10분 넘게 → GPS 끄고 Wi-Fi·기지국 15초, 벗어난 채 30분 → 안내를 스스로 끝냄
 *      배터리 15% 이하(충전 중 아님) → 간격 두 배 (회전 앞도 2초)
 *  · 워치가 폰과 연결돼 있으면 시스템이 폰·워치 중 전력이 덜 드는 쪽 위치를 쓴다 (Wear OS 기본 동작)
 * 순수 계산이라 단위 테스트로 검증한다.
 */
object LocationPolicy {

    /** 화면을 보고 있을 때 */
    val INTERACTIVE = LocPlan(LocPower.HIGH, 1_000)

    /** 저전력 화면에서 '지도에서 보기'만 한 경로를 띄워 둔 경우 (안내 서비스 없음) */
    val AMBIENT_VIEW_ONLY = LocPlan(LocPower.HIGH, 10_000, maxDelayMs = 20_000, minDistanceM = 5f)

    const val STILL_MPS = 0.4f
    /** 경로 밖에 이만큼 넘게 있으면 GPS 대신 Wi-Fi·기지국 위치(멈춰 있을 때) */
    const val LOST_LONG_MS = 10 * 60_000L
    /** 경로를 벗어난 채 이만큼 지나면 안내를 스스로 끝낸다 (잊고 둔 안내가 배터리를 다 쓰지 않게) — [LostClock] */
    const val GIVE_UP_MS = 30 * 60_000L
    const val AFTER_ARRIVAL_ACTIVE_MS = 2 * 60_000L
    /** 기차·고속버스: 출발 이만큼 전부터 GPS (탔는지 알아야 하므로) */
    private const val WAIT_GPS_MINUTES = 3.0
    /** 기차·고속버스: 시간표 도착 이만큼 전부터 GPS 10초, 그 안쪽은 3초 */
    private const val LONG_GPS_MINUTES = 10.0
    private const val ALIGHT_GPS_MINUTES = 3.0
    private const val NOT_MATCHED_FAST_MS = 60_000L

    /**
     * 안내 서비스의 위치 계획.
     * @param bike 자전거 안내인지 (빠르게 움직이므로 더 촘촘히)
     * @param speedMps 최근 속도 m/s (모르면 null)
     * @param sinceArrivalMs 도착한 뒤 지난 시간 (도착 전이면 무시)
     * @param sinceStartMs 안내를 시작한 뒤 지난 시간
     * @param lowBattery 배터리 15% 이하이면서 충전 중이 아님
     * @param sinceLostMs 경로를 벗어났거나(이탈·정류장 지나침) 아직 경로에 못 오른 채 지난 시간 (경로 위면 0)
     */
    fun navigation(
        s: NavState,
        bike: Boolean,
        speedMps: Float?,
        sinceArrivalMs: Long,
        sinceStartMs: Long = 0,
        lowBattery: Boolean = false,
        sinceLostMs: Long = 0,
        /** 지금 시각 (기차·버스 시간표와 비교) */
        nowMs: Long = System.currentTimeMillis(),
    ): LocPlan {
        val plan = basePlan(s, bike, speedMps, sinceArrivalMs, sinceStartMs, sinceLostMs, nowMs)
        if (!lowBattery || plan.power != LocPower.HIGH) return plan
        return plan.copy(
            intervalMs = maxOf(plan.intervalMs * 2, 2_000L),
            maxDelayMs = plan.maxDelayMs * 2,
        )
    }

    private fun basePlan(
        s: NavState, bike: Boolean, speedMps: Float?, sinceArrivalMs: Long, sinceStartMs: Long, sinceLostMs: Long, nowMs: Long,
    ): LocPlan {
        if (s.arrived) {
            return if (sinceArrivalMs < AFTER_ARRIVAL_ACTIVE_MS) LocPlan(LocPower.BALANCED, 10_000, minDistanceM = 5f)
            else LocPlan(LocPower.PASSIVE, 60_000)
        }
        val still = speedMps != null && speedMps < STILL_MPS
        // 재탐색 중에는 정확히 따라간다 (잠깐)
        if (s.rerouting) return LocPlan(LocPower.HIGH, 1_000)
        // 기차가 내릴 역을 지나침: 다음 역에 설 때까지는 내릴 수 없고 다시 찾기도 그때 하므로 저전력으로 기다린다
        if (s.passedStop && s.onLongRide) return LocPlan(LocPower.BALANCED, 30_000)
        if (s.offRoute || s.passedStop || s.missedRide || !s.matched) {
            return when {
                // 10분 넘게 경로 밖: 멈춰 있으면(실내 등) GPS 를 끄고 Wi-Fi·기지국 위치, 움직이면 3초
                sinceLostMs >= LOST_LONG_MS ->
                    if (still) LocPlan(LocPower.BALANCED, 15_000, minDistanceM = 10f) else LocPlan(LocPower.HIGH, 3_000)
                // 벗어난 채 멈춰 있음 → 5초 (움직이면 바로 촘촘히)
                still -> LocPlan(LocPower.HIGH, 5_000, minDistanceM = 3f)
                // 이탈·정류장 지나침·기차 놓침: 재탐색할 수 있게 1초
                s.offRoute || s.passedStop || s.missedRide -> LocPlan(LocPower.HIGH, 1_000)
                // 아직 경로에 오르기 전: 처음 1분만 1초, 그 뒤(실내 등)엔 3초
                else -> LocPlan(LocPower.HIGH, if (sinceStartMs < NOT_MATCHED_FAST_MS) 1_000 else 3_000)
            }
        }
        val toNext = s.toActionM ?: s.toStepEndM ?: Int.MAX_VALUE
        // 시간표상 출발(기다리는 중)·도착(타는 중)까지 남은 분
        val minutesLeft = s.longRideAtMs?.let { (it - nowMs) / 60_000.0 }
        // 기차·고속버스 역에서 기다리는 중: 출발 3분 전까지는 Wi-Fi·기지국 위치 30초 (역 안이라 GPS 도 잘 안 잡힘)
        if (s.waitingLongRide && !s.onRide && minutesLeft != null && minutesLeft > WAIT_GPS_MINUTES) {
            return LocPlan(LocPower.BALANCED, 30_000)
        }
        if (s.onRide) {
            if (s.onSubway) return LocPlan(LocPower.BALANCED, 10_000)                   // 지하: 기지국·Wi-Fi 위치
            if (s.onLongRide) {
                // 기차·고속버스: 내릴 역이 10km 넘게, 도착까지 10분 넘게 → GPS 끄고 기지국·Wi-Fi 위치 1분 (KTX 안은 GPS 도 잘 안 잡힘).
                // 중간 역에 서 있어도(멈춤) 그대로 — 1시간 넘게 타도 GPS 를 거의 안 씀. 모아 받기는 안 함(오래된 위치는 버려지므로).
                // 시속 300km 면 1분에 5km 라 거리만 보면 늦으니 시간표 도착 10분 전부터는 GPS
                // 시간표보다 늦어지면(도착 시각이 지남) 거리로만 판단 — 늦은 기차에서 3초 GPS 를 계속 쓰지 않게
                val onTime = minutesLeft != null && minutesLeft >= 0
                val soon = onTime && minutesLeft!! <= LONG_GPS_MINUTES
                return when {
                    toNext > 10_000 && !soon -> LocPlan(LocPower.BALANCED, 60_000)
                    toNext > 4_000 && (!onTime || minutesLeft!! > ALIGHT_GPS_MINUTES) -> LocPlan(LocPower.HIGH, 10_000)
                    else -> LocPlan(LocPower.HIGH, 3_000)                                 // 하차 알림·내리기
                }
            }
            return when {
                still -> LocPlan(LocPower.HIGH, 5_000, minDistanceM = 3f)              // 신호·정류장에서 서 있음
                toNext > 3_000 -> LocPlan(LocPower.HIGH, 15_000, maxDelayMs = 30_000)  // 내릴 곳이 아주 멀다
                toNext > 1_500 -> LocPlan(LocPower.HIGH, 10_000, maxDelayMs = 20_000)  // 내릴 곳이 멀다
                else -> LocPlan(LocPower.HIGH, 3_000)                                  // 하차 알림 준비
            }
        }
        if (still) return LocPlan(LocPower.HIGH, 5_000, minDistanceM = 3f)            // 신호 대기 등
        if (bike) return if (toNext <= 300) LocPlan(LocPower.HIGH, 1_000) else LocPlan(LocPower.HIGH, 2_000)
        return when {
            toNext <= 60 -> LocPlan(LocPower.HIGH, 1_000)                             // 할 일 바로 앞 (35m 전 알림)
            toNext <= 250 -> LocPlan(LocPower.HIGH, 2_000)                            // 할 일 접근
            else -> LocPlan(LocPower.HIGH, 4_000, maxDelayMs = 8_000)                 // 긴 직진
        }
    }
}
