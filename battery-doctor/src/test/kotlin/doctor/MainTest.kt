package doctor

import doctor.fix.Journal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class MainTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val addr = "192.168.0.23:5555"

    @Test
    fun argumentParsing() {
        assertNull(Main.parse(arrayOf()).command)
        Main.parse(arrayOf("connect", addr)).let { assertEquals("connect", it.command); assertEquals(listOf(addr), it.positional) }
        Main.parse(arrayOf("collect", "--bugreport")).let { assertEquals("collect", it.command); assertTrue(it.has("bugreport")) }
        Main.parse(arrayOf("fix", "--apply", "wifi-scan-off,bg-restrict:com.example.drain")).let { assertEquals("wifi-scan-off,bg-restrict:com.example.drain", it.flag("apply")) }
        Main.parse(arrayOf("fix", "--apply=a,b", "--yes")).let { assertEquals("a,b", it.flag("apply")); assertTrue(it.has("yes")) }
        Main.parse(arrayOf("undo", "--all")).let { assertTrue(it.has("all")); assertTrue(it.positional.isEmpty()) }
        Main.parse(arrayOf("undo", "3")).let { assertFalse(it.has("all")); assertEquals(listOf("3"), it.positional) }
        Main.parse(arrayOf("evidence", "--purchase", "2026-08-07")).let { assertEquals("2026-08-07", it.flag("purchase")) }
        Main.parse(arrayOf("analyze", "/tmp/x")).let { assertEquals(listOf("/tmp/x"), it.positional) }
        assertEquals("help", Main.parse(arrayOf("--help")).command)
        Main.parse(arrayOf("treat", "--strong", "--yes", "--no-grant", "--bugreport")).let {
            assertEquals("treat", it.command)
            assertTrue(it.has("strong")); assertTrue(it.has("yes")); assertTrue(it.has("no-grant")); assertTrue(it.has("bugreport"))
        }
        Main.parse(arrayOf("treat")).let { assertFalse(it.has("strong")); assertFalse(it.has("no-grant")) }
        assertEquals("grant", Main.parse(arrayOf("grant")).command)
    }

    @Test
    fun helpListsGrantAndTreat() {
        val (code, out) = run(fakeAdb(), tmp.newFolder("data"), "help")
        assertEquals(0, code)
        assertTrue(out.contains("grant"))
        assertTrue(out.contains("treat [--strong] [--yes] [--no-grant] [--bugreport]"))
        assertTrue(out.contains("undo --all"))
    }

    @Test
    fun menuHasGrantAndTreatItems() {
        val (code, out) = run(fakeAdb(), tmp.newFolder("data"), answers = listOf("9", "0"))
        assertEquals(0, code)
        assertTrue(out.contains(" 7  권한 주기"))
        assertTrue(out.contains(" 8  자동 치료"))
        assertTrue(out.contains("0~8 중에서 고르세요"))
    }

    private fun fakeAdb() = FakeAdb(addr).apply {
        on("devices", "List of devices attached\n$addr\tdevice\n\n")
        on("shell getprop", sample("getprop.txt"))
        on("shell settings get global wifi_scan_always_enabled", "1\n")
        on("shell cmd appops get com.example.drain RUN_ANY_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND: allow\n")
        on("shell dumpsys batterystats --reset", "Battery stats reset.\n")
    }

    private fun run(adb: FakeAdb, data: File, vararg args: String, answers: List<String> = emptyList()): Pair<Int, String> {
        val io = FakeConsole(answers)
        val code = Main.run(args.toList().toTypedArray(), io, data, { adb }, zone, LocalDate.of(2026, 10, 20), clock = { 1792414865000L }, open = { false })
        return code to io.text()
    }

    @Test
    fun analyzeWritesReportAndPrintsVerdict() {
        val data = tmp.newFolder("data")
        val snap = sampleSnapshot(data)
        val (code, out) = run(fakeAdb(), data, "analyze")
        assertEquals(out, 0, code)
        assertTrue(File(snap.dir, "report.html").isFile)
        assertTrue(out.contains("판정"))
        assertTrue(out.contains(Cause.SOFTWARE.label))
        assertTrue(out.contains("report.html"))
        assertTrue(out.contains("지금 할 일"))
    }

    /** 두 번째 수집부터는 지난 수집과 나란히 — 사이에 한 조치와 함께 (같은 기록이면 '차이가 크지 않음') */
    @Test
    fun analyzeComparesWithThePreviousCollection() {
        val data = tmp.newFolder("data")
        val first = sampleSnapshot(data)
        val second = File(data, "snapshots/20261021-090000").apply { mkdirs() }
        first.dir.listFiles()!!.filter { it.isFile && it.name != "report.html" }.forEach { it.copyTo(File(second, it.name), overwrite = true) }
        val t1 = Snapshot(first.dir).time
        File(second, "date.txt").writeText(((t1 + 35 * 3_600_000L) / 1000).toString())
        Journal(File(data, "journal.jsonl")).append(doctor.fix.JournalEntry(t1 + 3_600_000L, "trim-caches", null, emptyList(), null, false))
        val (code, out) = run(fakeAdb(), data, "analyze")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("지난 수집과 비교"))
        assertTrue(out, out.contains("trim-caches"))
        assertTrue(out, out.contains("차이가 크지 않"))
        assertTrue(File(second, "report.html").readText().contains("치료 전후 비교"))
        // 처음 수집이면 비교 없음
        val (_, firstOut) = run(fakeAdb(), data, "analyze", first.dir.path)
        assertFalse(firstOut.contains("지난 수집과 비교"))
    }

    @Test
    fun analyzeWithoutSnapshotExplains() {
        val (code, out) = run(fakeAdb(), tmp.newFolder("data"), "analyze")
        assertEquals(1, code)
        assertTrue(out.contains("스냅샷"))
    }

    @Test
    fun resetRunsBatterystatsResetAndExplainsProtocol() {
        val adb = fakeAdb()
        val (code, out) = run(adb, tmp.newFolder("data"), "reset")
        assertEquals(out, 0, code)
        assertTrue(adb.joined().contains("shell dumpsys batterystats --reset"))
        assertTrue(out.contains("자동 Wi-Fi 끄기"))
        assertTrue(out.contains("충전기"))
    }

    @Test
    fun commandsNeedingADeviceFailClearlyWithoutOne() {
        val adb = FakeAdb().apply { on("devices", "List of devices attached\n\n") }
        val (code, out) = run(adb, tmp.newFolder("data"), "reset")
        assertEquals(1, code)
        assertTrue(out.contains("연결"))
        assertFalse(adb.joined().any { it.contains("--reset") })
    }

    @Test
    fun fixApplyAndUndoAllThroughTheJournal() {
        val data = tmp.newFolder("data")
        sampleSnapshot(data)
        val adb = fakeAdb()
        val (code, out) = run(adb, data, "fix", "--apply", "wifi-scan-off,bg-restrict:com.example.drain")
        assertEquals(out, 0, code)
        val j = adb.joined()
        assertTrue(j.contains("shell settings put global wifi_scan_always_enabled 0"))
        assertTrue(j.contains("shell cmd appops set com.example.drain RUN_ANY_IN_BACKGROUND ignore"))
        val journal = Journal(File(data, "journal.jsonl"))
        assertEquals(listOf("wifi-scan-off", "bg-restrict:com.example.drain"), journal.entries().map { it.id })
        assertEquals("1", journal.entries()[0].before)
        assertTrue(out.contains("측정 시작") || out.contains("reset"))

        adb.calls.clear()
        val (code2, out2) = run(adb, data, "undo", "--all")
        assertEquals(out2, 0, code2)
        assertEquals(listOf("shell cmd appops set com.example.drain RUN_ANY_IN_BACKGROUND allow", "shell settings put global wifi_scan_always_enabled 1"), adb.joined().filter { it != "devices" })
        assertTrue(journal.entries().all { it.undone })
    }

    @Test
    fun fixRefusesUnknownOrDeniedIdsAndStrongNeedsYes() {
        val data = tmp.newFolder("data")
        sampleSnapshot(data)
        val adb = fakeAdb()
        val (code, out) = run(adb, data, "fix", "--apply", "disable:com.google.android.gms,nonsense")
        assertEquals(1, code)
        assertTrue(out.contains("없는 조치") || out.contains("거부"))
        assertFalse(adb.joined().any { it.contains("disable-user") })
        // 강함 등급은 --yes 없이는 적용하지 않음(비대화형)
        val (code2, out2) = run(adb, data, "fix", "--apply", "reboot")
        assertEquals(1, code2)
        assertTrue(out2.contains("강함"))
        assertFalse(adb.joined().any { it == "reboot" })
        val (code3, _) = run(adb, data, "fix", "--apply", "reboot", "--yes")
        assertEquals(0, code3)
        assertTrue(adb.joined().any { it == "reboot" })
    }

    @Test
    fun interactiveFixAppliesRecommendedOnEnter() {
        val data = tmp.newFolder("data")
        sampleSnapshot(data)
        val adb = fakeAdb()
        val (code, out) = run(adb, data, "fix", answers = listOf(""))
        assertEquals(out, 0, code)
        assertTrue(out.contains("추천"))
        val ids = Journal(File(data, "journal.jsonl")).entries().map { it.id }
        assertTrue(ids.contains("wifi-scan-off"))
        assertTrue(ids.contains("bg-restrict:com.example.drain"))
        assertFalse(ids.any { it.startsWith("clear:") || it == "reboot" || it == "gms-rollback" })
    }

    @Test
    fun undoOneByDisplayedNumber() {
        val data = tmp.newFolder("data")
        sampleSnapshot(data)
        val adb = fakeAdb()
        run(adb, data, "fix", "--apply", "wifi-scan-off,bg-restrict:com.example.drain")
        adb.calls.clear()
        val (code, _) = run(adb, data, "undo", "1")
        assertEquals(0, code)
        assertEquals(listOf("shell settings put global wifi_scan_always_enabled 1"), adb.joined().filter { it != "devices" })
        val entries = Journal(File(data, "journal.jsonl")).entries()
        assertTrue(entries[0].undone)
        assertFalse(entries[1].undone)
    }

    @Test
    fun evidenceBuildsZipFromLatestSnapshot() {
        val data = tmp.newFolder("data")
        sampleSnapshot(data)
        val (code, out) = run(fakeAdb(), data, "evidence", "--purchase", "2026-08-07")
        assertEquals(out, 0, code)
        assertTrue(File(data, "evidence/20261020.zip").isFile)
        assertTrue(File(data, "evidence/20261020/share/README_공유안내.txt").isFile)
        assertTrue(out.contains("share"))
        assertEquals("2026-08-07", Config(data).purchaseDate)   // 구매일은 저장해 둔다
    }

    @Test
    fun collectThenAnalyzeUsesTheFakeDevice() {
        val data = tmp.newFolder("data")
        val adb = fakeAdb().apply {
            for ((name, args) in Collect.COMMANDS) on("shell " + args.joinToString(" "), sample("$name.txt"))
            on("shell ls " + Collect.WATCH_CSV, Collect.WATCH_CSV + "\n")
            pullable[Collect.WATCH_CSV] = sample("watch_log.csv")
        }
        val (code, out) = run(adb, data, "collect")
        assertEquals(out, 0, code)
        val snap = Collect.latest(data)!!
        assertTrue(File(snap.dir, "report.html").isFile)
        assertTrue(File(snap.dir, "watch_log.csv").isFile)
        assertTrue(out.contains(Cause.SOFTWARE.label))
    }

    @Test
    fun menuShowsSixItemsAndExitsOnZero() {
        val (code, out) = run(fakeAdb(), tmp.newFolder("data"), answers = listOf("0"))
        assertEquals(0, code)
        for (item in listOf("1", "2", "3", "4", "5", "6", "0")) assertTrue(out.contains(" $item "))
        assertTrue(out.contains("연결"))
        assertTrue(out.contains("증거 패키지"))
    }

    @Test
    fun unknownCommandPrintsHelp() {
        val (code, out) = run(fakeAdb(), tmp.newFolder("data"), "frobnicate")
        assertEquals(2, code)
        assertTrue(out.contains("connect"))
        assertTrue(out.contains("evidence"))
    }
}
