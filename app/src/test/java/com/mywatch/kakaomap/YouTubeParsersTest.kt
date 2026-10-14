package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.PlayedHistory
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeParsers
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeText
import com.mywatch.kakaomap.tube.TubeVideo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 워치용 유튜브 앱이 YouTube Data API v3 응답(실제 모양)에서 읽는 것: 동영상(길이·조회수·올린 날·채널), 채널(사진·구독자·업로드 목록),
 * 재생목록, 재생목록 속 동영상, 구독, 섞인 검색 결과. 그리고 유튜브 앱처럼 보여 주는 글(조회수 1.2만회 · 3일 전 · 구독자 120만명)
 */
class YouTubeParsersTest {

    private val day = 86_400_000L

    @Test
    fun videoDetailsReadDurationViewsDateAndChannel() {
        // videos.list 의 제목은 올린 사람이 쓴 그대로 (search.list 처럼 &#39; 로 바꾸지 않음) — 꺾쇠·& 가 그대로 남아야 한다
        val json = JSONObject(
            """
            {"kind":"youtube#videoListResponse","items":[
              {"id":"BzYnNdJhZQw","snippet":{"publishedAt":"2017-03-24T09:00:01Z","channelId":"UCweOkPb1wVVH0Q0Tlj4a5Pw",
                "title":"[MV] IU(아이유) _ Through the Night(밤편지)","channelTitle":"1theK (원더케이)","liveBroadcastContent":"none"},
               "contentDetails":{"duration":"PT4M54S"},"statistics":{"viewCount":"512345678","likeCount":"3000000"},"status":{"embeddable":true}},
              {"id":"jfKfPfyJRdk","snippet":{"publishedAt":"2022-07-12T16:11:24.123Z","channelId":"UCSJ4gkVC6NrvII8umztf0Ow",
                "title":"lofi hip hop radio 📚 beats to relax\/study to","channelTitle":"Lofi Girl","liveBroadcastContent":"live"},
               "contentDetails":{"duration":"P0D"},"statistics":{"viewCount":"9000"}},
              {"id":"upcoming001","snippet":{"title":"곧 시작","channelTitle":"x","liveBroadcastContent":"upcoming"},"contentDetails":{"duration":"P0D"}},
              {"id":"nostatsxxxx","snippet":{"title":"드라마 <도깨비> OST & 뒷이야기","channelTitle":"y","liveBroadcastContent":"none"},
               "contentDetails":{"duration":"PT1H2M3S"},"status":{"embeddable":false}},
              {"id":"agelimitxxx","snippet":{"title":"나이 제한","channelTitle":"z","liveBroadcastContent":"none"},
               "contentDetails":{"duration":"PT3M","contentRating":{"ytRating":"ytAgeRestricted"}},"status":{"embeddable":true}},
              {"id":"bad id","snippet":{"title":"이상한 번호"}}
            ]}
            """.trimIndent()
        )
        val m = TubeParsers.videoDetails(json)
        assertEquals(setOf("BzYnNdJhZQw", "jfKfPfyJRdk", "nostatsxxxx", "agelimitxxx"), m.keys)
        val iu = m.getValue("BzYnNdJhZQw")
        assertEquals("[MV] IU(아이유) _ Through the Night(밤편지)", iu.title)
        assertEquals("1theK (원더케이)", iu.channel)
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw", iu.channelId)
        assertEquals(294, iu.durationSec)
        assertEquals(512345678L, iu.views)
        assertEquals(1490346001000L, iu.publishedAt)
        assertTrue(iu.embeddable)
        val lofi = m.getValue("jfKfPfyJRdk")
        assertTrue(lofi.live)
        assertNull(lofi.durationSec)
        assertEquals("lofi hip hop radio 📚 beats to relax/study to", lofi.title)
        assertEquals(1657642284123L, lofi.publishedAt)
        assertTrue("status 가 없으면 틀 수 있다고 본다", lofi.embeddable)
        val drama = m.getValue("nostatsxxxx")
        assertEquals("드라마 <도깨비> OST & 뒷이야기", drama.title)
        assertNull("조회수를 숨기면 모름", drama.views)
        assertEquals(3723, drama.durationSec)
        assertFalse("다른 곳에 넣어 틀기(임베드)를 막은 영상", drama.embeddable)
        assertFalse("나이 제한 영상은 로그인 안 된 플레이어에서 안 나온다", m.getValue("agelimitxxx").embeddable)
    }

    @Test
    fun searchKeepsVideosChannelsAndPlaylistsInTheirOrder() {
        val json = JSONObject(
            """
            {"nextPageToken":"CBQQAA","items":[
              {"id":{"kind":"youtube#channel","channelId":"UC3IZKseVpdzPSBaWxBxundA"},
               "snippet":{"title":"HYBE LABELS","channelTitle":"HYBE LABELS","thumbnails":{"default":{"url":"https://yt3.ggpht.com/a=s88"},"medium":{"url":"https://yt3.ggpht.com/a=s240"}}}},
              {"id":{"kind":"youtube#video","videoId":"pSUydWEqKwE"},
               "snippet":{"title":"NewJeans (뉴진스) &#39;Ditto&#39; Official MV (side A)","channelTitle":"HYBE LABELS","channelId":"UC3IZKseVpdzPSBaWxBxundA",
                 "publishedAt":"2022-12-19T15:00:10Z","liveBroadcastContent":"none"}},
              {"id":{"kind":"youtube#playlist","playlistId":"PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"},
               "snippet":{"title":"드라마 &lt;도깨비&gt; OST 모음","channelTitle":"멜론 &amp; 친구들","thumbnails":{"high":{"url":"https://i.ytimg.com/vi/pSUydWEqKwE/hqdefault.jpg"}}}},
              {"id":{"kind":"youtube#video","videoId":"pSUydWEqKwE"},"snippet":{"title":"중복","channelTitle":"x"}},
              {"id":{"kind":"youtube#video","videoId":"upcomingxx1"},"snippet":{"title":"예정","channelTitle":"x","liveBroadcastContent":"upcoming"}}
            ]}
            """.trimIndent()
        )
        val page = TubeParsers.youtubeSearchAll(json)
        assertEquals("CBQQAA", page.next)
        assertEquals(listOf("pSUydWEqKwE"), page.videos.map { it.id })
        assertEquals("NewJeans (뉴진스) 'Ditto' Official MV (side A)", page.videos[0].title)
        assertEquals("UC3IZKseVpdzPSBaWxBxundA", page.videos[0].channelId)
        assertEquals(listOf(TubeChannel("UC3IZKseVpdzPSBaWxBxundA", "HYBE LABELS", "https://yt3.ggpht.com/a=s240")), page.channels)
        // search.list 는 제목을 &lt; &amp; 로 바꿔 보낸다 → 되돌리되 꺾쇠 속 글은 지우지 않는다
        assertEquals(
            listOf(TubePlaylist("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx", "드라마 <도깨비> OST 모음", "멜론 & 친구들", "https://i.ytimg.com/vi/pSUydWEqKwE/hqdefault.jpg")),
            page.playlists,
        )
    }

    @Test
    fun channelsReadPictureSubscribersAndUploads() {
        val json = JSONObject(
            """
            {"items":[
              {"id":"UCweOkPb1wVVH0Q0Tlj4a5Pw","snippet":{"title":"1theK (원더케이)","thumbnails":{"default":{"url":"https://yt3.ggpht.com/k=s88"},"medium":{"url":"https://yt3.ggpht.com/k=s240"}}},
               "statistics":{"subscriberCount":"25300000","videoCount":"13000","hiddenSubscriberCount":false},
               "contentDetails":{"relatedPlaylists":{"likes":"","uploads":"UUweOkPb1wVVH0Q0Tlj4a5Pw"}}},
              {"id":"UChiddenxxxxxxxxxxxxxxxx","snippet":{"title":"숨긴 채널"},"statistics":{"hiddenSubscriberCount":true,"videoCount":"3"}}
            ]}
            """.trimIndent()
        )
        val list = TubeParsers.channels(json)
        assertEquals(
            TubeChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK (원더케이)", "https://yt3.ggpht.com/k=s240", 25_300_000, 13_000, "UUweOkPb1wVVH0Q0Tlj4a5Pw"),
            list[0],
        )
        assertEquals(TubeChannel("UChiddenxxxxxxxxxxxxxxxx", "숨긴 채널", null, null, 3, null), list[1])
        // 업로드 재생목록은 채널 번호의 UC 를 UU 로 (응답에 없을 때)
        assertEquals("UUweOkPb1wVVH0Q0Tlj4a5Pw", TubeLinks.uploadsOf("UCweOkPb1wVVH0Q0Tlj4a5Pw"))
        assertNull(TubeLinks.uploadsOf("PLabc"))
        assertNull(TubeLinks.uploadsOf("UCshort"))
    }

    @Test
    fun playlistsAndTheirItemsSkipPrivateAndDeletedVideos() {
        val lists = TubeParsers.playlists(JSONObject(
            """
            {"nextPageToken":"P2","items":[
              {"id":"PL1aaaaaaaaaa","snippet":{"title":"드라이브 노래","channelTitle":"나","thumbnails":{"medium":{"url":"https://i.ytimg.com/vi/x/mqdefault.jpg"}}},"contentDetails":{"itemCount":42}},
              {"id":"PL2bbbbbbbbbb","snippet":{"title":"빈 목록","channelTitle":"나"},"contentDetails":{"itemCount":0}}
            ]}
            """.trimIndent()
        ))
        assertEquals("P2", lists.second)
        assertEquals(TubePlaylist("PL1aaaaaaaaaa", "드라이브 노래", "나", "https://i.ytimg.com/vi/x/mqdefault.jpg", 42), lists.first[0])
        assertEquals(0, lists.first[1].count)

        val items = TubeParsers.playlistItems(JSONObject(
            """
            {"nextPageToken":"I2","items":[
              {"snippet":{"title":"첫 곡","resourceId":{"kind":"youtube#video","videoId":"BzYnNdJhZQw"}},"contentDetails":{"videoId":"BzYnNdJhZQw"}},
              {"snippet":{"title":"Private video","resourceId":{"videoId":"privatexxxx"}},"contentDetails":{"videoId":"privatexxxx"}},
              {"snippet":{"title":"Deleted video","resourceId":{"videoId":"deletedxxxx"}},"contentDetails":{"videoId":"deletedxxxx"}},
              {"snippet":{"title":"둘째 곡","resourceId":{"videoId":"pSUydWEqKwE"}}},
              {"snippet":{"title":"또 첫 곡","resourceId":{"videoId":"BzYnNdJhZQw"}}}
            ]}
            """.trimIndent()
        ))
        assertEquals(listOf("BzYnNdJhZQw", "pSUydWEqKwE"), items.first)
        assertEquals("I2", items.second)
    }

    @Test
    fun subscriptionsAreChannels() {
        val r = TubeParsers.subscriptions(JSONObject(
            """
            {"items":[
              {"snippet":{"title":"1theK (원더케이)","resourceId":{"kind":"youtube#channel","channelId":"UCweOkPb1wVVH0Q0Tlj4a5Pw"},
               "thumbnails":{"default":{"url":"https://yt3.ggpht.com/k=s88"}}}},
              {"snippet":{"title":"이상한 것","resourceId":{"channelId":"nope"}}}
            ]}
            """.trimIndent()
        ))
        assertEquals(listOf(TubeChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK (원더케이)", "https://yt3.ggpht.com/k=s88")), r.first)
        assertNull(r.second)
    }

    @Test
    fun countsAndTimesReadLikeTheYouTubeApp() {
        assertEquals("조회수 없음", TubeText.views(0))
        assertEquals("조회수 1회", TubeText.views(1))
        assertEquals("조회수 999회", TubeText.views(999))
        assertEquals("조회수 1.2천회", TubeText.views(1_234))
        assertEquals("조회수 1천회", TubeText.views(1_000))
        // 유튜브처럼 반올림하지 않고 버린다 (9,999 는 '1만'이 아니다)
        assertEquals("조회수 9.9천회", TubeText.views(9_999))
        assertEquals("조회수 1만회", TubeText.views(10_000))
        assertEquals("조회수 1.2만회", TubeText.views(12_345))
        assertEquals("조회수 12만회", TubeText.views(123_456))
        assertEquals("조회수 1234만회", TubeText.views(12_345_678))
        assertEquals("조회수 9999만회", TubeText.views(99_999_999))
        assertEquals("조회수 1억회", TubeText.views(100_000_000))
        assertEquals("조회수 5.1억회", TubeText.views(512_345_678))
        assertEquals("조회수 12억회", TubeText.views(1_234_567_890))
        assertEquals("구독자 2530만명", TubeText.subscribers(25_300_000))
        assertEquals("구독자 1.5천명", TubeText.subscribers(1_500))

        val now = 1_760_000_000_000L
        assertEquals("방금 전", TubeText.ago(now - 30_000, now))
        assertEquals("방금 전", TubeText.ago(now + 60_000, now))   // 시계가 조금 어긋나도
        assertEquals("5분 전", TubeText.ago(now - 5 * 60_000, now))
        assertEquals("3시간 전", TubeText.ago(now - 3 * 3_600_000, now))
        assertEquals("1일 전", TubeText.ago(now - day - 1, now))
        assertEquals("6일 전", TubeText.ago(now - 6 * day, now))
        assertEquals("2주 전", TubeText.ago(now - 15 * day, now))
        assertEquals("1개월 전", TubeText.ago(now - 31 * day, now))
        assertEquals("11개월 전", TubeText.ago(now - 340 * day, now))
        assertEquals("8년 전", TubeText.ago(now - 8 * 366 * day, now))
        assertEquals(1490346001000L, TubeParsers.iso8601Ms("2017-03-24T09:00:01Z"))
        assertEquals(1490346001000L, TubeParsers.iso8601Ms("2017-03-24T18:00:01+09:00"))
        assertNull(TubeParsers.iso8601Ms("어제"))
        assertNull(TubeParsers.iso8601Ms(null))
    }

    @Test
    fun playedHistoryKeepsTheChannelSoYouCanOpenItLater() {
        val v = TubeVideo("BzYnNdJhZQw", "밤편지", "1theK", 294, channelId = "UCweOkPb1wVVH0Q0Tlj4a5Pw", views = 5, publishedAt = 7, embeddable = false)
        val back = PlayedHistory.decode(PlayedHistory.encode(PlayedHistory.add(emptyList(), v, 1_000)), 1_000).single().video
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw", back.channelId)
        assertFalse("워치에서 못 트는 영상이었다는 것도 기억 (다시 누르면 바로 폰으로)", back.embeddable)
        // 조회수 같은 통계는 기록에 남기지 않는다 (받은 통계를 30일 넘게 두지 않게)
        assertNull(back.views)
        assertNull(back.publishedAt)
        // 예전 기록(채널 없음)·망가진 채널 번호는 채널 없이 읽는다
        val old = PlayedHistory.decode("""[{"id":"BzYnNdJhZQw","title":"t","channel":"c","cid":"nope","at":1000}]""", 1_000).single().video
        assertNull(old.channelId)
        assertTrue(old.embeddable)
    }
}
