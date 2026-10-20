package doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CollectTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val expectedNames = listOf(
        "getprop", "battery", "batterystats", "batterystats_checkin", "power", "alarm", "sensorservice", "location",
        "deviceidle", "jobscheduler", "wearable", "gms_package", "packages_u", "packages_e", "packages_d", "packages_3",
        "settings_global", "settings_secure", "settings_system", "doze_whitelist", "date",
    )

    private fun happyAdb() = FakeAdb("192.168.0.23:5555").apply {
        on("shell getprop", "[ro.product.model]: [SM-L350]\n[ro.build.display.id]: [AZH4.260818.001]\n")
        on("shell dumpsys battery", "Current Battery Service state:\n  level: 52\n")
        on("shell date +%s", "1760878865\n")
        on("shell ls " + Collect.WATCH_CSV, Collect.WATCH_CSV + "\n")
        pullable[Collect.WATCH_CSV] = "# battery-log v1\nt,level\n"
    }

    @Test
    fun runsEveryCommandAndWritesAFilePerName() {
        val adb = happyAdb()
        val log = ArrayList<String>()
        val snap = Collect.run(adb, tmp.root, bugreport = false, log = { log += it }, now = 1760878865000L)
        assertTrue(snap.dir.isDirectory)
        assertEquals(File(tmp.root, "snapshots"), snap.dir.parentFile)
        for (name in expectedNames) {
            assertTrue("$name.txt 가 있어야 함", File(snap.dir, "$name.txt").isFile)
        }
        assertEquals(expectedNames, Collect.COMMANDS.map { it.first })
        // 모든 명령이 shell 로 호출됨
        val joined = adb.joined()
        assertTrue(joined.contains("shell dumpsys batterystats --checkin"))
        assertTrue(joined.contains("shell dumpsys activity service WearableService"))
        assertTrue(joined.contains("shell dumpsys package com.google.android.gms"))
        assertTrue(joined.contains("shell pm list packages -U"))
        assertTrue(joined.contains("shell cmd deviceidle whitelist"))
        assertTrue(joined.contains("shell settings list secure"))
        // 워치 CSV 는 ls 로 확인한 뒤 pull
        assertEquals(listOf(Collect.WATCH_CSV), adb.pulls.map { it.first })
        assertEquals("# battery-log v1\nt,level\n", File(snap.dir, "watch_log.csv").readText())
        // meta
        val meta = File(snap.dir, "meta.properties").readText()
        assertTrue(meta.contains("serial=192.168.0.23\\:5555") || meta.contains("serial=192.168.0.23:5555"))
        assertTrue(meta.contains("model=SM-L350"))
        assertTrue(meta.contains("time=1760878865000"))
        assertEquals("[ro.product.model]: [SM-L350]\n[ro.build.display.id]: [AZH4.260818.001]\n", snap.text("getprop"))
        assertEquals(1760878865000L, snap.time)   // date +%s(워치 시계)를 우선
        assertFalse(adb.joined().any { it.startsWith("bugreport") })
        assertTrue(log.any { it.contains("getprop") })
    }

    @Test
    fun oneFailureDoesNotStopTheRest() {
        val adb = happyAdb().apply {
            on("shell dumpsys batterystats --checkin", "", code = 1, err = "Can't find service: batterystats")
        }
        val snap = Collect.run(adb, tmp.root, bugreport = false, log = {}, now = 1L)
        assertFalse(File(snap.dir, "batterystats_checkin.txt").exists())
        val err = File(snap.dir, "batterystats_checkin.error.txt")
        assertTrue(err.isFile)
        assertTrue(err.readText().contains("Can't find service"))
        assertNull(snap.text("batterystats_checkin"))
        assertNotNull(snap.error("batterystats_checkin"))
        assertTrue(snap.failed().contains("batterystats_checkin"))
        // 뒤 명령들은 계속 수집됨
        assertTrue(File(snap.dir, "power.txt").isFile)
        assertTrue(File(snap.dir, "date.txt").isFile)
    }

    @Test
    fun missingWatchCsvIsSkipped() {
        val adb = happyAdb().apply {
            on("shell ls " + Collect.WATCH_CSV, "", code = 1, err = "ls: ${Collect.WATCH_CSV}: No such file or directory")
        }
        val snap = Collect.run(adb, tmp.root, bugreport = false, log = {}, now = 1L)
        assertTrue(adb.pulls.isEmpty())
        assertFalse(File(snap.dir, "watch_log.csv").exists())
        assertNull(snap.watchCsv)
    }

    @Test
    fun bugreportFlagRunsAdbBugreportIntoTheSnapshot() {
        val adb = happyAdb()
        val snap = Collect.run(adb, tmp.root, bugreport = true, log = {}, now = 1L)
        val call = adb.calls.firstOrNull { it.firstOrNull() == "bugreport" }
        assertNotNull(call)
        assertEquals(File(snap.dir, "bugreport.zip").path, call!![1])
    }

    @Test
    fun latestPicksTheNewestSnapshotFolder() {
        val root = tmp.root
        File(root, "snapshots/20261018-220000").mkdirs()
        File(root, "snapshots/20261019-220105").mkdirs()
        File(root, "snapshots/notes.txt").writeText("x")
        assertEquals("20261019-220105", Collect.latest(root)!!.dir.name)
        assertNull(Collect.latest(tmp.newFolder("empty")))
    }

    @Test
    fun snapshotTimeFallsBackToFolderName() {
        val dir = File(tmp.root, "snapshots/20261019-220105").apply { mkdirs() }
        val snap = Snapshot(dir)
        val expected = java.time.LocalDateTime.of(2026, 10, 19, 22, 1, 5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, snap.time)
    }
}
