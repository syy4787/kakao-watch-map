package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** '지금 할 일' 줄의 글 크기 — 렌더(480px)로 잰 값: 제목 한 줄 한글 9자·두 줄, 설명 한 줄 13자·세 줄. 띄어쓰기에서 줄이 바뀐다 */
class TodoTextTest {
    @Test
    fun linesBreakAtSpacesLikeTheWatch() {
        // 렌더에서: "Play 서비스" / "업데이트 뒤 더 닳음" 두 줄, 한글 9자가 한 줄
        assertEquals(2, TodoText.lines("Play 서비스 업데이트 뒤 더 닳음", TodoText.TITLE_LINE))
        assertEquals(1, TodoText.lines("가나다라마바사아자", TodoText.TITLE_LINE))
        assertEquals(2, TodoText.lines("가나다라마바사아자차", TodoText.TITLE_LINE))
        // 렌더에서 세 줄이던 설명
        assertEquals(3, TodoText.lines("Play 서비스 앱 정보 › 저장공간 › 캐시 지우기 → 강제 재시작", TodoText.DETAIL_LINE))
        // 렌더에서 잘리던 설명 ('(데이터 지…')
        assertFalse(TodoText.fitsDetail("Play 서비스 앱 정보 > 저장공간 > 캐시 지우기 (데이터 지우기는 하지 마세요 — 계정이 다시 동기화됩니다)"))
        assertFalse(TodoText.fitsTitle("Google Play 서비스 과소모 — 2026년 갤럭시워치에 되풀이된 문제"))
    }

    @Test
    fun longNamesAreShortenedToFit() {
        val t = TodoText.fitTitle("Samsung Galaxy Wearable Plugin Service", " 업데이트 뒤 더 닳음")
        assertTrue(t, t.startsWith("Samsung") && t.endsWith("… 업데이트 뒤 더 닳음") && TodoText.fitsTitle(t))
        assertEquals("카카오톡", TodoText.clip("카카오톡"))
        assertTrue(TodoText.width(TodoText.clip("Samsung Galaxy Wearable Plugin")) <= 6.0)
    }
}
