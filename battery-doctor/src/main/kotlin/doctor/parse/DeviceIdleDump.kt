package doctor.parse

/** Doze 상태와 화이트리스트. [userWhitelist] 는 사용자가(또는 앱이) 넣은 것 — 되돌릴 수 있는 대상 */
data class IdleState(val state: String?, val whitelist: List<String>, val userWhitelist: List<String> = emptyList(), val lightState: String? = null)

/** `dumpsys deviceidle` + `cmd deviceidle whitelist` */
object DeviceIdleDump {
    private val state = Regex("mState=(\\w+)")
    private val light = Regex("mLightState=(\\w+)")
    private val cmdLine = Regex("^(system-excidle|system|user),([\\w.]+),(\\d+)\\s*$")

    fun parse(dump: String?, whitelistCmd: String?): IdleState? {
        if (dump == null && whitelistCmd == null) return null
        val all = LinkedHashSet<String>()
        val user = LinkedHashSet<String>()
        var fromCmd = false
        whitelistCmd?.lines()?.forEach { line ->
            cmdLine.find(line.trim())?.let { m ->
                fromCmd = true
                all += m.groupValues[2]
                if (m.groupValues[1] == "user") user += m.groupValues[2]
            }
        }
        var st: String? = null
        var ls: String? = null
        if (dump != null) {
            st = state.find(dump)?.groupValues?.get(1)
            ls = light.find(dump)?.groupValues?.get(1)
            if (!fromCmd) {
                var section: String? = null
                for (line in dump.lines()) {
                    val t = line.trim()
                    when {
                        t == "Whitelist (except idle) system apps:" || t == "Whitelist system apps:" -> section = "system"
                        t == "Whitelist user apps:" -> section = "user"
                        t.startsWith("Whitelist") || (t.isNotEmpty() && !line.startsWith("    ")) -> section = null
                        section != null && t.isNotEmpty() -> {
                            all += t
                            if (section == "user") user += t
                        }
                    }
                }
            }
        }
        return IdleState(st, all.toList(), user.toList(), ls)
    }
}
