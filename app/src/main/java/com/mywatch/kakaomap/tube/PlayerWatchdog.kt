package com.mywatch.kakaomap.tube

/**
 * 재생 화면의 배터리 지킴이 (순수 계산 — 단위 테스트로 검증). 재생 중엔 화면을 켜 두므로(보이지 않는 재생 금지 — 화면이 꺼지면 멈춤)
 * 손대지 않은 채 오래 두면 화면이 재생 전력의 큰 몫을 쓴다:
 *  · [DIM_AFTER_MS] 동안 손대지 않으면 화면을 어둡게 — 소리만 듣는 경우가 대부분 ([dim] = false 인 Shorts 는 보는 것이라 그대로)
 *  · [PAUSE_AFTER_MS] 동안 손대지 않으면 멈춤 — 잊고 둔 재생·한 곡 반복·믹스 이어 듣기가 몇 시간씩 화면을 켜 두지 않게
 * 손대면(터치·베젤·버튼) 밝게 돌리고 처음부터 센다. 다음 곡으로 저절로 넘어가도 센 시간은 이어진다.
 */
class PlayerWatchdog(
    private val dim: Boolean,
    private val dimAfterMs: Long = DIM_AFTER_MS,
    private val pauseAfterMs: Long = PAUSE_AFTER_MS,
) {
    enum class Act { DIM, BRIGHT, PAUSE }

    var playing = false
        private set
    var dimmed = false
        private set
    private var lastInput: Long? = null

    /** 손댐 — 어두웠으면 밝게 */
    fun input(now: Long): List<Act> {
        lastInput = now
        return brighten()
    }

    /** 재생 시작(다음 곡 포함) */
    fun play(now: Long) {
        playing = true
        if (lastInput == null) lastInput = now
    }

    /** 멈춤·화면을 떠남 — 어두웠으면 밝게 */
    fun pause(): List<Act> {
        playing = false
        return brighten()
    }

    /** 다음에 [check] 할 때 (재생 중이 아니면 null) */
    fun nextCheckAt(): Long? {
        if (!playing) return null
        val from = lastInput ?: return null
        return if (dim && !dimmed) from + dimAfterMs else from + pauseAfterMs
    }

    fun check(now: Long): List<Act> {
        if (!playing) return emptyList()
        val from = lastInput ?: return emptyList()
        if (now - from >= pauseAfterMs) {
            playing = false
            return brighten() + Act.PAUSE
        }
        if (dim && !dimmed && now - from >= dimAfterMs) {
            dimmed = true
            return listOf(Act.DIM)
        }
        return emptyList()
    }

    private fun brighten(): List<Act> {
        if (!dimmed) return emptyList()
        dimmed = false
        return listOf(Act.BRIGHT)
    }

    companion object {
        const val DIM_AFTER_MS = 2 * 60_000L
        const val PAUSE_AFTER_MS = 45 * 60_000L
        /** 어둡게 할 때의 밝기 (0~1) — 화면은 읽을 수 있게 */
        const val DIM_BRIGHTNESS = 0.15f
    }
}
