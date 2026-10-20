package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdsTest {
    @Test
    fun idleFollowsAndroidVitals() {
        assertEquals(Grade.NORMAL, Thresholds.idle(3.2))
        assertEquals(Grade.CAUTION, Thresholds.idle(3.21))
        assertEquals(Grade.CAUTION, Thresholds.idle(4.44))
        assertEquals(Grade.ABNORMAL, Thresholds.idle(4.45))
        assertEquals(Grade.UNKNOWN, Thresholds.idle(null))
    }

    @Test
    fun mixedDayAndNightAndExperiments() {
        assertEquals(Grade.NORMAL, Thresholds.mixed24h(3.3))
        assertEquals(Grade.CAUTION, Thresholds.mixed24h(4.9))
        assertEquals(Grade.ABNORMAL, Thresholds.mixed24h(5.0))
        assertEquals(Grade.NORMAL, Thresholds.overnight(3.2))
        assertEquals(Grade.CAUTION, Thresholds.overnight(4.0))
        assertEquals(Grade.ABNORMAL, Thresholds.overnight(4.5))
        assertEquals(Grade.NORMAL, Thresholds.experiment(1.0))
        assertEquals(Grade.CAUTION, Thresholds.experiment(2.0))
        assertEquals(Grade.ABNORMAL, Thresholds.experiment(2.1))
    }

    @Test
    fun worstIgnoresUnknown() {
        assertEquals(Grade.ABNORMAL, Grade.worst(listOf(Grade.NORMAL, Grade.ABNORMAL, Grade.UNKNOWN)))
        assertEquals(Grade.UNKNOWN, Grade.worst(listOf(Grade.UNKNOWN)))
        assertEquals(Grade.UNKNOWN, Grade.worst(emptyList()))
    }
}
