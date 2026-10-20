package doctor.parse

/**
 * `dumpsys batterystats` 기본 텍스트 파서. "Statistics since last charge" 아래만 본다.
 * 어떤 줄이 없어도 나머지는 채우고, 이상한 텍스트에 예외를 내지 않는다.
 */
object BatteryStatsText {
    private val timeOnBattery = Regex("Time on battery:\\s*(.+?)\\s*\\([\\d.]+%\\)\\s*realtime")
    private val screenOff = Regex("Time on battery screen off:\\s*(.+?)\\s*\\([\\d.]+%\\)\\s*realtime,\\s*(.+?)\\s*\\([\\d.]+%\\)\\s*uptime")
    private val screenOffNoUptime = Regex("Time on battery screen off:\\s*(.+?)\\s*\\([\\d.]+%\\)")
    private val screenOffAwakeAlt = Regex("Screen off:.*?Awake:\\s*([\\dhms ]+ms)")
    private val screenOn = Regex("^\\s*Screen on:\\s*(.+?)\\s*\\([\\d.]+%\\)", RegexOption.MULTILINE)
    private val discharge = Regex("^\\s*Discharge:\\s*(\\d+)\\s*mAh", RegexOption.MULTILINE)
    private val lower = Regex("Amount discharged \\(lower bound\\):\\s*(\\d+)")
    private val upper = Regex("Amount discharged \\(upper bound\\):\\s*(\\d+)")
    private val dischargeOn = Regex("Amount discharged while screen on:\\s*(\\d+)")
    private val dischargeOff = Regex("Amount discharged while screen off:\\s*(\\d+)")
    private val capacity = Regex("Capacity:\\s*([\\d.]+),\\s*Computed drain:\\s*([\\d.]+),\\s*actual drain:\\s*([\\d.]+)(?:-([\\d.]+))?")
    private val radio = Regex("Mobile radio active time:\\s*(.+?)\\s*\\(")
    private val powerLine = Regex("^\\s{2,}(Uid \\S+|[A-Z][A-Za-z -]*?):\\s*([\\d.]+)")
    // 이름에 ':' 가 들어갈 수 있어(예: 173:qcom,smd-rpm:222:fc4) 시간 표기 바로 앞의 ": " 에서 자른다
    private const val DUR = "((?:\\d+(?:ms|[dhms]) ?)+)"
    private val kernelLine = Regex("^\\s*Kernel Wake lock (.+): $DUR\\s*\\((\\d+) times\\)")
    private val partialLine = Regex("^\\s*Wake lock (\\S+) (.+): $DUR\\s*\\((\\d+) times\\)")
    private val reasonLine = Regex("^\\s*Wakeup reason (.+): $DUR\\s*\\((\\d+) times\\)")
    private val uidHeader = Regex("^  (u\\d+[ais]\\d+|\\d+)(?: \\([^)]*\\))?:\\s*$")
    private val apkLine = Regex("^\\s*Apk (\\S+):")
    private val wakeupAlarms = Regex("^\\s*(\\d+) wakeup alarms")
    private val sensorLine = Regex("^\\s*Sensor (\\S+):\\s*(.+?)\\s*realtime")
    private val wifiScan = Regex("Wifi Scan(?: \\(blamed\\))?:\\s*(.+?)\\s*\\((\\d+) times\\)")

    fun parse(text: String?): BatteryStats? {
        if (text == null) return null
        return try { parseInner(text) } catch (_: Exception) { BatteryStats() }
    }

    private fun parseInner(all: String): BatteryStats {
        val startIdx = all.indexOf("Statistics since last charge")
        val text = if (startIdx >= 0) all.substring(startIdx) else all
        val lines = text.lines()

        val onBattery = timeOnBattery.find(text)?.let { Durations.parseMs(it.groupValues[1]) }
        var offMs: Long? = null
        var awakeMs: Long? = null
        screenOff.find(text)?.let { offMs = Durations.parseMs(it.groupValues[1]); awakeMs = Durations.parseMs(it.groupValues[2]) }
        if (offMs == null) screenOffNoUptime.find(text)?.let { offMs = Durations.parseMs(it.groupValues[1]) }
        if (awakeMs == null) screenOffAwakeAlt.find(text)?.let { awakeMs = Durations.parseMs(it.groupValues[1]) }
        val onMs = screenOn.find(text)?.let { Durations.parseMs(it.groupValues[1]) }
        val dMah = discharge.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val lo = lower.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val hi = upper.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val dOn = dischargeOn.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val dOff = dischargeOff.find(text)?.groupValues?.get(1)?.toIntOrNull()
        var capMah: Int? = null
        var computed: Double? = null
        var actual: Pair<Double, Double>? = null
        capacity.find(text)?.let { m ->
            capMah = m.groupValues[1].toDoubleOrNull()?.toInt()
            computed = m.groupValues[2].toDoubleOrNull()
            val a = m.groupValues[3].toDoubleOrNull()
            val b = m.groupValues[4].toDoubleOrNull() ?: a
            if (a != null && b != null) actual = a to b
        }
        val radioMs = radio.find(text)?.let { Durations.parseMs(it.groupValues[1]) }

        val power = ArrayList<PowerItem>()
        val kernel = ArrayList<WakeLock>()
        val partial = ArrayList<WakeLock>()
        val reasons = ArrayList<WakeLock>()
        val uidPkgs = LinkedHashMap<Int, MutableList<String>>()
        val alarms = LinkedHashMap<String, Int>()
        val sensorMs = LinkedHashMap<Int, Long>()
        var gpsMs = 0L
        var gpsSeen = false
        var wifiCount = 0
        var wifiSeen = false

        var section = ""          // power / kernel / partial / reasons / uid
        var curUid: Int? = null
        var curApk: String? = null
        for (raw in lines) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.startsWith("Estimated power use") -> { section = "power"; continue }
                t.startsWith("All kernel wake locks") -> { section = "kernel"; continue }
                t.startsWith("All partial wake locks") -> { section = "partial"; continue }
                t.startsWith("All wakeup reasons") -> { section = "reasons"; continue }
            }
            uidHeader.find(line)?.let { m ->
                curUid = Uids.parse(m.groupValues[1]); curApk = null; section = "uid"; return@let
            }
            if (t.isEmpty()) { if (section != "uid") section = ""; continue }
            when (section) {
                "power" -> powerLine.find(line)?.let { m ->
                    val label = m.groupValues[1]
                    val mah = m.groupValues[2].toDoubleOrNull() ?: return@let
                    if (label.startsWith("Capacity")) return@let
                    power += PowerItem(label, null, mah, null)
                }
                "kernel" -> kernelLine.find(line)?.let { m ->
                    kernel += WakeLock(null, null, m.groupValues[1], Durations.parseMs(m.groupValues[2]) ?: 0, m.groupValues[3].toIntOrNull() ?: 0)
                }
                "partial" -> partialLine.find(line)?.let { m ->
                    partial += WakeLock(Uids.parse(m.groupValues[1]), null, m.groupValues[2], Durations.parseMs(m.groupValues[3]) ?: 0, m.groupValues[4].toIntOrNull() ?: 0)
                }
                "reasons" -> reasonLine.find(line)?.let { m ->
                    reasons += WakeLock(null, null, m.groupValues[1], Durations.parseMs(m.groupValues[2]) ?: 0, m.groupValues[3].toIntOrNull() ?: 0)
                }
                "uid" -> {
                    val uid = curUid
                    apkLine.find(line)?.let { m ->
                        curApk = m.groupValues[1]
                        if (uid != null) uidPkgs.getOrPut(uid) { ArrayList() }.let { l -> if (m.groupValues[1] !in l) l += m.groupValues[1] }
                    }
                    wakeupAlarms.find(line)?.let { m -> curApk?.let { apk -> alarms[apk] = (alarms[apk] ?: 0) + (m.groupValues[1].toIntOrNull() ?: 0) } }
                    sensorLine.find(line)?.let { m ->
                        val ms = Durations.parseMs(m.groupValues[2]) ?: 0
                        if (m.groupValues[1] == "GPS") { gpsMs += ms; gpsSeen = true }
                        else if (uid != null) sensorMs[uid] = (sensorMs[uid] ?: 0) + ms
                    }
                    wifiScan.find(line)?.let { m ->
                        if (!line.contains("(actual)")) { wifiCount += m.groupValues[2].toIntOrNull() ?: 0; wifiSeen = true }
                    }
                }
            }
        }

        val base = BatteryStats(
            onBatteryMs = onBattery, screenOnMs = onMs, screenOffMs = offMs, screenOffAwakeMs = awakeMs,
            dischargePercent = if (lo != null && hi != null) lo to hi else null,
            dischargeMah = dMah, dischargeScreenOn = dOn, dischargeScreenOff = dOff,
            capacityMah = capMah, computedDrainMah = computed, actualDrainMah = actual,
            radioActiveMs = radioMs, wifiScanCount = if (wifiSeen) wifiCount else null, gpsMs = if (gpsSeen) gpsMs else null,
            uidPackages = uidPkgs, wakeupAlarmsByPkg = alarms, sensorMsByUid = sensorMs,
        )
        val total = computed ?: power.sumOf { it.mah }.takeIf { it > 0 }
        return base.copy(
            powerItems = power.map { p ->
                val pkg = if (p.label.startsWith("Uid ")) base.packageOf(Uids.parse(p.label.removePrefix("Uid "))) else null
                p.copy(pkg = pkg, percent = total?.let { p.mah / it * 100 })
            },
            partialWakeLocks = partial.map { base.named(it) },
            kernelWakeLocks = kernel,
            wakeupReasons = reasons,
        )
    }
}
