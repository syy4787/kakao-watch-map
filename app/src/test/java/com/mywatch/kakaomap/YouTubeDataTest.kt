package com.mywatch.kakaomap

import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.NetworkException
import com.mywatch.kakaomap.tube.SignInNeededException
import com.mywatch.kakaomap.tube.Uploads
import com.mywatch.kakaomap.tube.YouTubeData
import com.mywatch.kakaomap.tube.YouTubeHttpException
import com.mywatch.kakaomap.tube.YouTubeQuotaException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Collections

/**
 * 유튜브 Data API 를 부르는 쪽: 무엇을 어떤 값으로 묻는지(키·로그인·필요한 칸만), 여러 번 나눠 묻기, 구독 피드 모으기,
 * 오류(한도·로그인 풀림·권한·인터넷)를 사용자에게 보여 줄 말로. 네트워크는 가짜 구글 서버
 */
class YouTubeDataTest {

    private class Call(val path: String, val params: Map<String, String>, val headers: Map<String, String>)

    /** 가짜 구글 서버: 경로별 대답을 정해 두고, 들어온 요청을 모두 적는다 (구독 피드는 여러 스레드에서 부른다) */
    private class FakeGoogle {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        val routes = mutableMapOf<String, (Call) -> Http.Response>()

        fun get(url: String, headers: Map<String, String>): Http.Response {
            val uri = URI(url)
            assertEquals("https", uri.scheme)
            assertEquals("www.googleapis.com", uri.host)
            assertTrue(uri.rawPath.startsWith("/youtube/v3/"))
            val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val call = Call(uri.rawPath.removePrefix("/youtube/v3"), params, headers)
            calls += call
            return routes[call.path]?.invoke(call) ?: error(404, "notFound")
        }

        fun count(path: String) = calls.count { it.path == path }
        fun last(path: String) = calls.last { it.path == path }
    }

    private val key = "SECRETKEY123"

    private fun data(g: FakeGoogle, token: () -> String? = { null }, onUnauthorized: () -> Unit = {}, key: String = this.key) =
        YouTubeData(key, token, g::get, onUnauthorized)

    @Test
    fun popularAsksForTheKoreanChartWithOnlyTheNeededFields() {
        val g = FakeGoogle()
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw", views = 512_345_678), video("pSUydWEqKwE"), next = "CHART2") }
        val page = data(g, token = { "AT" }).popular("10", null)
        assertEquals(listOf("BzYnNdJhZQw", "pSUydWEqKwE"), page.videos.map { it.id })
        assertEquals(512_345_678L, page.videos[0].views)
        assertEquals("CHART2", page.next)
        val c = g.last("/videos")
        assertEquals("mostPopular", c.params["chart"])
        assertEquals("KR", c.params["regionCode"])
        assertEquals("10", c.params["videoCategoryId"])
        assertEquals("snippet,contentDetails,statistics,status,player", c.params["part"])
        assertTrue("받는 칸을 줄인다 (워치는 폰 인터넷을 빌려 씀)", c.params["fields"].orEmpty().contains("statistics/viewCount"))
        // 누구나 보는 차트는 로그인했어도 키로 (로그인이 풀려도 홈은 보이게)
        assertEquals(key, c.params["key"])
        assertNull(c.headers["Authorization"])

        data(g).popular(null, "CHART2")
        assertFalse(g.last("/videos").params.containsKey("videoCategoryId"))
        assertEquals("CHART2", g.last("/videos").params["pageToken"])
    }

    @Test
    fun searchMixesVideosChannelsPlaylistsAndFillsInVideoDetails() {
        val g = FakeGoogle()
        g.routes["/search"] = {
            ok(
                """
                {"nextPageToken":"S2","items":[
                  {"id":{"kind":"youtube#channel","channelId":"UCweOkPb1wVVH0Q0Tlj4a5Pw"},"snippet":{"title":"1theK (원더케이)","channelTitle":"1theK (원더케이)"}},
                  {"id":{"kind":"youtube#video","videoId":"BzYnNdJhZQw"},"snippet":{"title":"IU &#39;밤편지&#39;","channelTitle":"1theK (원더케이)","liveBroadcastContent":"none"}},
                  {"id":{"kind":"youtube#playlist","playlistId":"PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"},"snippet":{"title":"아이유 모음","channelTitle":"멜론"}},
                  {"id":{"kind":"youtube#video","videoId":"gonevideo01"},"snippet":{"title":"지워진 영상","channelTitle":"x","liveBroadcastContent":"none"}}
                ]}
                """.trimIndent()
            )
        }
        // 영상 정보는 지워진 영상 것 없이 온다 → 그 영상은 틀 수 없으니 뺀다
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw", title = "IU '밤편지'", duration = "PT4M54S")) }
        val page = data(g).search("아이유 밤편지", null)
        assertEquals(listOf("BzYnNdJhZQw"), page.videos.map { it.id })
        assertEquals(294, page.videos[0].durationSec)
        assertEquals("IU '밤편지'", page.videos[0].title)
        assertEquals(listOf("UCweOkPb1wVVH0Q0Tlj4a5Pw"), page.channels.map { it.id })
        assertEquals(listOf("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"), page.playlists.map { it.id })
        assertEquals("S2", page.next)

        val s = g.last("/search")
        assertEquals("video,channel,playlist", s.params["type"])
        assertEquals("아이유 밤편지", s.params["q"])
        assertEquals("KR", s.params["regionCode"])
        assertEquals("ko", s.params["relevanceLanguage"])
        assertEquals("snippet", s.params["part"])
        assertEquals("BzYnNdJhZQw,gonevideo01", g.last("/videos").params["id"])
        assertFalse("id 로 물을 땐 maxResults 를 같이 보내면 안 된다", g.last("/videos").params.containsKey("maxResults"))
    }

    @Test
    fun searchStillShowsVideosWhenTheirDetailsCannotBeFetched() {
        val g = FakeGoogle()
        g.routes["/search"] = {
            ok("""{"items":[{"id":{"videoId":"BzYnNdJhZQw"},"snippet":{"title":"밤편지","channelTitle":"1theK","liveBroadcastContent":"none"}}]}""")
        }
        g.routes["/videos"] = { error(500, "backendError") }
        val page = data(g).search("밤편지", null)
        assertEquals(listOf("BzYnNdJhZQw"), page.videos.map { it.id })
        assertNull("길이는 모르지만 결과는 보여 준다", page.videos[0].durationSec)
        assertNull(page.next)
    }

    @Test
    fun detailsAreAskedFiftyAtATime() {
        val g = FakeGoogle()
        g.routes["/videos"] = { c -> videos(*c.params.getValue("id").split(',').map { video(it) }.toTypedArray()) }
        val ids = (1..120).map { vid(it) }
        val m = data(g).details(ids + listOf("bad", vid(1)))
        assertEquals(ids.toSet(), m.keys)
        assertEquals(listOf(50, 50, 20), g.calls.map { it.params.getValue("id").split(',').size })
    }

    @Test
    fun channelPageReadsTheChannelItsUploadsAndItsPlaylists() {
        val g = FakeGoogle()
        g.routes["/channels"] = {
            ok(
                """
                {"items":[{"id":"UCweOkPb1wVVH0Q0Tlj4a5Pw","snippet":{"title":"1theK (원더케이)","thumbnails":{"medium":{"url":"https://yt3.ggpht.com/k=s240"}}},
                  "statistics":{"subscriberCount":"25300000","videoCount":"13000","hiddenSubscriberCount":false},
                  "contentDetails":{"relatedPlaylists":{"uploads":"UUweOkPb1wVVH0Q0Tlj4a5Pw"}}}]}
                """.trimIndent()
            )
        }
        g.routes["/playlistItems"] = {
            ok(
                """
                {"nextPageToken":"U2","items":[
                  {"snippet":{"title":"새 영상","resourceId":{"videoId":"pSUydWEqKwE"}},"contentDetails":{"videoId":"pSUydWEqKwE"},"status":{"privacyStatus":"public"}},
                  {"snippet":{"title":"Private video","resourceId":{"videoId":"privatexxxx"}},"contentDetails":{"videoId":"privatexxxx"}},
                  {"snippet":{"title":"예전 영상","resourceId":{"videoId":"BzYnNdJhZQw"}},"contentDetails":{"videoId":"BzYnNdJhZQw"}}
                ]}
                """.trimIndent()
            )
        }
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw"), video("pSUydWEqKwE")) } // 정보는 다른 순서로 와도
        g.routes["/playlists"] = {
            ok("""{"items":[{"id":"PL1aaaaaaaaaa","snippet":{"title":"라이브 모음","channelTitle":"1theK (원더케이)"},"contentDetails":{"itemCount":12}}]}""")
        }
        val yt = data(g)
        val ch = yt.channel("UCweOkPb1wVVH0Q0Tlj4a5Pw")!!
        assertEquals(25_300_000L, ch.subscribers)
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/channels").params["id"])

        val uploads = yt.videosOf(ch.uploads!!, null)
        assertEquals("재생목록 순서 그대로 (비공개는 빼고)", listOf("pSUydWEqKwE", "BzYnNdJhZQw"), uploads.videos.map { it.id })
        assertEquals("U2", uploads.next)
        assertEquals("UUweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlistItems").params["playlistId"])
        assertEquals("pSUydWEqKwE,BzYnNdJhZQw", g.last("/videos").params["id"])

        val lists = yt.channelPlaylists(ch.id, null)
        assertEquals(listOf("PL1aaaaaaaaaa"), lists.playlists.map { it.id })
        assertEquals(12, lists.playlists[0].count)
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlists").params["channelId"])

        // 영상이 하나도 없는 채널(업로드 목록 없음 404)은 빈 목록, 이상한 번호는 묻지도 않는다
        g.routes["/playlistItems"] = { error(404, "playlistNotFound") }
        assertTrue(yt.videosOf("UUemptyxxxxxxxxxxxxxxxxx", null).videos.isEmpty())
        val before = g.calls.size
        assertNull(yt.channel("not a channel"))
        assertTrue(yt.videosOf("bad id!", null).videos.isEmpty())
        assertEquals(before, g.calls.size)
    }

    /**
     * 점검 C9: 재생목록 첫 쪽(20개)이 모두 비공개·삭제된 영상이면 뒤에 볼 영상이 있어도 '동영상이 없어요'로 끝났다 →
     * 볼 영상이 나올 때까지 세 쪽까지 더 본다
     */
    @Test
    fun aPlaylistWhoseFirstPageIsAllGoneKeepsLooking() {
        val g = FakeGoogle()
        fun gone(n: Int) = (1..n).joinToString(",") { """{"snippet":{"title":"Private video","resourceId":{"videoId":"private%04d"}}}""".format(it) }
        g.routes["/playlistItems"] = { c ->
            when (c.params["pageToken"]) {
                null -> ok("""{"nextPageToken":"P2","items":[${gone(20)}]}""")
                "P2" -> ok("""{"nextPageToken":"P3","items":[${gone(3)},{"contentDetails":{"videoId":"BzYnNdJhZQw"}}]}""")
                else -> ok("""{"items":[]}""")
            }
        }
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw")) }
        val page = data(g).videosOf("PL1aaaaaaaaaa", null)
        assertEquals(listOf("BzYnNdJhZQw"), page.videos.map { it.id })
        assertEquals("P3", page.next)
        assertEquals(2, g.count("/playlistItems"))
        // 끝까지 볼 영상이 없으면 세 쪽까지만 보고 (더 보기로 이어서)
        g.routes["/playlistItems"] = { c -> ok("""{"nextPageToken":"Q${(c.params["pageToken"] ?: "Q1").drop(1).toInt() + 1}","items":[${gone(20)}]}""") }
        val before = g.count("/playlistItems")
        val none = data(g).videosOf("PL1aaaaaaaaaa", null)
        assertTrue(none.videos.isEmpty())
        assertEquals(3, g.count("/playlistItems") - before)
        assertEquals("Q4", none.next)
    }

    @Test
    fun myPlaylistsSayWhetherTheyArePrivate() {
        val g = FakeGoogle()
        g.routes["/playlists"] = {
            ok("""{"items":[{"id":"PLmine0000001","snippet":{"title":"드라이브","channelTitle":"나"},"contentDetails":{"itemCount":3},"status":{"privacyStatus":"private"}},
                {"id":"PLmine0000002","snippet":{"title":"공유","channelTitle":"나"},"contentDetails":{"itemCount":3},"status":{"privacyStatus":"unlisted"}}]}""")
        }
        val lists = data(g, token = { "AT1" }).myPlaylists(null).playlists
        assertEquals(listOf("private", "unlisted"), lists.map { it.privacy })
        assertTrue(g.last("/playlists").params["part"].orEmpty().split(',').contains("status"))
        assertTrue(g.last("/playlists").params["fields"].orEmpty().contains("status/privacyStatus"))
    }

    @Test
    fun myDataNeedsSignInAndNeverAsksWithoutIt() {
        val g = FakeGoogle()
        val yt = data(g, token = { null })
        for (call in listOf<() -> Unit>({ yt.myPlaylists(null) }, { yt.liked(null) }, { yt.subscriptions(null) }, { yt.myChannel() })) {
            try {
                call()
                fail("로그인 안 했으면 내 데이터는 못 받는다")
            } catch (e: SignInNeededException) {
                // ok
            }
        }
        assertTrue(g.calls.isEmpty())
    }

    @Test
    fun myDataUsesTheSignInTokenInsteadOfTheKey() {
        val g = FakeGoogle()
        g.routes["/playlists"] = { ok("""{"items":[{"id":"PLmine0000001","snippet":{"title":"드라이브","channelTitle":"나"},"contentDetails":{"itemCount":3}}]}""") }
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw"), next = "L2") }
        g.routes["/subscriptions"] = {
            ok("""{"items":[{"snippet":{"title":"1theK (원더케이)","resourceId":{"channelId":"UCweOkPb1wVVH0Q0Tlj4a5Pw"}}}]}""")
        }
        g.routes["/channels"] = { ok("""{"items":[{"id":"UCmyownchannelxxxxxxxxx1","snippet":{"title":"내 채널"}}]}""") }
        val yt = data(g, token = { "AT1" })

        assertEquals(listOf("PLmine0000001"), yt.myPlaylists(null).playlists.map { it.id })
        val pl = g.last("/playlists")
        assertEquals("true", pl.params["mine"])
        assertEquals("Bearer AT1", pl.headers["Authorization"])
        assertFalse("로그인으로 물을 땐 키를 붙이지 않는다", pl.params.containsKey("key"))

        val liked = yt.liked(null)
        assertEquals(listOf("BzYnNdJhZQw"), liked.videos.map { it.id })
        assertEquals("L2", liked.next)
        assertEquals("like", g.last("/videos").params["myRating"])
        assertEquals(1, g.count("/videos"))

        assertEquals(listOf("UCweOkPb1wVVH0Q0Tlj4a5Pw"), yt.subscriptions(null).channels.map { it.id })
        assertEquals("true", g.last("/subscriptions").params["mine"])
        assertEquals("내 채널", yt.myChannel()?.title)
        assertEquals("true", g.last("/channels").params["mine"])

        // 내 재생목록(비공개일 수 있음)의 영상은 로그인으로 묻는다
        g.routes["/playlistItems"] = { ok("""{"items":[{"contentDetails":{"videoId":"BzYnNdJhZQw"}}]}""") }
        yt.videosOf("PLmine0000001", null, mine = true)
        assertEquals("Bearer AT1", g.last("/playlistItems").headers["Authorization"])
    }

    @Test
    fun anExpiredTokenIsRenewedOnceAndThenItMeansSignInAgain() {
        val g = FakeGoogle()
        var current = "AT1"
        var renewed = 0
        g.routes["/playlists"] = { c ->
            if (c.headers["Authorization"] == "Bearer AT1") error(401, "authError")
            else ok("""{"items":[{"id":"PLmine0000001","snippet":{"title":"드라이브","channelTitle":"나"}}]}""")
        }
        val yt = data(g, token = { current }, onUnauthorized = { renewed++; current = "AT2" })
        assertEquals(1, yt.myPlaylists(null).playlists.size)
        assertEquals(1, renewed)
        assertEquals(2, g.count("/playlists"))

        // 새 토큰도 거절되면 (계정에서 앱 접근을 끊음) 로그인이 필요하다
        g.routes["/playlists"] = { error(401, "authError") }
        try {
            yt.myPlaylists(null)
            fail()
        } catch (e: SignInNeededException) {
            assertEquals(4, g.count("/playlists"))
        }
    }

    @Test
    fun missingPermissionAsksToSignInAndAllowIt() {
        val g = FakeGoogle()
        g.routes["/videos"] = { error(403, "insufficientPermissions") }
        try {
            data(g, token = { "AT" }).liked(null)
            fail()
        } catch (e: SignInNeededException) {
            assertTrue(e.message!!.contains("권한"))
        }
    }

    @Test
    fun withoutAKeyPublicDataUsesTheSignInIfThereIsOne() {
        val g = FakeGoogle()
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw")) }
        data(g, token = { "AT" }, key = "").popular(null, null)
        assertEquals("Bearer AT", g.last("/videos").headers["Authorization"])
        assertFalse(g.last("/videos").params.containsKey("key"))
        try {
            data(g, token = { null }, key = " ").popular(null, null)
            fail()
        } catch (e: ApiException) {
            assertEquals(1, g.count("/videos"))
        }
    }

    @Test
    fun errorsBecomeShortKoreanMessagesWithoutSecrets() {
        val g = FakeGoogle()
        val yt = data(g, token = { "TOKEN-SECRET" })
        val seen = mutableListOf<Exception>()

        g.routes["/search"] = { error(403, "quotaExceeded") }
        try { yt.search("밤편지", null); fail() } catch (e: YouTubeQuotaException) { seen += e; assertTrue(e.message!!.contains("검색")) }

        // 검색 말고 나머지는 따로 한도(하루 1만)를 쓴다
        g.routes["/videos"] = { error(403, "quotaExceeded") }
        try { yt.popular(null, null); fail() } catch (e: YouTubeQuotaException) { seen += e; assertFalse(e.message!!.contains("검색")) }

        g.routes["/videos"] = { error(403, "rateLimitExceeded") }
        try { yt.popular(null, null); fail() } catch (e: YouTubeHttpException) { seen += e; assertEquals(403, e.code) }

        g.routes["/videos"] = { error(500, "backendError") }
        try { yt.popular(null, null); fail() } catch (e: YouTubeHttpException) { seen += e; assertEquals("유튜브 서버 오류 (500)", e.message) }

        g.routes["/videos"] = { Http.Response(200, "<html>proxy login</html>") }
        try { yt.popular(null, null); fail() } catch (e: ApiException) { seen += e; assertEquals("유튜브 응답을 읽지 못했어요", e.message) }

        g.routes["/videos"] = { throw IOException("timeout https://www.googleapis.com/youtube/v3/videos?key=$key") }
        try { yt.popular(null, null); fail() } catch (e: NetworkException) { seen += e }

        g.routes["/videos"] = { Http.Response(400, """{"error":{"code":400,"message":"API key not valid ($key)","errors":[{"reason":"badRequest"}],"details":[{"reason":"API_KEY_INVALID"}]}}""") }
        try { yt.popular(null, null); fail() } catch (e: YouTubeHttpException) { seen += e; assertEquals("유튜브 API 키가 올바르지 않아요", e.message) }

        seen.forEach { e ->
            assertFalse(e.toString(), e.message.orEmpty().contains(key))
            assertFalse(e.toString(), e.message.orEmpty().contains("TOKEN-SECRET"))
            assertNull("원인 예외(주소에 키가 있을 수 있음)를 달고 다니지 않는다", e.cause)
        }
    }

    @Test
    fun subscriptionFeedTakesTheNewestVideosOfTheTopChannels() = runBlocking {
        val g = FakeGoogle()
        val channels = (1..20).map { "UC" + "c%021d".format(it) }
        g.routes["/subscriptions"] = {
            ok("""{"items":[${channels.joinToString(",") { """{"snippet":{"title":"채널 $it","resourceId":{"channelId":"$it"}}}""" }}]}""")
        }
        // 채널 k 의 최신 3개. 2번 채널의 셋째는 1번 채널과 같이 만든 영상(같은 번호)
        fun idsOf(k: Int) = (1..3).map { j -> if (k == 2 && j == 3) feedId(1, 1) else feedId(k, j) }
        g.routes["/playlistItems"] = { c ->
            val k = channels.indexOf("UC" + c.params.getValue("playlistId").substring(2)) + 1
            when (k) {
                3 -> error(404, "playlistNotFound")       // 영상이 하나도 없는 채널
                4 -> error(500, "backendError")           // 잠깐 안 되는 채널은 빼고 나머지로
                else -> ok("""{"items":[${idsOf(k).joinToString(",") { """{"contentDetails":{"videoId":"$it"}}""" }}]}""")
            }
        }
        // 올린 때: 채널마다 섞여 있게
        fun publishedOf(id: String): Long {
            val k = id.substring(1, 3).toInt()
            val j = id.substring(4, 5).toInt()
            return 1_760_000_000_000L - ((k * 7) % 15) * 3_600_000L - j * 60_000L
        }
        g.routes["/videos"] = { c ->
            videos(*c.params.getValue("id").split(',').map { video(it, publishedMs = publishedOf(it)) }.toTypedArray())
        }

        val feed = data(g, token = { "AT" }).subscriptionFeed(maxChannels = 15, perChannel = 3, max = 30)

        assertEquals(1, g.count("/subscriptions"))
        assertEquals("구독 채널(관련도순) 앞 15개만", 15, g.count("/playlistItems"))
        assertEquals("영상 정보는 한 번에", 1, g.count("/videos"))
        val asked = (1..15).filter { it != 3 && it != 4 }.flatMap(::idsOf).distinct()
        assertEquals(asked.toSet(), g.last("/videos").params.getValue("id").split(',').toSet())
        val expected = asked.sortedByDescending(::publishedOf).take(30)
        assertEquals(expected, feed.map { it.id })
        assertEquals("업로드 목록은 키로 (누구나 보는 것)", key, g.last("/playlistItems").params["key"])
        assertEquals("3", g.last("/playlistItems").params["maxResults"])
    }

    @Test
    fun subscriptionFeedFailsOnlyWhenNoChannelCouldBeRead() = runBlocking {
        val g = FakeGoogle()
        g.routes["/subscriptions"] = {
            ok("""{"items":[{"snippet":{"title":"a","resourceId":{"channelId":"UCweOkPb1wVVH0Q0Tlj4a5Pw"}}},{"snippet":{"title":"b","resourceId":{"channelId":"UC3IZKseVpdzPSBaWxBxundA"}}}]}""")
        }
        g.routes["/playlistItems"] = { throw IOException("down") }
        try {
            data(g, token = { "AT" }).subscriptionFeed()
            fail()
        } catch (e: NetworkException) {
            assertEquals(0, g.count("/videos"))
        }
        // 구독이 하나도 없으면 빈 피드 (오류 아님)
        g.routes["/subscriptions"] = { ok("""{"items":[]}""") }
        assertTrue(data(g, token = { "AT" }).subscriptionFeed().isEmpty())
    }

    /** 동영상 정보는 어디서 묻든 분류와 플레이어 크기(세로인지)를 함께 — 크기는 maxHeight 를 보낸 요청에만 온다(문서). 값은 1단위 그대로 */
    @Test
    fun everyVideoQuestionAsksForTheShapeAndCategory() {
        val g = FakeGoogle()
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw", duration = "PT30S", size = 203 to 360, category = "10")) }
        val yt = data(g, token = { "AT" })
        val chart = yt.popular("10", null).videos.single()
        assertEquals("10", chart.categoryId)
        assertTrue("세로 30초 = Shorts", chart.isShort)
        assertEquals(true, yt.details(listOf("BzYnNdJhZQw")).getValue("BzYnNdJhZQw").portrait)
        yt.liked(null)
        assertEquals(3, g.count("/videos"))
        g.calls.forEach { c ->
            assertEquals("snippet,contentDetails,statistics,status,player", c.params["part"])
            assertEquals("360", c.params["maxHeight"])
            val fields = c.params["fields"].orEmpty()
            assertTrue(fields, fields.contains("liveBroadcastContent,categoryId)"))
            assertTrue(fields, fields.contains("player(embedWidth,embedHeight)"))
        }
        assertEquals(360, YouTubeData.MAX_HEIGHT)
        assertFalse("id 로 물을 땐 maxResults 없이", g.calls[1].params.containsKey("maxResults"))
    }

    @Test
    fun channelUploadsOfEachKindUseTheirOwnList() {
        val g = FakeGoogle()
        val ch = "UCweOkPb1wVVH0Q0Tlj4a5Pw"
        g.routes["/videos"] = videosFrom(mapOf(
            "longvideo01" to long("longvideo01"),
            "shortvideo1" to short("shortvideo1"),
            "livevideo01" to video("livevideo01", duration = "P0D", size = 0 to 0, live = true),
        ))
        g.routes["/playlistItems"] = { c ->
            when (c.params["playlistId"]) {
                "UULFweOkPb1wVVH0Q0Tlj4a5Pw" -> items(listOf("longvideo01"), next = "LF2")
                "UUSHweOkPb1wVVH0Q0Tlj4a5Pw" -> items(listOf("shortvideo1"))
                "UULVweOkPb1wVVH0Q0Tlj4a5Pw" -> items(listOf("livevideo01"))
                "UUweOkPb1wVVH0Q0Tlj4a5Pw" -> items(listOf("shortvideo1", "longvideo01", "livevideo01"))
                else -> error(404, "playlistNotFound")
            }
        }
        val yt = data(g)
        val long = yt.uploads(ch, Uploads.LONG, null)
        assertEquals(listOf("longvideo01"), long.videos.map { it.id })
        assertEquals("다음 쪽 값은 그대로", "LF2", long.next)
        assertEquals("UULFweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlistItems").params["playlistId"])
        yt.uploads(ch, Uploads.LONG, "LF2")
        assertEquals("UULFweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlistItems").params["playlistId"])
        assertEquals("LF2", g.last("/playlistItems").params["pageToken"])

        assertEquals(listOf("shortvideo1"), yt.uploads(ch, Uploads.SHORTS, null).videos.map { it.id })
        assertEquals("UUSHweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlistItems").params["playlistId"])
        assertEquals(listOf("livevideo01"), yt.uploads(ch, Uploads.LIVE, null).videos.map { it.id })
        assertEquals("UULVweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlistItems").params["playlistId"])
        assertEquals(listOf("shortvideo1", "longvideo01", "livevideo01"), yt.uploads(ch, Uploads.ALL, null).videos.map { it.id })
        assertEquals("UUweOkPb1wVVH0Q0Tlj4a5Pw", g.last("/playlistItems").params["playlistId"])
        assertEquals("목록은 키로 (누구나 보는 것)", key, g.last("/playlistItems").params["key"])
        assertEquals(5, g.count("/playlistItems"))

        // 이상한 채널 번호는 묻지도 않는다
        val before = g.calls.size
        Uploads.entries.forEach { assertTrue(yt.uploads("UCbad", it, null).videos.isEmpty()) }
        assertEquals(before, g.calls.size)
    }

    /**
     * UULF·UUSH·UULV 는 문서에 없는 자동 재생목록 — 없으면(404) 긴 동영상·Shorts 는 전체(UU)에서 모양으로 골라 대신하고,
     * 다음 쪽은 'UU:' 를 붙인 값으로 전체에서 같은 거르기로 이어서. 생방송은 대신할 길이 없어 빈 목록
     */
    @Test
    fun aMissingListOfOneKindFallsBackToAllUploadsFilteredByShape() {
        val g = FakeGoogle()
        val ch = "UChlgI3UHCOnwUGzWzbJ3H5w"
        val all = "UUhlgI3UHCOnwUGzWzbJ3H5w"
        val (s1, s2, s3) = listOf("short000001", "short000002", "short000003")
        val (l1, l2, l3, l4) = listOf("long0000001", "long0000002", "long0000003", "long0000004")
        g.routes["/videos"] = videosFrom(listOf(s1, s2, s3).associateWith { short(it) } + listOf(l1, l2, l3, l4).associateWith { long(it) })
        // 전체 목록: 1쪽 = Shorts·긴 것 섞임, 2쪽 = 긴 것만, 3쪽 = Shorts 하나 (끝)
        g.routes["/playlistItems"] = { c ->
            if (c.params["playlistId"] != all) error(404, "playlistNotFound")
            else when (c.params["pageToken"]) {
                null -> items(listOf(s1, l1, s2, l2), next = "U2")
                "U2" -> items(listOf(l3, l4), next = "U3")
                "U3" -> items(listOf(s3))
                else -> error(400, "invalidPageToken")
            }
        }
        val yt = data(g)
        val long = yt.uploads(ch, Uploads.LONG, null)
        assertEquals(listOf(l1, l2), long.videos.map { it.id })
        assertEquals("UU:U2", long.next)
        assertEquals(listOf("UULFhlgI3UHCOnwUGzWzbJ3H5w", all), g.calls.filter { it.path == "/playlistItems" }.map { it.params["playlistId"] })

        // 'UU:' 값으로 부르면 특별 목록은 다시 묻지 않고 전체에서 이어서
        var before = g.count("/playlistItems")
        val long2 = yt.uploads(ch, Uploads.LONG, "UU:U2")
        assertEquals(listOf(l3, l4), long2.videos.map { it.id })
        assertEquals("UU:U3", long2.next)
        assertEquals(1, g.count("/playlistItems") - before)
        assertEquals(all, g.last("/playlistItems").params["playlistId"])
        assertEquals("U2", g.last("/playlistItems").params["pageToken"])

        val shorts = yt.uploads(ch, Uploads.SHORTS, null)
        assertEquals(listOf(s1, s2), shorts.videos.map { it.id })
        assertEquals("UU:U2", shorts.next)
        // Shorts 가 하나도 없는 쪽은 넘겨서 볼 것이 나올 때까지 (세 쪽까지)
        before = g.count("/playlistItems")
        val shorts2 = yt.uploads(ch, Uploads.SHORTS, "UU:U2")
        assertEquals(listOf(s3), shorts2.videos.map { it.id })
        assertNull(shorts2.next)
        assertEquals(2, g.count("/playlistItems") - before)

        // 생방송은 대신하지 않는다 (전체 목록도 묻지 않음)
        before = g.count("/playlistItems")
        val live = yt.uploads(ch, Uploads.LIVE, null)
        assertTrue(live.videos.isEmpty())
        assertNull(live.next)
        assertEquals(1, g.count("/playlistItems") - before)

        // 동영상이 하나도 없는 채널(전체도 404)은 오류 없이 빈 목록
        val empty = "UC3IZKseVpdzPSBaWxBxundA"
        Uploads.entries.forEach { kind ->
            val page = yt.uploads(empty, kind, null)
            assertTrue("$kind", page.videos.isEmpty())
            assertNull("$kind", page.next)
        }

        // 404 가 아닌 오류는 그대로 (대신하지 않음)
        g.routes["/playlistItems"] = { error(500, "backendError") }
        try {
            yt.uploads(ch, Uploads.LONG, null)
            fail()
        } catch (e: YouTubeHttpException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun uploadsFeedTakesTheNewestOfOneKindFromSeveralChannels() = runBlocking {
        val g = FakeGoogle()
        val chA = "UCweOkPb1wVVH0Q0Tlj4a5Pw"
        val chB = "UChlgI3UHCOnwUGzWzbJ3H5w"
        val chC = "UC3IZKseVpdzPSBaWxBxundA"
        val chD = "UCSJ4gkVC6NrvII8umztf0Ow"
        val t0 = 1_760_000_000_000L
        val h = 3_600_000L
        g.routes["/videos"] = videosFrom(mapOf(
            "aLong000001" to long("aLong000001", t0 - 1 * h),
            "aLong000002" to long("aLong000002", t0 - 5 * h),
            "bShort00001" to short("bShort00001", t0 - 2 * h),
            "bLong000001" to long("bLong000001", t0 - 3 * h),
            "bLong000002" to long("bLong000002", t0 - 4 * h),
            "bLong000003" to long("bLong000003", t0 - 6 * h),
            "dLong000001" to long("dLong000001", t0 - h / 2),
            "aLive000001" to video("aLive000001", duration = "P0D", size = 0 to 0, live = true, publishedMs = t0),
        ))
        g.routes["/playlistItems"] = { c ->
            when (c.params["playlistId"]) {
                "UULF" + chA.substring(2) -> items(listOf("aLong000001", "aLong000002"))
                // B 는 긴 동영상 목록이 없음 → 전체에서 두 배(4개)를 받아 긴 것만, 채널마다 2개까지
                "UU" + chB.substring(2) -> items(listOf("bShort00001", "bLong000001", "bLong000002", "bLong000003"))
                "UULF" + chC.substring(2) -> error(500, "backendError")       // 잠깐 안 되는 채널은 빼고
                "UULF" + chD.substring(2) -> items(listOf("dLong000001"))
                "UULV" + chA.substring(2) -> items(listOf("aLive000001"))
                else -> error(404, "playlistNotFound")
            }
        }
        val feed = data(g).uploadsFeed(listOf(chA, chB, chC, chD, chA, "UCbad"), Uploads.LONG, perChannel = 2)
        assertEquals(listOf("dLong000001", "aLong000001", "bLong000001", "bLong000002", "aLong000002"), feed.map { it.id })
        assertEquals("정보는 한 번에", 1, g.count("/videos"))
        assertEquals(
            setOf("aLong000001", "aLong000002", "bShort00001", "bLong000001", "bLong000002", "bLong000003", "dLong000001"),
            g.last("/videos").params.getValue("id").split(',').toSet(),
        )
        val asked = g.calls.filter { it.path == "/playlistItems" }
        assertEquals("같은 채널은 한 번, 이상한 번호는 묻지 않는다", 5, asked.size)
        asked.forEach { c ->
            val n = if (c.params["playlistId"] == "UU" + chB.substring(2)) "4" else "2"
            assertEquals(c.params["playlistId"], n, c.params["maxResults"])
            assertEquals("contentDetails", c.params["part"])
            assertEquals(key, c.params["key"])
        }
        assertEquals(listOf("dLong000001", "aLong000001"), data(g).uploadsFeed(listOf(chA, chB, chD), Uploads.LONG, 2, max = 2).map { it.id })

        // 생방송 목록이 없으면(404) 그 채널은 없음 — 전체로 대신하지 않는다
        val before = g.count("/playlistItems")
        val live = data(g).uploadsFeed(listOf(chA, chB), Uploads.LIVE, 3)
        assertEquals(listOf("aLive000001"), live.map { it.id })
        assertEquals(2, g.count("/playlistItems") - before)

        // 채널이 없으면 묻지 않고 빈 목록
        val calls = g.calls.size
        assertTrue(data(g).uploadsFeed(emptyList(), Uploads.LONG, 3).isEmpty())
        assertEquals(calls, g.calls.size)
    }

    @Test
    fun uploadsFeedFailsOnlyWhenNoChannelCouldBeRead() = runBlocking {
        val g = FakeGoogle()
        val chA = "UCweOkPb1wVVH0Q0Tlj4a5Pw"
        val chB = "UChlgI3UHCOnwUGzWzbJ3H5w"
        g.routes["/playlistItems"] = { c ->
            if (c.params["playlistId"]!!.endsWith(chA.substring(2))) error(500, "backendError") else throw IOException("down")
        }
        try {
            data(g).uploadsFeed(listOf(chA, chB), Uploads.SHORTS, 3)
            fail()
        } catch (e: YouTubeHttpException) {
            assertEquals("준 채널 순서의 첫 오류", 500, e.code)
            assertEquals(0, g.count("/videos"))
        }
        try {
            data(g).shortsOf(listOf(chB, chA), 3)
            fail()
        } catch (e: NetworkException) {
            assertEquals(0, g.count("/videos"))
        }
    }

    /** Shorts 고르기: 채널마다 새것부터, 준 채널 순서대로 번갈아 · Shorts 모양(세로·3분 이하)만 */
    @Test
    fun shortsTakeTurnsBetweenChannelsInTheGivenOrder() = runBlocking {
        val g = FakeGoogle()
        val chA = "UCweOkPb1wVVH0Q0Tlj4a5Pw"
        val chB = "UChlgI3UHCOnwUGzWzbJ3H5w"
        val chC = "UC3IZKseVpdzPSBaWxBxundA"
        val chD = "UCSJ4gkVC6NrvII8umztf0Ow"
        val t0 = 1_760_000_000_000L
        val m = 60_000L
        g.routes["/videos"] = videosFrom(mapOf(
            "aShort00001" to short("aShort00001", t0 - 10 * m),
            "aShort00002" to short("aShort00002", t0 - 20 * m),
            "aShort00003" to short("aShort00003", t0 - 30 * m),
            "bShort00001" to short("bShort00001", t0 - 1 * m),
            "bShort00002" to short("bShort00002", t0 - 2 * m),
            "bShort00003" to short("bShort00003", t0 - 3 * m),
            "bShort00004" to short("bShort00004", t0 - 4 * m),
            "bLong000001" to long("bLong000001", t0),
            "cShort00001" to short("cShort00001", t0 - 100 * m),
            "cWide000001" to video("cWide000001", duration = "PT30S", size = 640 to 360),
            "cTall000001" to video("cTall000001", duration = "PT4M", size = 203 to 360),
            "cUnknown001" to video("cUnknown001", duration = "PT30S"),
        ))
        g.routes["/playlistItems"] = { c ->
            when (c.params["playlistId"]) {
                // 목록 순서가 새것부터가 아니어도 채널 안에서는 새것부터
                "UUSH" + chA.substring(2) -> items(listOf("aShort00002", "aShort00001", "aShort00003"))
                // B 는 Shorts 목록이 없음 → 전체에서 6개(두 배)를 받아 Shorts 만 3개까지
                "UU" + chB.substring(2) -> items(listOf("bLong000001", "bShort00001", "bShort00002", "bShort00003", "bShort00004"))
                "UUSH" + chC.substring(2) -> items(listOf("cWide000001", "cShort00001", "cTall000001", "cUnknown001"))
                "UUSH" + chD.substring(2) -> items(emptyList())
                else -> error(404, "playlistNotFound")
            }
        }
        val shorts = data(g).shortsOf(listOf(chC, chA, chD, chB), perChannel = 3)
        assertEquals(
            listOf("cShort00001", "aShort00001", "bShort00001", "aShort00002", "bShort00002", "aShort00003", "bShort00003"),
            shorts.map { it.id },
        )
        assertTrue(shorts.all { it.isShort })
        assertEquals("정보는 한 번에", 1, g.count("/videos"))
        g.calls.filter { it.path == "/playlistItems" }.forEach { c ->
            val n = if (c.params["playlistId"] == "UU" + chB.substring(2)) "6" else "3"
            assertEquals(c.params["playlistId"], n, c.params["maxResults"])
        }
        assertTrue(data(g).shortsOf(emptyList(), 3).isEmpty())
    }

    @Test
    fun searchVideosSendsExactlyTheGivenConditionsAndKeepsTheOrder() {
        val g = FakeGoogle()
        val found = """
            {"nextPageToken":"S2","items":[
              {"id":{"kind":"youtube#video","videoId":"livevideo01"},"snippet":{"title":"생방송","channelTitle":"YTN","liveBroadcastContent":"live"}},
              {"id":{"kind":"youtube#channel","channelId":"UChlgI3UHCOnwUGzWzbJ3H5w"},"snippet":{"title":"YTN"}},
              {"id":{"kind":"youtube#video","videoId":"gonevideo01"},"snippet":{"title":"지워짐","channelTitle":"x","liveBroadcastContent":"none"}},
              {"id":{"kind":"youtube#video","videoId":"livevideo02"},"snippet":{"title":"생방송 2","channelTitle":"KBS","liveBroadcastContent":"live"}}
            ]}
        """.trimIndent()
        g.routes["/search"] = { ok(found) }
        // 정보는 다른 순서로, 지워진 것 없이 온다
        g.routes["/videos"] = {
            videos(
                video("livevideo02", duration = "P0D", size = 0 to 0, live = true, views = 7),
                video("livevideo01", duration = "P0D", size = 0 to 0, live = true, views = 9),
            )
        }
        val yt = data(g)
        val page = yt.searchVideos(mapOf("eventType" to "live"), null)
        assertEquals("검색 순서 그대로, 정보에 없는 것은 빼고", listOf("livevideo01", "livevideo02"), page.videos.map { it.id })
        assertTrue(page.videos.all { it.live })
        assertEquals(9L, page.videos[0].views)
        assertTrue("동영상만", page.channels.isEmpty())
        assertEquals("S2", page.next)
        assertEquals(
            mapOf(
                "part" to "snippet", "type" to "video", "maxResults" to "20", "regionCode" to "KR", "relevanceLanguage" to "ko",
                "fields" to YouTubeData.SEARCH_FIELDS, "eventType" to "live", "key" to key,
            ),
            g.last("/search").params,
        )
        assertEquals("livevideo01,gonevideo01,livevideo02", g.last("/videos").params["id"])

        // Shorts 보충 (짧은 동영상 · #shorts) — 준 그대로, 다음 쪽까지
        yt.searchVideos(mapOf("videoDuration" to "short", "q" to "#shorts"), "S2")
        val shorts = g.last("/search")
        assertEquals("short", shorts.params["videoDuration"])
        assertEquals("#shorts", shorts.params["q"])
        assertEquals("S2", shorts.params["pageToken"])
        assertFalse(shorts.params.containsKey("eventType"))
        // 기본 조건(동영상만·받는 칸)은 바꿀 수 없다
        yt.searchVideos(mapOf("type" to "channel", "fields" to "items", "videoCategoryId" to "25"), null)
        val news = g.last("/search")
        assertEquals("video", news.params["type"])
        assertEquals(YouTubeData.SEARCH_FIELDS, news.params["fields"])
        assertEquals("25", news.params["videoCategoryId"])

        // 정보를 못 받으면 검색 결과만이라도
        g.routes["/videos"] = { error(500, "backendError") }
        assertEquals(listOf("livevideo01", "gonevideo01", "livevideo02"), yt.searchVideos(mapOf("eventType" to "live"), null).videos.map { it.id })
        // 검색 한도(하루 100번)를 쓴다 — 다 쓰면 검색 한도라고
        g.routes["/search"] = { error(403, "quotaExceeded") }
        try {
            yt.searchVideos(mapOf("eventType" to "live"), null)
            fail()
        } catch (e: YouTubeQuotaException) {
            assertTrue(e.message!!.contains("검색"))
        }
        // 결과가 없으면 정보는 묻지 않는다
        g.routes["/search"] = { ok("""{"items":[]}""") }
        val videosBefore = g.count("/videos")
        assertTrue(yt.searchVideos(mapOf("eventType" to "live"), null).videos.isEmpty())
        assertEquals(videosBefore, g.count("/videos"))
    }

    @Test
    fun theChannelOfAVideoComesFromItsDetails() {
        val g = FakeGoogle()
        g.routes["/videos"] = { c ->
            if (c.params["id"] == "BzYnNdJhZQw") videos(video("BzYnNdJhZQw", channelId = "UCweOkPb1wVVH0Q0Tlj4a5Pw", channelTitle = "1theK (원더케이)"))
            else videos()
        }
        val yt = data(g)
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw" to "1theK (원더케이)", yt.channelOfVideo("BzYnNdJhZQw"))
        assertEquals(1, g.count("/videos"))
        assertNull("지워진 동영상", yt.channelOfVideo("gonevideo01"))
        val before = g.calls.size
        assertNull("이상한 번호는 묻지 않는다", yt.channelOfVideo("bad\"id"))
        assertEquals(before, g.calls.size)
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw", channelId = "nope")) }
        assertNull("채널 번호가 이상하면 모름", yt.channelOfVideo("BzYnNdJhZQw"))
        g.routes["/videos"] = { videos(video("BzYnNdJhZQw", channelTitle = " ")) }
        assertEquals("UCweOkPb1wVVH0Q0Tlj4a5Pw" to "채널", yt.channelOfVideo("BzYnNdJhZQw"))
    }

    companion object {
        fun ok(body: String) = Http.Response(200, body)

        fun error(code: Int, reason: String) =
            Http.Response(code, """{"error":{"code":$code,"message":"x","errors":[{"message":"x","domain":"youtube","reason":"$reason"}]}}""")

        fun vid(n: Int) = "vid%08d".format(n)

        /** 채널 k 의 j 번째 영상 번호 (11자) */
        fun feedId(k: Int, j: Int) = "v%02d_%dabcdef".format(k, j)

        /** [size] = 플레이어 크기 (너비 to 높이, maxHeight=360 으로 물은 답 — 글로 온다), [category] = 분류 번호 */
        fun video(
            id: String,
            title: String = "제목 $id",
            duration: String = "PT3M",
            views: Long = 1_000,
            publishedMs: Long = 1_700_000_000_000L,
            channelId: String = "UCweOkPb1wVVH0Q0Tlj4a5Pw",
            channelTitle: String = "1theK (원더케이)",
            size: Pair<Int, Int>? = null,
            category: String? = null,
            live: Boolean = false,
        ): String {
            val published = java.time.Instant.ofEpochMilli(publishedMs).toString()
            val t = org.json.JSONObject.quote(title)
            val c = org.json.JSONObject.quote(channelTitle)
            val cat = category?.let { ""","categoryId":"$it"""" }.orEmpty()
            val player = size?.let { (w, h) -> ""","player":{"embedWidth":"$w","embedHeight":"$h"}""" }.orEmpty()
            return """{"id":"$id","snippet":{"publishedAt":"$published","channelId":"$channelId","title":$t,"channelTitle":$c,""" +
                """"liveBroadcastContent":"${if (live) "live" else "none"}"$cat},""" +
                """"contentDetails":{"duration":"$duration"},"statistics":{"viewCount":"$views"},"status":{"embeddable":true}$player}"""
        }

        /** 세로 30초 (Shorts — 실제로 203×360 으로 옴) */
        fun short(id: String, publishedMs: Long = 1_700_000_000_000L) = video(id, duration = "PT30S", publishedMs = publishedMs, size = 203 to 360)

        /** 가로 4분 */
        fun long(id: String, publishedMs: Long = 1_700_000_000_000L) = video(id, duration = "PT4M", publishedMs = publishedMs, size = 640 to 360)

        fun videos(vararg items: String, next: String? = null) =
            ok("""{${if (next != null) "\"nextPageToken\":\"$next\"," else ""}"items":[${items.joinToString(",")}]}""")

        /** playlistItems 한 쪽 (part=contentDetails 만 와도 읽는다) */
        fun items(ids: List<String>, next: String? = null) =
            ok("""{${if (next != null) "\"nextPageToken\":\"$next\"," else ""}"items":[${ids.joinToString(",") { """{"contentDetails":{"videoId":"$it"}}""" }}]}""")

        /** 가짜 동영상 정보: 물은 번호 중 [catalog] 에 있는 것만 (물은 순서대로) */
        private fun videosFrom(catalog: Map<String, String>): (Call) -> Http.Response =
            { c -> videos(*c.params.getValue("id").split(',').mapNotNull { catalog[it] }.toTypedArray()) }
    }
}
