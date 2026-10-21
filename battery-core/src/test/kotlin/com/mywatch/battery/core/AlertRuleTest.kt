package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertRuleTest {
    private val q = 15 * 60_000L
    private val h = 3_600_000L
    private val now = 1_760_000_000_000L

    private fun twoHours(pph: Double, interactive: Boolean = false, plugged: Boolean = false) =
        (0..8).map { i -> BatterySample(t = now - 2 * h + i * q, level = (100 - pph * i / 4).toInt(), interactive = interactive, plugged = plugged) }

    @Test
    fun sixPercentAnHourWithTheScreenOffAlerts() {
        val a = AlertRule.check(twoHours(6.0), now, lastAlertAt = null)
        assertNotNull(a)
        assertEquals(6.0, a!!.pph, 0.01)
        assertEquals(2.0, a.hours, 1e-9)
    }

    @Test
    fun quietWhenTheScreenWasOnOrDrainIsModest() {
        assertNull(AlertRule.check(twoHours(6.0, interactive = true), now, null))
        assertNull(AlertRule.check(twoHours(4.0), now, null))
        assertNull(AlertRule.check(twoHours(6.0, plugged = true), now, null))
    }

    @Test
    fun needsEnoughSamples() {
        assertNull(AlertRule.check(twoHours(6.0).takeLast(4), now, null))
    }

    @Test
    fun atMostOnceInSixHours() {
        assertNull(AlertRule.check(twoHours(6.0), now, lastAlertAt = now - 5 * h))
        assertNotNull(AlertRule.check(twoHours(6.0), now, lastAlertAt = now - 7 * h))
    }

    /** 시계가 뒤로 갔다 — 미래가 된 마지막 알림 시각이 그만큼 알림을 막지 않게 (시나리오 점검) */
    @Test
    fun aCooldownStampFromTheFutureDoesNotSilenceAlerts() {
        assertNotNull(AlertRule.check(twoHours(6.0), now, lastAlertAt = now + 3 * h))
    }

    /**
     * 시킨 대로 재시작했으면 재시작 앞의 과소모를 재시작 뒤 판단에 섞지 않는다 — 재시작 뒤 1시간 45분이 쌓여야 다시 본다. 그전엔
     * 2시간 창이 재시작을 걸쳐, 과소모가 사라진 직후에 '시간당 5.5%'로 절전을 켜고 알렸다 (시나리오 점검)
     */
    @Test
    fun aRestartStartsTheWindowOver() {
        val bootAt = now - 35 * 60_000L
        val hog = (0..5).map { i -> BatterySample(t = now - 2 * h + i * q, level = 100 - 2 * i) }   // 시간당 8% — 100 → 90
        val after = listOf(
            BatterySample(t = bootAt, level = 89, event = Event.BOOT),
            BatterySample(t = now - 30 * 60_000L, level = 89), BatterySample(t = now - q, level = 89), BatterySample(t = now, level = 88),
        )
        assertNull("재시작 뒤 35분 — 아직 판단하지 않는다", AlertRule.check(hog + after, now, null))
        // 재시작 뒤에도 그대로 새면(1시간 45분 넘게) 다시 알린다
        val still = (0..8).map { i -> BatterySample(t = bootAt + i * q, level = 89 - 6 * i / 4, event = if (i == 0) Event.BOOT else Event.PERIODIC) }
        val a = AlertRule.check(hog + still, bootAt + 8 * q, null)
        assertNotNull(a)
        assertEquals(6.0, a!!.pph, 0.6)
    }
}
