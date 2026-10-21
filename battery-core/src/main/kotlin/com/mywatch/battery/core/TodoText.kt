package com.mywatch.battery.core

/**
 * 워치 배터리 화면 '지금 할 일' 줄의 글 크기 — 480px 둥근 화면 렌더로 잰 값:
 * 제목(13sp, 아이콘·번호 옆) 한 줄에 한글 9자·두 줄, 설명(12sp, 줄 전체) 한 줄에 한글 13자·세 줄.
 * 넘치면 '…'로 잘려 뒤가 안 보인다 — 잘린 자리가 다른 뜻으로 읽힐 수 있어('데이터 지…') 할 일 글은 이 안에 맞춘다.
 * 줄은 띄어쓰기에서 바뀐다고 보고(낱말 하나가 한 줄보다 길면 글자로 자름) 넉넉하게 잰다.
 */
object TodoText {
    const val TITLE_LINE = 9.0
    const val TITLE_LINES = 2
    const val DETAIL_LINE = 13.0
    const val DETAIL_LINES = 3

    /**
     * 화면에서 차지하는 너비를 한글 한 글자 = 1 로 어림한다 (영문·숫자는 한글보다 좁다 — 'Samsung Health' 14자는 한글 8자쯤).
     * 글자 수로 재면 영문 이름을 쓸데없이 줄이게 된다
     */
    fun width(s: String): Double = s.sumOf { ch -> charWidth(ch) }

    private fun charWidth(ch: Char): Double = when {
        ch in '가'..'힣' || ch.code in 0x3130..0x318F || ch.code in 0x4E00..0x9FFF || ch.code in 0xFF00..0xFFEF -> 1.0
        ch == '…' || ch.code in 0x2190..0x21FF -> 1.0   // 말줄임표·화살표
        ch == ' ' -> 0.3
        ch in 'A'..'Z' -> 0.68
        ch.isLetterOrDigit() -> 0.55
        else -> 0.4
    }

    /** [s] 를 한 줄 너비 [line] 에 띄어쓰기로 나눠 담으면 몇 줄인가 (줄바꿈 글자는 새 줄) */
    fun lines(s: String, line: Double): Int = s.split('\n').sumOf { para -> paraLines(para, line) }

    private fun paraLines(para: String, line: Double): Int {
        var count = 1
        var used = 0.0
        for (word in para.split(' ').filter { it.isNotEmpty() }) {
            val w = width(word)
            val need = if (used == 0.0) w else used + charWidth(' ') + w
            when {
                need <= line -> used = need
                w <= line -> { count++; used = w }
                else -> {
                    // 한 줄보다 긴 낱말은 글자로 자른다
                    if (used > 0.0) { count++; used = 0.0 }
                    for (ch in word) {
                        val c = charWidth(ch)
                        if (used + c > line) { count++; used = c } else used += c
                    }
                }
            }
        }
        return count
    }

    fun fitsTitle(s: String): Boolean = lines(s, TITLE_LINE) <= TITLE_LINES
    fun fitsDetail(s: String): Boolean = lines(s, DETAIL_LINE) <= DETAIL_LINES

    /** [name] + [tail] 을 제목 두 줄 안에 — 넘치면 이름을 줄여 '…' */
    fun fitTitle(name: String, tail: String): String {
        if (fitsTitle(name + tail)) return name + tail
        var n = name
        while (n.length > 1 && !fitsTitle(n.trimEnd() + "…" + tail)) n = n.dropLast(1)
        return n.trimEnd() + "…" + tail
    }

    /** 이름을 너비 [max] 안으로 (넘치면 줄여 '…') — 설명 속 앱 이름이 줄을 다 먹지 않게 */
    fun clip(name: String, max: Double = 6.0): String {
        if (width(name) <= max) return name
        var n = name
        while (n.length > 1 && width(n.trimEnd() + "…") > max) n = n.dropLast(1)
        return n.trimEnd() + "…"
    }
}
