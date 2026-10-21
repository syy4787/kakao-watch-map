package doctor

import doctor.fix.Fixer
import doctor.fix.Journal
import doctor.fix.Lever
import doctor.fix.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FixerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun journal() = Journal(File(tmp.root, "journal.jsonl"))

    private val wifi = Lever(
        id = "wifi-scan-off", title = "Wi-Fi 스캔 끄기", detail = "", tier = Tier.SAFE,
        readState = { it.run(listOf("shell", "settings", "get", "global", "wifi_scan_always_enabled")).out.trim() },
        apply = listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "0")),
        undo = { before -> listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", before ?: "1")) },
    )
    private val bg = Lever(
        id = "bg-restrict:com.example.drain", title = "백그라운드 제한", detail = "", tier = Tier.CAUTION, packages = listOf("com.example.drain"),
        readState = { "allow" },
        apply = listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "ignore")),
        undo = { before -> listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", before ?: "default")) },
    )
    private val clear = Lever(
        id = "clear:com.example.drain", title = "데이터 삭제", detail = "", tier = Tier.STRONG, packages = listOf("com.example.drain"),
        readState = { null }, apply = listOf(listOf("shell", "pm", "clear", "com.example.drain")), undo = { null },
    )

    @Test
    fun applyRecordsBeforeStateAndUndoCommands() {
        val adb = FakeAdb().apply { on("shell settings get global wifi_scan_always_enabled", "1\n") }
        val j = journal()
        assertTrue(Fixer.apply(adb, wifi, j, now = 5000L))
        assertTrue(adb.joined().contains("shell settings put global wifi_scan_always_enabled 0"))
        val e = j.entries().single()
        assertEquals("wifi-scan-off", e.id)
        assertEquals("1", e.before)
        assertEquals(5000L, e.time)
        assertEquals(wifi.apply, e.applied)
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "1")), e.undo)
        assertFalse(e.undone)
        assertTrue(e.ok)
    }

    @Test
    fun failedApplyIsRecordedAsNotOk() {
        val adb = FakeAdb().apply { on("shell settings put global wifi_scan_always_enabled 0", "", code = 1, err = "Security exception") }
        val j = journal()
        assertFalse(Fixer.apply(adb, wifi, j))
        val e = j.entries().single()
        assertFalse(e.ok)
        assertTrue(e.note!!.contains("Security exception"))
    }

    @Test
    fun undoAllReplaysInReverseAndSkipsUndoneOrIrreversible() {
        val adb = FakeAdb().apply { on("shell settings get global wifi_scan_always_enabled", "1\n") }
        val j = journal()
        Fixer.apply(adb, wifi, j, now = 1)
        Fixer.apply(adb, bg, j, now = 2)
        Fixer.apply(adb, clear, j, now = 3)
        adb.calls.clear()
        val n = Fixer.undo(adb, j, null)
        assertEquals(2, n)
        val cmds = adb.joined()
        assertEquals(listOf(
            "shell cmd appops set com.example.drain RUN_ANY_IN_BACKGROUND allow",
            "shell settings put global wifi_scan_always_enabled 1",
        ), cmds)
        val entries = j.entries()
        assertTrue(entries[0].undone)
        assertTrue(entries[1].undone)
        assertFalse(entries[2].undone)   // 되돌릴 수 없는 조치는 그대로
        // 다시 전부 되돌리기 → 할 일 없음
        adb.calls.clear()
        assertEquals(0, Fixer.undo(adb, j, null))
        assertTrue(adb.calls.isEmpty())
    }

    /** 모두 되돌리기는 끝에 그렇다는 기록을 남긴다 — 번호로 하나씩 되돌린 것과 가르려고(치료 전후 비교의 기준 · 리뷰). 번호로 되돌리면 남기지 않는다 */
    @Test
    fun undoAllLeavesAMarkerButUndoOneDoesNot() {
        val adb = FakeAdb().apply { on("shell settings get global wifi_scan_always_enabled", "1\n") }
        val j = journal()
        Fixer.apply(adb, wifi, j, now = 1)
        Fixer.apply(adb, bg, j, now = 2)
        Fixer.undo(adb, j, 1, now = 3)
        assertEquals(listOf("wifi-scan-off", "bg-restrict:com.example.drain"), j.entries().map { it.id })
        Fixer.undo(adb, j, null, now = 4)
        val last = j.entries().last()
        assertEquals(Fixer.UNDO_ALL, last.id)
        assertEquals(4L, last.time)
        assertFalse(last.undone)
        assertEquals(null, last.undo)
        assertEquals("되돌린 기록 1개", last.note)
        // 되돌린 것이 없으면(0개) 남기지 않는다 — 아무것도 바뀌지 않았으니 앞의 일회성 조치도 그대로 (리뷰)
        Fixer.undo(adb, j, null, now = 5)
        assertEquals(1, j.entries().count { it.id == Fixer.UNDO_ALL })
    }

    @Test
    fun undoOneByIndex() {
        val adb = FakeAdb().apply { on("shell settings get global wifi_scan_always_enabled", "0\n") }
        val j = journal()
        Fixer.apply(adb, wifi, j, now = 1)
        Fixer.apply(adb, bg, j, now = 2)
        adb.calls.clear()
        assertEquals(1, Fixer.undo(adb, j, 0))
        assertEquals(listOf("shell settings put global wifi_scan_always_enabled 0"), adb.joined())
        assertTrue(j.entries()[0].undone)
        assertFalse(j.entries()[1].undone)
        assertEquals(0, Fixer.undo(adb, j, 0))      // 이미 되돌림
        assertEquals(0, Fixer.undo(adb, j, 9))      // 없는 번호
    }

    @Test
    fun undoFailureLeavesEntryNotUndone() {
        val adb = FakeAdb().apply { on("shell settings get global wifi_scan_always_enabled", "1\n") }
        val j = journal()
        Fixer.apply(adb, wifi, j, now = 1)
        adb.on("shell settings put global wifi_scan_always_enabled 1", "", code = 1, err = "offline")
        assertEquals(0, Fixer.undo(adb, j, null))
        assertFalse(j.entries()[0].undone)
        assertNull(Fixer.lastError.takeIf { it.isNullOrBlank() })
    }
}
