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

    /**
     * 워치 앱 요약은 가져오기 전에 새로 쓰게 한다(셸만 보낼 수 있는 받는 곳 — 워치 앱 ShellRestoreReceiver) — 하루 한 번 쓴 묵은
     * 요약이 아니라 지금의 알려진 문제·지표로 보고서와 자동 치료를 고르게
     */
    @Test
    fun theWatchSummaryIsRefreshedBeforeItIsPulled() {
        val adb = happyAdb().apply {
            on("shell am broadcast", "Broadcasting: Intent { flg=0x400000 }\nBroadcast completed: result=0, data=\"summary:ok\"\n")
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[지표] at=1760878800000\n"
        }
        val log = ArrayList<String>()
        Collect.run(adb, tmp.root, bugreport = false, log = { log += it }, now = 1760878865000L)
        val refresh = adb.calls.indexOfFirst { it.joinToString(" ").contains(Collect.SUMMARY_ACTION) }
        val list = adb.calls.indexOfFirst { it == listOf("shell", "ls", Collect.WATCH_SUMMARY) }
        assertTrue(adb.calls.toString(), refresh in 0 until list)
        assertTrue(adb.calls[refresh].toString(), adb.calls[refresh].containsAll(listOf("-n", Collect.SHELL_RECEIVER)))
        assertTrue(log.toString(), log.any { it.contains("워치 앱 요약 새로 쓰기") })
        assertTrue(log.toString(), log.none { it.contains("있던 요약") })
    }

    /**
     * 수집 → 분석까지: 워치 시계(date)는 요약을 새로 쓰기 전에 읽으므로 새 요약의 'at' 이 수집 시각보다 몇 초 늦다 — 그래도 '방금 쓴
     * 요약'으로 보고 워치가 짚은 Play 서비스 과소모를 소견으로 가져온다 (리뷰에서 찾음: 늘 '미래'라 버려졌다)
     */
    @Test
    fun aRefreshedSummaryReachesTheFindings() {
        val adb = happyAdb().apply {
            on("shell am broadcast", "Broadcast completed: result=0, data=\"summary:ok\"\n")
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[판정] 주의\n[지표] at=${1760878865L * 1000 + 5_000} idle_pph=3.1 app_window_min=120 issue_gms=2 issue_home=0 back_gms=0 back_home=0\n"
        }
        val snap = Collect.run(adb, tmp.root, bugreport = false, log = {}, now = 1760878865000L)
        val f = Analyzer.facts(snap, java.time.ZoneId.of("Asia/Seoul"))
        assertTrue(Analyzer.findings(f).map { it.id }.toString(), Analyzer.findings(f).any { it.id == "watch-gms" })
    }

    /** 요약이 아예 없으면(기록 전) '있던 요약을 가져온다'고 하지 않는다 */
    @Test
    fun noSummaryNoFallbackLine() {
        val adb = happyAdb().apply { on("shell am broadcast", "", code = 1) }
        val log = ArrayList<String>()
        Collect.run(adb, tmp.root, bugreport = false, log = { log += it }, now = 1760878865000L)
        assertTrue(log.toString(), log.none { it.contains("있던 요약") })
    }

    /** 예전 워치 앱(이 신호를 모름)이나 실패면 있던 요약을 그대로 가져오고 그렇다고 적는다 */
    @Test
    fun anOldWatchAppKeepsItsLastSummary() {
        val adb = happyAdb().apply {
            on("shell am broadcast", "Broadcasting: Intent { flg=0x400000 }\nBroadcast completed: result=0\n")
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[지표] at=1760800000000\n"
        }
        val log = ArrayList<String>()
        val snap = Collect.run(adb, tmp.root, bugreport = false, log = { log += it }, now = 1760878865000L)
        assertTrue(log.toString(), log.any { it.contains("있던 요약") })
        assertNotNull(snap.watchSummary)
    }

    /** 수집 폴더 하나 — 워치 시계(date)와 PC 시계(meta time)를 따로 줄 수 있게 */
    private fun snapAt(name: String, pcAt: Long, watchAt: Long = pcAt): Snapshot {
        val dir = File(tmp.root, "snapshots/$name").apply { mkdirs() }
        File(dir, "date.txt").writeText((watchAt / 1000).toString())
        File(dir, "meta.properties").writeText("time=$pcAt\n")
        return Snapshot(dir)
    }

    /**
     * 치료 전후 비교의 '전'은 바로 앞 수집이 아니라 마지막 조치(되돌리지 않은 것)보다 앞선 마지막 수집 — 자동 치료 10분 뒤에 확인하려고
     * 한 번 더 수집했어도 하루 뒤 수집이 그 10분짜리(초기화 직후라 비교할 값이 없음)가 아니라 치료 전 수집과 견주게 (시나리오 점검).
     * 조치 시각(PC 시계)은 수집의 PC 시계와 견준다 — 워치 시계가 몇 분 앞서도 치료 전 수집을 놓치지 않게
     */
    @Test
    fun theBaselineIsTheCollectionBeforeTheLastTreatment() {
        val h = 3_600_000L
        val t0 = 1_792_000_000_000L
        val a = snapAt("20261014-100000", t0, watchAt = t0 + 3 * 60_000L)          // 워치 시계가 3분 앞섬
        val b = snapAt("20261014-101000", t0 + 10 * 60_000L)
        val c = snapAt("20261015-120000", t0 + 26 * h)
        fun entry(at: Long, undone: Boolean = false) = doctor.fix.JournalEntry(at, "trim-caches", null, emptyList(), null, undone)
        val treated = listOf(entry(t0 + 60_000L))
        assertEquals(a.dir, Collect.baseline(tmp.root, c, treated)!!.dir)
        assertEquals(a.dir, Collect.baseline(tmp.root, b, treated)!!.dir)
        assertNull("처음 수집", Collect.baseline(tmp.root, a, treated))
        // 조치가 없거나 되돌렸거나 일주일이 넘었으면 바로 앞 수집
        assertEquals(b.dir, Collect.baseline(tmp.root, c, emptyList())!!.dir)
        assertEquals(b.dir, Collect.baseline(tmp.root, c, listOf(entry(t0 + 60_000L, undone = true)))!!.dir)
        val d = snapAt("20261023-120000", t0 + 9 * 24 * h)
        assertEquals(c.dir, Collect.baseline(tmp.root, d, treated)!!.dir)
        // PC 시계
        assertEquals(t0, a.pcTime)
        assertEquals(t0 + 3 * 60_000L, a.time)
    }

    /**
     * '조치 전'을 고를 때 아무것도 바꾸지 않은 기록(이미 있던 권한 — 되돌릴 것 없음)은 조치로 치지 않는다. 되돌릴 수 없는 일회성 조치(캐시
     * 정리·Play 서비스 다시 시작)는 같은 때 한 조치를 되돌렸으면(undo --all) 함께 끝난 것으로 — 되돌린 뒤 일주일 내내 치료 전과 견주지 않게 (리뷰)
     */
    @Test
    fun noOpGrantsAndUndoneRunsDoNotMoveTheBaseline() {
        val h = 3_600_000L
        val t0 = 1_792_000_000_000L
        val a = snapAt("20261014-100000", t0)
        val b = snapAt("20261014-120000", t0 + 2 * h)
        val c = snapAt("20261015-120000", t0 + 26 * h)
        fun e(at: Long, id: String, undo: List<List<String>>?, undone: Boolean = false) = doctor.fix.JournalEntry(at, id, null, emptyList(), undo, undone)
        val revoke = listOf(listOf("shell", "pm", "revoke", "com.mywatch.kakaomap", "android.permission.BATTERY_STATS"))
        // b 바로 전에 자동 치료를 다시 했는데 권한은 이미 있었다(되돌릴 것 없음) — 조치가 아니니 바로 앞 수집(b)과
        assertEquals(b.dir, Collect.baseline(tmp.root, c, listOf(e(t0 + 2 * h - 60_000L, "grant:android.permission.BATTERY_STATS", emptyList())))!!.dir)
        // 실제로 준 권한(되돌릴 것 있음)은 조치
        assertEquals(a.dir, Collect.baseline(tmp.root, c, listOf(e(t0 + 2 * h - 60_000L, "grant:android.permission.BATTERY_STATS", revoke)))!!.dir)
        // a 뒤 자동 치료(되돌릴 수 있는 조치 + 일회성 조치)를 undo --all 로 되돌렸으면 일회성도 끝난 것
        val run = listOf(e(t0 + 60_000L, "wifi-scan-off", revoke, undone = true), e(t0 + 90_000L, "trim-caches", null))
        assertEquals(b.dir, Collect.baseline(tmp.root, c, run)!!.dir)
        assertEquals(a.dir, Collect.baseline(tmp.root, c, run.map { it.copy(undone = false) })!!.dir)
        // 일회성 조치만 한 치료는 되돌릴 것이 없어 그대로 치료 전(a)과
        assertEquals(a.dir, Collect.baseline(tmp.root, c, listOf(e(t0 + 90_000L, "trim-caches", null)))!!.dir)
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
    fun theWatchAppSummaryIsPulledWhenPresent() {
        val adb = happyAdb().apply {
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[사용 내역 24시간] 22시간 동안 61% 중 화면(1시간) 30% · 대기(21시간) 31%\n"
        }
        val snap = Collect.run(adb, tmp.root, bugreport = false, log = {}, now = 1760878865000L)
        assertEquals(listOf(Collect.WATCH_CSV, Collect.WATCH_SUMMARY), adb.pulls.map { it.first })
        assertTrue(snap.watchSummary!!.contains("사용 내역"))
        // 없으면 그냥 넘어간다 (예전 워치 앱)
        val older = Collect.run(happyAdb(), tmp.newFolder("older"), bugreport = false, log = {}, now = 1760878865000L)
        assertEquals(null, older.watchSummary)
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
