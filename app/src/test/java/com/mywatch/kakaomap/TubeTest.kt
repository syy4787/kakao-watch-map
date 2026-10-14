package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.PlayedHistory
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.SearchHistory
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeParsers
import com.mywatch.kakaomap.tube.TubeText
import com.mywatch.kakaomap.tube.TubeVideo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 워치에서 유튜브 노래를 찾아 폰의 유튜브 앱(내 계정 = 내 알고리즘)으로 틀기.
 * 워치는 검색·고르기·기록만 하고, 재생과 '믹스'(다음 곡 추천)는 폰의 공식 유튜브 앱이 한다.
 */
class TubeTest {

    @Test
    fun findsTheVideoIdInEveryKindOfYouTubeLink() {
        val id = "BzYnNdJhZQw"
        listOf(
            "http://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?feature=share&v=$id&t=30",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id?autoplay=1",
            "https://www.youtube.com/live/$id",
        ).forEach { assertEquals(it, id, TubeLinks.videoId(it)) }

        listOf(
            "http://tv.kakao.com/channel/2653417/cliplink/301479181",
            "https://www.youtube.com/channel/UC3IZKseVpdzPSBaWxBxundA",
            "https://www.youtube.com/watch?v=short",
            "https://notyoutube.com/watch?v=$id",
            "not a url",
            "",
        ).forEach { assertNull(it, TubeLinks.videoId(it)) }
    }

    @Test
    fun phoneLinksPlayAsAMixSoTheAlgorithmKeepsGoing() {
        // 믹스: 이 곡에서 시작해 유튜브가 내 계정 기록으로 비슷한 곡을 계속 이어 준다
        assertEquals("https://www.youtube.com/watch?v=BzYnNdJhZQw&list=RDBzYnNdJhZQw&start_radio=1", TubeLinks.watchUrl("BzYnNdJhZQw", mix = true))
        assertEquals("https://www.youtube.com/watch?v=BzYnNdJhZQw", TubeLinks.watchUrl("BzYnNdJhZQw", mix = false))
        assertEquals("https://i.ytimg.com/vi/BzYnNdJhZQw/mqdefault.jpg", TubeLinks.thumbnail("BzYnNdJhZQw"))
        // 폰에서 유튜브 검색 결과로 바로 (워치 검색이 안 될 때)
        assertEquals("https://www.youtube.com/results?search_query=%EB%B0%A4%ED%8E%B8%EC%A7%80%20%26%20C%2B%2B", TubeLinks.searchUrl("밤편지 & C++"))
    }

    @Test
    fun watchPlayerPageLivesOnTheRepositorysGitHubPages() {
        // 워치 재생 화면 = 이 저장소의 GitHub Pages 의 tube/ (주소의 사용자 이름은 소문자)
        assertEquals("https://syy4787.github.io/kakao-watch-map/tube/", TubeLinks.playerBase("syy4787/kakao-watch-map"))
        assertEquals("https://syy4787.github.io/kakao-watch-map/tube/", TubeLinks.playerBase("Syy4787/kakao-watch-map"))
        // 저장소를 모르면(로컬 빌드) 워치 재생 없음 → 폰으로
        listOf(null, "", " ", "kakao-watch-map", "a/b/c", "bad name/x", "x/../y").forEach {
            assertNull("'$it'", TubeLinks.playerBase(it))
        }
    }

    @Test
    fun privacyPolicyLivesNextToThePlayerPage() {
        // 유튜브 API 정책: 개인정보 처리방침은 언제든 볼 수 있게 — 재생 화면과 같은 GitHub Pages 에
        assertEquals("https://syy4787.github.io/kakao-watch-map/privacy.html", TubeLinks.privacyUrl("syy4787/kakao-watch-map"))
        assertNull("저장소를 모르면 없음", TubeLinks.privacyUrl(null))
        assertEquals("https://www.youtube.com/t/terms", TubeLinks.YOUTUBE_TERMS)
    }

    @Test
    fun watchPlayerLinkCarriesTheSongAfterTheHashSoItIsNeverSentToTheServer() {
        val base = "https://syy4787.github.io/kakao-watch-map/tube/"
        val song = TubeVideo("BzYnNdJhZQw", "밤편지 & Night", "IU 아이유")
        assertEquals(
            base + "#v=BzYnNdJhZQw&mix=1&t=%EB%B0%A4%ED%8E%B8%EC%A7%80%20%26%20Night&c=IU%20%EC%95%84%EC%9D%B4%EC%9C%A0",
            TubeLinks.playerUrl(base, song, Queue.Mix),
        )
        assertEquals(base + "#v=BzYnNdJhZQw&t=%EB%B0%A4%ED%8E%B8%EC%A7%80%20%26%20Night&c=IU%20%EC%95%84%EC%9D%B4%EC%9C%A0", TubeLinks.playerUrl(base, song, Queue.Single))
        // 제목·채널을 모르면 빼고, 너무 긴 제목은 잘라 주소가 끝없이 길어지지 않게
        assertEquals(base + "#v=BzYnNdJhZQw&mix=1", TubeLinks.playerUrl(base, TubeVideo("BzYnNdJhZQw", " ", ""), Queue.Mix))
        val long = TubeLinks.playerUrl(base, TubeVideo("BzYnNdJhZQw", "가".repeat(500), "나".repeat(500)), Queue.Mix)
        assertTrue(long.length < 1_500)
        assertFalse(long.substringBefore('#').contains("BzYnNdJhZQw"))
        // 이모지(두 칸짜리 글자)가 잘리는 자리에 걸려도 반쪽('?')이 남지 않는다
        val emoji = TubeLinks.playerUrl(base, TubeVideo("BzYnNdJhZQw", "가".repeat(99) + "🎵🎵", "📻".repeat(50)), Queue.Single)
        val t = java.net.URLDecoder.decode(emoji.substringAfter("&t=").substringBefore('&'), "UTF-8")
        val c = java.net.URLDecoder.decode(emoji.substringAfter("&c=").substringBefore('&'), "UTF-8")
        assertEquals("가".repeat(99) + "🎵", t)
        assertEquals("📻".repeat(40), c)
        // 같은 곡을 다시 골라도 주소가 달라지게 (같은 탭에서 다시 틀도록)
        assertEquals(base + "#v=BzYnNdJhZQw&mix=1&n=1700000000000", TubeLinks.playerUrl(base, TubeVideo("BzYnNdJhZQw", "", ""), Queue.Mix, nonce = 1_700_000_000_000))
    }

    @Test
    fun phoneLinksCanStartWhereTheWatchLeftOff() {
        // 워치에서 듣던 곳부터 폰에서 이어 듣기
        assertEquals("https://www.youtube.com/watch?v=BzYnNdJhZQw&list=RDBzYnNdJhZQw&start_radio=1&t=83s", TubeLinks.watchUrl("BzYnNdJhZQw", mix = true, startSec = 83))
        assertEquals("https://www.youtube.com/watch?v=BzYnNdJhZQw&t=5s", TubeLinks.watchUrl("BzYnNdJhZQw", mix = false, startSec = 5))
        assertEquals(TubeLinks.watchUrl("BzYnNdJhZQw", mix = true), TubeLinks.watchUrl("BzYnNdJhZQw", mix = true, startSec = 0))
    }

    @Test
    fun handOffLinksFromThePlayerPageAreReadCarefully() {
        // 재생 화면의 '폰에서 이어 듣기' → kakaowatch://tube/phone?v=…&t=…&mix=1&title=…&channel=…
        val h = TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=83&mix=1&title=%EB%B0%A4%ED%8E%B8%EC%A7%80%20%26%20Night&channel=IU")
        assertEquals(TubeVideo("BzYnNdJhZQw", "밤편지 & Night", "IU"), h?.video)
        assertEquals(83, h?.startSec)
        assertEquals(true, h?.mix)
        // 제목이 없어도, 믹스가 아니어도
        val plain = TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=0")
        assertEquals(TubeVideo("BzYnNdJhZQw", "", ""), plain?.video)
        assertEquals(false, plain?.mix)
        // 웹 페이지가 보낸 값이라 이상하면 버리거나 바로잡는다
        listOf(
            "kakaowatch://tube",
            "kakaowatch://tube/phone",
            "kakaowatch://tube/phone?v=short&t=1",
            "kakaowatch://tube/phone?v=BzYnNdJhZQw%22%3E&t=1",
            "kakaowatch://other/phone?v=BzYnNdJhZQw",
            "https://tube/phone?v=BzYnNdJhZQw",
            "kakaowatch://tube/play?v=BzYnNdJhZQw",
            "not a uri ::",
        ).forEach { assertNull(it, TubeLinks.handoff(it)) }
        listOf("-5", "abc", "999999999999", "90000").forEach {
            assertEquals(it, 0, TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=$it")?.startSec)
        }
        val messy = TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=3&title=" + "%E2%80%8B%0A%09가".repeat(80) + "&channel=" + "나".repeat(90))
        assertTrue(messy!!.video.title.codePointCount(0, messy.video.title.length) <= 100)
        assertFalse(messy.video.title.any { it == '\n' || it == '\t' })
        assertEquals(40, messy.video.channel.length)
        // 글자 방향을 뒤집거나(U+202E) 안 보이게 숨기는 글자(U+200B·U+2066) 는 뺀다 — 확인 화면 제목이 다르게 보이지 않게
        val tricky = TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=3&title=%E2%80%AEevil%E2%81%A6%E2%80%8Bsong&channel=%EF%BB%BFIU")
        assertEquals("evilsong", tricky?.video?.title)
        assertEquals("IU", tricky?.video?.channel)
        assertEquals("가 나", TubeText.clean("가\u200B \u202A나\u2069", 10))
        // 잉글랜드·스코틀랜드 같은 깃발 이모지는 '태그 글자'로 이어 붙인다 — 그건 남긴다
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        assertEquals("응원 $england", TubeText.clean("응원 $england", 20))
    }

    @Test
    fun titlesComeOutAsPlainText() {
        assertEquals("[MV] IU(아이유) _ Through the Night(밤편지)", TubeText.plain("[MV] <b>IU</b>(<b>아이유</b>) _ Through the Night(밤편지)"))
        assertEquals("Guns N' Roses - Sweet Child O' Mine", TubeText.plain("Guns N&#39; Roses - Sweet Child O&#x27; Mine"))
        assertEquals("Tom & Jerry \"live\" <Love>", TubeText.plain("Tom &amp; Jerry &quot;live&quot; &lt;Love&gt;"))
        assertEquals("한 줄로", TubeText.plain("  한\n   줄로 "))
        assertEquals("깨진 &#99999999; 은 그대로", TubeText.plain("깨진 &#99999999; 은 그대로"))
    }

    @Test
    fun durationsReadLikeYouTube() {
        assertEquals(294, TubeText.isoSeconds("PT4M54S"))
        assertEquals(3723, TubeText.isoSeconds("PT1H2M3S"))
        assertEquals(60, TubeText.isoSeconds("PT1M"))
        assertEquals(90061, TubeText.isoSeconds("P1DT1H1M1S"))
        assertNull("생방송·예정은 길이가 0", TubeText.isoSeconds("P0D"))
        assertNull(TubeText.isoSeconds("garbage"))
        assertNull(TubeText.isoSeconds(null))
        assertEquals("4:54", TubeText.clock(294))
        assertEquals("1:02:03", TubeText.clock(3723))
        assertEquals("0:07", TubeText.clock(7))
    }

    @Test
    fun kakaoVideoSearchKeepsOnlyYouTubeVideos() {
        val json = JSONObject(
            """
            {"meta":{"total_count":3021,"pageable_count":800,"is_end":false},
             "documents":[
              {"title":"[MV] <b>IU</b>(<b>아이유</b>) _ Through the Night(<b>밤편지</b>)","play_time":294,
               "thumbnail":"https://search2.kakaocdn.net/argon/138x78_80_pr/a","url":"http://www.youtube.com/watch?v=BzYnNdJhZQw",
               "datetime":"2017-03-24T18:00:04.000+09:00","author":"1theK (원더케이)"},
              {"title":"아이유 밤편지 라이브","play_time":250,"thumbnail":"","url":"http://tv.kakao.com/channel/1/cliplink/2",
               "datetime":"2017-05-06T00:36:45.000+09:00","author":"KBS"},
              {"title":"밤편지 커버 &amp; 피아노","play_time":0,"thumbnail":"","url":"https://youtu.be/aaaaaaaaaaa",
               "datetime":"2020-01-01T00:00:00.000+09:00","author":"커버하는 사람"},
              {"title":"중복","play_time":294,"thumbnail":"","url":"https://m.youtube.com/watch?v=BzYnNdJhZQw",
               "datetime":"2017-03-24T18:00:04.000+09:00","author":"1theK"}
             ]}
            """.trimIndent()
        )
        val page = TubeParsers.kakaoVclip(json)
        assertEquals(listOf("BzYnNdJhZQw", "aaaaaaaaaaa"), page.videos.map { it.id })
        val iu = page.videos.first()
        assertEquals("[MV] IU(아이유) _ Through the Night(밤편지)", iu.title)
        assertEquals("1theK (원더케이)", iu.channel)
        assertEquals(294, iu.durationSec)
        assertEquals("밤편지 커버 & 피아노", page.videos[1].title)
        assertNull("길이 0 = 모름", page.videos[1].durationSec)
        assertFalse(page.isEnd)
        assertTrue(TubeParsers.kakaoVclip(JSONObject("""{"meta":{"is_end":true},"documents":[]}""")).isEnd)
    }

    @Test
    fun youTubeErrorsBecomeSimpleReasons() {
        val quota = """{"error":{"code":403,"message":"The request cannot be completed because you have exceeded your quota.","errors":[{"message":"...","domain":"youtube.quota","reason":"quotaExceeded"}]}}"""
        assertEquals("quotaExceeded", TubeParsers.youtubeErrorReason(quota))
        val badKey = """{"error":{"code":400,"message":"API key not valid.","errors":[{"reason":"badRequest"}],"details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertEquals("badRequest", TubeParsers.youtubeErrorReason(badKey))
        assertNull(TubeParsers.youtubeErrorReason("<html>oops</html>"))
    }

    @Test
    fun searchHistoryKeepsNewestFirstWithoutDuplicates() {
        var h = emptyList<String>()
        h = SearchHistory.add(h, "아이유 밤편지")
        h = SearchHistory.add(h, "  뉴진스\n Ditto ")
        h = SearchHistory.add(h, "아이유  밤편지 ")
        assertEquals(listOf("아이유 밤편지", "뉴진스 Ditto"), h)
        h = SearchHistory.add(h, "NewJeans Ditto")
        h = SearchHistory.add(h, "newjeans DITTO")
        assertEquals(listOf("newjeans DITTO", "아이유 밤편지", "뉴진스 Ditto"), h)
        assertEquals("빈 검색어는 남기지 않는다", h, SearchHistory.add(h, "   "))
        val many = (1..20).fold(emptyList<String>()) { acc, i -> SearchHistory.add(acc, "노래 $i") }
        assertEquals(SearchHistory.MAX, many.size)
        assertEquals("노래 20", many.first())
        assertEquals(listOf("아이유 밤편지", "뉴진스 Ditto"), SearchHistory.remove(h, "NewJeans Ditto"))
        assertTrue(SearchHistory.clean("가".repeat(300)).length <= SearchHistory.MAX_LENGTH)
        assertEquals(h, SearchHistory.decode(SearchHistory.encode(h)))
        assertEquals(emptyList<String>(), SearchHistory.decode(null))
    }

    @Test
    fun playedSongsAreKeptThirtyDaysNewestFirst() {
        val day = 24 * 3_600_000L
        val now = 100 * day
        val a = TubeVideo("BzYnNdJhZQw", "밤편지", "1theK", 294)
        val b = TubeVideo("aaaaaaaaaaa", "Ditto", "HYBE LABELS", 186)
        var list = PlayedHistory.add(emptyList(), a, now - 40 * day)
        list = PlayedHistory.add(list, b, now - day)
        // 40일 전 기록은 지운다 (유튜브 API 로 받은 정보는 30일까지만 보관)
        assertEquals(listOf(b), list.map { it.video })
        list = PlayedHistory.add(list, a, now)
        list = PlayedHistory.add(list, b, now)
        assertEquals(listOf(b, a), list.map { it.video })

        val decoded = PlayedHistory.decode(PlayedHistory.encode(list), now)
        assertEquals(list, decoded)
        assertEquals("한 달 지나 열면 사라짐", emptyList<Any>(), PlayedHistory.decode(PlayedHistory.encode(list), now + 31 * day))
        assertEquals(emptyList<Any>(), PlayedHistory.decode("not json", now))

        val many = (0 until 50).fold(emptyList<com.mywatch.kakaomap.tube.Played>()) { acc, i ->
            PlayedHistory.add(acc, TubeVideo("video${i.toString().padStart(6, '0')}", "곡 $i", "누군가", 200), now)
        }
        assertEquals(PlayedHistory.MAX, many.size)
        assertEquals("곡 49", many.first().video.title)
    }
}
