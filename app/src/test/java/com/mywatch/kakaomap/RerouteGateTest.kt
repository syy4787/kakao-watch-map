package com.mywatch.kakaomap

import com.mywatch.kakaomap.nav.NavState
import com.mywatch.kakaomap.nav.RerouteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경로를 벗어나면 다시 찾기 — 언제 또 찾을지.
 * 새 경로는 늘 지금 위치에서 시작해 곧바로 '경로 위'가 되므로, 그것만으로 간격을 처음(30초)으로 되돌리면
 * 일부러 옆길로 가는 동안 30초마다 '이탈' 진동 + 다시 찾기 + 1초 GPS 가 되풀이됐다(사용자 실측 구조).
 * 새 경로를 따라 실제로 나아가야(100m 또는 2분) 처음으로. 한 여정 10번을 다 쓰면 말없이 멈추지 않고 알리며, 누르면 한 번 더
 */
class RerouteGateTest {

    private val s = 1_000L
    private fun on(remaining: Int) = NavState(routeVersion = 2, stepIndex = 0, matched = true, remainingM = remaining)

    @Test
    fun aParallelStreetBacksOffInsteadOfBuzzingEvery30Seconds() {
        val g = RerouteGate()
        assertTrue(g.canReroute(0))
        g.rerouted(0)
        g.onRoute(on(800), 5 * s)          // 새 경로는 지금 위치에서 시작 — 바로 '경로 위'
        assertFalse(g.canReroute(40 * s))  // 다시 벗어남: 30초가 아니라 1분
        assertTrue(g.canReroute(61 * s))
        g.rerouted(61 * s)
        g.onRoute(on(790), 65 * s)
        assertFalse(g.canReroute(61 * s + 119 * s))
        assertTrue(g.canReroute(61 * s + 121 * s))
    }

    @Test
    fun realProgressOnTheNewRouteResetsTheWait() {
        val g = RerouteGate()
        g.rerouted(0)
        g.onRoute(on(800), 5 * s)
        g.onRoute(on(695), 25 * s)         // 105m 나아감 (자전거)
        assertTrue("다시 30초 간격", g.canReroute(35 * s))
    }

    @Test
    fun twoMinutesOnTheNewRouteAlsoCounts() {
        val g = RerouteGate()
        g.rerouted(0)
        g.rerouted(60 * s)
        g.rerouted(180 * s)                // 나아가지 못한 채 세 번 → 다음은 4분 뒤
        assertFalse(g.canReroute(300 * s))
        g.onRoute(on(800), 185 * s)
        g.onRoute(on(790), 306 * s)        // 거의 안 움직였지만 2분 넘게 경로 위 (멈춰 기다림 등)
        assertTrue(g.canReroute(306 * s))
    }

    @Test
    fun afterTenReroutesItStopsSaysSoAndATapAllowsOneMore() {
        val g = RerouteGate()
        var t = 0L
        repeat(RerouteGate.MAX_PER_TRIP) {
            t += 10 * 60 * s
            assertTrue(g.canReroute(t))
            g.rerouted(t)
        }
        assertTrue(g.exhausted)
        assertFalse(g.canReroute(t + 60 * 60 * s))
        g.manual()
        assertTrue("누르면 바로 한 번 더", g.canReroute(t + 1 * s))
        g.rerouted(t + 1 * s)
        assertTrue(g.exhausted)
    }

    @Test
    fun aNewTripStartsFresh() {
        val g = RerouteGate()
        repeat(RerouteGate.MAX_PER_TRIP) { g.rerouted(it * 600 * s) }
        g.newTrip()
        assertFalse(g.exhausted)
        assertTrue(g.canReroute(0))
        assertEquals(30 * s, g.interval())
    }
}
