package com.mywatch.kakaomap

import com.mywatch.kakaomap.data.ErrorEntry
import com.mywatch.kakaomap.data.ErrorLogFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 워치에서 난 오류를 앱 정보 화면에 남기는 기록 — 사용자가 "잘 안 돼"라고 할 때 무엇이 안 됐는지 알 수 있게.
 * 인증키는 절대 남기지 않고, 같은 오류가 되풀이되면 한 줄로 센다.
 */
class ErrorLogTest {

    @Test
    fun keysNeverLandInTheLog() {
        // 공공데이터 주소는 인증키를 주소에 싣는다 — 오류 문구에 주소가 섞여 와도 키는 지운다 (값은 가짜)
        val raw = "failed to connect: https://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList" +
            "?serviceKey=FAKEkey0123456789abcdefFAKE%2Bkey&_type=json&cityCode=25"
        val s = ErrorLogFormat.sanitize(raw)
        assertFalse(s, s.contains("FAKEkey0123456789"))
        assertTrue(s, s.contains("serviceKey=…&_type=json&cityCode=25"))
        assertEquals("Authorization: KakaoAK …", ErrorLogFormat.sanitize("Authorization: KakaoAK 0123456789abcdef0123456789abcdef"))
        // 키처럼 보이는 긴 16진수 덩어리도 가린다 (짧은 숫자·노선 번호는 그대로)
        assertEquals("키 … 확인, 707번 3분", ErrorLogFormat.sanitize("키 0123456789abcdef0123456789abcdef 확인, 707번 3분"))
    }

    @Test
    fun googleKeysAndTokensNeverLandInTheLogEither() {
        // 유튜브 API 키·구글 로그인 토큰·클라이언트 비밀 (값은 가짜 — 모양만)
        val raw = "GET https://www.googleapis.com/youtube/v3/search?part=snippet&key=AIzaSyFAKEfake_FAKE-fake0123456789abcd&q=x " +
            "Authorization: Bearer ya29.a0FAKE-fake_TOKEN.fake refresh=1//0gFAKE-fake_REFRESH secret=GOCSPX-FAKE_fake-Secret0"
        val s = ErrorLogFormat.sanitize(raw)
        listOf("AIzaSyFAKE", "ya29.a0FAKE", "1//0gFAKE", "GOCSPX-FAKE").forEach { assertFalse("$it 가 남음: $s", s.contains(it)) }
        assertTrue("나머지는 그대로: $s", s.contains("/youtube/v3/search?part=snippet&key=…&q=x"))
    }

    @Test
    fun aCrashShowsWhereInOurCode() {
        val cause = NullPointerException("x was null")
        val e = IllegalStateException("boom", cause)
        e.stackTrace = arrayOf(
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("com.mywatch.kakaomap.ui.DirectionsActivity", "render", "DirectionsActivity.kt", 210),
            StackTraceElement("com.mywatch.kakaomap.ui.DirectionsActivity\$onRow\$1", "invoke", "DirectionsActivity.kt", 300),
            StackTraceElement("com.mywatch.kakaomap.ui.WearList", "a", "WearList.kt", 12),
            StackTraceElement("com.mywatch.kakaomap.MainActivity", "b", "MainActivity.kt", 13),
        )
        assertEquals(
            "IllegalStateException: boom\n" +
                "← DirectionsActivity.render:210\n← DirectionsActivity\$onRow\$1.invoke:300\n← WearList.a:12\n" +
                "원인: NullPointerException: x was null",
            ErrorLogFormat.crashText(e, "com.mywatch.kakaomap"),
        )
        // 우리 코드가 스택에 없으면(시스템 안에서 남) 맨 위 한 줄
        val sys = RuntimeException()
        sys.stackTrace = arrayOf(StackTraceElement("android.view.View", "draw", "View.java", 1))
        assertEquals("RuntimeException\n← View.draw:1", ErrorLogFormat.crashText(sys, "com.mywatch.kakaomap"))
    }

    @Test
    fun theSameErrorAgainBecomesACount() {
        val a = ErrorEntry(1_000L, "실시간 버스", "네트워크 오류", "1.0.111")
        var list = ErrorLogFormat.add(emptyList(), a, max = 3)
        list = ErrorLogFormat.add(list, a.copy(atMs = 5_000L), max = 3)
        assertEquals(1, list.size)
        assertEquals(2, list[0].count)
        assertEquals(5_000L, list[0].atMs)
        // 10분 넘게 지나 다시 나면 새 줄
        list = ErrorLogFormat.add(list, a.copy(atMs = 5_000L + 11 * 60_000L), max = 3)
        assertEquals(2, list.size)
        // 다른 오류는 새 줄, 최대 개수를 넘으면 오래된 것부터 버린다
        list = ErrorLogFormat.add(list, a.copy(atMs = 9_000_000L, message = "다른 오류"), max = 3)
        list = ErrorLogFormat.add(list, a.copy(atMs = 9_100_000L, where = "검색"), max = 3)
        assertEquals(listOf("네트워크 오류", "다른 오류", "네트워크 오류"), list.map { it.message })
        assertEquals("검색", list.last().where)
    }

    @Test
    fun entriesSurviveTheFileRoundTrip() {
        val e = ErrorEntry(1_760_000_000_000L, "길찾기·도보", "줄바꿈\n과 \"따옴표\"", "1.0.111", 2)
        assertEquals(e, ErrorLogFormat.decode(ErrorLogFormat.encode(e)))
        assertFalse(ErrorLogFormat.encode(e).contains('\n'))
        assertNull(ErrorLogFormat.decode("깨진 줄"))
        assertNull(ErrorLogFormat.decode(""))
    }

    @Test
    fun eachEntryReadsAsOneShortNote() {
        val at = Calendar.getInstance(Locale.KOREA).apply { set(2026, Calendar.OCTOBER, 13, 9, 21, 0) }.timeInMillis
        assertEquals(
            "10/13 09:21 · 실시간 버스 ×3\n공공데이터 키가 이 기능에 아직 등록되지 않았어요\n(v1.0.111)",
            ErrorLogFormat.line(ErrorEntry(at, "실시간 버스", "공공데이터 키가 이 기능에 아직 등록되지 않았어요", "1.0.111", 3)),
        )
        assertEquals("10/13 09:21 · 검색\n네트워크 연결을 확인하세요", ErrorLogFormat.line(ErrorEntry(at, "검색", "네트워크 연결을 확인하세요", "")))
    }
}
