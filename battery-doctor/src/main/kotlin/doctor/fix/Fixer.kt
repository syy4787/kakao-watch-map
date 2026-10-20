package doctor.fix

import doctor.AdbRunner

/** 조치 적용·되돌리기. 모든 적용은 이전 상태와 함께 [Journal] 에 남는다 */
object Fixer {
    /** 마지막 실패 사유(화면 표시용) */
    @Volatile var lastError: String? = null

    /** 이전 상태를 읽고 → 적용 명령을 차례로 실행 → 기록. 하나라도 실패하면 false(기록은 남김) */
    fun apply(adb: AdbRunner, lever: Lever, journal: Journal, now: Long = System.currentTimeMillis()): Boolean {
        val before = runCatching { lever.readState(adb) }.getOrNull()
        var ok = true
        val errors = ArrayList<String>()
        for (cmd in lever.apply) {
            val r = try { adb.run(cmd, 120) } catch (e: Exception) { doctor.Result(-3, "", e.toString()) }
            if (!r.ok) {
                ok = false
                errors += "${cmd.joinToString(" ")} → ${(r.err.ifBlank { r.out }).trim().lineSequence().firstOrNull() ?: "exit ${r.code}"}"
                break
            }
        }
        val undo = runCatching { lever.undo(before) }.getOrNull()
        journal.append(JournalEntry(now, lever.id, before, lever.apply, undo, undone = false, ok = ok, note = errors.joinToString("; ").ifEmpty { null }))
        lastError = errors.joinToString("; ").ifEmpty { null }
        return ok
    }

    /**
     * [index] 가 null 이면 기록 역순으로 전부, 아니면 그 번호(0부터) 하나만. 이미 되돌렸거나 되돌릴 수 없는 기록은 건너뛴다.
     * 되돌린 기록 수를 돌려준다.
     */
    fun undo(adb: AdbRunner, journal: Journal, index: Int?): Int {
        val entries = journal.entries()
        val targets: List<Int> = if (index == null) entries.indices.reversed().toList() else listOf(index).filter { it in entries.indices }
        var n = 0
        lastError = null
        for (i in targets) {
            val e = entries[i]
            if (e.undone) continue
            val cmds = e.undo ?: continue
            var ok = true
            for (cmd in cmds) {
                val r = try { adb.run(cmd, 120) } catch (ex: Exception) { doctor.Result(-3, "", ex.toString()) }
                if (!r.ok) {
                    ok = false
                    lastError = "${e.id}: ${cmd.joinToString(" ")} → ${(r.err.ifBlank { r.out }).trim().lineSequence().firstOrNull() ?: "exit ${r.code}"}"
                    break
                }
            }
            if (ok) { journal.markUndone(i); n++ }
        }
        return n
    }
}
