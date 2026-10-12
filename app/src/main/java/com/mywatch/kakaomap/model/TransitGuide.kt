package com.mywatch.kakaomap.model

/** 대중교통 경로 → 안내 구간·짧은 이름 (안드로이드와 무관한 순수 변환이라 단위 테스트로 검증) */
object TransitGuide {

    /** 구간 막대·칩에 쓰는 짧은 이름: 버스 번호 / 지하철 노선 */
    fun label(step: TransitStep): String = when (step.type) {
        TransitStepType.WALK -> "도보"
        TransitStepType.BUS -> step.vehicles.joinToString("/") { it.name }.ifBlank { "버스" }
        TransitStepType.SUBWAY -> step.vehicles.firstOrNull()?.name?.ifBlank { null } ?: "지하철"
        TransitStepType.OTHER -> "기타"
    }

    /** 안내 배너에 쓰는 탈 것 이름: "617번", "대전 1호선" (탈 것이 아니면 null) */
    fun lineName(step: TransitStep): String? = when (step.type) {
        TransitStepType.BUS -> label(step).let { if (it == "버스") it else "${it}번" }
        TransitStepType.SUBWAY -> label(step)
        else -> null
    }

    /** 경로 카드의 첫 탈 것 줄: "617번 · 시청역 승차", "대전 1호선 · 대전역 승차" */
    fun boardText(step: TransitStep): String {
        val name = lineName(step) ?: label(step)
        val stop = step.boardingStop?.ifBlank { null }
        return if (stop != null) "$name · $stop 승차" else "$name 승차"
    }

    /** 경로 상세·알림에 쓰는 전체 문구 */
    fun text(step: TransitStep): String {
        val stops = "${step.boardingStop ?: ""} → ${step.alightingStop ?: ""}"
        return when (step.type) {
            TransitStepType.WALK -> if (step.approx) step.guidance
            else "도보 ${Format.duration(step.timeSec)} · ${Format.distance(step.distanceM)}"
            TransitStepType.BUS -> label(step).let { if (it == "버스") "버스 · $stops" else "${it}번 버스 · $stops" }
            TransitStepType.SUBWAY -> "${label(step)} · $stops"
            TransitStepType.OTHER -> step.guidance
        }
    }

    fun steps(r: TransitRoute): List<GuideStep> = r.steps.map {
        val ride = it.type == TransitStepType.BUS || it.type == TransitStepType.SUBWAY
        GuideStep(
            text = text(it),
            points = it.points,
            ride = ride,
            alight = if (ride) it.alightingStop?.ifBlank { null } else null,
            subway = it.type == TransitStepType.SUBWAY,
            approx = it.approx,
            line = lineName(it),
            board = if (ride) it.boardingStop?.ifBlank { null } else null,
        )
    }
}
