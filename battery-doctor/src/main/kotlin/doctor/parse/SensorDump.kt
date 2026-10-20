package doctor.parse

/** 센서 하나를 쓰는 앱 하나. [rateHz] 는 마지막 등록의 샘플링 주기에서, 모르면 null */
data class SensorUse(val sensor: String, val pkg: String, val rateHz: Double?, val active: Boolean, val type: String? = null, val uid: Int? = null) {
    val isMotion: Boolean get() = type?.contains("accelerometer") == true || type?.contains("gyroscope") == true || sensor.contains("Accel", true) || sensor.contains("Gyro", true)
    val isHeartRate: Boolean get() = type?.contains("heart_rate") == true || sensor.contains("HR", false) || sensor.contains("Heart", true)
}

/**
 * `dumpsys sensorservice` — "Sensor List"(핸들→이름·타입), 연결 블록(패키지→활성 센서), "Previous Registrations"(샘플링 주기).
 */
object SensorDump {
    private val listLine = Regex("^(0x[0-9a-fA-F]+)\\)\\s*(.+?)\\s*\\|.*?type:\\s*(\\S+?)\\((\\d+)\\)")
    private val connPkg = Regex("^\\s*(\\S+)\\s*\\|\\s*WakeLockRefCount\\s*\\d+\\s*\\|\\s*uid\\s*(\\d+)")
    private val connSensor = Regex("^\\s*(.+?)\\s+(0x[0-9a-fA-F]+)\\s*\\|\\s*status:\\s*(\\w+)")
    private val registration = Regex("([+-])\\s*(0x[0-9a-fA-F]+)\\s+pid=\\d+\\s+uid=(\\d+)\\s+package=(\\S+)(?:\\s+samplingPeriod=(\\d+)us)?")

    fun parse(text: String?): List<SensorUse> {
        if (text == null) return emptyList()
        return try { parseInner(text) } catch (_: Exception) { emptyList() }
    }

    private fun parseInner(text: String): List<SensorUse> {
        val names = HashMap<String, String>()     // handle → name
        val types = HashMap<String, String>()     // handle → type
        val rates = HashMap<Pair<String, String>, Double>()   // (pkg, handle) → Hz (마지막 '+' 등록)
        for (line in text.lines()) {
            listLine.find(line)?.let { m ->
                val h = norm(m.groupValues[1]); names[h] = m.groupValues[2].trim(); types[h] = m.groupValues[3]
            }
        }
        // Previous Registrations 는 최신이 위에 오므로, 처음 만난 '+' 만 쓴다
        for (line in text.lines()) {
            val m = registration.find(line) ?: continue
            if (m.groupValues[1] != "+") continue
            val key = m.groupValues[4] to norm(m.groupValues[2])
            val us = m.groupValues[5].toLongOrNull() ?: continue
            if (us > 0 && key !in rates) rates[key] = 1_000_000.0 / us
        }
        val out = ArrayList<SensorUse>()
        var pkg: String? = null
        var uid: Int? = null
        var inConnections = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("Connection Number")) { inConnections = true; pkg = null; continue }
            if (line.trimStart().startsWith("Previous Registrations") || line.contains("direct connections")) { inConnections = false; pkg = null }
            if (!inConnections) continue
            connPkg.find(line)?.let { m -> pkg = m.groupValues[1]; uid = m.groupValues[2].toIntOrNull(); return@let }
            val p = pkg ?: continue
            connSensor.find(line)?.let { m ->
                val handle = norm(m.groupValues[2])
                val name = names[handle] ?: m.groupValues[1].trim()
                out += SensorUse(name, p, rates[p to handle], m.groupValues[3].equals("active", true), types[handle], uid)
            }
        }
        return out
    }

    private fun norm(handle: String) = handle.lowercase().removePrefix("0x").trimStart('0').ifEmpty { "0" }
}
