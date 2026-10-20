package com.mywatch.battery.core

/**
 * 표본 CSV. 첫 줄 [MAGIC], 둘째 줄 [HEADER], 그 뒤 한 줄에 표본 하나.
 * 파서는 관대하다: 기록 도중 전원이 꺼져 반쯤 쓰인 줄, 모르는 열, 빈 줄은 건너뛴다.
 */
object SampleCsv {
    const val MAGIC = "# battery-log v1"
    const val HEADER = "t,level,chargeUah,voltageMv,tempDeciC,currentUa,status,plugged,interactive,event,note"

    fun line(s: BatterySample): String = listOf(
        s.t.toString(), s.level.toString(), s.chargeUah?.toString() ?: "", s.voltageMv?.toString() ?: "",
        s.tempDeciC?.toString() ?: "", s.currentUa?.toString() ?: "", s.status.name, if (s.plugged) "1" else "0",
        if (s.interactive) "1" else "0", s.event.name, s.note.replace(',', ';').replace('\n', ' ').replace('\r', ' '),
    ).joinToString(",")

    /** 표본이 아닌 줄(머리말·주석·깨진 줄)은 null */
    fun parseLine(line: String): BatterySample? {
        val raw = line.trim()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("t,")) return null
        val c = raw.split(',')
        if (c.size < 10) return null
        val t = c[0].toLongOrNull() ?: return null
        val level = c[1].toIntOrNull() ?: return null
        if (level !in 0..100) return null
        val status = runCatching { Status.valueOf(c[6]) }.getOrNull() ?: return null
        val event = runCatching { Event.valueOf(c[9]) }.getOrNull() ?: return null
        return BatterySample(
            t = t, level = level, chargeUah = c[2].toIntOrNull(), voltageMv = c[3].toIntOrNull(),
            tempDeciC = c[4].toIntOrNull(), currentUa = c[5].toIntOrNull(), status = status,
            plugged = c[7] == "1", interactive = c[8] == "1", event = event,
            note = if (c.size > 10) c.subList(10, c.size).joinToString(",") else "",
        )
    }

    /** 시간순으로, 같은 시각·같은 계기의 중복은 하나만 */
    fun parse(text: String): List<BatterySample> =
        text.lineSequence().mapNotNull(::parseLine).distinctBy { it.t to it.event }.sortedBy { it.t }.toList()
}
