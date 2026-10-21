package com.mywatch.kakaomap.battery

import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Thresholds
import kotlin.math.abs

/**
 * 배터리 화면 맨 위 '지금 할 일' — 여러 칸(알려진 문제·잔량 표시·화면 켜짐·밤사이·충전 습관)에 흩어진 조언 중 급한 것 [MAX] 개.
 * 차례: 비정상 알려진 문제 → 최근 7일 안에 늘어난 꺾임(그 무렵 바뀐 것에 맞는 할 일) → 확실한 잔량 표시 오차 → 주의 알려진 문제 →
 * 오래 켜 둔 워치(7일+, 대기 소모 높음)면 재시작 → 화면 켜짐(손목 올림·알림) → 밤사이(삼성 헬스 등) →
 * 권한이 없으면 PC 툴킷 → 충전 습관
 */
object BatteryTodo {
    const val MAX = 3

    data class Todo(val title: String, val detail: String?, val grade: Grade)

    /** 이만큼 넘게 재시작하지 않았고 대기 소모가 높으면 재시작을 권한다 */
    const val RESTART_AFTER_DAYS = 7

    fun pick(ins: Insights, permitted: Boolean, label: (String) -> String = { it }, now: Long = System.currentTimeMillis()): List<Todo> {
        val out = ArrayList<Todo>()
        fun split(advice: String, g: Grade) = Todo(advice.substringBefore(" — "), advice.substringAfter(" — ", "").ifEmpty { null }, g)
        ins.issues.filter { it.grade == Grade.ABNORMAL }.forEach { out += Todo(it.title, it.steps.firstOrNull(), it.grade) }
        // 최근 7일 안에 대기 소모가 늘어 꺾였으면 그 무렵 가장 그럴듯한 변화에 맞는 할 일
        ins.trend?.takeIf { it.fresh }?.let { t ->
            val shift = t.shift ?: return@let
            val (title, detail) = t.advice { pkg -> label(pkg).takeIf { it != pkg } } ?: return@let
            out += Todo(title, detail, if (Thresholds.idle(shift.after) == Grade.ABNORMAL) Grade.ABNORMAL else Grade.CAUTION)
        }
        ins.analysis?.drift?.takeIf { abs(it.driftPoints) >= GaugeDriftRule.CERTAIN_POINTS }?.let { d ->
            val title = if (d.driftPoints < 0) "잔량 표시가 ${-d.driftPoints}포인트 낮게 셈 — 실제로는 더 남음" else "잔량 표시가 ${d.driftPoints}포인트 높게 셈 — 갑자기 꺼질 수 있음"
            out += Todo(title, d.advice, Grade.CAUTION)
        }
        ins.issues.filter { it.grade != Grade.ABNORMAL }.forEach { out += Todo(it.title, it.steps.firstOrNull(), it.grade) }
        // 오래 켜 둔 워치 + 대기 소모가 높음 → 한 번 재시작 (멈춘 서비스가 풀리는 일이 많다 — 2026년 광탈 글들의 공통 해결담)
        ins.bootAt?.let { boot ->
            val days = ((now - boot) / (24 * 3_600_000L)).toInt()
            val idle = ins.breakdown?.idlePph ?: ins.analysis?.idle24?.percentPerHour
            val high = Thresholds.idle(idle).let { it == Grade.CAUTION || it == Grade.ABNORMAL } || ins.trend?.fresh == true
            if (days >= RESTART_AFTER_DAYS && high) {
                out += Todo("재시작한 지 ${days}일 — 한 번 재시작", "홈+뒤로 버튼을 꺼질 때까지(7초 이상) — 오래 켜 둔 워치는 재시작으로 멈춘 서비스가 풀려 대기 소모가 줄곤 합니다", Grade.CAUTION)
            }
        }
        ins.wakes?.advice(label)?.forEach { out += split(it, Grade.CAUTION) }
        ins.night?.advice?.forEach { out += split(it, Grade.CAUTION) }
        if (!permitted) {
            out += Todo("지킴이 권한 주기", "PC 툴킷 메뉴 8(자동 치료)을 한 번 — 밤·과소모 때 워치가 스스로 조절", Grade.UNKNOWN)
        }
        ins.charging?.advice?.forEach { out += split(it, Grade.CAUTION) }
        return out.distinctBy { it.title }.take(MAX)
    }
}
