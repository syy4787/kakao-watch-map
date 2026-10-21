package com.mywatch.kakaomap.tile

import java.text.BreakIterator
import java.util.Locale

/**
 * 타일 글 줄바꿈. 앱 화면은 테마의 lineBreakWordStyle=phrase 로 낱말(어절) 단위로 줄을 바꾸지만, 타일은 시스템이 그려 그 설정을
 * 줄 수 없다 — 그러면 한글은 아무 글자 사이에서나 줄이 바뀌어 '않습'/'니다', '원래'/'대로'로 갈라진다 (렌더로 찾음).
 * 낱말 안 글자 사이에 단어 결합자(U+2060 — 보이지 않고 폭 0, 그 자리에서 줄을 바꾸지 않음)를 넣어 띄어쓰기에서만 바뀌게 한다.
 * 한 낱말이 한 줄보다 길면 시스템이 알아서 끊는다. 읽어 주기(TalkBack)에는 [spoken] 원문을 준다.
 * (보이지 않는 문자는 소스에 그대로 쓰지 않고 \u 로 — 편집기·정규화가 몰래 바꾸지 않게)
 */
internal object TileText {
    const val WJ = '\u2060'
    const val NBSP = '\u00A0'
    private const val PART_SEP = " · "

    /**
     * 띄어쓰기에서만 줄이 바뀌게 — 한글·한자·가나가 낀 글자 사이에 결합자. 글자(자소 묶음 — 첫가끝 자모·결합 부호·이모지 묶음)
     * 경계에서만 넣어 글자를 가르지 않는다
     */
    fun keepWords(s: String): String {
        if (s.isEmpty()) return s
        val chars = BreakIterator.getCharacterInstance(Locale.KOREAN).apply { setText(s) }
        val out = StringBuilder(s.length * 2)
        var start = chars.first()
        var end = chars.next()
        var prev = -1   // 앞 글자의 마지막 코드 포인트
        while (end != BreakIterator.DONE) {
            val first = s.codePointAt(start)
            if (prev >= 0 && joins(prev, first)) out.append(WJ)
            out.append(s, start, end)
            prev = s.codePointBefore(end)
            start = end
            end = chars.next()
        }
        return out.toString()
    }

    /**
     * 'A · B' 꼴(예상 시각 '내일 15% 새벽 3:24 · 0% 오전 7:40', 소모율 '시간당 4.0% · 주의')은 A·B 각각을 한 줄에 —
     * 줄이 바뀌면 '·' 뒤에서만 ('3:24' 와 '새벽'이 갈라지지 않게). 가운뎃점이 없으면 [keepWords] 그대로
     */
    fun keepParts(s: String): String {
        if (!s.contains(PART_SEP)) return keepWords(s)
        return keepWords(s.split(PART_SEP).joinToString("$NBSP· ") { it.replace(' ', NBSP) })
    }

    /** 넣은 표시를 뺀 원문 */
    fun plain(s: String): String = s.replace(WJ.toString(), "").replace(NBSP, ' ')

    /**
     * 읽어 줄 글 — 가운뎃점·줄표를 쉼표로 (어떤 한국어 음성은 '가운뎃점'이라 읽는다). 타일의 이 줄들은 전에는 읽어 주지 않던 것
     */
    fun spoken(s: String): String = plain(s).replace(PART_SEP, ", ").replace(" — ", ", ")

    private fun joins(a: Int, b: Int): Boolean =
        (cjk(a) || cjk(b)) && !space(a) && !space(b) && !part(a) && !part(b) && !jamo(b)

    private fun cjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    private fun space(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /** 글자를 이루는 조각 — 결합 부호, 이음·서식 문자(ZWJ·결합자 자신), 이모지 피부색, 반각 탁점 (글자 경계로 이미 거르지만 한 번 더) */
    private fun part(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> true
        else -> cp in 0x1F3FB..0x1F3FF || cp == 0xFF9E || cp == 0xFF9F
    }

    /** 첫가끝 자모 — 앞 글자와 한 글자로 합쳐질 수 있어 그 앞엔 넣지 않는다 */
    private fun jamo(cp: Int): Boolean = cp in 0x1100..0x11FF || cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF
}
