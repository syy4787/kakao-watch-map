package doctor

import doctor.fix.Journal
import doctor.fix.JournalEntry
import doctor.fix.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JournalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun jsonRoundTripOfFlatObjectsAndStringLists() {
        val obj = linkedMapOf<String, Any?>(
            "s" to "따옴표 \" 역슬래시 \\ 줄바꿈 \n 탭 \t 유니코드 \u0001 끝",
            "n" to 1760878865000L, "d" to 1.5, "b" to true, "z" to null,
            "l" to listOf(listOf("shell", "settings", "put", "global", "k", "v"), listOf("reboot")),
            "e" to emptyList<String>(),
        )
        val text = Json.write(obj)
        assertFalse(text.contains('\n'))
        val back = Json.parse(text) as Map<*, *>
        assertEquals(obj["s"], back["s"])
        assertEquals(1760878865000L, (back["n"] as Number).toLong())
        assertEquals(1.5, (back["d"] as Number).toDouble(), 0.0)
        assertEquals(true, back["b"])
        assertNull(back["z"])
        assertTrue(back.containsKey("z"))
        assertEquals(obj["l"], back["l"])
        assertEquals(emptyList<Any>(), back["e"])
        assertEquals(mapOf("a" to listOf(1.0, "x", null)), Json.parse("""{ "a" : [ 1 , "x" , null ] }"""))
        assertEquals("a/b", Json.parse("\"a\\/b\""))
        assertEquals("한", Json.parse("\"\\ud55c\""))
    }

    @Test
    fun appendReadAndMarkUndone() {
        val file = File(tmp.root, "data/journal.jsonl")
        val j = Journal(file)
        assertTrue(j.entries().isEmpty())
        val e1 = JournalEntry(1000L, "wifi-scan-off", "1", listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "0")), listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "1")), false)
        val e2 = JournalEntry(2000L, "bg-restrict:com.example.drain", null, listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "ignore")), listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "default")), false, ok = true, note = "\"테스트\"")
        val e3 = JournalEntry(3000L, "clear:com.example.drain", null, listOf(listOf("shell", "pm", "clear", "com.example.drain")), null, false, ok = false)
        j.append(e1); j.append(e2); j.append(e3)
        assertEquals(3, file.readLines().size)
        assertEquals(listOf(e1, e2, e3), j.entries())
        j.markUndone(0)
        val again = Journal(file).entries()
        assertTrue(again[0].undone)
        assertFalse(again[1].undone)
        assertEquals(3, file.readLines().size)
        assertEquals(e2, again[1])
        assertNull(again[2].undo)
        assertFalse(again[2].ok)
    }

    @Test
    fun corruptLinesAreSkipped() {
        val file = File(tmp.root, "journal.jsonl")
        file.writeText("{\"time\":1,\"id\":\"a\",\"applied\":[[\"reboot\"]],\"undone\":false}\nnot json at all\n{\"time\":2,\"id\":\"b\",\"applied\":[],\"undone\":true}\n")
        val entries = Journal(file).entries()
        assertEquals(listOf("a", "b"), entries.map { it.id })
        assertTrue(entries[1].undone)
        assertNull(entries[0].before)
    }
}
