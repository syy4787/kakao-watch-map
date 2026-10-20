package com.mywatch.battery.core

/**
 * 게이지 보정 루틴 — 잔량 게이지는 완충(충전 종료)과 거의 빈 상태에서 스스로 다시 배운다. 그래서 드리프트가 확인되면
 * **① 100%까지 충전한 뒤 1시간 더 꽂아 두기 → ② 평소처럼 10% 아래까지 쓰기 → ③ 끊지 말고 다시 100% + 1시간** 을
 * 한 바퀴 돌리는 것이 강제 재시작 다음으로 할 수 있는 일이다. 워치가 15분 표본만 보고 단계를 넘기고 단계가 바뀔 때 알린다.
 */
data class CalibrationState(
    /** 0 = 안 함, 1 = 완충 유지, 2 = 방전, 3 = 다시 완충 유지, [CalibrationRule.STEP_DONE] = 끝 */
    val step: Int = 0,
    val startedAt: Long = 0L,
    /** 이번 단계에서 완충이 시작된 때 (0 = 아직) */
    val fullSince: Long = 0L,
    /** 시작할 때의 드리프트(전압 기준과의 차이) — 끝난 뒤 지금 값과 비교한다 */
    val driftBefore: Int? = null,
    val finishedAt: Long = 0L,
) {
    val active: Boolean get() = step in 1..3
}

sealed class CalibrationAction {
    /** 완충에 닿았다 — 이제부터 1시간 유지 */
    data class HoldFull(val step: Int) : CalibrationAction()
    /** 1단계(완충 유지) 또는 2단계(방전) 끝 — 다음 할 일을 알린다 */
    data class StepDone(val step: Int) : CalibrationAction()
    data class Done(val driftBefore: Int?) : CalibrationAction()
    object Expired : CalibrationAction()
}

object CalibrationRule {
    const val STEP_DONE = 4
    const val HOLD_FULL_MS = 60L * 60_000L
    /** 2단계: 이 아래까지 쓰면 끝 */
    const val DRAIN_TO = 10
    const val MAX_MS = 14L * 86_400_000L
    /** 이보다 묵은 표본으로는 판단하지 않는다 */
    const val STALE_MS = 45L * 60_000L

    fun start(now: Long, driftPoints: Int?): CalibrationState = CalibrationState(step = 1, startedAt = now, driftBefore = driftPoints)

    fun isFull(s: BatterySample): Boolean = s.plugged && (s.status == Status.FULL || s.level >= 100)

    fun decide(now: Long, st: CalibrationState, latest: BatterySample?): Pair<List<CalibrationAction>, CalibrationState> {
        if (!st.active) return emptyList<CalibrationAction>() to st
        if (now - st.startedAt > MAX_MS) return listOf(CalibrationAction.Expired) to CalibrationState()
        if (latest == null || latest.t > now || now - latest.t > STALE_MS) return emptyList<CalibrationAction>() to st
        return when (st.step) {
            1, 3 -> {
                if (!isFull(latest)) emptyList<CalibrationAction>() to st.copy(fullSince = 0L)
                else if (st.fullSince == 0L) listOf(CalibrationAction.HoldFull(st.step)) to st.copy(fullSince = latest.t)
                else if (latest.t - st.fullSince >= HOLD_FULL_MS) {
                    if (st.step == 1) listOf(CalibrationAction.StepDone(1)) to st.copy(step = 2, fullSince = 0L)
                    else listOf(CalibrationAction.Done(st.driftBefore)) to st.copy(step = STEP_DONE, fullSince = 0L, finishedAt = latest.t)
                } else emptyList<CalibrationAction>() to st
            }
            2 -> {
                if (!latest.plugged && latest.level <= DRAIN_TO) listOf(CalibrationAction.StepDone(2)) to st.copy(step = 3, fullSince = 0L)
                else emptyList<CalibrationAction>() to st
            }
            else -> emptyList<CalibrationAction>() to st
        }
    }

    /** 화면·알림에 쓰는 단계 설명 */
    fun stepLabel(step: Int): String? = when (step) {
        1 -> "1단계 — 충전기에 올려 100%가 된 뒤 1시간 더 두세요"
        2 -> "2단계 — 충전기에서 빼고 평소처럼 ${DRAIN_TO}% 아래까지 쓰세요"
        3 -> "3단계 — 충전기에 올리고 끊지 말고 100%가 된 뒤 1시간 더 두세요"
        STEP_DONE -> "끝 — 몇 시간 쓴 뒤 '잔량 표시 점검'에서 오차가 줄었는지 보세요"
        else -> null
    }
}
