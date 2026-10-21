package com.mywatch.kakaomap.nav

import com.mywatch.kakaomap.model.Format

/**
 * 안내 알림(진행 중 활동·실시간 업데이트 칩)의 글과 고칠 때 (순수 계산 — 단위 테스트로 검증).
 * 알림을 고칠 때마다 시스템 UI·시계 화면(진행 중 활동 표시)이 깨어나므로, 남은 거리는 반올림해 위치마다 글이 바뀌지 않게 하고
 * 화면이 꺼져 있으면 30초에 한 번만 (도착·이탈·다시 찾기·다음 할 일이 바뀐 것은 바로).
 */
object NavNotice {
    const val INTERACTIVE_GAP_MS = 5_000L
    const val SCREEN_OFF_GAP_MS = 30_000L

    /** 1km 안은 50m, 그 밖은 100m 단위로 (최소 50m) */
    fun round(m: Int): Int {
        val step = if (m < 1_000) 50 else 100
        return (((m + step / 2) / step) * step).coerceAtLeast(50)
    }

    /** "좌회전 · 남은 350m" — 도착했으면 남은 거리 없이 */
    fun text(guide: String, remainingM: Int?, arrived: Boolean): String {
        val left = remainingM?.takeIf { !arrived }?.let { " · 남은 ${Format.distance(round(it))}" } ?: ""
        return guide + left
    }

    /** 실시간 업데이트 칩의 짧은 글 */
    fun short(remainingM: Int?, arrived: Boolean): String? = when {
        arrived -> "도착"
        else -> remainingM?.let { Format.distance(round(it)) }
    }

    /**
     * '다음 할 일'이 무엇인지 — 구간·할 일·탐/기다림·지나침. 안내 글(1m 단위 거리가 든 '117m 후 좌회전')은 위치마다 바뀌므로 쓰지 않는다.
     * 이 값이 바뀌면 알림을 바로 고친다
     */
    fun stepKey(s: NavState): String =
        listOf(s.routeVersion, s.stepIndex, s.bannerTitle, s.action, s.onRide, s.boardingStep, s.waitingBus, s.passedStop, s.missedRide).joinToString("|")

    fun shouldUpdate(changed: Boolean, sinceLastMs: Long, interactive: Boolean, important: Boolean): Boolean {
        if (!changed) return false
        if (important) return true
        return sinceLastMs >= if (interactive) INTERACTIVE_GAP_MS else SCREEN_OFF_GAP_MS
    }
}
