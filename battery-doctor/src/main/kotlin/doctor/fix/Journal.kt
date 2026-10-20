package doctor.fix

import java.io.File

/** 조치 기록 한 줄. [undo] 가 null 이면 되돌릴 수 없는 조치(기록만) */
data class JournalEntry(
    val time: Long,
    val id: String,
    val before: String?,
    val applied: List<List<String>>,
    val undo: List<List<String>>?,
    val undone: Boolean,
    /** 적용 명령이 전부 성공했나 */
    val ok: Boolean = true,
    val note: String? = null,
) {
    fun toJson(): String = Json.write(linkedMapOf("time" to time, "id" to id, "before" to before, "applied" to applied, "undo" to undo, "undone" to undone, "ok" to ok, "note" to note))

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(line: String): JournalEntry? {
            val m = runCatching { Json.parse(line) as? Map<String, Any?> }.getOrNull() ?: return null
            val id = m["id"] as? String ?: return null
            fun cmds(v: Any?): List<List<String>>? = (v as? List<*>)?.map { row -> (row as? List<*>)?.map { it.toString() } ?: emptyList() }
            return JournalEntry(
                time = (m["time"] as? Number)?.toLong() ?: 0L,
                id = id,
                before = m["before"] as? String,
                applied = cmds(m["applied"]) ?: emptyList(),
                undo = cmds(m["undo"]),
                undone = m["undone"] == true,
                ok = m["ok"] != false,
                note = m["note"] as? String,
            )
        }
    }
}

/** `data/journal.jsonl` — 한 줄에 JSON 객체 하나. 깨진 줄은 건너뛴다 */
class Journal(val file: File) {
    fun append(e: JournalEntry) {
        file.parentFile?.mkdirs()
        file.appendText(e.toJson() + "\n", Charsets.UTF_8)
    }

    fun entries(): List<JournalEntry> =
        if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let { JournalEntry.fromJson(it) } }

    /** [index] 번째(0부터, entries() 순서) 기록을 되돌림으로 표시 — 파일을 통째로 다시 쓴다 */
    fun markUndone(index: Int) {
        val all = entries()
        if (index !in all.indices) return
        val updated = all.mapIndexed { i, e -> if (i == index) e.copy(undone = true) else e }
        file.writeText(updated.joinToString("") { it.toJson() + "\n" }, Charsets.UTF_8)
    }
}
