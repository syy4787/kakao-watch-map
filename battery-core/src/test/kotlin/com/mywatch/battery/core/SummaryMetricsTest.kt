package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 워치 앱 요약의 '[지표]' 한 줄 — 앱이 쓰고 PC 툴킷이 읽는다 */
class SummaryMetricsTest {

    @Test
    fun theLineRoundTripsAndSkipsUnknownValues() {
        val line = SummaryMetrics.line(linkedMapOf("idle_pph" to 1.25, "gesture_wakes" to 120.0, "night_pph" to null, "screen_pph" to 18.0))
        assertEquals("[지표] idle_pph=1.25 gesture_wakes=120 screen_pph=18", line)
        val text = "갤럭시워치 배터리 기록 요약\n[판정] 정상\n$line\n[레버 점검] perm=yes saver=global/low_power"
        assertEquals(mapOf("idle_pph" to 1.25, "gesture_wakes" to 120.0, "screen_pph" to 18.0), SummaryMetrics.parse(text))
    }

    @Test
    fun brokenOrMissingLinesGiveNothing() {
        assertEquals(emptyMap<String, Double>(), SummaryMetrics.parse(null))
        assertEquals(emptyMap<String, Double>(), SummaryMetrics.parse("[판정] 정상"))
        assertEquals(mapOf("ok" to 2.0), SummaryMetrics.parse("[지표] broken=abc ok=2 =3 nokey"))
    }
}
