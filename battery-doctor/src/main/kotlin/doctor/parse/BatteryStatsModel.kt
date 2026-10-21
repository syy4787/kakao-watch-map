package doctor.parse

/** 웨이크락·커널 웨이크락·깨움 이유 한 줄. 커널/이유는 uid·pkg 가 null */
data class WakeLock(val uid: Int?, val pkg: String?, val tag: String, val totalMs: Long, val count: Int) {
    /** 배터리 사용 시간당 초 */
    fun secondsPerHour(onBatteryMs: Long?): Double? =
        onBatteryMs?.takeIf { it > 0 }?.let { totalMs / 1000.0 / (it / 3_600_000.0) }
}

/** "Estimated power use" 한 항목. [label] 은 "Uid u0a123" 또는 Screen/Idle/Bluetooth… */
data class PowerItem(val label: String, val pkg: String?, val mah: Double, val percent: Double?) {
    val display: String get() = pkg ?: label
}

/**
 * `dumpsys batterystats` 의 "Statistics since last charge" 요약. 섹션이 없으면 해당 값은 null·빈 목록.
 * 퍼센트 값은 표시 잔량 포인트, mAh 는 추정치.
 */
data class BatteryStats(
    val onBatteryMs: Long? = null,
    val screenOnMs: Long? = null,
    val screenOffMs: Long? = null,
    /** 화면 꺼진 채 배터리로 돌던 동안 CPU 가 깨어 있던 시간(uptime) */
    val screenOffAwakeMs: Long? = null,
    /** Amount discharged (lower, upper) — 잔량 포인트 */
    val dischargePercent: Pair<Int, Int>? = null,
    /** Discharge: N mAh */
    val dischargeMah: Int? = null,
    /** 화면 켜짐 중 소모 포인트 */
    val dischargeScreenOn: Int? = null,
    /** 화면 꺼짐 중 소모 포인트 */
    val dischargeScreenOff: Int? = null,
    val capacityMah: Int? = null,
    val computedDrainMah: Double? = null,
    val actualDrainMah: Pair<Double, Double>? = null,
    val powerItems: List<PowerItem> = emptyList(),
    val partialWakeLocks: List<WakeLock> = emptyList(),
    val kernelWakeLocks: List<WakeLock> = emptyList(),
    val wakeupReasons: List<WakeLock> = emptyList(),
    val radioActiveMs: Long? = null,
    val wifiScanCount: Int? = null,
    val gpsMs: Long? = null,
    val uidPackages: Map<Int, List<String>> = emptyMap(),
    /** 앱별 블록의 "N wakeup alarms" */
    val wakeupAlarmsByPkg: Map<String, Int> = emptyMap(),
    /** uid 별 센서 사용 시간 합(GPS 제외) */
    val sensorMsByUid: Map<Int, Long> = emptyMap(),
) {
    val onBatteryHours: Double? get() = onBatteryMs?.takeIf { it > 0 }?.let { it / 3_600_000.0 }

    /** 화면 꺼짐 중 깨어 있던 비율(0~1) */
    val screenOffAwakeRatio: Double?
        get() {
            val off = screenOffMs ?: return null
            val awake = screenOffAwakeMs ?: return null
            if (off <= 0) return null
            return awake.toDouble() / off
        }

    /** 배터리 사용 시간으로 환산한 소모율(%/h), 표시 잔량 하한 기준 */
    val percentPerHour: Double?
        get() {
            val h = onBatteryHours ?: return null
            val p = dischargePercent?.first ?: return null
            if (h < 0.5) return null
            return p / h
        }

    /**
     * uid 의 대표 패키지 — 여럿이면 알려진 문제를 보는 패키지(Play 서비스·One UI Watch 홈)를 앞에, 시스템 uid('android' 가 낀 것)는
     * 그대로 첫 패키지 (워치 앱의 HealthReader.groupKey 와 같은 규칙)
     */
    fun packageOf(uid: Int?): String? = uid?.let { u ->
        val names = uidPackages[u] ?: return@let null
        if ("android" in names) names.firstOrNull() else NAMED.firstOrNull { it in names } ?: names.firstOrNull()
    }

    private companion object {
        val NAMED = listOf("com.google.android.gms", "com.samsung.android.wearable.sysui")
    }

    /** uid → 대표 패키지로 웨이크락에 이름을 붙인다 */
    fun named(w: WakeLock): WakeLock = if (w.pkg != null || w.uid == null) w else w.copy(pkg = packageOf(w.uid))
}

/** 텍스트와 checkin 결과 합치기 — 텍스트 우선, 빈 곳만 checkin 으로 */
object BatteryStatsMerge {
    fun merge(text: BatteryStats?, checkin: BatteryStats?): BatteryStats? {
        if (text == null) return checkin
        if (checkin == null) return text
        val uids = LinkedHashMap<Int, MutableList<String>>()
        for ((k, v) in text.uidPackages) uids.getOrPut(k) { ArrayList() }.addAll(v)
        for ((k, v) in checkin.uidPackages) { val l = uids.getOrPut(k) { ArrayList() }; v.forEach { if (it !in l) l += it } }
        val merged = BatteryStats(
            onBatteryMs = text.onBatteryMs ?: checkin.onBatteryMs,
            screenOnMs = text.screenOnMs ?: checkin.screenOnMs,
            screenOffMs = text.screenOffMs ?: checkin.screenOffMs,
            screenOffAwakeMs = text.screenOffAwakeMs ?: checkin.screenOffAwakeMs,
            dischargePercent = text.dischargePercent ?: checkin.dischargePercent,
            dischargeMah = text.dischargeMah ?: checkin.dischargeMah,
            dischargeScreenOn = text.dischargeScreenOn ?: checkin.dischargeScreenOn,
            dischargeScreenOff = text.dischargeScreenOff ?: checkin.dischargeScreenOff,
            capacityMah = text.capacityMah ?: checkin.capacityMah,
            computedDrainMah = text.computedDrainMah ?: checkin.computedDrainMah,
            actualDrainMah = text.actualDrainMah ?: checkin.actualDrainMah,
            powerItems = text.powerItems.ifEmpty { checkin.powerItems },
            partialWakeLocks = text.partialWakeLocks.ifEmpty { checkin.partialWakeLocks },
            kernelWakeLocks = text.kernelWakeLocks.ifEmpty { checkin.kernelWakeLocks },
            wakeupReasons = text.wakeupReasons.ifEmpty { checkin.wakeupReasons },
            radioActiveMs = text.radioActiveMs ?: checkin.radioActiveMs,
            wifiScanCount = text.wifiScanCount ?: checkin.wifiScanCount,
            gpsMs = text.gpsMs ?: checkin.gpsMs,
            uidPackages = uids,
            wakeupAlarmsByPkg = text.wakeupAlarmsByPkg.ifEmpty { checkin.wakeupAlarmsByPkg },
            sensorMsByUid = text.sensorMsByUid.ifEmpty { checkin.sensorMsByUid },
        )
        // 합친 uid 표로 이름이 비어 있던 웨이크락·전력 항목을 다시 채운다
        return merged.copy(
            partialWakeLocks = merged.partialWakeLocks.map { merged.named(it) },
            powerItems = merged.powerItems.map { if (it.pkg == null) it.copy(pkg = merged.packageOf(Uids.parse(it.label.removePrefix("Uid ")))) else it },
        )
    }
}
