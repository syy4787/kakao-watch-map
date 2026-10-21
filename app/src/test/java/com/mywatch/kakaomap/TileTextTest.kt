package com.mywatch.kakaomap

import com.mywatch.kakaomap.tile.TileText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 타일 글 줄바꿈 표시: 타일은 시스템이 그려 앱 테마의 낱말 단위 줄바꿈(phrase)이 안 먹는다 → 한글은 아무 글자 사이에서나 줄이 바뀌어
 * '않습'/'니다'로 갈라졌다. 낱말 안 글자 사이에 보이지 않는 단어 결합자(U+2060)를 넣어 띄어쓰기에서만 바뀌게
 */
class TileTextTest {

    private val wj = "\u2060"
    private val nbsp = '\u00A0'

    @Test
    fun hangulWordsStayWhole() {
        assertEquals("않${wj}습${wj}니${wj}다", TileText.keepWords("않습니다"))
        assertEquals("충${wj}전 중", TileText.keepWords("충전 중"))
        // 숫자·괄호·가운뎃점과 붙은 한글도 한 덩어리 ('15분' / '(아침·충전')
        assertEquals("15${wj}분", TileText.keepWords("15분"))
        assertEquals("(${wj}아${wj}침${wj}·${wj}충${wj}전", TileText.keepWords("(아침·충전"))
    }

    @Test
    fun latinDigitsAndSpacesAreLeftAlone() {
        assertEquals("Samsung Galaxy… 3:24", TileText.keepWords("Samsung Galaxy… 3:24"))
        // 띄어쓰기(줄 바꿀 자리)와 붙임 공백 옆에는 넣지 않는다
        assertEquals("새${wj}벽${nbsp}3:24 · 0%", TileText.keepWords("새벽${nbsp}3:24 · 0%"))
    }

    /** 글자를 이루는 조각(첫가끝 자모·결합 부호·ZWJ·이모지 수식) 사이엔 넣지 않는다 — 글자가 깨지지 않게 */
    @Test
    fun neverSplitsAGlyph() {
        assertEquals("\u1100\u1161\u11A8", TileText.keepWords("\u1100\u1161\u11A8"))
        assertEquals("가\u0301", TileText.keepWords("가\u0301"))
        assertEquals("가\u200D나", TileText.keepWords("가\u200D나"))
        assertEquals("톡$wj\uD83D\uDC4D\uD83C\uDFFB", TileText.keepWords("톡\uD83D\uDC4D\uD83C\uDFFB"))
    }

    /** 글자 경계(자소 묶음)로 본다 — 첫 자모 + 완성 음절(한 글자), 반각 가나 + 탁점도 가르지 않는다 (리뷰에서 찾음) */
    @Test
    fun graphemeClustersAreNeverSplit() {
        assertEquals("\u1100가", TileText.keepWords("\u1100가"))
        assertEquals("ｶﾞ", TileText.keepWords("ｶﾞ"))
        assertEquals("가${wj}ｶﾞ", TileText.keepWords("가ｶﾞ"))
    }

    /** 'A · B' 의 앞이나 뒤가 비어도·여럿이어도 원문으로 돌아오고, 줄 바꿀 자리는 가운뎃점 뒤에만 */
    @Test
    fun partsEdgeCases() {
        for (s in listOf(" · 0% 오전 7:40", "내일 15% 새벽 3:24 · ", "15% 오후 10:05 · 0% 내일 새벽 2:10 · 주의")) {
            assertEquals(s, TileText.plain(TileText.keepParts(s)))
        }
        val three = TileText.keepParts("15% 오후 10:05 · 0% 내일 새벽 2:10 · 주의")
        assertEquals(three, 2, three.count { it == ' ' })
    }

    /** 읽어 주기는 표시를 뺀 원문에서 가운뎃점·줄표를 쉼표로 ('가운뎃점'이라 읽지 않게) */
    @Test
    fun spokenTextHasNoMarkersOrDots() {
        assertEquals("시간당 4.0%, 주의", TileText.spoken(TileText.keepParts("시간당 4.0% · 주의")))
        assertEquals("Play 서비스 과소모, 캐시 지우기", TileText.spoken("Play 서비스 과소모 — 캐시 지우기"))
        assertEquals("지킴이: 밤 모드 중 (아침·충전 때 원래대로)", TileText.spoken("지킴이: 밤 모드 중 (아침·충전 때 원래대로)"))
    }

    @Test
    fun readsBackAsTheOriginalAndIsIdempotent() {
        for (s in listOf("지킴이: 밤 모드 중 (아침·충전 때 원래대로)", "내일 15% 새벽 3:24 · 0% 오전 7:40", "Play 서비스 과소모 — 캐시 지우기", "")) {
            assertEquals(s, TileText.plain(TileText.keepWords(s)))
            assertEquals(s, TileText.plain(TileText.keepParts(s)))
            assertEquals(TileText.keepWords(s), TileText.keepWords(TileText.keepWords(s)))
        }
    }

    /** 'A · B' 꼴은 A·B 각각을 한 줄에 — 바뀌면 '·' 뒤에서만 ('3:24' / '오전' 처럼 시각이 갈라지지 않게) */
    @Test
    fun partsStayOnOneLineEach() {
        val s = TileText.keepParts("내일 15% 새벽 3:24 · 0% 오전 7:40")
        assertEquals("줄 바꿀 자리는 하나: $s", 1, s.count { it == ' ' })
        assertTrue(s, s.substringBefore(' ').endsWith("·"))
        // 가운뎃점이 없으면 낱말 단위 그대로
        assertEquals(TileText.keepWords("지킴이: 대기 중"), TileText.keepParts("지킴이: 대기 중"))
    }
}
