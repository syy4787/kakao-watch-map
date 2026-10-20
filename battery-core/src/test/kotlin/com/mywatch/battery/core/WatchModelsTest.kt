package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchModelsTest {
    @Test
    fun galaxyWatch9ModelsMapToTheirCapacity() {
        assertEquals(445, WatchModels.nominalMah("SM-L350"))
        assertEquals(445, WatchModels.nominalMah("SM-L355N"))
        assertEquals(390, WatchModels.nominalMah("sm-l340"))
        assertEquals(390, WatchModels.nominalMah("SM-L345U"))
        assertEquals("44mm", WatchModels.sizeLabel("SM-L355"))
        assertEquals("40mm", WatchModels.sizeLabel("SM-L340"))
    }

    @Test
    fun unknownModelsHaveNoCapacity() {
        assertNull(WatchModels.nominalMah(null))
        assertNull(WatchModels.nominalMah("SM-L310"))
        assertNull(WatchModels.sizeLabel(""))
    }
}
