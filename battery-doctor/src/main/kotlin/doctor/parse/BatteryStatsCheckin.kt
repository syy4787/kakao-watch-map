package doctor.parse

/**
 * `dumpsys batterystats --checkin` 파서. `9,<uid>,<l|i>,<key>,…` 행만 본다.
 * 텍스트 파서의 보조·검증용 — 둘 중 하나만 성공해도 분석은 된다.
 */
object BatteryStatsCheckin {
    private val labels = mapOf(
        "scrn" to "Screen", "idle" to "Idle", "cell" to "Cell standby", "phone" to "Phone", "wifi" to "Wifi",
        "blue" to "Bluetooth", "flashlight" to "Flashlight", "ambi" to "Ambient display", "camera" to "Camera",
        "memory" to "Memory", "unacc" to "Unaccounted", "over" to "Over-counted", "user" to "User",
    )

    fun parse(text: String?): BatteryStats? {
        if (text == null) return null
        return try { parseInner(text) } catch (_: Exception) { BatteryStats() }
    }

    private fun parseInner(text: String): BatteryStats {
        var onBattery: Long? = null
        var screenOff: Long? = null
        var screenOffAwake: Long? = null
        var capacity: Int? = null
        var screenOn: Long? = null
        var radio: Long? = null
        var dPct: Pair<Int, Int>? = null
        var dMah: Int? = null
        var dOn: Int? = null
        var dOff: Int? = null
        var computed: Double? = null
        var actual: Pair<Double, Double>? = null
        val uidPkgs = LinkedHashMap<Int, MutableList<String>>()
        val power = ArrayList<Triple<Int, String, Double>>()   // uid, label, mAh
        val partial = ArrayList<WakeLock>()
        val kernel = ArrayList<WakeLock>()
        val reasons = ArrayList<WakeLock>()
        val sensorMs = LinkedHashMap<Int, Long>()
        var gps = 0L
        var gpsSeen = false
        var wifiCount = 0
        var wifiSeen = false

        for (raw in text.lineSequence()) {
            if (!raw.startsWith("9,")) continue
            val f = Csv.split(raw.trim())
            if (f.size < 5) continue
            val uid = f[1].toIntOrNull() ?: continue
            val key = f[3]
            fun l(i: Int) = f.getOrNull(i)?.trim()?.toLongOrNull()
            fun d(i: Int) = f.getOrNull(i)?.trim()?.toDoubleOrNull()
            fun i(i: Int) = f.getOrNull(i)?.trim()?.toIntOrNull()
            when (key) {
                "uid" -> { val pkg = f.getOrNull(5)?.trim(); val u = i(4); if (u != null && !pkg.isNullOrEmpty()) uidPkgs.getOrPut(u) { ArrayList() }.let { if (pkg !in it) it += pkg } }
                "bt" -> { onBattery = l(5); screenOff = l(10); screenOffAwake = l(11); capacity = i(12) }
                "m" -> { screenOn = l(4); radio = l(8) }
                "dc" -> {
                    val lo = i(4); val hi = i(5)
                    if (lo != null && hi != null) dPct = lo to hi
                    dOn = i(6); dOff = i(7)
                    l(8)?.let { dMah = (it / 1000).toInt() }
                }
                "pws" -> {
                    capacity = capacity ?: d(4)?.toInt()
                    computed = d(5)
                    val a = d(6); val b = d(7) ?: a
                    if (a != null && b != null) actual = a to b
                }
                "pwi" -> { val mah = d(5); if (mah != null) power += Triple(uid, f[4].trim(), mah) }
                "wl" -> {
                    val name = f[4]
                    val pIdx = f.indexOfFirst { it == "p" }.takeIf { it > 5 }
                    val ms = if (pIdx != null) l(pIdx - 1) else l(5)
                    val count = if (pIdx != null) i(pIdx + 1) else i(6)
                    if (ms != null) partial += WakeLock(uid, null, name, ms, count ?: 0)
                }
                "kwl" -> l(5)?.let { kernel += WakeLock(null, null, f[4], it, i(6) ?: 0) }
                "wr" -> l(5)?.let { reasons += WakeLock(null, null, f[4], it, i(6) ?: 0) }
                "sr" -> {
                    val handle = i(4); val ms = l(5)
                    if (handle != null && ms != null) {
                        if (handle == -10000) { gps += ms; gpsSeen = true } else sensorMs[uid] = (sensorMs[uid] ?: 0) + ms
                    }
                }
                "wfl" -> i(7)?.let { wifiCount += it; wifiSeen = true }
            }
        }

        val base = BatteryStats(
            onBatteryMs = onBattery, screenOnMs = screenOn, screenOffMs = screenOff, screenOffAwakeMs = screenOffAwake,
            dischargePercent = dPct, dischargeMah = dMah, dischargeScreenOn = dOn, dischargeScreenOff = dOff,
            capacityMah = capacity, computedDrainMah = computed, actualDrainMah = actual,
            radioActiveMs = radio, wifiScanCount = if (wifiSeen) wifiCount else null, gpsMs = if (gpsSeen) gps else null,
            uidPackages = uidPkgs, sensorMsByUid = sensorMs,
        )
        val total = computed ?: power.sumOf { it.third }.takeIf { it > 0 }
        val items = power.map { (uid, label, mah) ->
            val isUid = label == "uid"
            val shown = if (isUid) "Uid ${uidLabel(uid)}" else (labels[label] ?: label)
            PowerItem(shown, if (isUid) base.packageOf(uid) else null, mah, total?.let { mah / it * 100 })
        }.sortedByDescending { it.mah }
        return base.copy(powerItems = items, partialWakeLocks = partial.map { base.named(it) }, kernelWakeLocks = kernel, wakeupReasons = reasons)
    }

    /** 10123 → "u0a123", 1000 → "1000" */
    fun uidLabel(uid: Int): String {
        val user = uid / 100_000
        val app = uid % 100_000
        return when {
            app in 10_000..19_999 -> "u${user}a${app - 10_000}"
            app >= 99_000 -> "u${user}i${app - 99_000}"
            else -> uid.toString()
        }
    }
}
