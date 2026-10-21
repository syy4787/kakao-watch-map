package com.mywatch.kakaomap.battery

import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import kotlin.math.abs

/**
 * 배터리 화면 맨 위 '지금 할 일' — 여러 칸(알려진 문제·잔량 표시·화면 켜짐·밤사이·충전 습관)에 흩어진 조언 중 급한 것 [MAX] 개.
 * 차례: 비정상 알려진 문제 → 확실한 잔량 표시 오차 → 주의 알려진 문제 → 화면 켜짐(손목 올림·알림) → 밤사이(삼성 헬스 등) →
 * 권한이 없으면 PC 툴킷 → 충전 습관
 */
object BatteryTodo {
    const val MAX = 3

    data class Todo(val title: String, val detail: String?, val grade: Grade)

    fun pick(ins: Insights, permitted: Boolean, label: (String) -> String = { it }): List<Todo> {
        val out = ArrayList<Todo>()
        fun split(advice: String, g: Grade) = Todo(advice.substringBefore(" — "), advice.substringAfter(" — ", "").ifEmpty { null }, g)
        ins.issues.filter { it.grade == Grade.ABNORMAL }.forEach { out += Todo(it.title, it.steps.firstOrNull(), it.grade) }
        ins.analysis?.drift?.takeIf { abs(it.driftPoints) >= GaugeDriftRule.CERTAIN_POINTS }?.let { d ->
            val title = if (d.driftPoints < 0) "잔량 표시가 ${-d.driftPoints}포인트 낮게 셈 — 실제로는 더 남음" else "잔량 표시가 ${d.driftPoints}포인트 높게 셈 — 갑자기 꺼질 수 있음"
            out += Todo(title, d.advice, Grade.CAUTION)
        }
        ins.issues.filter { it.grade != Grade.ABNORMAL }.forEach { out += Todo(it.title, it.steps.firstOrNull(), it.grade) }
        ins.wakes?.advice(label)?.forEach { out += split(it, Grade.CAUTION) }
        ins.night?.advice?.forEach { out += split(it, Grade.CAUTION) }
        if (!permitted) {
            out += Todo("지킴이 권한 주기", "PC 툴킷 메뉴 8(자동 치료)을 한 번 — 밤·과소모 때 워치가 스스로 조절", Grade.UNKNOWN)
        }
        ins.charging?.advice?.forEach { out += split(it, Grade.CAUTION) }
        return out.distinctBy { it.title }.take(MAX)
    }
}
