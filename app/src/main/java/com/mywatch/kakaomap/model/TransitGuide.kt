package com.mywatch.kakaomap.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 대중교통 경로 → 안내 구간·짧은 이름 (안드로이드와 무관한 순수 변환이라 단위 테스트로 검증) */
object TransitGuide {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val SEOUL = ZoneId.of("Asia/Seoul")

    /** 시간표 시각 → "09:58" */
    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(SEOUL).format(CLOCK)

    /** 구간 막대·칩에 쓰는 짧은 이름: 버스 번호 / 지하철 노선 / "KTX 105" / "고속 우등" */
    fun label(step: TransitStep): String = when (step.type) {
        TransitStepType.WALK -> "도보"
        TransitStepType.BUS -> step.vehicles.joinToString("/") { it.name }.ifBlank { "버스" }
        TransitStepType.SUBWAY -> step.vehicles.firstOrNull()?.name?.ifBlank { null } ?: "지하철"
        TransitStepType.TRAIN -> step.vehicles.firstOrNull()?.let { "${it.type} ${it.name}".trim() }?.ifBlank { null } ?: "기차"
        TransitStepType.COACH -> step.vehicles.firstOrNull()?.let { "${it.type} ${it.name}".trim() }?.ifBlank { null } ?: "고속버스"
        TransitStepType.OTHER -> "기타"
    }

    /** 안내 배너에 쓰는 탈 것 이름: "617번", "대전 1호선", "KTX 105", "고속버스 우등" (탈 것이 아니면 null) */
    fun lineName(step: TransitStep): String? = when (step.type) {
        TransitStepType.BUS -> label(step).let { if (it == "버스") it else "${it}번" }
        TransitStepType.SUBWAY, TransitStepType.TRAIN -> label(step)
        TransitStepType.COACH -> step.vehicles.firstOrNull()
            ?.let { "${it.type.ifBlank { "고속" }}버스 ${it.name}".trim() } ?: "고속버스"
        else -> null
    }

    /**
     * 경로 카드의 탈 것 줄: "617번 · 시청역 승차", "대전 1호선 · 대전역 승차", "KTX 105 09:58 출발 · 대전역".
     * 기차·버스는 시각을 앞에 — 터미널 이름이 길어 두 줄을 넘겨도 출발 시각은 잘리지 않게
     */
    fun boardText(step: TransitStep): String {
        val name = lineName(step) ?: label(step)
        val stop = step.boardingStop?.ifBlank { null }
        val at = step.departAtMs
        if (step.type.scheduled && at != null) return "$name ${clock(at)} 출발" + (stop?.let { " · $it" } ?: "")
        return if (stop != null) "$name · $stop 승차" else "$name 승차"
    }

    /** 경로 카드의 대표 줄 — 기차·고속버스가 있으면 그것(시각이 정해져 있어 가장 중요), 없으면 첫 탈 것 */
    fun headline(route: TransitRoute): String? {
        val pick = route.steps.firstOrNull { it.type.scheduled } ?: route.firstRide ?: return null
        return boardText(pick)
    }

    /** 실시간 도착을 붙일 버스 구간 번호 — 기차·고속버스를 타기 전 버스만 (내려서 탈 버스는 몇 시간 뒤라 지금 도착 정보는 엉뚱함) */
    fun liveBusIndexes(route: TransitRoute): List<Int> {
        val firstScheduled = route.steps.indexOfFirst { it.type.scheduled }.let { if (it < 0) route.steps.size else it }
        return (0 until firstScheduled).filter { route.steps[it].type == TransitStepType.BUS }
    }

    /** 경로 상세·알림에 쓰는 전체 문구 */
    fun text(step: TransitStep): String {
        val stops = "${step.boardingStop ?: ""} → ${step.alightingStop ?: ""}"
        return when (step.type) {
            TransitStepType.WALK -> if (step.approx) step.guidance
            else "도보 ${Format.duration(step.timeSec)} · ${Format.distance(step.distanceM)}"
            TransitStepType.BUS -> label(step).let { if (it == "버스") "버스 · $stops" else "${it}번 버스 · $stops" }
            TransitStepType.SUBWAY -> "${label(step)} · $stops"
            TransitStepType.TRAIN, TransitStepType.COACH -> "${lineName(step)} · $stops"
            TransitStepType.OTHER -> step.guidance
        }
    }

    fun steps(r: TransitRoute): List<GuideStep> = r.steps.map {
        val ride = it.type.ride
        GuideStep(
            text = text(it),
            points = it.points,
            ride = ride,
            alight = if (ride) it.alightingStop else null,
            subway = it.type == TransitStepType.SUBWAY,
            approx = it.approx,
            line = lineName(it),
            board = if (ride) it.boardingStop?.ifBlank { null } else null,
            timeSec = it.timeSec,
            longRide = when (it.type) {
                TransitStepType.TRAIN -> LongRide.TRAIN
                TransitStepType.COACH -> LongRide.COACH
                else -> null
            },
            departAtMs = it.departAtMs,
            arriveAtMs = it.arriveAtMs,
        )
    }
}
