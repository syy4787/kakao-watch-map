package doctor.parse

/** 위치 요청 중인 앱 하나(보고서 화면용 — 공유본에서는 location 덤프를 통째로 뺀다) */
data class LocationUse(val provider: String, val pkg: String, val intervalMs: Long?)

/** `dumpsys location` (Android 12+ 형식) — provider 블록 안의 CallerIdentity[uid/pid pkg] … interval=… */
object LocationDump {
    private val provider = Regex("^\\s*(\\w+) provider:\\s*$")
    private val caller = Regex("CallerIdentity\\[\\d+/\\d+ (\\S+?)\\]")
    private val interval = Regex("\\binterval=([\\w.+]+)")

    fun parse(text: String?): List<LocationUse> {
        if (text == null) return emptyList()
        val out = ArrayList<LocationUse>()
        var cur = "?"
        try {
            for (line in text.lines()) {
                provider.find(line)?.let { cur = it.groupValues[1]; return@let }
                val pkg = caller.find(line)?.groupValues?.get(1) ?: continue
                val iv = interval.find(line)?.groupValues?.get(1)?.let { Durations.parseMs(it) }
                out += LocationUse(cur, pkg, iv)
            }
        } catch (_: Exception) {}
        return out
    }
}
