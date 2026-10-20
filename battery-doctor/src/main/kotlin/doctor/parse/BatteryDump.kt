package doctor.parse

/** `dumpsys battery` 의 지금 상태. 삼성 확장 필드(`mSavedBattery*`)는 이름 그대로 [samsung] 에 보관 */
data class BatteryNow(
    val level: Int?,
    val scale: Int?,
    val voltageMv: Int?,
    val tempDeciC: Int?,
    val health: String?,
    val status: String?,
    val chargeCounterUah: Int?,
    val samsung: Map<String, String>,
) {
    val healthLabel: String?
        get() = when (health) {
            null -> null; "1" -> "알 수 없음"; "2" -> "양호"; "3" -> "과열"; "4" -> "수명 다함"; "5" -> "과전압"; "6" -> "원인 불명 고장"; "7" -> "저온"; else -> health
        }

    val statusLabel: String?
        get() = when (status) {
            null -> null; "1" -> "알 수 없음"; "2" -> "충전 중"; "3" -> "방전 중"; "4" -> "충전 안 함"; "5" -> "완충"; else -> status
        }

    /** 삼성 ASOC(절대 충전 상태 %, 사실상 건강도) — 있을 때만 */
    val asoc: Int? get() = samsung["mSavedBatteryAsoc"]?.trim()?.toDoubleOrNull()?.toInt()

    /** 전하량 계수기 ÷ 표시 잔량으로 역산한 100% 용량(mAh) */
    val impliedCapacityMah: Double?
        get() {
            val c = chargeCounterUah ?: return null
            val l = level ?: return null
            if (l <= 0 || c <= 0) return null
            return c / 1000.0 / l * 100.0
        }
}

object BatteryDump {
    private val kv = Regex("^\\s*([A-Za-z][A-Za-z0-9 _]*?):\\s*(.*?)\\s*$")

    fun parse(text: String?): BatteryNow? {
        if (text == null) return null
        val map = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val m = kv.find(line) ?: continue
            map[m.groupValues[1]] = m.groupValues[2]
        }
        fun int(k: String) = map[k]?.trim()?.toIntOrNull()
        return BatteryNow(
            level = int("level"), scale = int("scale"), voltageMv = int("voltage"), tempDeciC = int("temperature"),
            health = map["health"]?.trim()?.takeIf { it.isNotEmpty() }, status = map["status"]?.trim()?.takeIf { it.isNotEmpty() },
            chargeCounterUah = int("Charge counter"),
            samsung = map.filterKeys { it.startsWith("mSavedBattery") },
        )
    }
}
