package com.mywatch.kakaomap.tube

/**
 * 재생 화면의 배터리 지킴이 (순수 계산 — 단위 테스트로 검증). 재생 중엔 화면을 켜 두므로(보이지 않는 재생 금지 — 화면이 꺼지면 멈춤)
 * 손대지 않은 채 오래 두면 화면이 재생 전력의 큰 몫을 쓴다:
 *  · [DIM_AFTER_MS] 동안 손대지 않으면 화면을 어둡게 — 소리만 듣는 경우가 대부분 ([dim] = false 인 Shorts 는 보는 것이라 그대로)
 *  · [PAUSE_AFTER_MS] 동안 손대지 않으면 멈춤 — 잊고 둔 재생·한 곡 반복·믹스 이어 듣기가 몇 시간씩 화면을 켜 두지 않게
 * 손대면(터치·베젤·버튼, 화면이 다시 보임) 밝게 돌리고 처음부터 센다. 다음 곡으로 넘어가는 잠깐의 멈춤은 그대로 이어 세고
 * (밝아졌다 어두워지는 깜빡임 없이), 멈춘 채 [BRIGHT_AFTER_PAUSE_MS] 면 밝게, [FRESH_AFTER_PAUSE_MS] 넘게 멈췄다 다시 틀면 새로 센다.
 * 화면을 떠나면([stop]) 바로 밝게.
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
    /** 재생이 멈춘 때 (재생 중이거나 화면을 떠났으면 null) */
    private var pausedAt: Long? = null

    /** 손댐·화면이 다시 보임 — 어두웠으면 밝게 */
    fun input(now: Long): List<Act> {
        lastInput = now
        return brighten()
    }

    /** 재생 시작(다음 곡 포함). 한참 멈췄다 손대지 않고 다시 틀면(엔진을 다시 열어 저절로 등) 새로 센다 */
    fun play(now: Long) {
        val gap = pausedAt?.let { now - it }
        if (lastInput == null || (gap != null && gap >= FRESH_AFTER_PAUSE_MS)) lastInput = now
        pausedAt = null
        playing = true
    }

    /** 재생이 멈춤 — 다음 곡으로 넘어가는 중일 수 있어 밝기는 잠시 그대로 ([check] 가 [BRIGHT_AFTER_PAUSE_MS] 뒤 밝게) */
    fun pause(now: Long) {
        if (!playing) return
        playing = false
        pausedAt = now
    }

    /** 화면을 떠남 — 바로 밝게 (다음에 볼 때 어두운 화면이 남지 않게) */
    fun stop(): List<Act> {
        playing = false
        pausedAt = null
        return brighten()
    }

    /** 다음에 [check] 할 때 (할 일이 없으면 null) */
    fun nextCheckAt(): Long? {
        if (playing) {
            val from = lastInput ?: return null
            return if (dim && !dimmed) from + dimAfterMs else from + pauseAfterMs
        }
        val p = pausedAt
        return if (dimmed && p != null) p + BRIGHT_AFTER_PAUSE_MS else null
    }

    fun check(now: Long): List<Act> {
        if (!playing) {
            val p = pausedAt
            return if (dimmed && p != null && now - p >= BRIGHT_AFTER_PAUSE_MS) brighten() else emptyList()
        }
        val from = lastInput ?: return emptyList()
        if (now - from >= pauseAfterMs) {
            playing = false
            pausedAt = null
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
        /** 멈춘 채 이만큼이면 밝게 (다음 곡으로 넘어가는 몇 초는 기다림) */
        const val BRIGHT_AFTER_PAUSE_MS = 10_000L
        /** 이만큼 넘게 멈췄다 다시 틀면 새로 센다 */
        const val FRESH_AFTER_PAUSE_MS = 30_000L
        /** 어둡게 할 때의 밝기 (0~1) — 화면은 읽을 수 있게 */
        const val DIM_BRIGHTNESS = 0.15f
    }
}
