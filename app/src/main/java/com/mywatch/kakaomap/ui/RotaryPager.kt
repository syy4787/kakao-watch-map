package com.mywatch.kakaomap.ui

/**
 * Shorts 재생 화면의 베젤·크라운: 한 칸 = Shorts 한 장 (시계 방향 = 다음 장). 베젤 한 칸이 굴리는 양은 기기마다 달라
 * 굴린 양이 아니라 칸 수로 센다. 빨리 돌리면 [GAP_MS] 마다 한 장 — 페이지가 장을 맞추는 사이 여러 장을 건너뛰지 않게
 */
class RotaryPager {
    private var lastAt: Long? = null

    /** 이번 칸으로 넘길 장 수: 1 다음 · -1 앞 · 0 그대로. [axis] = AXIS_SCROLL (음수 = 시계 방향) */
    fun step(axis: Float, nowMs: Long): Int {
        if (axis == 0f || axis.isNaN()) return 0
        lastAt?.let { if (nowMs - it < GAP_MS) return 0 }
        lastAt = nowMs
        return if (axis < 0) 1 else -1
    }

    companion object {
        /** 한 장을 넘긴 뒤 다음 장까지의 최소 간격 */
        const val GAP_MS = 250L
    }
}
