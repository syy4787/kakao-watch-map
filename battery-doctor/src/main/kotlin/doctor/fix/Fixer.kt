package doctor.fix

import doctor.AdbRunner

/** 조치 적용·되돌리기. 모든 적용은 이전 상태와 함께 [Journal] 에 남는다 */
object Fixer {
    private const val WSS = "android.permission.WRITE_SECURE_SETTINGS"
    /** 앱의 '모두 되돌리기' (셸만 보낼 수 있는 받는 곳 — 워치 앱 ShellRestoreReceiver) */
    val RESTORE_BROADCAST = listOf(
        "shell", "am", "broadcast", "--include-stopped-packages",
        "-n", "${doctor.Grant.PKG}/.battery.ShellRestoreReceiver", "-a", "${doctor.Grant.PKG}.battery.SHELL_RESTORE",
    )

    /**
     * '모두 되돌리기'(undo --all)를 한 때 — 기록 끝에 남긴다. 그 앞의 일회성 조치(캐시 정리·Play 서비스 다시 시작처럼 되돌릴 것이 없는 것)도
     * 그때 함께 끝난 것으로 본다([doctor.Collect.actions]) — 번호로 하나씩 되돌린 것과 기록만으로는 가를 수 없어서 (리뷰)
     */
    const val UNDO_ALL = "undo-all"

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
     * [index] 가 null 이면 기록 역순으로 전부(끝에 [UNDO_ALL] 기록을 남김), 아니면 그 번호(0부터) 하나만. 이미 되돌렸거나 되돌릴 수 없는
     * 기록은 건너뛴다. 되돌린 기록 수를 돌려준다.
     */
    fun undo(adb: AdbRunner, journal: Journal, index: Int?, now: Long = System.currentTimeMillis()): Int {
        val entries = journal.entries()
        val targets: List<Int> = if (index == null) entries.indices.reversed().toList() else listOf(index).filter { it in entries.indices }
        var n = 0
        lastError = null
        for (i in targets) {
            val e = entries[i]
            if (e.undone) continue
            val cmds = e.undo ?: continue
            // 지킴이 권한(WRITE_SECURE_SETTINGS)을 거두기 전에 앱이 바꿔 둔 설정(밤 모드의 AOD·손목 올려 켜기·절전 등)을 되돌리게 한다 —
            // 거둔 뒤엔 앱이 되돌릴 수 없다. 앱이 예전 버전이라 받는 곳이 없어도 그냥 지나간다
            if (cmds.any { it.takeLast(2) == listOf(doctor.Grant.PKG, WSS) && "revoke" in it }) {
                runCatching { adb.run(RESTORE_BROADCAST, 30) }
            }
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
        if (index == null) journal.append(JournalEntry(now, UNDO_ALL, null, emptyList(), null, undone = false, ok = lastError == null, note = "되돌린 기록 ${n}개"))
        return n
    }
}
