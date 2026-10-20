package doctor.parse

/** 패키지별 알람 통계(부팅 이후 누적) */
data class AlarmStat(val pkg: String, val wakeups: Int, val totalMs: Long, val count: Int) {
    fun wakeupsPerHour(elapsedMs: Long?): Double? = elapsedMs?.takeIf { it > 0 }?.let { wakeups / (it / 3_600_000.0) }
}

/** `dumpsys alarm` — "Alarm Stats"(없으면 "Top Alarms")와 nowELAPSED(통계 기간의 분모) */
data class AlarmInfo(val stats: List<AlarmStat>, val elapsedMs: Long?)

object AlarmDump {
    private val elapsed = Regex("nowELAPSED=\\+?(\\S+)")
    private val statsHeader = Regex("^\\s*(?:\\*ACTIVE\\* )?(\\S+?):(\\S+) \\+?(\\S+) running, (\\d+) wakeups:?\\s*$")
    private val filterLine = Regex("^\\s*(?:\\*ACTIVE\\* )?\\+?(\\S+) (\\d+) wakes (\\d+) alarms")
    private val topLine = Regex("^\\s*\\+?(\\S+) running, (\\d+) wakeups, (\\d+) alarms: (\\S+?):(\\S+)\\s*$")

    fun parse(text: String?): AlarmInfo {
        if (text == null) return AlarmInfo(emptyList(), null)
        return try { parseInner(text) } catch (_: Exception) { AlarmInfo(emptyList(), null) }
    }

    private fun parseInner(text: String): AlarmInfo {
        val elapsedMs = elapsed.find(text)?.let { Durations.parseMs(it.groupValues[1]) }
        val lines = text.lines()
        val out = LinkedHashMap<String, AlarmStat>()
        var inStats = false
        var cur: String? = null
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("Alarm Stats:")) { inStats = true; continue }
            if (!inStats) continue
            statsHeader.find(line)?.let { m ->
                val pkg = m.groupValues[2]
                cur = pkg
                out[pkg] = AlarmStat(pkg, m.groupValues[4].toIntOrNull() ?: 0, Durations.parseMs(m.groupValues[3]) ?: 0, 0)
                return@let
            }
            if (statsHeader.matches(line)) continue
            val pkg = cur ?: continue
            filterLine.find(line)?.let { m ->
                val prev = out[pkg] ?: return@let
                out[pkg] = prev.copy(count = prev.count + (m.groupValues[3].toIntOrNull() ?: 0))
            }
        }
        if (out.isEmpty()) {
            // 구형/축약 출력: Top Alarms 로 대신
            var inTop = false
            for (line in lines) {
                val t = line.trim()
                if (t.startsWith("Top Alarms:")) { inTop = true; continue }
                if (!inTop) continue
                if (t.startsWith("Alarm Stats:")) break
                topLine.find(line)?.let { m ->
                    val pkg = m.groupValues[5]
                    val prev = out[pkg]
                    val add = AlarmStat(pkg, m.groupValues[2].toIntOrNull() ?: 0, Durations.parseMs(m.groupValues[1]) ?: 0, m.groupValues[3].toIntOrNull() ?: 0)
                    out[pkg] = if (prev == null) add else prev.copy(wakeups = prev.wakeups + add.wakeups, totalMs = prev.totalMs + add.totalMs, count = prev.count + add.count)
                }
            }
        }
        return AlarmInfo(out.values.sortedByDescending { it.wakeups }, elapsedMs)
    }
}
