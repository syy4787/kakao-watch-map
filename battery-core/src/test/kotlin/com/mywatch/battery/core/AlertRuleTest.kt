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
}
