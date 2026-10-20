package doctor.parse

/** AOSP 덤프의 시간 표기 두 가지("1h 2m 3s 123ms", "+1h2m3s123ms")와 순수 ms 숫자를 ms 로 */
object Durations {
    private val unit = Regex("(\\d+)\\s*(ms|d|h|m|s)")

    fun parseMs(text: String?): Long? {
        if (text == null) return null
        val t = text.trim().trimStart('+', '-').trim()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return t.toLongOrNull()
        var total = 0L
        var any = false
        for (m in unit.findAll(t)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            total += when (m.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1000L
                else -> n
            }
            any = true
        }
        return if (any) total else null
    }

    /** ms → "1시간 2분" 같은 짧은 한국어 */
    fun human(ms: Long?): String {
        if (ms == null) return "-"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}시간 ${m}분"
            m > 0 -> "${m}분 ${sec}초"
            else -> "${sec}초"
        }
    }
}

/** "u0a123" / "1000" / "u0i45" → 정수 uid */
object Uids {
    private val re = Regex("^u(\\d+)([ais])(\\d+)$")

    fun parse(s: String?): Int? {
        if (s == null) return null
        val t = s.trim()
        t.toIntOrNull()?.let { return it }
        val m = re.find(t) ?: return null
        val user = m.groupValues[1].toInt()
        val n = m.groupValues[3].toInt()
        val base = when (m.groupValues[2]) {
            "a" -> 10_000 + n
            "i" -> 99_000 + n
            else -> n
        }
        return user * 100_000 + base
    }
}

/** 따옴표를 존중하는 쉼표 분리(checkin 의 "173:qcom,smd-rpm:222:fc4" 같은 이름용) */
object Csv {
    fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
