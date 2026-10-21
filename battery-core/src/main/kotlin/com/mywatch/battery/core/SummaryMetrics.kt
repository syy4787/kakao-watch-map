package com.mywatch.battery.core

import java.util.Locale

/**
 * 워치 앱 요약(summary.txt)의 '[지표] 이름=값 …' 한 줄 — 사람이 읽는 글 사이에 기계가 읽을 숫자를 둔다.
 * 워치 앱이 [line] 으로 쓰고, PC 툴킷이 [parse] 로 읽어 조치 추천과 치료 전후 비교에 쓴다.
 * 이름: idle_pph(대기 %/h) · day_pph(24시간 평균) · night_pph(밤사이) · screen_pph(화면 켜진 동안) · screen_share(화면 몫 0~1) ·
 * wakes · short_wakes · gesture_wakes(알림 없이 5초 안에 꺼짐) · night_gesture_wakes(그중 밤 0~6시) · notif_wakes · notifications · drift(잔량 표시 오차 포인트)
 */
object SummaryMetrics {
    const val TAG = "[지표]"

    /** null 값은 뺀다. 숫자는 소수 둘째 자리까지, 끝의 0 은 지운다 */
    fun line(values: Map<String, Double?>): String =
        TAG + values.entries.filter { it.value != null && !it.value!!.isNaN() && !it.value!!.isInfinite() }
            .joinToString("") { " ${it.key}=${num(it.value!!)}" }

    /** 요약 글에서 '[지표]' 줄을 찾아 이름→값. 줄이 없거나 숫자가 아니면 그 항목은 뺀다 */
    fun parse(text: String?): Map<String, Double> {
        val l = text?.lineSequence()?.firstOrNull { it.trimStart().startsWith(TAG) } ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (tok in l.trimStart().removePrefix(TAG).trim().split(Regex("\\s+"))) {
            val eq = tok.indexOf('=')
            if (eq <= 0) continue
            val v = tok.substring(eq + 1).toDoubleOrNull() ?: continue
            out[tok.substring(0, eq)] = v
        }
        return out
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}
