package com.mywatch.kakaomap

import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.tube.NetworkException
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeParsers
import com.mywatch.kakaomap.tube.TubeSearcher
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.YouTubeQuotaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 유튜브 자체 검색 → (안 되면) 카카오 동영상 검색으로 넘어가기, 카카오 여러 쪽 모으기 — 가짜 응답으로 */
class TubeSearcherTest {

    private fun v(n: Int) = TubeVideo("video${n.toString().padStart(6, '0')}", "곡 $n", "채널")
    private fun videos(range: IntRange) = range.map(::v)

    private var blocked = false
    private val youtubeCalls = mutableListOf<String?>()
    private val kakaoCalls = mutableListOf<Int>()

    private val detailCalls = mutableListOf<List<String>>()

    private fun searcher(
        key: String = "KEY",
        youtube: (String, String?) -> TubePage = { _, _ -> TubePage(videos(1..20), "TOKEN2") },
        kakao: (String, Int) -> TubeParsers.KakaoPage = { _, page -> TubeParsers.KakaoPage(videos(page * 100..page * 100 + 11), isEnd = false) },
        details: ((List<String>) -> Map<String, TubeVideo>)? = null,
    ) = TubeSearcher(
        youtubeKey = key,
        youtubeBlocked = { blocked },
        blockYoutube = { blocked = true },
        youtube = { q, token -> youtubeCalls += token; youtube(q, token) },
        kakao = { q, page -> kakaoCalls += page; kakao(q, page) },
        details = details?.let { d -> { ids -> detailCalls += ids; d(ids) } },
    )

    /**
     * 유튜브 검색 한도(하루 100번)를 다 써 카카오로 찾은 결과엔 워치에서 틀 수 있는지·길이·생방송이 없어,
     * 누르면 못 트는 영상이 섞였다 — 영상 정보(videos.list, 검색 한도와 따로인 1단위)를 한 번 물어 채운다.
     * 유튜브에 없는 영상(지워짐·비공개)은 빼고, 못 물으면 카카오 결과 그대로
     */
    @Test
    fun kakaoResultsAreFilledInWithYouTubesOwnDetails() {
        blocked = true
        val kakaoList = listOf(TubeVideo("BzYnNdJhZQw", "밤편지 (카카오 제목)", "1theK"), TubeVideo("goneeeeeeee", "지워진 영상", "누군가"), TubeVideo("pSUydWEqKwE", "라일락", "1theK"))
        val r = searcher(
            kakao = { _, _ -> TubeParsers.KakaoPage(kakaoList, isEnd = true) },
            details = { ids ->
                mapOf(
                    "BzYnNdJhZQw" to TubeVideo("BzYnNdJhZQw", "[MV] 아이유 밤편지", "1theK (원더케이)", 254, channelId = "UCweOkPb1wVVH0Q0Tlj4a5Pw", views = 512_000_000),
                    "pSUydWEqKwE" to TubeVideo("pSUydWEqKwE", "라일락", "1theK (원더케이)", 214, embeddable = false),
                ).filterKeys { it in ids }
            },
        ).search("밤편지")
        assertEquals(TubeSource.KAKAO, r.source)
        assertEquals(listOf(listOf("BzYnNdJhZQw", "goneeeeeeee", "pSUydWEqKwE")), detailCalls)
        assertEquals("유튜브에 없는 영상은 빼고 카카오 순서 그대로", listOf("BzYnNdJhZQw", "pSUydWEqKwE"), r.page.videos.map { it.id })
        assertEquals(254, r.page.videos[0].durationSec)
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw", r.page.videos[0].channelId)
        assertFalse("워치에서 못 트는 것은 표시", r.page.videos[1].embeddable)
    }

    @Test
    fun ifTheDetailsCannotBeFetchedKakaoResultsStayAsTheyAre() {
        blocked = true
        val r = searcher(details = { throw ApiException("유튜브 사용 한도를 다 썼어요") }).search("밤편지")
        assertEquals(12, r.page.videos.size)
        val noKey = searcher(key = "", details = { error("키가 없으면 묻지 않는다") }).search("밤편지")
        assertEquals(12, noKey.page.videos.size)
    }

    @Test
    fun withoutAKeyItUsesKakao() {
        val r = searcher(key = "").search("밤편지")
        assertEquals(TubeSource.KAKAO, r.source)
        assertNull(r.note)
        assertEquals(emptyList<String?>(), youtubeCalls)
        assertEquals(listOf(1), kakaoCalls)
    }

    @Test
    fun withAKeyItUsesYouTubeItself() {
        val r = searcher().search("밤편지")
        assertEquals(TubeSource.YOUTUBE, r.source)
        assertEquals(20, r.page.videos.size)
        assertEquals("TOKEN2", r.page.next)
        assertEquals(emptyList<Int>(), kakaoCalls)
    }

    @Test
    fun usedUpQuotaSwitchesToKakaoForTheRestOfTheDay() {
        val s = searcher(youtube = { _, _ -> throw YouTubeQuotaException() })
        val first = s.search("밤편지")
        assertEquals(TubeSource.KAKAO, first.source)
        assertTrue(first.note!!.contains("한도"))
        assertTrue(blocked)
        // 같은 날 다음 검색은 유튜브에 묻지도 않는다
        youtubeCalls.clear()
        assertEquals(TubeSource.KAKAO, s.search("Ditto").source)
        assertEquals(emptyList<String?>(), youtubeCalls)
    }

    @Test
    fun aMomentaryErrorFallsBackOnlyForThatSearch() {
        val r = searcher(youtube = { _, _ -> throw ApiException("유튜브 검색 요청이 잠깐 몰렸어요") }).search("밤편지")
        assertEquals(TubeSource.KAKAO, r.source)
        assertTrue(r.note!!.startsWith("유튜브 검색 요청이 잠깐 몰렸어요"))
        assertFalse("잠깐 몰린 건 하루 내내 막을 일이 아님", blocked)
    }

    @Test
    fun noInternetIsNotHiddenBehindKakao() {
        try {
            searcher(youtube = { _, _ -> throw NetworkException() }).search("밤편지")
            fail("인터넷이 끊겼으면 카카오도 안 되므로 그대로 알린다")
        } catch (e: NetworkException) {
            assertEquals(emptyList<Int>(), kakaoCalls)
        }
    }

    @Test
    fun moreFromYouTubeStaysOnYouTube() {
        val s = searcher()
        assertEquals("TOKEN2", s.search("밤편지", next = "TOKEN2", source = TubeSource.YOUTUBE).page.next)
        assertEquals(listOf<String?>("TOKEN2"), youtubeCalls)

        // 한도를 다 쓴 날엔 '더 보기'를 보내지도 않고 알린다 (화면은 '더 보기'를 없앤다)
        blocked = true
        youtubeCalls.clear()
        try {
            s.search("밤편지", next = "TOKEN3", source = TubeSource.YOUTUBE)
            fail()
        } catch (e: YouTubeQuotaException) {
            assertEquals(emptyList<String?>(), youtubeCalls)
        }

        // 한도가 '더 보기' 도중에 다 차도 마찬가지 (카카오 결과를 유튜브 목록 뒤에 섞지 않는다)
        blocked = false
        try {
            searcher(youtube = { _, _ -> throw YouTubeQuotaException() }).search("밤편지", next = "TOKEN3", source = TubeSource.YOUTUBE)
            fail()
        } catch (e: YouTubeQuotaException) {
            assertTrue(blocked)
            assertEquals(emptyList<Int>(), kakaoCalls)
        }
    }

    @Test
    fun kakaoGathersUpToThreePagesForTenYouTubeVideos() {
        // 쪽마다 유튜브 영상이 4개뿐이면 3쪽까지 모은다
        val few = searcher(key = "", kakao = { _, page -> TubeParsers.KakaoPage(videos(page * 100..page * 100 + 3), isEnd = false) })
        val r = few.search("밤편지")
        assertEquals(listOf(1, 2, 3), kakaoCalls)
        assertEquals(12, r.page.videos.size)
        assertEquals("4", r.page.next)

        // '더 보기'는 그다음 쪽부터
        kakaoCalls.clear()
        few.search("밤편지", next = "4", source = TubeSource.KAKAO)
        assertEquals(listOf(4, 5, 6), kakaoCalls)

        // 한 쪽에 10개 넘게 있으면 한 쪽만
        kakaoCalls.clear()
        assertEquals("2", searcher(key = "").search("밤편지").page.next)
        assertEquals(listOf(1), kakaoCalls)
    }

    @Test
    fun kakaoStopsAtTheEndAndKeepsWhatItGotWhenALaterPageFails() {
        val end = searcher(key = "", kakao = { _, page -> TubeParsers.KakaoPage(videos(page..page), isEnd = page == 2) })
        assertNull(end.search("밤편지").page.next)

        val last = searcher(key = "", kakao = { _, page -> TubeParsers.KakaoPage(videos(page..page), isEnd = false) })
        assertNull("카카오는 15쪽까지", last.search("밤편지", next = "15", source = TubeSource.KAKAO).page.next)

        // 2쪽에서 끊겨도 1쪽 결과는 보여 주고, '더 보기'는 2쪽부터 다시
        val flaky = searcher(key = "", kakao = { _, page ->
            if (page == 2) throw ApiException("네트워크 연결을 확인하세요")
            TubeParsers.KakaoPage(videos(page * 100..page * 100 + 3), isEnd = false)
        })
        val r = flaky.search("밤편지")
        assertEquals(4, r.page.videos.size)
        assertEquals("2", r.page.next)

        // 첫 쪽부터 안 되면 오류
        try {
            searcher(key = "", kakao = { _, _ -> throw ApiException("오늘 사용량을 다 썼어요") }).search("밤편지")
            fail()
        } catch (e: ApiException) {
            assertEquals("오늘 사용량을 다 썼어요", e.message)
        }
    }

    @Test
    fun sameVideoOnTwoKakaoPagesAppearsOnce() {
        val r = searcher(key = "", kakao = { _, page -> TubeParsers.KakaoPage(videos(1..4) + v(page + 50), isEnd = page == 3) }).search("밤편지")
        assertEquals(listOf(1, 2, 3, 4, 51, 52, 53).map { v(it).id }, r.page.videos.map { it.id })
    }
}
