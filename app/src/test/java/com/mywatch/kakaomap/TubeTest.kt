package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.PlayedHistory
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
    fun youTubeSearchAndDurationsCombine() {
        val search = JSONObject(
            """
            {"nextPageToken":"CBQQAA",
             "items":[
              {"id":{"kind":"youtube#video","videoId":"BzYnNdJhZQw"},
               "snippet":{"title":"[MV] IU(아이유) _ Through the Night(밤편지)","channelTitle":"1theK (원더케이)","liveBroadcastContent":"none"}},
              {"id":{"kind":"youtube#video","videoId":"jfKfPfyJRdk"},
               "snippet":{"title":"lofi hip hop radio &#128218; beats to relax/study to","channelTitle":"Lofi Girl","liveBroadcastContent":"live"}},
              {"id":{"kind":"youtube#video","videoId":"upcoming001"},
               "snippet":{"title":"곧 시작","channelTitle":"누군가","liveBroadcastContent":"upcoming"}},
              {"id":{"kind":"youtube#channel","channelId":"UC123"},"snippet":{"title":"채널","channelTitle":"채널"}}
             ]}
            """.trimIndent()
        )
        val page = TubeParsers.youtubeSearch(search)
        assertEquals("CBQQAA", page.next)
        // 아직 시작 안 한 생방송·채널은 틀 수 없으니 뺀다
        assertEquals(listOf("BzYnNdJhZQw", "jfKfPfyJRdk"), page.videos.map { it.id })
        assertTrue(page.videos[1].live)
        assertEquals("lofi hip hop radio 📚 beats to relax/study to", page.videos[1].title)

        val durations = TubeParsers.youtubeDurations(JSONObject(
            """{"items":[{"id":"BzYnNdJhZQw","contentDetails":{"duration":"PT4M54S"}},{"id":"jfKfPfyJRdk","contentDetails":{"duration":"P0D"}}]}"""
        ))
        val withTimes = TubeParsers.withDurations(page.videos, durations)
        assertEquals(294, withTimes[0].durationSec)
        assertNull(withTimes[1].durationSec)

        assertNull(TubeParsers.youtubeSearch(JSONObject("""{"items":[]}""")).next)
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
