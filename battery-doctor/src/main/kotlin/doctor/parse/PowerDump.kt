package doctor.parse

/** 지금 쥐고 있는 웨이크락 하나 */
data class HeldWakeLock(val type: String, val tag: String, val uid: Int?, val pkg: String?, val heldMs: Long?) {
    val display: String get() = "${pkg ?: uid?.toString() ?: "?"} · $tag (${Durations.human(heldMs)})"
}

/** `dumpsys power` 요약 */
data class PowerState(val wakefulness: String?, val displayState: String?, val heldWakeLocks: List<HeldWakeLock>) {
    /** 덤프 시점에 화면이 켜져 있었나 */
    val interactive: Boolean get() = wakefulness.equals("Awake", true) || wakefulness.equals("Dreaming", true)
}

object PowerDump {
    private val wakefulness = Regex("mWakefulness=(\\w+)")
    private val display = Regex("Display Power: state=(\\w+)")
    private val lock = Regex("^\\s*(\\w+_WAKE_LOCK)\\s+'(.*?)'\\s*(.*)$")
    private val acq = Regex("ACQ=([+-]?[\\dhmsd]+)")
    private val uid = Regex("uid=(\\d+)")
    private val ws = Regex("WorkSource\\{\\s*\\d+\\s+([\\w.]+)")

    fun parse(text: String?): PowerState? {
        if (text == null) return null
        val locks = ArrayList<HeldWakeLock>()
        var inLocks = false
        try {
            for (line in text.lines()) {
                val t = line.trim()
                if (t.startsWith("Wake Locks:")) { inLocks = true; continue }
                if (inLocks && (t.isEmpty() || t.startsWith("Suspend Blockers"))) { inLocks = false }
                if (!inLocks) continue
                val m = lock.find(line) ?: continue
                val rest = m.groupValues[3]
                locks += HeldWakeLock(
                    type = m.groupValues[1], tag = m.groupValues[2],
                    uid = uid.find(rest)?.groupValues?.get(1)?.toIntOrNull(),
                    pkg = ws.find(rest)?.groupValues?.get(1),
                    heldMs = acq.find(rest)?.let { Durations.parseMs(it.groupValues[1]) },
                )
            }
        } catch (_: Exception) {}
        return PowerState(wakefulness.find(text)?.groupValues?.get(1), display.find(text)?.groupValues?.get(1), locks)
    }
}
