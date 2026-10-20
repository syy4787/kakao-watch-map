package doctor

import doctor.parse.AlarmDump
import doctor.parse.BatteryDump
import doctor.parse.DeviceIdleDump
import doctor.parse.GmsPackage
import doctor.parse.LocationDump
import doctor.parse.Packages
import doctor.parse.PowerDump
import doctor.parse.SensorDump
import doctor.parse.SettingsDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpParsersTest {

    @Test
    fun batteryDumpKeepsSamsungFieldsVerbatim() {
        val b = BatteryDump.parse(sample("battery.txt"))!!
        assertEquals(48, b.level)
        assertEquals(100, b.scale)
        assertEquals(3812, b.voltageMv)
        assertEquals(298, b.tempDeciC)
        assertEquals("2", b.health)
        assertEquals("양호", b.healthLabel)
        assertEquals("3", b.status)
        assertEquals("방전 중", b.statusLabel)
        assertEquals(196800, b.chargeCounterUah)
        assertEquals(
            mapOf("mSavedBatteryAsoc" to "96", "mSavedBatteryMaxTableWh" to "1700", "mSavedBatteryUsage" to "1234", "mSavedBatteryBsoh" to "98.4"),
            b.samsung,
        )
        assertEquals(96, b.asoc)
        assertEquals(410.0, b.impliedCapacityMah!!, 0.01)   // 196800 µAh / 48%
    }

    @Test
    fun batteryDumpWithoutFieldsIsAllNull() {
        val b = BatteryDump.parse("Current Battery Service state:\n  present: true\n")!!
        assertNull(b.level)
        assertNull(b.chargeCounterUah)
        assertNull(b.impliedCapacityMah)
        assertTrue(b.samsung.isEmpty())
        assertNull(b.asoc)
        assertNull(BatteryDump.parse(null))
    }

    @Test
    fun alarmStatsPerPackage() {
        val a = AlarmDump.parse(sample("alarm.txt"))
        assertEquals(30612000L, a.elapsedMs)
        val drain = a.stats.first { it.pkg == "com.example.drain" }
        assertEquals(200, drain.wakeups)
        assertEquals(192543L, drain.totalMs)
        assertEquals(420, drain.count)
        assertEquals(30, a.stats.first { it.pkg == "com.google.android.gms" }.wakeups)
        assertEquals(12, a.stats.first { it.pkg == "android" }.wakeups)
        assertEquals(510, a.stats.first { it.pkg == "android" }.count)
        assertEquals(5, a.stats.size)
        assertEquals(200.0 / (30612000.0 / 3_600_000), drain.wakeupsPerHour(a.elapsedMs)!!, 0.01)
    }

    @Test
    fun alarmFallsBackToTopAlarmsAndToleratesEmpty() {
        val top = """
              Top Alarms:
                +3m12s543ms running, 200 wakeups, 420 alarms: u0a123:com.example.drain
                  *walarm*:com.example.drain.SYNC
                +1m0s0ms running, 12 wakeups, 510 alarms: 1000:android
                  *alarm*:android.intent.action.TIME_TICK
        """.trimIndent()
        val a = AlarmDump.parse(top)
        assertNull(a.elapsedMs)
        assertEquals(2, a.stats.size)
        assertEquals(200, a.stats[0].wakeups)
        assertEquals("com.example.drain", a.stats[0].pkg)
        assertTrue(AlarmDump.parse(null).stats.isEmpty())
        assertTrue(AlarmDump.parse("nothing here").stats.isEmpty())
    }

    @Test
    fun sensorConnectionsWithRates() {
        val uses = SensorDump.parse(sample("sensorservice.txt"))
        assertEquals(4, uses.size)
        val drain = uses.first { it.pkg == "com.example.drain" }
        assertEquals("LSM6DSO Accelerometer", drain.sensor)
        assertEquals("android.sensor.accelerometer", drain.type)
        assertTrue(drain.active)
        assertEquals(50.0, drain.rateHz!!, 0.01)   // samplingPeriod=20000us
        val hr = uses.first { it.pkg == "com.samsung.android.wear.shealth" && it.type == "android.sensor.heart_rate" }
        assertEquals(1.0, hr.rateHz!!, 0.01)
        assertTrue(hr.active)
        // 등록 해제된 GMS 는 활성 목록에 없다
        assertFalse(uses.any { it.pkg == "com.google.android.gms" })
        assertTrue(SensorDump.parse(null).isEmpty())
        assertTrue(SensorDump.parse("Sensor List:\n").isEmpty())
    }

    @Test
    fun locationListeners() {
        val l = LocationDump.parse(sample("location.txt"))
        assertEquals(3, l.size)
        val drain = l.first { it.pkg == "com.example.drain" }
        assertEquals("fused", drain.provider)
        assertEquals(30000L, drain.intervalMs)
        val gps = l.first { it.provider == "gps" }
        assertEquals("com.samsung.android.wear.shealth", gps.pkg)
        assertEquals(1000L, gps.intervalMs)
        assertEquals(3600000L, l.first { it.pkg == "com.google.android.gms" }.intervalMs)
        assertTrue(LocationDump.parse(null).isEmpty())
    }

    @Test
    fun deviceIdleStateAndWhitelists() {
        val withCmd = DeviceIdleDump.parse(sample("deviceidle.txt"), sample("doze_whitelist.txt"))!!
        assertEquals("IDLE", withCmd.state)
        assertEquals("OVERRIDE", withCmd.lightState)
        assertEquals(listOf("com.android.providers.calendar", "com.google.android.gms", "com.android.providers.downloads", "com.example.drain"), withCmd.whitelist)
        assertEquals(listOf("com.example.drain"), withCmd.userWhitelist)
        val dumpOnly = DeviceIdleDump.parse(sample("deviceidle.txt"), null)!!
        assertEquals(listOf("com.android.providers.calendar", "com.google.android.gms", "com.android.providers.downloads", "com.example.drain"), dumpOnly.whitelist)
        assertEquals(listOf("com.example.drain"), dumpOnly.userWhitelist)
        val cmdOnly = DeviceIdleDump.parse(null, sample("doze_whitelist.txt"))!!
        assertNull(cmdOnly.state)
        assertEquals(4, cmdOnly.whitelist.size)
        assertNull(DeviceIdleDump.parse(null, null))
    }

    @Test
    fun packagesCombineTheFourLists() {
        val p = Packages.parse(sample("packages_u.txt"), sample("packages_e.txt"), sample("packages_d.txt"), sample("packages_3.txt"))
        assertEquals(21, p.size)
        val drain = p.first { it.name == "com.example.drain" }
        assertEquals(10123, drain.uid)
        assertTrue(drain.enabled)
        assertTrue(drain.thirdParty)
        val msg = p.first { it.name == "com.google.android.apps.messaging" }
        assertFalse(msg.enabled)
        assertFalse(msg.thirdParty)
        val android = p.first { it.name == "android" }
        assertEquals(1000, android.uid)
        assertFalse(android.thirdParty)
        assertEquals(listOf("com.example.drain"), Packages.byUid(p, 10123).map { it.name })
        // -U 없이 -3 만 있어도 목록이 된다
        val only3 = Packages.parse(null, null, null, sample("packages_3.txt"))
        assertEquals(3, only3.size)
        assertNull(only3[0].uid)
        assertTrue(Packages.parse(null, null, null, null).isEmpty())
    }

    @Test
    fun settingsKeyValues() {
        val g = SettingsDump.parse(sample("settings_global.txt"))
        assertEquals("1", g["wifi_scan_always_enabled"])
        assertEquals("0", g["adaptive_battery_management_enabled"])
        assertEquals("Galaxy Watch9 (ABCD)", g["device_name"])
        assertEquals(15, g.size)
        assertEquals("a=b=c".let { SettingsDump.parse(it)["a"] }, "b=c")
        assertTrue(SettingsDump.parse(null).isEmpty())
        assertTrue(SettingsDump.parse("no equals sign here\n").isEmpty())
    }

    @Test
    fun powerDumpHeldWakeLocks() {
        val p = PowerDump.parse(sample("power.txt"))!!
        assertEquals("Asleep", p.wakefulness)
        assertEquals("OFF", p.displayState)
        assertFalse(p.interactive)
        assertEquals(2, p.heldWakeLocks.size)
        val job = p.heldWakeLocks[0]
        assertEquals("PARTIAL_WAKE_LOCK", job.type)
        assertEquals("*job*/com.example.drain/.SyncJob", job.tag)
        assertEquals(10123, job.uid)
        assertEquals("com.example.drain", job.pkg)
        assertEquals(192345L, job.heldMs)
        assertEquals(1041, p.heldWakeLocks[1].uid)
        assertNull(p.heldWakeLocks[1].pkg)
        assertNull(PowerDump.parse(null))
        assertTrue(PowerDump.parse("Power Manager State:\n  mWakefulness=Awake\n")!!.heldWakeLocks.isEmpty())
        assertTrue(PowerDump.parse("Power Manager State:\n  mWakefulness=Awake\n")!!.interactive)
    }

    @Test
    fun gmsVersionName() {
        assertEquals("26.39.35 (240400-123456789)", GmsPackage.versionName(sample("gms_package.txt")))
        assertNull(GmsPackage.versionName("no version"))
        assertNull(GmsPackage.versionName(null))
        assertNotNull(GmsPackage.versionCode(sample("gms_package.txt")))
        assertEquals(263935020L, GmsPackage.versionCode(sample("gms_package.txt")))
    }
}
