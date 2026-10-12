package com.mywatch.kakaomap.ui

import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.TravelMode

/** 지도 위 안내 배너·한 줄 알약 문구 (안드로이드와 무관한 순수 계산이라 단위 테스트로 검증) */
object NavOverlayText {

    /** 안내 문구 → 배너 두 줄: 첫 줄(굵게) + 나머지(작게, ' · ' 로 이음) */
    fun bannerLines(guide: String): Pair<String, String?> {
        val lines = guide.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val main = lines.firstOrNull() ?: return "" to null
        return main to lines.drop(1).joinToString(" · ").ifEmpty { null }
    }

    /**
     * 아래 한 줄 알약: "8.3km · 14:05 도착".
     * 대중교통은 처음 계획한 도착 시각, 도보·자전거는 남은 거리만큼 걸리는 시간으로 지금부터 계산.
     * 위치를 아직 모르면 경로 요약, 도착한 뒤에는 목적지 이름.
     */
    fun pill(
        mode: TravelMode,
        remainingM: Int?,
        arrived: Boolean,
        totalDistanceM: Int,
        totalTimeSec: Int,
        startedAtMs: Long,
        nowMs: Long,
        summary: String,
        destinationName: String,
    ): String {
        if (arrived) return "→ $destinationName"
        val left = remainingM ?: return summary
        val dist = Format.distance(left)
        return when {
            mode == TravelMode.TRANSIT -> "$dist · ${Format.clock24After(totalTimeSec, startedAtMs)} 도착"
            totalDistanceM > 0 && totalTimeSec > 0 -> {
                val sec = (left.toLong() * totalTimeSec / totalDistanceM).toInt()
                "$dist · ${Format.clock24After(sec, nowMs)} 도착"
            }
            else -> dist
        }
    }
}
