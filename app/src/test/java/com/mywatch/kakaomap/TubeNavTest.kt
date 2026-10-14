package com.mywatch.kakaomap

import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.tube.Handoff
import com.mywatch.kakaomap.tube.SignInNeededException
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.YouTubeQuotaException
import com.mywatch.kakaomap.ui.tube.Feed
import com.mywatch.kakaomap.ui.tube.Fetched
import com.mywatch.kakaomap.ui.tube.Page
import com.mywatch.kakaomap.ui.tube.TubeBackend
import com.mywatch.kakaomap.ui.tube.TubeLoader
import com.mywatch.kakaomap.ui.tube.TubeNav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 유튜브 앱의 화면 이동(뒤로 가면 한 칸씩)과 데이터 받기(잠깐 기억 · 더 보기 · 실패해도 보던 것은 그대로 · 로그아웃하면 내 것 지우기)
 */
class TubeNavTest {

    private val v = TubeVideo("BzYnNdJhZQw", "밤편지", "IU")

    @Test
    fun backAlwaysGoesOneStepAndStopsAtHome() {
        val nav = TubeNav()
        assertEquals(Page.Home, nav.top)
        assertFalse("처음 화면에서 뒤로 = 앱을 닫는다", nav.back())
        nav.push(Page.Search("아이유"))
        nav.push(Page.Channel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK"))
        nav.push(Page.Channel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK"))
        assertEquals("같은 화면을 두 번 쌓지 않는다", 3, nav.depth)
        nav.push(Page.Menu(v))
        nav.push(Page.Menu(v.copy(id = "pSUydWEqKwE")))
        assertEquals("메뉴 위에 메뉴는 없다", 4, nav.depth)
        assertTrue(nav.back())
        assertEquals(Page.Channel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK"), nav.top)
        assertTrue(nav.back())
        assertEquals(Page.Search("아이유"), nav.top)
        nav.reset()
        assertEquals(Page.Home, nav.top)
        assertEquals(1, nav.depth)
    }

    @Test
    fun aLongTrailKeepsHomeAtTheBottom() {
        val nav = TubeNav()
        repeat(40) { nav.push(Page.Channel("UC" + "%022d".format(it), "채널 $it")) }
        assertTrue(nav.depth <= TubeNav.MAX)
        while (nav.back()) Unit
        assertEquals(Page.Home, nav.top)
    }

    @Test
    fun signingOutDropsMyPagesAndReplacingTheLoginPageSkipsIt() {
        val nav = TubeNav()
        nav.push(Page.Library)
        nav.push(Page.Liked)
        nav.push(Page.Menu(v))
        nav.dropMine()
        assertEquals("좋아요 화면은 사라지고 보관함으로", Page.Library, nav.top)

        nav.push(Page.Login)
        nav.replaceTop(Page.Account)
        assertEquals(Page.Account, nav.top)
        nav.back()
        assertEquals("로그인 화면은 뒤로 가기에 남지 않는다", Page.Library, nav.top)
        nav.push(Page.Handoff(Handoff(v, 83, mix = false)))
        assertTrue(nav.top is Page.Handoff)
    }

    // ---------------- 받기 ----------------

    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val changes = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    /** 가짜 유튜브: 받기마다 정해 둔 대로 (gate 가 있으면 그것이 끝날 때까지 기다림) */
    private class Fake : TubeBackend {
        val asked = mutableListOf<Pair<String, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        var fail: Exception? = null
        var pages = 3
        override suspend fun fetch(feed: Feed, token: String?, source: TubeSource?): Fetched {
            asked += feed.key to token
            gate?.await()
            fail?.let { throw it }
            val n = token?.removePrefix("T")?.toInt() ?: 0
            val ids = (0 until 2).map { "vid%08d".format(n * 10 + it) }
            return Fetched(
                TubePage(ids.map { TubeVideo(it, "제목 $it", "채널") }, if (n + 1 < pages) "T${n + 1}" else null,
                    channels = if (n == 0) listOf(TubeChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK", null)) else emptyList()),
                source = source ?: TubeSource.YOUTUBE,
            )
        }
    }

    private fun loader(fake: Fake) = TubeLoader(scope, fake, { now }) { changes += it.key }

    @Test
    fun aPageIsFetchedOnceAndKeptForAWhile() {
        val fake = Fake()
        val l = loader(fake)
        val f = Feed.Popular("10")
        l.load(f)
        assertEquals(2, l.slot(f).page?.videos?.size)
        l.load(f)
        assertEquals("15~30분은 기억한 것으로", 1, fake.asked.size)
        now += Feed.Popular("10").ttlMs + 1
        l.load(f)
        assertEquals(2, fake.asked.size)
        l.load(f, force = true)
        assertEquals("새로 고침은 바로", 3, fake.asked.size)
    }

    @Test
    fun whileLoadingThereIsNoDataYetAndOldDataStaysWhileRefreshing() {
        val fake = Fake()
        val l = loader(fake)
        val f = Feed.Search("아이유")
        fake.gate = CompletableDeferred()
        l.load(f)
        assertTrue(l.slot(f).loading)
        assertNull(l.slot(f).page)
        l.load(f)
        assertEquals("받는 중엔 또 묻지 않는다", 1, fake.asked.size)
        fake.gate!!.complete(Unit)
        assertFalse(l.slot(f).loading)
        assertEquals(2, l.slot(f).page?.videos?.size)

        fake.gate = CompletableDeferred()
        l.load(f, force = true)
        assertTrue(l.slot(f).loading)
        assertEquals("새로 고치는 동안 보던 것은 그대로", 2, l.slot(f).page?.videos?.size)
        fake.gate!!.complete(Unit)
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun moreAppendsTheNextPageAndStopsAtTheEnd() {
        val fake = Fake()
        val l = loader(fake)
        val f = Feed.ChannelVideos("UCweOkPb1wVVH0Q0Tlj4a5Pw")
        l.load(f)
        l.more(f)
        l.more(f)
        val page = l.slot(f).page!!
        assertEquals(6, page.videos.size)
        assertNull(page.next)
        assertEquals("채널은 같은 것을 두 번 넣지 않는다", 1, page.channels.size)
        l.more(f)
        assertEquals("끝이면 더 묻지 않는다", listOf(null, "T1", "T2"), fake.asked.map { it.second })
    }

    @Test
    fun aFailedLoadSaysWhyAndAFailedMoreKeepsWhatWasShown() {
        val fake = Fake()
        val l = loader(fake)
        val f = Feed.Liked
        fake.fail = SignInNeededException()
        l.load(f)
        assertTrue(l.slot(f).signIn)
        assertNull(l.slot(f).page)

        val s = Feed.Search("밤편지")
        fake.fail = null
        l.load(s)
        fake.fail = ApiException("유튜브 서버 오류 (500)")
        l.more(s)
        assertEquals(2, l.slot(s).page?.videos?.size)
        assertEquals("유튜브 서버 오류 (500)", l.takeMoreError(s))
        assertNull("알림은 한 번만", l.takeMoreError(s))
        assertEquals("T1", l.slot(s).page?.next)

        // 오늘 한도를 다 썼으면 더 보기를 없앤다
        fake.fail = YouTubeQuotaException()
        l.more(s)
        assertNull(l.slot(s).page?.next)
        // 다시 시도하면 처음부터
        fake.fail = null
        l.load(f, force = true)
        assertFalse(l.slot(f).signIn)
        assertEquals(2, l.slot(f).page?.videos?.size)
    }

    @Test
    fun signingOutForgetsMyDataButNotPublicPages() {
        val fake = Fake()
        val l = loader(fake)
        listOf(Feed.SubsFeed, Feed.Liked, Feed.MyPlaylists, Feed.Account, Feed.PlaylistVideos("PLmine0000001", own = true), Feed.Popular(null), Feed.Search("x"))
            .forEach(l::load)
        l.clearMine()
        assertNull(l.slot(Feed.SubsFeed).page)
        assertNull(l.slot(Feed.Liked).page)
        assertNull(l.slot(Feed.Account).page)
        assertNull(l.slot(Feed.PlaylistVideos("PLmine0000001", own = true)).page)
        assertEquals(2, l.slot(Feed.Popular(null)).page?.videos?.size)
        assertEquals(2, l.slot(Feed.Search("x")).page?.videos?.size)
    }

    @Test
    fun aSlowAnswerForSomethingAlreadyForgottenIsDropped() {
        val fake = Fake()
        val l = loader(fake)
        fake.gate = CompletableDeferred()
        l.load(Feed.Liked)
        l.clearMine()   // 받는 중에 로그아웃
        fake.gate!!.complete(Unit)
        assertNull("로그아웃 전에 묻던 내 데이터가 늦게 와도 남기지 않는다", l.slot(Feed.Liked).page)
    }
}
