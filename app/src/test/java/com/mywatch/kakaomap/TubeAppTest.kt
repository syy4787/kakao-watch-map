package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TokenStore
import com.mywatch.kakaomap.tube.Tokens
import com.mywatch.kakaomap.tube.TubeAccount
import com.mywatch.kakaomap.tube.TubeAuth
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.tube.playerDir
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubePlayerActivity
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.ui.tube.Feed
import com.mywatch.kakaomap.ui.tube.Fetched
import com.mywatch.kakaomap.ui.tube.TubeBackend
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * 워치용 유튜브 앱을 실제로 써 보듯이: 홈 → 누르면 앱 안 재생 화면(목록 그대로 이어서), 엔진이 죽었으면 인터넷 앱, 못 트는 것은 폰,
 * 검색 → 채널 → 뒤로, 길게 눌러 메뉴, 로그인(코드 → 폰에서 허용 → 저절로), 로그아웃, 폰에서 이어 보기, 타일, 다시 시도·더 보기
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)   // 갤럭시 워치 44mm (480×480)
class TubeAppTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val now = System.currentTimeMillis()
    private val browser = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")

    private fun v(id: String, title: String = "제목 $id", embeddable: Boolean = true) =
        TubeVideo(id, title, "1theK (원더케이)", 294, channelId = "UCweOkPb1wVVH0Q0Tlj4a5Pw", views = 1_000, publishedAt = now - 86_400_000, embeddable = embeddable)

    private val music = listOf(v("musicaaaaa1"), v("musicaaaaa2"), v("musicaaaaa3", embeddable = false), v("musicaaaaa4"))
    private val popular = listOf(v("popularaaa1"), v("popularaaa2"))
    private val channel = TubeChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK (원더케이)", "https://yt3.ggpht.com/k=s240", 25_300_000, 13_000, "UUweOkPb1wVVH0Q0Tlj4a5Pw")

    /** 가짜 유튜브 (바로 대답 — [holds] 에 있는 물음은 풀어 줄 때까지 기다림: 느린 연결) */
    private class FakeBackend : TubeBackend {
        val pages = HashMap<String, (String?) -> Fetched>()
        val asked = mutableListOf<String>()
        val holds = HashMap<String, CompletableDeferred<Unit>>()
        var fail: Exception? = null
        override suspend fun fetch(feed: Feed, token: String?, source: TubeSource?): Fetched {
            val key = feed.key + (token?.let { "@$it" } ?: "")
            asked += key
            holds[key]?.await()
            fail?.let { throw it }
            return pages[feed.key]?.invoke(token) ?: Fetched(TubePage(emptyList(), null))
        }
    }

    private class MemoryStore : TokenStore {
        var t: Tokens? = null
        override fun load() = t
        override fun save(t: Tokens) { this.t = t }
        override fun clear() { t = null }
    }

    private val backend = FakeBackend()
    private val store = MemoryStore()
    private val posts = mutableListOf<Pair<String, Map<String, String>>>()
    private var tokenReply: Http.Response = Http.Response(428, """{"error":"authorization_pending"}""")
    /** 구글에 토큰을 물을 때 (그 사이 다른 물음이 끝나 로그인되는 경우를 흉내) */
    private var onTokenAsked: () -> Unit = {}
    private val phoneOpened = mutableListOf<String>()

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        TubeState(context).clear()
        TubeState(context).agree()
        playerDir(context).deleteRecursively()
        WatchPlayer.base = base
        WatchPlayer.probe = { true }
        WatchPlayer.okUntil = 0L
        WatchPlayer.failUntil = 0L
        TubeActivity.backendOverride = backend
        TubeActivity.engineCheck = { true }
        TubeAccount.override = TubeAuth("123-abc.apps.googleusercontent.com", "secret", { url, form ->
            posts += url to form
            when {
                url.endsWith("/device/code") -> Http.Response(200, """{"device_code":"DC","user_code":"GQVQ-JKEC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""")
                url.endsWith("/token") -> tokenReply.also { onTokenAsked() }
                else -> Http.Response(200, "")
            }
        }, store)
        PhoneOpener.override = { url -> phoneOpened += url; PhoneOpener.Result.OPENED }
        backend.pages[Feed.Popular("10").key] = { token -> Fetched(TubePage(if (token == null) music else listOf(v("musicaaaaa5")), if (token == null) "M2" else null)) }
        backend.pages[Feed.Popular(null).key] = { Fetched(TubePage(popular, null)) }
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
        WatchPlayer.reset()
        TubeActivity.backendOverride = null
        TubeActivity.engineCheck = TubeActivity.defaultEngineCheck
        TubeAccount.override = null
        PhoneOpener.override = null
    }

    // ---------------- 도우미 ----------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 다른 스레드(로그인·확인)의 일이 화면에 돌아올 때까지 (실제 시간으로 최대 3초) */
    private fun settle(until: () -> Boolean = { false }) {
        val deadline = System.currentTimeMillis() + 3_000
        idle()
        while (System.currentTimeMillis() < deadline && !until()) {
            Thread.sleep(5)
            idle()
        }
    }

    private fun open(intent: Intent? = null): TubeActivity {
        val a = (if (intent == null) Robolectric.buildActivity(TubeActivity::class.java) else Robolectric.buildActivity(TubeActivity::class.java, intent)).setup().get()
        settle { !opening(a) }
        return a
    }

    private fun opening(a: TubeActivity) = a.findViewById<View>(R.id.status).visibility == View.VISIBLE

    private fun rows(a: TubeActivity): List<Row> = (a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter).rows

    private fun titles(a: TubeActivity) = rows(a).filterIsInstance<Row.Header>().map { it.title }

    private fun tap(a: TubeActivity, long: Boolean = false, wait: Boolean = true, which: (Row) -> Boolean) {
        val rv = a.findViewById<RecyclerView>(R.id.list)
        val i = rows(a).indexOfFirst(which)
        assertTrue("누를 줄이 있다: ${rows(a).map { it::class.simpleName + ":" + ((it as? Row.Action)?.id ?: (it as? Row.Header)?.title ?: "") }}", i >= 0)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        rv.scrollToPosition(i)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        val holder = rv.findViewHolderForAdapterPosition(i)!!
        val target = if (rows(a)[i] is Row.Chips) {
            val chips = rows(a)[i] as Row.Chips
            (holder.itemView as android.view.ViewGroup).getChildAt(chips.items.indexOfFirst { which(Row.Action(it.id, it.icon, it.text)) }.coerceAtLeast(0))
        } else holder.itemView
        if (long) target.performLongClick() else target.performClick()
        if (wait) settle { !opening(a) } else idle()
    }

    private fun tapAction(a: TubeActivity, id: String, wait: Boolean = true) =
        tap(a, wait = wait) { it is Row.Action && it.id == id || it is Row.Chips && it.items.any { c -> c.id == id } }

    private fun tapCard(a: TubeActivity, id: String, long: Boolean = false) = tap(a, long) { it is Row.VideoCard && it.video.id == id }

    private fun started(a: TubeActivity): Intent? = shadowOf(a).nextStartedActivity

    private fun Intent.playerPage(): String? = getStringExtra(TubePlayerActivity.EXTRA_URL)?.replace(Regex("&n=\\d+"), "")

    private fun installBrowser() {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        })
    }

    private fun signIn() {
        store.t = Tokens("AT", "RT", now + 3_600_000)
    }

    // ---------------- 처음 ----------------

    @Test
    fun theFirstTimeItAsksToAgreeAndDoesNotAskYouTubeBefore() {
        context.getSharedPreferences("tube", Context.MODE_PRIVATE).edit().clear().commit()
        val a = open()
        assertEquals("시작하기 전에", titles(a).first())
        assertTrue("동의하기 전엔 유튜브에 묻지 않는다: ${backend.asked}", backend.asked.isEmpty())
        tapAction(a, TubeRows.A_YT_TERMS)
        assertEquals(listOf(TubeLinks.YOUTUBE_TERMS), phoneOpened)
        tapAction(a, TubeRows.A_AGREE)
        assertEquals("유튜브", titles(a).first())
        assertTrue(backend.asked.contains(Feed.Popular("10").key))
        a.finish()
        val again = open()
        assertEquals("다시 열면 바로 홈", "유튜브", titles(again).first())
    }

    @Test
    fun beforeAgreeingBackClosesAndShortcutsWait() {
        context.getSharedPreferences("tube", Context.MODE_PRIVATE).edit().clear().commit()
        TubeState(context).addPlayed(v("lastaaaaaa1"))
        val a = open(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
        assertEquals("타일에서 와도 먼저 묻는다", "시작하기 전에", titles(a).first())
        assertNull("동의 전엔 틀지 않는다", started(a))
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(a.isFinishing)
    }

    // ---------------- 재생 ----------------

    @Test
    fun aCardOnHomePlaysInTheAppWithTheRestOfTheListAfterIt() {
        val a = open()
        assertTrue(titles(a).containsAll(listOf("유튜브", "인기 음악", "인기 동영상")))
        tapCard(a, "musicaaaaa1")
        val s = started(a)
        assertEquals(TubePlayerActivity::class.java.name, s?.component?.className)
        // 워치에서 못 트는 musicaaaaa3 은 빼고 이어서
        assertEquals(TubeLinks.playerUrl(base, music[0], Queue.Ids(listOf("musicaaaaa2", "musicaaaaa4")), inApp = true), s?.playerPage())
        assertEquals("musicaaaaa1", TubeState(context).lastPlayed()?.id)
        assertTrue("본 동영상이 '이어서 보기'에", titles(a).contains("이어서 보기"))
    }

    @Test
    fun ifTheEngineDiedRecentlyItPlaysInTheWatchBrowserInstead() {
        installBrowser()
        PlayerGuard.of(context).failed()
        val a = open()
        tapCard(a, "popularaaa1")
        val s = started(a)
        assertEquals(Intent.ACTION_VIEW, s?.action)
        assertEquals(TubeLinks.playerUrl(base, popular[0], Queue.Ids(listOf("popularaaa2"))), s?.dataString?.replace(Regex("&n=\\d+"), ""))
        assertEquals(context.getString(R.string.tube_engine_browser), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aVideoThatBlocksOtherPlayersOpensOnThePhone() {
        val a = open()
        tapCard(a, "musicaaaaa3")
        assertNull(started(a))
        assertEquals(listOf(TubeLinks.watchUrl("musicaaaaa3", mix = false)), phoneOpened)
    }

    @Test
    fun ifThePlayerPageCannotBeReachedItPlaysOnThePhone() {
        WatchPlayer.probe = { false }
        val a = open()
        tapCard(a, "popularaaa2")
        assertNull(started(a))
        assertEquals(listOf(TubeLinks.watchUrl("popularaaa2", mix = false)), phoneOpened)
    }

    // ---------------- 둘러보기 ----------------

    @Test
    fun searchThenChannelThenBackOneStepAtATime() {
        backend.pages[Feed.Search("아이유").key] = {
            Fetched(TubePage(listOf(v("searchaaaa1")), null, channels = listOf(channel), playlists = listOf(TubePlaylist("PL1aaaaaaaaaa", "모음", "멜론", null, 3))), TubeSource.YOUTUBE)
        }
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelVideos(channel.id).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1"), v("chanaaaaaa2")), null)) }
        val a = open()
        tapAction(a, TubeRows.A_SEARCH)
        val input = shadowOf(a).nextStartedActivityForResult
        assertNotNull("말·키보드 입력", input)
        val result = Intent()
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), result, Bundle().apply { putCharSequence("query", "아이유") })
        shadowOf(a).receiveResult(input.intent, Activity.RESULT_OK, result)
        settle()
        assertEquals("‘아이유’", titles(a).first())
        assertEquals(listOf("아이유"), TubeState(context).searches)
        tap(a) { it is Row.ChannelItem }
        assertEquals("1theK (원더케이)", titles(a).first())
        assertEquals(2, rows(a).filterIsInstance<Row.VideoCard>().size)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("‘아이유’", titles(a).first())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("유튜브", titles(a).first())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("홈에서 뒤로 = 닫기", a.isFinishing)
    }

    /**
     * 점검 C8: 비공개 내 재생목록의 '모두 재생'은 받은 첫 쪽(20개)만 이어 틀고 멈췄다 → 재생 화면이 받는 만큼(50개)
     * 더 받아서 튼다. 폰에서 열 땐 그 재생목록으로 (폰 유튜브는 로그인돼 있어 비공개 목록도 열린다)
     */
    @Test
    fun playingAllOfMyPrivatePlaylistQueuesMoreThanTheFirstPage() {
        signIn()
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.MyPlaylists.key] = {
            Fetched(TubePage(emptyList(), null, playlists = listOf(TubePlaylist("PLmine0000001", "드라이브", "나", null, 30, privacy = "private"))))
        }
        val first = (1..20).map { v("mine%07d".format(it)) }
        val second = (21..30).map { v("mine%07d".format(it)) }
        backend.pages[Feed.PlaylistVideos("PLmine0000001", own = true).key] = { token ->
            if (token == null) Fetched(TubePage(first, "M2")) else Fetched(TubePage(second, null))
        }
        val a = open()
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_MY_PLAYLISTS)
        tap(a) { it is Row.PlaylistItem }
        tapAction(a, TubeRows.A_PLAY_ALL)
        settle { shadowOf(a).peekNextStartedActivity() != null }
        val s = started(a)
        val ids = Uri.parse(s?.getStringExtra(TubePlayerActivity.EXTRA_URL)?.replace("#", "?")).getQueryParameter("ids").orEmpty().split(',')
        assertEquals("처음 것 다음 29개 (둘째 쪽까지)", (2..30).map { "mine%07d".format(it) }, ids)
        assertTrue(backend.asked.contains(Feed.PlaylistVideos("PLmine0000001", own = true).key + "@M2"))
    }

    private val myPlaylist = Feed.PlaylistVideos("PLmine0000001", own = true)

    /** 비공개 내 재생목록(30개 — 첫 쪽 20개, 둘째 쪽 10개)을 열어 둔다 */
    private fun openMyPrivatePlaylist(): ActivityController<TubeActivity> {
        signIn()
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.MyPlaylists.key] = {
            Fetched(TubePage(emptyList(), null, playlists = listOf(TubePlaylist("PLmine0000001", "드라이브", "나", null, 30, privacy = "private"))))
        }
        backend.pages[myPlaylist.key] = { token ->
            if (token == null) Fetched(TubePage((1..20).map { v("mine%07d".format(it)) }, "M2"))
            else Fetched(TubePage((21..30).map { v("mine%07d".format(it)) }, null))
        }
        val controller = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = controller.get()
        settle { !opening(a) }
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_MY_PLAYLISTS)
        tap(a) { it is Row.PlaylistItem }
        assertEquals("드라이브", titles(a).first())
        return controller
    }

    /**
     * 리뷰에서 찾음: 비공개 내 재생목록의 '모두 재생'은 더 받는 사이 홈으로 나가도 다 받으면 재생 화면을 띄워 저절로 틀었다
     * (안드로이드는 앱이 막 가려진 뒤 몇 초는 화면을 띄울 수 있다) → 나갔으면 받은 것만 목록에 두고 틀지 않는다
     */
    @Test
    fun leavingWhileMyPlaylistIsGatheredDoesNotStartThePlayer() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[myPlaylist.key + "@M2"] = hold
        val controller = openMyPrivatePlaylist()
        val a = controller.get()
        tapAction(a, TubeRows.A_PLAY_ALL, wait = false)
        assertTrue("더 받는 중", opening(a))
        controller.userLeaving().pause().stop()   // 홈으로
        hold.complete(Unit)
        idle()
        assertNull("나갔으면 틀지 않는다", started(a))
        controller.start().resume()
        settle { !opening(a) }
        assertEquals("받은 것은 목록에", 30, rows(a).filterIsInstance<Row.VideoCard>().size)
        assertNull("돌아와도 저절로 틀지 않는다", started(a))
    }

    private val power get() = shadowOf(context.getSystemService(PowerManager::class.java))

    /**
     * 재검토에서 찾음: 손목을 내려 화면만 꺼져도(항상 켜짐 화면을 끈 워치) '나감'으로 보아 기다리던 '모두 재생'을 버렸다 →
     * 화면이 꺼진 동안엔 틀지 않고(보일 때만 재생), 다시 볼 때(1분 안) 연다
     */
    @Test
    fun turningTheScreenOffWhileMyPlaylistIsGatheredOpensThePlayerWhenYouLookAgain() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[myPlaylist.key + "@M2"] = hold
        val controller = openMyPrivatePlaylist()
        val a = controller.get()
        tapAction(a, TubeRows.A_PLAY_ALL, wait = false)
        power.setIsInteractive(false)   // 손목을 내림
        controller.pause().stop()
        hold.complete(Unit)
        idle()
        assertNull("화면이 꺼진 동안엔 틀지 않는다", started(a))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        power.setIsInteractive(true)    // 다시 봄
        controller.start().resume()
        settle { shadowOf(a).peekNextStartedActivity() != null }
        val s = started(a)
        assertEquals(TubePlayerActivity::class.java.name, s?.component?.className)
        val ids = Uri.parse(s?.getStringExtra(TubePlayerActivity.EXTRA_URL)?.replace("#", "?")).getQueryParameter("ids").orEmpty().split(',')
        assertEquals((2..30).map { "mine%07d".format(it) }, ids)
    }

    @Test
    fun aPlayWaitingWhileTheScreenWasOffForMoreThanAMinuteIsForgotten() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[myPlaylist.key + "@M2"] = hold
        val controller = openMyPrivatePlaylist()
        val a = controller.get()
        tapAction(a, TubeRows.A_PLAY_ALL, wait = false)
        power.setIsInteractive(false)
        controller.pause().stop()
        hold.complete(Unit)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        power.setIsInteractive(true)
        controller.start().resume()
        settle { !opening(a) }
        assertNull("한참 뒤에 다시 보면 저절로 틀지 않는다", started(a))
    }

    /**
     * 재검토에서 찾음: 타일의 '믹스'로 열고 재생 화면을 확인하는 사이 나가면 '타일에서 온 일'이 남아, 나중에 폰에서 여는 동영상을 고르면
     * 앱이 저절로 닫혔다
     */
    @Test
    fun aTileMixAbandonedByLeavingDoesNotCloseTheAppLater() {
        TubeState(context).addPlayed(v("lastaaaaaa1"))
        val checking = CompletableDeferred<Unit>()
        WatchPlayer.probe = { checking.await(); true }
        val controller = Robolectric.buildActivity(TubeActivity::class.java, Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)).setup()
        val a = controller.get()
        idle()
        controller.userLeaving().pause().stop()   // 확인하는 사이 홈으로
        checking.complete(Unit)
        idle()
        assertNull("나갔으면 틀지 않는다", started(a))
        controller.start().resume()
        settle { !opening(a) }
        tapCard(a, "musicaaaaa3")                  // 폰에서만 열리는 동영상
        assertEquals(listOf(TubeLinks.watchUrl("musicaaaaa3", mix = false)), phoneOpened)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse("앱이 저절로 닫히지 않는다", a.isFinishing)
    }

    @Test
    fun goingBackWhileMyPlaylistIsGatheredDoesNotStartThePlayer() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[myPlaylist.key + "@M2"] = hold
        val a = openMyPrivatePlaylist().get()
        tapAction(a, TubeRows.A_PLAY_ALL, wait = false)
        a.onBackPressedDispatcher.onBackPressed()   // 마음을 바꿈
        idle()
        hold.complete(Unit)
        idle()
        assertNull("돌아갔으면 틀지 않는다", started(a))
        assertEquals("내 재생목록", titles(a).first())
    }

    /** 리뷰에서 찾음: 끝까지 내려 다음 쪽을 저절로 받는 중에 '모두 재생'을 누르면 같은 쪽을 또 받았다 (사용량) */
    @Test
    fun playAllWaitsForThePageAlreadyComingInsteadOfAskingForItAgain() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[myPlaylist.key + "@M2"] = hold
        val a = openMyPrivatePlaylist().get()
        val rv = a.findViewById<RecyclerView>(R.id.list)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        repeat(30) {
            rv.measure(spec, spec)
            rv.layout(0, 0, 480, 480)
            rv.scrollBy(0, 400)
            idle()
        }
        assertTrue("끝까지 내려 다음 쪽을 받는 중", backend.asked.contains(myPlaylist.key + "@M2"))
        tapAction(a, TubeRows.A_PLAY_ALL, wait = false)
        hold.complete(Unit)
        settle { shadowOf(a).peekNextStartedActivity() != null }
        val ids = Uri.parse(started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL)?.replace("#", "?")).getQueryParameter("ids").orEmpty().split(',')
        assertEquals((2..30).map { "mine%07d".format(it) }, ids)
        assertEquals("같은 쪽은 한 번만", 1, backend.asked.count { it == myPlaylist.key + "@M2" })
    }

    @Test
    fun aVideoFromMyPlaylistThatOnlyPlaysOnThePhoneOpensThatPlaylistThere() {
        signIn()
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.MyPlaylists.key] = {
            Fetched(TubePage(emptyList(), null, playlists = listOf(TubePlaylist("PLmine0000001", "드라이브", "나", null, 2, privacy = "private"))))
        }
        backend.pages[Feed.PlaylistVideos("PLmine0000001", own = true).key] = {
            Fetched(TubePage(listOf(v("phoneonly01", embeddable = false), v("mine0000002")), null))
        }
        val a = open()
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_MY_PLAYLISTS)
        tap(a) { it is Row.PlaylistItem }
        tapCard(a, "phoneonly01")
        settle { phoneOpened.isNotEmpty() }
        assertEquals(listOf("https://www.youtube.com/watch?v=phoneonly01&list=PLmine0000001"), phoneOpened)
    }

    /** 재생 화면을 보는 사이 이 화면이 정리됐다가 다시 만들어져도 보던 화면 그대로 (예전엔 홈부터) */
    @Test
    fun theTrailComesBackWhenTheScreenIsRecreated() {
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelVideos(channel.id).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1")), null)) }
        val controller = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = controller.get()
        settle { !opening(a) }
        tapCard(a, "musicaaaaa1", long = true)
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.pause().stop().destroy()
        val again = Robolectric.buildActivity(TubeActivity::class.java).setup(saved).get()
        settle { !opening(again) }
        assertEquals("1theK (원더케이)", titles(again).first())
        again.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("유튜브", titles(again).first())
    }

    /** 오른쪽으로 쓸기도 한 화면씩 뒤로 (Wear OS 는 쓸면 액티비티를 그냥 닫아 유튜브가 통째로 닫혔다). 홈에서는 시스템이 닫는다 */
    @Test
    fun swipingRightStepsBackOneScreenAndHomeLeavesItToTheSystem() {
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelVideos(channel.id).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1")), null)) }
        val a = open()
        val swipe = a.findViewById<androidx.wear.widget.SwipeDismissFrameLayout>(R.id.swipe)
        assertFalse(swipe.isSwipeable)
        tapCard(a, "musicaaaaa1", long = true)
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        assertTrue(swipe.isSwipeable)
        val dismissed = androidx.wear.widget.SwipeDismissFrameLayout::class.java.getDeclaredMethod("performDismissFinishedCallbacks")
        dismissed.isAccessible = true
        dismissed.invoke(swipe)
        settle()
        assertEquals("유튜브", titles(a).first())
        assertFalse(a.isFinishing)
        assertFalse(swipe.isSwipeable)
    }

    @Test
    fun longPressOpensTheMenuAndGoingToTheChannelReplacesIt() {
        val a = open()
        tapCard(a, "musicaaaaa1", long = true)
        assertEquals("제목 musicaaaaa1", titles(a).first())
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("메뉴는 뒤로 가기에 남지 않는다", "유튜브", titles(a).first())
    }

    /** 지금 목록의 맨 위에 보이는 것 (화면을 한 번 그려 보고) — 동영상이면 번호, 제목이면 제목, 버튼이면 id */
    private fun firstShown(a: TubeActivity): String {
        val rv = a.findViewById<RecyclerView>(R.id.list)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        val i = (rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
        return when (val r = rows(a)[i]) {
            is Row.VideoCard -> "card:" + r.video.id
            is Row.Header -> "header:" + r.title
            is Row.Action -> "action:" + r.id
            else -> r::class.simpleName + "@" + i
        }
    }

    @Test
    fun afterTheMenuTheListIsWhereItWas() {
        val a = open()
        val home = rows(a)
        // 목록 아래쪽의 동영상을 길게 눌러 메뉴 → 취소 → 그 자리
        tapCard(a, "popularaaa2", long = true)
        tapAction(a, TubeRows.A_CANCEL)
        val where = firstShown(a)
        assertTrue("아래쪽(인기 동영상)이 보이고 있었다: $where", where.startsWith("card:popular") || where == "header:인기 동영상")
        // 메뉴 → 재생 → 돌아오면 그 자리 (방금 본 동영상이 위쪽 '이어서 보기'에 들어가도)
        tapCard(a, "popularaaa2", long = true)
        tapAction(a, TubeRows.A_PLAY)
        assertEquals("메뉴는 닫히고 목록으로", "유튜브", titles(a).first())
        assertEquals("메뉴에서 틀고 돌아오면 보던 자리", where, firstShown(a))
        // 메뉴 → 채널 → 뒤로 = 보던 자리
        tapCard(a, "popularaaa2", long = true)
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("방금 본 동영상이 '이어서 보기'에 (위쪽이 늘었다)", home.size + 2, rows(a).size)
        assertEquals("채널에서 뒤로 오면 보던 자리", where, firstShown(a))
    }

    @Test
    fun theChannelButtonOnAVideoOfThisChannelJustClosesTheMenu() {
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelVideos(channel.id).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1"), v("chanaaaaaa2")), null)) }
        val a = open()
        tapCard(a, "musicaaaaa1", long = true)
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        // 채널 화면에서 그 채널 동영상의 메뉴 → '채널로 가기' = 메뉴만 닫힌다 (같은 채널이 두 번 쌓이지 않게)
        tapCard(a, "chanaaaaaa2", long = true)
        assertEquals("제목 chanaaaaaa2", titles(a).first())
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        assertTrue("채널의 동영상들이 보인다", rows(a).filterIsInstance<Row.VideoCard>().any { it.video.id == "chanaaaaaa1" })
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("한 번 뒤로 = 홈", "유튜브", titles(a).first())
    }

    @Test
    fun aFailedSectionCanBeRetriedAndMoreAddsTheNextPage() {
        backend.fail = ApiException("네트워크 연결을 확인하세요")
        val a = open()
        assertTrue(rows(a).any { it is Row.Note && it.text == "네트워크 연결을 확인하세요" })
        backend.fail = null
        tapAction(a, TubeRows.A_RETRY)
        assertEquals("인기 음악 4개 + 인기 동영상 2개", 6, rows(a).filterIsInstance<Row.VideoCard>().size)
        tapAction(a, TubeRows.A_CHART_MUSIC)
        assertEquals("인기 음악", titles(a).first())
        tapAction(a, TubeRows.A_MORE)
        assertTrue(rows(a).filterIsInstance<Row.VideoCard>().any { it.video.id == "musicaaaaa5" })
        assertTrue(backend.asked.contains(Feed.Popular("10").key + "@M2"))
    }

    /**
     * 베젤·크라운으로 끝까지 내리면 다음 쪽을 저절로 (1단위로 싼 목록만 — 100단위인 검색 '더 보기'는 누를 때만).
     * 같은 쪽을 두 번 저절로 묻지는 않는다 (실패하면 '더 보기'를 눌러서)
     */
    @Test
    fun scrollingToTheEndLoadsTheNextPageByItselfButNotForSearches() {
        backend.pages[Feed.Popular("10").key] = { token ->
            val n = if (token == null) 0 else 1
            Fetched(TubePage((1..12).map { v("music%02d%04d".format(n, it)) }, if (token == null) "M2" else null))
        }
        val a = open()
        tapAction(a, TubeRows.A_CHART_MUSIC)
        assertFalse(backend.asked.contains(Feed.Popular("10").key + "@M2"))
        scrollToEnd(a)
        assertTrue("끝까지 내리면 다음 쪽", backend.asked.contains(Feed.Popular("10").key + "@M2"))
        assertEquals(24, rows(a).filterIsInstance<Row.VideoCard>().size)

        backend.pages[Feed.Search("아이유").key] = { token ->
            Fetched(TubePage((1..12).map { v("search%05d".format(it)) }, if (token == null) "S2" else null), TubeSource.YOUTUBE)
        }
        a.onBackPressedDispatcher.onBackPressed()
        settle()
        tapAction(a, TubeRows.A_SEARCH)
        val input = shadowOf(a).nextStartedActivityForResult
        val result = Intent()
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), result, Bundle().apply { putCharSequence("query", "아이유") })
        shadowOf(a).receiveResult(input.intent, Activity.RESULT_OK, result)
        settle()
        scrollToEnd(a)
        assertFalse("검색은 저절로 더 묻지 않는다", backend.asked.any { it.startsWith(Feed.Search("아이유").key + "@") })
    }

    private fun scrollToEnd(a: TubeActivity) {
        val rv = a.findViewById<RecyclerView>(R.id.list)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        repeat(30) {
            rv.measure(spec, spec)
            rv.layout(0, 0, 480, 480)
            rv.scrollBy(0, 400)
            settle()
        }
    }

    // ---------------- 로그인 ----------------

    @Test
    fun signingInShowsACodeAndFinishesByItselfWhenAllowedOnThePhone() {
        backend.pages[Feed.SubsFeed.key] = { Fetched(TubePage(listOf(v("feedaaaaaa1")), null)) }
        backend.pages[Feed.SubsChannels.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        val a = open()
        tapAction(a, TubeRows.A_SUBS)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_subs_need_login) })
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        assertEquals("GQVQ-JKEC", rows(a).filterIsInstance<Row.Code>().single().code)
        tapAction(a, TubeRows.A_LOGIN_PHONE)
        settle { phoneOpened.isNotEmpty() }
        assertEquals(listOf("https://www.google.com/device"), phoneOpened)
        settle { rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_login_phone_opened) } }
        assertTrue("코드는 그대로 보인다", rows(a).any { it is Row.Code })
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_login_phone_opened) })

        // 폰에서 허용 → 다음 물음에서 끝난다
        tokenReply = Http.Response(200, """{"access_token":"AT","expires_in":3599,"refresh_token":"RT","scope":"https://www.googleapis.com/auth/youtube.readonly"}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle { titles(a).firstOrNull() == "구독" && rows(a).any { it is Row.VideoCard } }
        assertEquals("로그인 화면은 닫히고 보던 화면으로", "구독", titles(a).first())
        assertTrue(rows(a).filterIsInstance<Row.VideoCard>().any { it.video.id == "feedaaaaaa1" })
        assertNotNull(store.t)
        assertEquals(context.getString(R.string.tube_login_done), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun signingOutForgetsMyDataAndLeavesMyPages() {
        signIn()
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.Liked.key] = { Fetched(TubePage(listOf(v("likedaaaaa1")), null)) }
        val a = open()
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_ACCOUNT)
        assertEquals("1theK (원더케이)", titles(a).first())
        tapAction(a, TubeRows.A_LOGOUT)
        settle { posts.any { it.first.endsWith("/revoke") } }
        assertNull(store.t)
        assertEquals("RT", posts.last { it.first.endsWith("/revoke") }.second["token"])
        assertEquals("보관함", titles(a).first())
        assertFalse(rows(a).any { it is Row.Action && it.id == TubeRows.A_LIKED })
        assertTrue(rows(a).any { it is Row.Action && it.id == TubeRows.A_LOGIN })
    }

    @Test
    fun whenAccessIsRemovedInTheGoogleAccountMyPagesAndDataGoAway() {
        signIn()
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.Liked.key] = { Fetched(TubePage(listOf(v("likedaaaaa1")), null)) }
        val c = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = c.get()
        settle { !opening(a) }
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_LIKED)
        assertTrue(rows(a).filterIsInstance<Row.VideoCard>().any { it.video.id == "likedaaaaa1" })
        // 구글 계정 > 보안에서 이 앱의 접근을 끊음 → 다음에 토큰을 쓸 때 앱이 알아차리고 지운다
        store.t = null
        c.pause().resume()
        settle()
        assertFalse("내 화면은 걷어 낸다", titles(a).first() == "좋아요 표시한 동영상")
        // 다른 계정으로 다시 로그인 → 예전 계정의 좋아요가 남아 있지 않다
        backend.pages[Feed.Liked.key] = { Fetched(TubePage(listOf(v("likedbbbbb1")), null)) }
        store.t = Tokens("AT2", "RT2", now + 3_600_000)
        c.pause().resume()
        settle()
        if (titles(a).first() != "보관함") tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_LIKED)
        val ids = rows(a).filterIsInstance<Row.VideoCard>().map { it.video.id }
        assertEquals(listOf("likedbbbbb1"), ids)
    }

    /**
     * 점검 C2: 구글 계정에서 접근을 끊은 뒤(앱은 다음 받기에서 알아차리고 토큰을 지움) 구독 화면의 '로그인'을 누르면
     * 내 화면을 걷어 내는 일이 '로그인' 화면까지 걷어 내 홈으로 튀었다 — 처음 누를 땐 안 되고 두 번째에야 됐다
     */
    @Test
    fun afterAccessWasRemovedTheLoginButtonWorksTheFirstTime() {
        signIn()
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        val a = open()
        store.t = null   // 다른 화면의 받기에서 접근이 끊긴 것을 알아차림
        tapAction(a, TubeRows.A_SUBS)
        assertEquals("구독 화면은 열린다", "구독", titles(a).first())
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_subs_need_login) })
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        assertEquals("로그인", titles(a).first())
        assertTrue(posts.any { it.first.endsWith("/device/code") })
    }

    /** 점검 C7: 기다리는 중 구글이 잠깐 안 되면(503) '로그인 오류'로 끝났다 → 천천히 계속 기다리다 승인되면 끝 */
    @Test
    fun aGoogleHiccupWhileWaitingDoesNotEndTheSignIn() {
        val a = open()
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        tokenReply = Http.Response(503, "")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle()
        assertTrue("코드를 그대로 보여 주며 기다림", rows(a).any { it is Row.Code })
        tokenReply = Http.Response(200, """{"access_token":"AT","expires_in":3599,"refresh_token":"RT","scope":"https://www.googleapis.com/auth/youtube.readonly"}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        settle { titles(a).firstOrNull() != "로그인" }
        assertEquals("보관함", titles(a).first())
        assertNotNull(store.t)
    }

    /** 화면이 꺼지기 직전 보낸 물음으로 이미 로그인됐는데 다음 물음이 '거절'로 오면(같은 코드) 거절이 아니라 로그인된 것 */
    @Test
    fun aLateAnswerAfterSigningInIsNotShownAsDenied() {
        val a = open()
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        tokenReply = Http.Response(403, """{"error":"access_denied"}""")
        // 물음이 오가는 사이 앞서 보낸 물음이 끝나 로그인됨
        onTokenAsked = { store.t = Tokens("AT", "RT", now + 3_600_000) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle { titles(a).firstOrNull() != "로그인" }
        assertFalse(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_login_denied) })
        assertEquals("보관함", titles(a).first())
    }

    @Test
    fun ifSignInFinishedWhileTheScreenWasOffItSaysSoInsteadOfCodeExpired() {
        val a = open()
        tapAction(a, TubeRows.A_LIBRARY)
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        // 화면이 꺼지기 직전 보낸 물음이 끝나 로그인됨 → 그 코드는 이미 쓰였다 (다시 물으면 invalid_grant)
        store.t = Tokens("AT", "RT", now + 3_600_000)
        tokenReply = Http.Response(400, """{"error":"invalid_grant"}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle { titles(a).firstOrNull() != "로그인" }
        assertEquals("로그인 화면은 닫히고 보던 화면으로", "보관함", titles(a).first())
        assertFalse(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_login_expired) })
    }

    // ---------------- 링크 · 타일 ----------------

    @Test
    fun continueOnPhoneFromThePlayerAsksOnceThenOpensThePlaylistAtThatTime() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=83&list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx&title=%EB%B0%A4%ED%8E%B8%EC%A7%80"))
        val a = open(link)
        assertEquals("밤편지", titles(a).first())
        assertTrue(phoneOpened.isEmpty())
        tapAction(a, TubeRows.A_HANDOFF)
        assertEquals(listOf("https://www.youtube.com/watch?v=BzYnNdJhZQw&list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx&t=83s"), phoneOpened)
        assertEquals("유튜브", titles(a).first())
    }

    @Test
    fun theTileMixPlaysTheLastVideoAsAMixInTheApp() {
        TubeState(context).addPlayed(v("lastaaaaaa1"))
        val a = open(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
        val s = started(a)
        assertEquals(TubePlayerActivity::class.java.name, s?.component?.className)
        assertEquals(TubeLinks.playerUrl(base, v("lastaaaaaa1"), Queue.Mix, inApp = true), s?.playerPage())
    }
}
