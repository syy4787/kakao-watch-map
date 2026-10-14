package com.mywatch.kakaomap

import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.NetworkException
import com.mywatch.kakaomap.tube.SignInNeededException
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
        assertEquals("snippet,contentDetails,statistics,status", c.params["part"])
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

    companion object {
        fun ok(body: String) = Http.Response(200, body)

        fun error(code: Int, reason: String) =
            Http.Response(code, """{"error":{"code":$code,"message":"x","errors":[{"message":"x","domain":"youtube","reason":"$reason"}]}}""")

        fun vid(n: Int) = "vid%08d".format(n)

        /** 채널 k 의 j 번째 영상 번호 (11자) */
        fun feedId(k: Int, j: Int) = "v%02d_%dabcdef".format(k, j)

        fun video(
            id: String,
            title: String = "제목 $id",
            duration: String = "PT3M",
            views: Long = 1_000,
            publishedMs: Long = 1_700_000_000_000L,
        ): String {
            val published = java.time.Instant.ofEpochMilli(publishedMs).toString()
            val t = org.json.JSONObject.quote(title)
            return """{"id":"$id","snippet":{"publishedAt":"$published","channelId":"UCweOkPb1wVVH0Q0Tlj4a5Pw","title":$t,"channelTitle":"1theK (원더케이)","liveBroadcastContent":"none"},""" +
                """"contentDetails":{"duration":"$duration"},"statistics":{"viewCount":"$views"},"status":{"embeddable":true}}"""
        }

        fun videos(vararg items: String, next: String? = null) =
            ok("""{${if (next != null) "\"nextPageToken\":\"$next\"," else ""}"items":[${items.joinToString(",")}]}""")
    }
}
