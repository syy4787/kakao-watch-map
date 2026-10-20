package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDrainTest {
    private val h = 3_600_000L

    @Test
    fun deltaSubtractsAndClampsAfterAReset() {
        val prev = mapOf("a" to UidUse("a", wakelockMs = 10_000, cpuMs = 5_000), "b" to UidUse("b", wakelockMs = 50_000))
        val now = mapOf(
            "a" to UidUse("a", wakelockMs = 25_000, cpuMs = 6_000),   // 그대로 늘어남
            "b" to UidUse("b", wakelockMs = 3_000),                   // 충전 뒤 통계가 비워져 줄어듦 → 지금 값
            "c" to UidUse("c", sensorMs = 7_000),                     // 새 앱
            "d" to UidUse("d"),                                        // 아무것도 안 씀 → 빠짐
        )
        val d = AppDrain.delta(prev, now).associateBy { it.pkg }
        assertEquals(15_000L, d["a"]!!.wakelockMs)
        assertEquals(1_000L, d["a"]!!.cpuMs)
        assertEquals(3_000L, d["b"]!!.wakelockMs)
        assertEquals(7_000L, d["c"]!!.sensorMs)
        assertTrue("d" !in d)
    }

    @Test
    fun sumMergesWindows() {
        val s = AppDrain.sum(listOf(listOf(UidUse("a", wakelockMs = 1_000, syncs = 1)), listOf(UidUse("a", wakelockMs = 2_000, jobs = 2), UidUse("b", cpuMs = 5))))
        val a = s.first { it.pkg == "a" }
        assertEquals(3_000L, a.wakelockMs); assertEquals(1, a.syncs); assertEquals(2, a.jobs)
        assertEquals(2, s.size)
    }

    @Test
    fun rankingFlagsStuckWakelocksAndVitalsThreshold() {
        val deltas = listOf(
            UidUse("stuck", wakelockMs = 70 * 60_000L),          // 2시간 중 70분
            UidUse("chatty", wakelockMs = 60_000L),              // 시간당 30초 > 18.5초
            UidUse("quiet", wakelockMs = 10_000L, cpuMs = 2_000), // 시간당 5초
            UidUse("gps", gpsMs = 40 * 60_000L),
            UidUse("sensor", sensorMs = 90 * 60_000L, fgMs = 0),
            UidUse("me", cpuMs = 1_000),
        )
        val r = AppDrain.rank(deltas, 2 * h, ownPkg = "me")
        assertEquals("gps", r[0].use.pkg)      // GPS ×3 가중치
        val stuck = r.first { it.use.pkg == "stuck" }
        assertEquals(Grade.ABNORMAL, stuck.grade)
        assertTrue(stuck.flags[0], stuck.flags[0].contains("stuck"))
        val chatty = r.first { it.use.pkg == "chatty" }
        assertEquals(Grade.CAUTION, chatty.grade)
        assertTrue(chatty.flags[0], chatty.flags[0].contains("시간당 30초"))
        assertEquals(Grade.NORMAL, r.first { it.use.pkg == "quiet" }.grade)
        assertTrue(r.first { it.use.pkg == "sensor" }.flags.any { it.contains("센서") })
        assertTrue(r.first { it.use.pkg == "me" }.flags.contains("이 앱"))
        assertEquals("웨이크락 1분", r.first { it.use.pkg == "chatty" }.summary)
    }
}
