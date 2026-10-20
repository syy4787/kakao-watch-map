package doctor.fix

/**
 * 외부 라이브러리 없이 쓰는 아주 작은 JSON. 값은 Map(LinkedHashMap) / List / String / Double / Boolean / null.
 * journal.jsonl 과 summary.json 처럼 납작한 구조에만 쓴다.
 */
object Json {
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble())
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) { if (!first) sb.append(','); first = false; str(sb, k.toString()); sb.append(':'); write(sb, value) }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) { if (!first) sb.append(','); first = false; write(sb, e) }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    /** 잘못된 JSON 이면 IllegalArgumentException */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i >= text.length) { "JSON 끝에 남은 글자: 위치 ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON 이 비어 있음" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("JSON 문자 오류 위치 $i: '$c'")
            }
        }
        private fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "JSON 리터럴 오류 위치 $i" }; i += word.length; return v }
        private fun number(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("JSON 숫자 오류 위치 $start")
        }
        private fun string(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "JSON 문자열이 닫히지 않음" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length)
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { require(i + 4 <= s.length); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("JSON 이스케이프 오류: \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        private fun obj(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = string()
                ws(); require(i < s.length && s[i] == ':') { "JSON ':' 기대 위치 $i" }; i++
                m[k] = value()
                ws()
                require(i < s.length) { "JSON 객체가 닫히지 않음" }
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw IllegalArgumentException("JSON 객체 오류 위치 $i")
            }
        }
        private fun arr(): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                require(i < s.length) { "JSON 배열이 닫히지 않음" }
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw IllegalArgumentException("JSON 배열 오류 위치 $i")
            }
        }
    }
}
