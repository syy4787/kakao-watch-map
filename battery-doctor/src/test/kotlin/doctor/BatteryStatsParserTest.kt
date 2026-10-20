package doctor

import doctor.parse.BatteryStatsCheckin
import doctor.parse.BatteryStatsMerge
import doctor.parse.BatteryStatsText
import doctor.parse.Durations
import doctor.parse.Uids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatsParserTest {

    @Test
    fun durationsInBothAospStyles() {
        assertEquals(29523123L, Durations.parseMs("8h 12m 3s 123ms"))
        assertEquals(192543L, Durations.parseMs("+3m12s543ms"))
        assertEquals(93600000L, Durations.parseMs("1d 2h 0m 0s 0ms"))
        assertEquals(20000L, Durations.parseMs("20s 0ms"))
        assertEquals(70000L, Durations.parseMs("-1m10s0ms"))
        assertEquals(12345L, Durations.parseMs("12345"))
        assertEquals(500L, Durations.parseMs("500ms"))
        assertNull(Durations.parseMs("abc"))
        assertNull(Durations.parseMs(""))
    }

    @Test
    fun uidStrings() {
        assertEquals(10123, Uids.parse("u0a123"))
        assertEquals(1000, Uids.parse("1000"))
        assertEquals(99045, Uids.parse("u0i45"))
        assertEquals(1010123, Uids.parse("u10a123"))
        assertNull(Uids.parse("Screen"))
    }

    @Test
    fun textParserReadsTheSummaryNumbers() {
        val s = BatteryStatsText.parse(sample("batterystats.txt"))!!
        assertEquals(29523123L, s.onBatteryMs)
        assertEquals(1500123L, s.screenOnMs)
        assertEquals(28023000L, s.screenOffMs)
        assertEquals(4210000L, s.screenOffAwakeMs)        // "Time on battery screen off: … uptime" = 꺼진 동안 깨어 있던 시간
        assertEquals(40 to 41, s.dischargePercent)
        assertEquals(178, s.dischargeMah)
        assertEquals(6, s.dischargeScreenOn)
        assertEquals(34, s.dischargeScreenOff)
        assertEquals(445, s.capacityMah)
        assertEquals(182.0, s.computedDrainMah!!, 0.001)
        assertEquals(174.0 to 178.0, s.actualDrainMah)
        assertEquals(390000L, s.radioActiveMs)
        assertEquals(28, s.wifiScanCount)                  // (blamed) 만 더함: 14 + 2 + 12
        assertEquals(720000L, s.gpsMs)
        assertEquals(0.15, s.screenOffAwakeRatio!!, 0.001)
    }

    @Test
    fun textParserReadsPowerItemsWithPackages() {
        val s = BatteryStatsText.parse(sample("batterystats.txt"))!!
        assertEquals(12, s.powerItems.size)
        val top = s.powerItems.first()
        assertEquals("Uid u0a123", top.label)
        assertEquals("com.example.drain", top.pkg)
        assertEquals(48.5, top.mah, 0.001)
        assertEquals(48.5 / 182 * 100, top.percent!!, 0.01)
        val screen = s.powerItems.first { it.label == "Screen" }
        assertEquals(30.2, screen.mah, 0.001)
        assertNull(screen.pkg)
        assertEquals("com.google.android.gms", s.powerItems.first { it.label == "Uid u0a45" }.pkg)
        assertEquals(mapOf(
            1000 to listOf("android"), 10045 to listOf("com.google.android.gms"), 10067 to listOf("com.example.weather"),
            10087 to listOf("com.samsung.android.wear.shealth"), 10123 to listOf("com.example.drain"),
        ), s.uidPackages)
    }

    @Test
    fun textParserReadsWakeLocksReasonsAndPerUidDetails() {
        val s = BatteryStatsText.parse(sample("batterystats.txt"))!!
        assertEquals(7, s.partialWakeLocks.size)
        val job = s.partialWakeLocks.first()
        assertEquals(10123, job.uid)
        assertEquals("com.example.drain", job.pkg)
        assertEquals("*job*/com.example.drain/.SyncJob", job.tag)
        assertEquals(2530000L, job.totalMs)
        assertEquals(310, job.count)
        assertEquals(6, s.kernelWakeLocks.size)
        assertEquals("PowerManagerService.WakeLocks", s.kernelWakeLocks.first().tag)
        assertEquals(4320000L, s.kernelWakeLocks.first().totalMs)
        assertEquals(1210, s.kernelWakeLocks.first().count)
        assertEquals(4, s.wakeupReasons.size)
        assertEquals("173:qcom,smd-rpm:222:fc4", s.wakeupReasons.first().tag)
        assertEquals(1234, s.wakeupReasons.first().count)
        assertEquals("Abort:Pending Wakeup Sources: alarmtimer", s.wakeupReasons[1].tag)
        // 앱별 블록
        assertEquals(200, s.wakeupAlarmsByPkg["com.example.drain"])
        assertEquals(30, s.wakeupAlarmsByPkg["com.google.android.gms"])
        assertEquals(28200000L, s.sensorMsByUid[10123])
        assertEquals(29520000L + 6000000L, s.sensorMsByUid[10087])   // GPS 는 센서 합계에서 뺀다
    }

    @Test
    fun textParserToleratesMissingSections() {
        val only = """
            Statistics since last charge:
              Time on battery: 2h 0m 0s 0ms (99.0%) realtime, 20m 0s 0ms (16.6%) uptime
              Screen on: 5m 0s 0ms (4.1%) 3x, Interactive: 5m 0s 0ms (4.1%)
        """.trimIndent()
        val s = BatteryStatsText.parse(only)!!
        assertEquals(7200000L, s.onBatteryMs)
        assertEquals(300000L, s.screenOnMs)
        assertNull(s.screenOffMs)
        assertNull(s.screenOffAwakeMs)
        assertNull(s.screenOffAwakeRatio)
        assertNull(s.dischargePercent)
        assertNull(s.computedDrainMah)
        assertTrue(s.powerItems.isEmpty())
        assertTrue(s.partialWakeLocks.isEmpty())
        assertTrue(s.kernelWakeLocks.isEmpty())
        assertTrue(s.uidPackages.isEmpty())
        assertNull(BatteryStatsText.parse(null))
        assertNotNull(BatteryStatsText.parse("완전히 다른 텍스트"))
        assertNull(BatteryStatsText.parse("완전히 다른 텍스트")!!.onBatteryMs)
    }

    @Test
    fun checkinParserReadsTheSameFacts() {
        val s = BatteryStatsCheckin.parse(sample("batterystats_checkin.txt"))!!
        assertEquals(29523123L, s.onBatteryMs)
        assertEquals(28023000L, s.screenOffMs)
        assertEquals(4210000L, s.screenOffAwakeMs)
        assertEquals(1500123L, s.screenOnMs)
        assertEquals(390000L, s.radioActiveMs)
        assertEquals(40 to 41, s.dischargePercent)
        assertEquals(178, s.dischargeMah)
        assertEquals(6, s.dischargeScreenOn)
        assertEquals(34, s.dischargeScreenOff)
        assertEquals(445, s.capacityMah)
        assertEquals(182.0, s.computedDrainMah!!, 0.001)
        assertEquals(174.0 to 178.0, s.actualDrainMah)
        assertEquals(listOf("android", "com.android.settings"), s.uidPackages[1000])
        assertEquals(listOf("com.example.drain"), s.uidPackages[10123])
        // pwi
        val drain = s.powerItems.first { it.pkg == "com.example.drain" }
        assertEquals(48.5, drain.mah, 0.001)
        assertEquals("Screen", s.powerItems.first { it.label == "Screen" }.label)
        assertEquals(30.2, s.powerItems.first { it.label == "Screen" }.mah, 0.001)
        assertEquals("Cell standby", s.powerItems.first { it.mah == 3.1 }.label)
        // wl: 'p' 표식 앞이 partial 시간, 뒤가 횟수
        val job = s.partialWakeLocks.first { it.tag == "*job*/com.example.drain/.SyncJob" }
        assertEquals(10123, job.uid)
        assertEquals("com.example.drain", job.pkg)
        assertEquals(2530000L, job.totalMs)
        assertEquals(310, job.count)
        assertEquals(9, s.partialWakeLocks.size)
        // kwl / wr: 따옴표 속 쉼표가 있는 이름
        assertEquals(6, s.kernelWakeLocks.size)
        assertEquals("PowerManagerService.WakeLocks", s.kernelWakeLocks.first().tag)
        assertEquals(4320000L, s.kernelWakeLocks.first().totalMs)
        assertEquals("173:qcom,smd-rpm:222:fc4", s.wakeupReasons.first().tag)
        assertEquals(1200000L, s.wakeupReasons.first().totalMs)
        assertEquals(1234, s.wakeupReasons.first().count)
        // sr / wfl
        assertEquals(28200000L, s.sensorMsByUid[10123])
        assertEquals(29520000L + 6000000L, s.sensorMsByUid[10087])
        assertEquals(720000L, s.gpsMs)
        assertEquals(26, s.wifiScanCount)
    }

    @Test
    fun checkinIgnoresGarbageLines() {
        val s = BatteryStatsCheckin.parse("hello\n9,0,l,bt,0,notanumber\n9,0,i,uid,10001,com.example.one\n9,,,\n")!!
        assertNull(s.onBatteryMs)
        assertEquals(listOf("com.example.one"), s.uidPackages[10001])
        assertNull(BatteryStatsCheckin.parse(null))
    }

    @Test
    fun mergePrefersTextAndFillsGapsFromCheckin() {
        val full = sample("batterystats.txt")
        // 텍스트에서 partial wake lock 섹션과 radio 줄을 지운다
        val cut = full.lines().filterNot { it.contains("All partial wake locks") || (it.trim().startsWith("Wake lock u0a") && !it.contains("partial (")) || it.contains("Mobile radio active time") }
            .joinToString("\n")
        val text = BatteryStatsText.parse(cut)!!
        assertTrue(text.partialWakeLocks.isEmpty())
        assertNull(text.radioActiveMs)
        val checkin = BatteryStatsCheckin.parse(sample("batterystats_checkin.txt"))!!
        val m = BatteryStatsMerge.merge(text, checkin)!!
        assertEquals(29523123L, m.onBatteryMs)
        assertEquals(390000L, m.radioActiveMs)           // checkin 에서 채움
        assertEquals(9, m.partialWakeLocks.size)         // checkin 목록으로 채움
        assertEquals(28, m.wifiScanCount)                // 텍스트 값 유지
        assertEquals(12, m.powerItems.size)              // 텍스트 목록 유지
        assertEquals(listOf("android", "com.android.settings"), m.uidPackages[1000])   // 합집합
        // 한쪽만 있어도 된다
        assertEquals(checkin, BatteryStatsMerge.merge(null, checkin))
        assertEquals(text, BatteryStatsMerge.merge(text, null))
        assertNull(BatteryStatsMerge.merge(null, null))
    }
}
