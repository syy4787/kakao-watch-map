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
import android.view.ViewGroup
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
import com.mywatch.kakaomap.tube.TubeInbox
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.Uploads
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
import com.mywatch.kakaomap.ui.tube.Page
import com.mywatch.kakaomap.ui.tube.TubeBackend
import com.mywatch.kakaomap.ui.tube.TubeTab
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
        // 인기(한국 — 음악·게임·영화 차트): 처음 쓰는 사람의 홈 '전체'는 이것
        backend.pages[Feed.Popular(null).key] = { token -> Fetched(TubePage(if (token == null) music + popular else listOf(v("trendaaaaa7")), if (token == null) "T2" else null)) }
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
        val target = rv.findViewHolderForAdapterPosition(i)!!.itemView
        if (long) target.performLongClick() else target.performClick()
        if (wait) settle { !opening(a) } else idle()
    }

    private fun tapAction(a: TubeActivity, id: String, wait: Boolean = true) = tap(a, wait = wait) { it is Row.Action && it.id == id }

    private fun tapCard(a: TubeActivity, id: String, long: Boolean = false) = tap(a, long) { it is Row.VideoCard && it.video.id == id }

    /** 한 줄 안의 칸(탭·칩·세로 카드)을 누른다 */
    private fun tapIn(a: TubeActivity, long: Boolean = false, which: (Row) -> Boolean, pick: (View) -> View) {
        val rv = a.findViewById<RecyclerView>(R.id.list)
        val i = rows(a).indexOfFirst(which)
        assertTrue("누를 줄이 있다: ${rows(a).map { it::class.simpleName }}", i >= 0)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        rv.scrollToPosition(i)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        val target = pick(rv.findViewHolderForAdapterPosition(i)!!.itemView)
        if (long) target.performLongClick() else target.performClick()
        settle { !opening(a) }
    }

    private fun tapTab(a: TubeActivity, tab: TubeTab) = tapIn(a, which = { it is Row.Tabs }) { v ->
        v.findViewById(
            when (tab) {
                TubeTab.HOME -> R.id.tab_home
                TubeTab.SHORTS -> R.id.tab_shorts
                TubeTab.SUBS -> R.id.tab_subs
                TubeTab.YOU -> R.id.tab_you
            },
        )
    }

    /** 칩 줄에서 [label] 칩을 누른다 */
    private fun tapChip(a: TubeActivity, label: String) = tapIn(a, which = { it is Row.ChipBar }) { v ->
        val row = v.findViewById<ViewGroup>(R.id.chip_row)
        (0 until row.childCount).map(row::getChildAt).first { it.contentDescription.toString().substringBefore(",") == label }
    }

    private fun chip(a: TubeActivity) = rows(a).filterIsInstance<Row.ChipBar>().firstOrNull()?.selected

    /** 한 줄 안에서 누를 수 있는 칸들 (보이는 것, 읽어 주기 글이 있는 것) — 차례대로 */
    private fun tappables(v: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE) return
            if (x.isClickable && x.contentDescription != null) out += x
            if (x is ViewGroup) (0 until x.childCount).forEach { walk(x.getChildAt(it)) }
        }
        walk(v)
        return out
    }

    /** Shorts 모양 (세로 · 30초) — 채널은 셋을 돌아가며 */
    private fun short(n: Int, ch: String = "UCshortsshortsshorts%04d".format(n % 3)) =
        TubeVideo("short%06d".format(n), "Shorts $n", "숏 채널 ${n % 3}", 30, channelId = ch, views = 10, publishedAt = now - 60_000, portrait = true)

    private fun cards(a: TubeActivity) = rows(a).filterIsInstance<Row.VideoCard>().map { it.video.id }

    private fun back(a: TubeActivity) {
        a.onBackPressedDispatcher.onBackPressed()
        settle { !opening(a) }
    }

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
        assertTrue("처음 쓰는 사람의 '전체'는 인기", backend.asked.contains(Feed.Popular(null).key))
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
    fun aCardOnHomePlaysInTheAppAndContinuesAsAYouTubeMix() {
        val a = open()
        assertEquals("유튜브", titles(a).first())
        assertTrue("맨 위 탭", rows(a).any { it is Row.Tabs })
        assertEquals("ALL", chip(a))
        tapCard(a, "musicaaaaa1")
        val s = started(a)
        assertEquals(TubePlayerActivity::class.java.name, s?.component?.className)
        // 유튜브 앱처럼 다음 동영상은 유튜브가 고른 것 (믹스) — 재생 화면이 그 목록을 앱에 알려 '전체'에 섞는다
        assertEquals(TubeLinks.playerUrl(base, music[0], Queue.Mix, inApp = true), s?.playerPage())
        assertEquals("musicaaaaa1", TubeState(context).lastPlayed()?.id)
        assertFalse("본 동영상은 '전체'에서 빠진다", rows(a).filterIsInstance<Row.VideoCard>().any { it.video.id == "musicaaaaa1" })
    }

    @Test
    fun ifTheEngineDiedRecentlyItPlaysInTheWatchBrowserInstead() {
        installBrowser()
        PlayerGuard.of(context).failed()
        val a = open()
        tapCard(a, "popularaaa1")
        val s = started(a)
        assertEquals(Intent.ACTION_VIEW, s?.action)
        assertEquals(TubeLinks.playerUrl(base, popular[0], Queue.Mix), s?.dataString?.replace(Regex("&n=\\d+"), ""))
        assertEquals(context.getString(R.string.tube_engine_browser), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aVideoThatBlocksOtherPlayersOpensOnThePhone() {
        val a = open()
        tapCard(a, "musicaaaaa3")
        assertNull(started(a))
        assertEquals("폰에서도 믹스로", listOf(TubeLinks.watchUrl("musicaaaaa3", mix = true)), phoneOpened)
    }

    @Test
    fun ifThePlayerPageCannotBeReachedItPlaysOnThePhone() {
        WatchPlayer.probe = { false }
        val a = open()
        tapCard(a, "popularaaa2")
        assertNull(started(a))
        assertEquals(listOf(TubeLinks.watchUrl("popularaaa2", mix = true)), phoneOpened)
    }

    // ---------------- 둘러보기 ----------------

    @Test
    fun searchThenChannelThenBackOneStepAtATime() {
        backend.pages[Feed.Search("아이유").key] = {
            Fetched(TubePage(listOf(v("searchaaaa1")), null, channels = listOf(channel), playlists = listOf(TubePlaylist("PL1aaaaaaaaaa", "모음", "멜론", null, 3))), TubeSource.YOUTUBE)
        }
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.LONG).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1"), v("chanaaaaaa2")), null)) }
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
        tapTab(a, TubeTab.YOU)
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
        tapTab(a, TubeTab.YOU)
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
        assertEquals(listOf(TubeLinks.watchUrl("musicaaaaa3", mix = true)), phoneOpened)
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
        tapTab(a, TubeTab.YOU)
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
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.LONG).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1")), null)) }
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
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.LONG).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1")), null)) }
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
        backend.pages[Feed.Popular("10").key] = { Fetched(TubePage((1..12).map { v("music%06d".format(it)) }, null)) }
        val a = open()
        tapChip(a, "음악")
        assertEquals("MUSIC", chip(a))
        // 목록 아래쪽의 동영상을 길게 눌러 메뉴 → 취소 → 그 자리
        tapCard(a, "music000009", long = true)
        tapAction(a, TubeRows.A_CANCEL)
        val where = firstShown(a)
        assertTrue("아래쪽이 보이고 있었다: $where", where.startsWith("card:music00000") && where != "card:music000001")
        // 메뉴 → 재생 → 돌아오면 그 자리 ('음악'은 본 것도 그대로 둔다)
        tapCard(a, "music000009", long = true)
        tapAction(a, TubeRows.A_PLAY)
        assertEquals("메뉴는 닫히고 목록으로", "유튜브", titles(a).first())
        assertEquals("메뉴에서 틀고 돌아오면 보던 자리", where, firstShown(a))
        // 메뉴 → 채널 → 뒤로 = 보던 자리
        tapCard(a, "music000009", long = true)
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals("1theK (원더케이)", titles(a).first())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("채널에서 뒤로 오면 보던 자리", where, firstShown(a))
        assertEquals("칩도 그대로", "MUSIC", chip(a))
    }

    @Test
    fun theChannelButtonOnAVideoOfThisChannelJustClosesTheMenu() {
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.LONG).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1"), v("chanaaaaaa2")), null)) }
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
    fun aFailedHomeCanBeRetriedAndMoreAddsTheNextPage() {
        backend.fail = ApiException("네트워크 연결을 확인하세요")
        val a = open()
        assertTrue(rows(a).any { it is Row.Note && it.text == "네트워크 연결을 확인하세요" })
        backend.fail = null
        tapAction(a, TubeRows.A_RETRY)
        assertEquals("인기 6개", 6, rows(a).filterIsInstance<Row.VideoCard>().size)
        tapAction(a, TubeRows.A_MORE)
        assertTrue(rows(a).filterIsInstance<Row.VideoCard>().any { it.video.id == "trendaaaaa7" })
        assertTrue(backend.asked.contains(Feed.Popular(null).key + "@T2"))
    }

    /**
     * 리뷰에서 찾음: 받기가 끝날 때마다 '새로 정해진 받을 거리'를 받는데, 실패한 것까지 또 받아 받기 → 실패 → 또 받기가 끝없이 돌았다
     * (인터넷이 없으면 배터리, 유튜브 오류면 하루 한도까지) → 실패한 것은 다시 시도·화면을 다시 열 때만
     */
    @Test
    fun aFailingNetworkIsNotAskedOverAndOver() {
        backend.fail = ApiException("네트워크 연결을 확인하세요")
        val a = open()
        settle()
        val first = backend.asked.size
        assertTrue("물은 것이 있다", first > 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        settle()
        assertEquals("가만히 있으면 더 묻지 않는다: ${backend.asked}", first, backend.asked.size)
        assertTrue(rows(a).any { it is Row.Action && it.id == TubeRows.A_RETRY })
    }

    /**
     * 베젤·크라운으로 끝까지 내리면 다음 쪽을 저절로 (1단위로 싼 목록만 — 100단위인 검색 '더 보기'는 누를 때만).
     * 같은 쪽을 두 번 저절로 묻지는 않는다 (실패하면 '더 보기'를 눌러서)
     */
    @Test
    fun scrollingToTheEndLoadsTheNextPageByItselfButNotForSearches() {
        backend.pages[Feed.Popular(null).key] = { token ->
            val n = if (token == null) 0 else 1
            Fetched(TubePage((1..12).map { v("trend%02d%04d".format(n, it)) }, if (token == null) "T2" else null))
        }
        val a = open()
        assertFalse(backend.asked.contains(Feed.Popular(null).key + "@T2"))
        scrollToEnd(a)
        assertTrue("끝까지 내리면 다음 쪽", backend.asked.contains(Feed.Popular(null).key + "@T2"))
        assertEquals(24, rows(a).filterIsInstance<Row.VideoCard>().size)

        backend.pages[Feed.Search("아이유").key] = { token ->
            Fetched(TubePage((1..12).map { v("search%05d".format(it)) }, if (token == null) "S2" else null), TubeSource.YOUTUBE)
        }
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
            idle()   // 가짜 유튜브는 바로 대답한다 (메인 스레드에서)
        }
        settle { !opening(a) }
    }

    // ---------------- 탭 · 칩 ----------------

    /** 맨 위 탭: 탭끼리는 자리만 바꾸고(뒤로 가기에 쌓이지 않음), 뒤로 = 홈, 홈 탭 = 홈 */
    @Test
    fun theTopTabsSwapInPlaceAndBackGoesHome() {
        val a = open()
        assertEquals(1, a.nav.depth)
        tapTab(a, TubeTab.SUBS)
        assertEquals("구독", titles(a).first())
        assertEquals(2, a.nav.depth)
        tapTab(a, TubeTab.YOU)
        assertEquals("나", titles(a).first())
        assertEquals("탭끼리는 자리만 바뀐다", 2, a.nav.depth)
        back(a)
        assertEquals("유튜브", titles(a).first())
        assertFalse(a.isFinishing)
        tapTab(a, TubeTab.YOU)
        tapTab(a, TubeTab.HOME)
        assertEquals("유튜브", titles(a).first())
        assertEquals(1, a.nav.depth)
    }

    /**
     * 홈 칩은 그 칩이 보여 줄 것만 받는다 — 게임 차트는 '게임'을 열 때, 실시간 검색(하루 100번 한도)은 '실시간'을 열 때만.
     * 칩은 뒤로 가기에 쌓이지 않고, '전체'가 아닌 칩에서 뒤로 = '전체' (그다음 뒤로 = 닫기)
     */
    @Test
    fun eachHomeChipAsksOnlyForWhatItShowsAndBackReturnsToAll() {
        backend.pages[Feed.Popular("20").key] = { Fetched(TubePage(listOf(v("gameaaaaaa1").copy(categoryId = "20")), null)) }
        backend.pages[Feed.Live.key] = { Fetched(TubePage(listOf(v("liveaaaaaa1").copy(live = true, durationSec = null)), null)) }
        val a = open()
        assertFalse("게임 차트는 게임 칩을 열 때", backend.asked.contains(Feed.Popular("20").key))
        assertFalse("실시간 검색은 실시간 칩을 열 때만", backend.asked.contains(Feed.Live.key))
        tapChip(a, "게임")
        assertEquals("GAMING", chip(a))
        assertTrue(backend.asked.contains(Feed.Popular("20").key))
        assertTrue(cards(a).contains("gameaaaaaa1"))
        assertFalse(backend.asked.contains(Feed.Live.key))
        assertEquals("칩은 뒤로 가기에 쌓이지 않는다", 1, a.nav.depth)
        tapChip(a, "실시간")
        assertTrue(backend.asked.contains(Feed.Live.key))
        assertEquals(listOf("liveaaaaaa1"), cards(a))
        back(a)
        assertEquals("뒤로 = 먼저 '전체'로", "ALL", chip(a))
        assertFalse(a.isFinishing)
        back(a)
        assertTrue("'전체'에서 뒤로 = 닫기", a.isFinishing)
    }

    /** 오른쪽으로 쓸기(Wear OS 의 뒤로)도 같다: '전체'가 아닌 칩이면 '전체'로 */
    @Test
    fun swipingBackFromAChipReturnsToAll() {
        val a = open()
        tapChip(a, "음악")
        val swipe = a.findViewById<androidx.wear.widget.SwipeDismissFrameLayout>(R.id.swipe)
        assertTrue("칩에서는 쓸어서 '전체'로", swipe.isSwipeable)
        val dismissed = androidx.wear.widget.SwipeDismissFrameLayout::class.java.getDeclaredMethod("performDismissFinishedCallbacks")
        dismissed.isAccessible = true
        dismissed.invoke(swipe)
        settle()
        assertEquals("ALL", chip(a))
        assertFalse(a.isFinishing)
        assertFalse("'전체'에서는 시스템이 닫는다", swipe.isSwipeable)
    }

    @Test
    fun channelChipsSwapInPlace() {
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.LONG).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1")), null)) }
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.SHORTS).key] = { Fetched(TubePage((1..3).map { short(it, channel.id) }, null)) }
        val a = open()
        tapCard(a, "musicaaaaa1", long = true)
        tapAction(a, TubeRows.A_CHANNEL)
        assertEquals(2, a.nav.depth)
        assertFalse("Shorts 는 Shorts 칩을 열 때", backend.asked.contains(Feed.ChannelUploads(channel.id, Uploads.SHORTS).key))
        tapChip(a, "Shorts")
        assertEquals("SHORTS", chip(a))
        assertTrue(backend.asked.contains(Feed.ChannelUploads(channel.id, Uploads.SHORTS).key))
        assertEquals("세로 카드 두 칸씩", 2, rows(a).count { it is Row.ShortsPair })
        assertEquals("칩은 쌓이지 않는다", 2, a.nav.depth)
        back(a)
        assertEquals("유튜브", titles(a).first())
    }

    /** 구독: 채널 사진 줄 — 누르면 그 채널, 끝의 '전체' = 구독 채널 모두 */
    @Test
    fun theAvatarStripOpensAChannelOrAllSubscriptions() {
        signIn()
        val other = TubeChannel("UCotherotherotherother12", "다른 채널", null)
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.SubsChannels.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel, other))) }
        backend.pages[Feed.SubsFeed.key] = { Fetched(TubePage(listOf(v("feedaaaaaa1")), null)) }
        backend.pages[Feed.ChannelInfo(other.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(other))) }
        val a = open()
        tapTab(a, TubeTab.SUBS)
        assertTrue(rows(a).any { it is Row.AvatarStrip })
        tapIn(a, which = { it is Row.AvatarStrip }) { v -> tappables(v)[1] }
        assertEquals("다른 채널", titles(a).first())
        back(a)
        assertEquals("구독", titles(a).first())
        tapIn(a, which = { it is Row.AvatarStrip }) { v -> tappables(v).last() }
        assertEquals("구독 채널", titles(a).first())
        assertEquals(listOf(channel.id, other.id), rows(a).filterIsInstance<Row.ChannelItem>().map { it.channel.id })
    }

    // ---------------- 알고리즘 ----------------

    /** 재생 화면이 알려 준 유튜브 믹스 추천(받은 편지)이 '전체' 맨 앞에 섞인다 — 받은 편지는 읽으면 지운다 */
    @Test
    fun mixRecommendationsFromThePlayerComeFirstInAll() {
        val recs = listOf(v("recaaaaaaa1"), v("recaaaaaaa2"))
        backend.pages[Feed.Recs(recs.map { it.id }).key] = { Fetched(TubePage(recs, null)) }
        TubeInbox.of(context).post("kakaowatch://tube/recs?seed=musicaaaaa1&ids=recaaaaaaa1,recaaaaaaa2")
        val a = open()
        settle { cards(a).firstOrNull() == "recaaaaaaa1" }
        assertEquals(listOf("recaaaaaaa1", "recaaaaaaa2"), cards(a).take(2))
        assertTrue("인기도 섞인다", cards(a).contains("popularaaa1"))
        assertTrue("받은 편지는 읽고 지운다", TubeInbox.of(context).drain().isEmpty())
        // '나'에 추천에 쓰는 것이 보인다 · 기록 지우기 = 믹스 추천도 지운다
        tapTab(a, TubeTab.YOU)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_recs_info, 0, 2, 0) })
        tapAction(a, TubeRows.A_CLEAR)
        assertTrue(TubeState(context).harvests(System.currentTimeMillis()).isEmpty())
        tapTab(a, TubeTab.HOME)
        assertFalse("지운 추천은 '전체'에서도 빠진다", cards(a).contains("recaaaaaaa1"))
    }

    /** 관심 없음 · 채널 추천 안 함: 바로 빠지고, 다시 열어도 (30일 기억) */
    @Test
    fun notInterestedTakesTheCardAwayAndIsRemembered() {
        val other = TubeVideo("otheraaaaa1", "다른 채널 동영상", "다른 채널", 200, channelId = "UCotherotherotherother12", views = 10, publishedAt = now - 1_000)
        backend.pages[Feed.Popular(null).key] = { Fetched(TubePage(music + popular + other, null)) }
        val a = open()
        tapCard(a, "popularaaa1", long = true)
        tapAction(a, TubeRows.A_NOT_INTERESTED)
        assertEquals("유튜브", titles(a).first())
        assertEquals(context.getString(R.string.tube_dismissed), ShadowToast.getTextOfLatestToast())
        assertFalse(cards(a).contains("popularaaa1"))
        assertTrue(TubeState(context).dismissedVideos(System.currentTimeMillis()).contains("popularaaa1"))
        tapCard(a, "musicaaaaa1", long = true)
        tapAction(a, TubeRows.A_NO_CHANNEL)
        assertEquals(context.getString(R.string.tube_channel_dismissed), ShadowToast.getTextOfLatestToast())
        assertEquals("그 채널의 것은 모두 빠진다", listOf("otheraaaaa1"), cards(a))
        a.finish()
        val again = open()
        assertEquals("다시 열어도", listOf("otheraaaaa1"), cards(again))
    }

    /** 처리방침이 바뀌었으면(판 2 — 워치 안 기록으로 추천) 예전에 동의했어도 다시 묻는다 */
    @Test
    fun agreeingToTheOldTermsIsNotEnough() {
        context.getSharedPreferences("tube", Context.MODE_PRIVATE).edit().putInt("terms_agreed", 1).commit()
        val a = open()
        assertEquals("시작하기 전에", titles(a).first())
        assertTrue("동의 전엔 유튜브에 묻지 않는다", backend.asked.isEmpty())
        tapAction(a, TubeRows.A_AGREE)
        assertEquals("유튜브", titles(a).first())
    }

    // ---------------- Shorts ----------------

    /** Shorts 탭: 준비되는 대로 Shorts 재생 화면을 한 번 바로 연다. 돌아오면 격자 — 세로 카드를 누르면 그 자리부터, 탭을 다시 누르면 처음부터 */
    @Test
    fun theShortsTabOpensTheShortsPlayerOnceWhenReady() {
        backend.pages[Feed.ShortsSearch.key] = { Fetched(TubePage((1..12).map { short(it) }, null)) }
        val controller = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = controller.get()
        settle { !opening(a) }
        tapTab(a, TubeTab.SHORTS)
        val s = started(a)
        assertEquals(TubePlayerActivity::class.java.name, s?.component?.className)
        val url = s?.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty()
        assertTrue(url, url.startsWith(base + "shorts.html#ids=short000001,"))
        assertTrue(url, url.contains("&i=0&") && url.endsWith("&app=1"))
        // 재생 화면에서 돌아오면 격자 (다시 열지 않는다)
        controller.pause().stop()
        controller.start().resume()
        settle { !opening(a) }
        assertNull("돌아오면 다시 열지 않는다", started(a))
        assertEquals("Shorts", titles(a).first())
        assertEquals(6, rows(a).count { it is Row.ShortsPair })
        tapIn(a, which = { it is Row.ShortsPair && it.index == 2 }) { v -> tappables(v)[1] }
        val url2 = started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty()
        assertTrue("누른 세로 카드부터: $url2", url2.contains("&i=3&"))
        controller.pause().stop()
        controller.start().resume()
        settle { !opening(a) }
        tapTab(a, TubeTab.SHORTS)
        assertTrue("탭을 다시 누르면 처음부터", started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty().contains("&i=0&"))
    }

    /** 홈·다른 앱으로 떠났으면 Shorts 가 다 받아져도 저절로 열지 않는다 (돌아와도) */
    @Test
    fun leavingBeforeTheShortsAreReadyMeansTheyDoNotOpenByThemselves() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[Feed.ShortsSearch.key] = hold
        backend.pages[Feed.ShortsSearch.key] = { Fetched(TubePage((1..12).map { short(it) }, null)) }
        val controller = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = controller.get()
        settle { !opening(a) }
        tapTab(a, TubeTab.SHORTS)
        assertNull("받는 중", started(a))
        controller.userLeaving().pause().stop()
        hold.complete(Unit)
        idle()
        assertNull(started(a))
        controller.start().resume()
        settle { !opening(a) }
        assertNull("돌아와도 저절로 열지 않는다", started(a))
        assertTrue(rows(a).any { it is Row.ShortsPair })
    }

    /** 손목을 내린 사이 다 받아졌으면 다시 볼 때 연다 (1분 안) — 보일 때만 재생 */
    @Test
    fun shortsReadyWhileTheScreenWasOffOpenWhenYouLookAgain() {
        val hold = CompletableDeferred<Unit>()
        backend.holds[Feed.ShortsSearch.key] = hold
        backend.pages[Feed.ShortsSearch.key] = { Fetched(TubePage((1..12).map { short(it) }, null)) }
        val controller = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = controller.get()
        settle { !opening(a) }
        tapTab(a, TubeTab.SHORTS)
        power.setIsInteractive(false)
        controller.pause().stop()
        hold.complete(Unit)
        idle()
        assertNull("화면이 꺼진 동안엔 열지 않는다", started(a))
        power.setIsInteractive(true)
        controller.start().resume()
        settle { shadowOf(a).peekNextStartedActivity() != null }
        assertTrue(started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty().contains("shorts.html#ids=short000001"))
    }

    /**
     * 무작위 여정에서 찾음: 맨 앞 Shorts 가 다른 곳 재생이 막힌 것이면 'Shorts 보기'·바로 열기가 폰으로 갔다 (저절로 폰이 열림) →
     * 워치에서 틀 수 있는 첫 Shorts 부터. 하나도 없으면 바로 열지 않고, 'Shorts 보기'를 누르면 폰으로
     */
    @Test
    fun theShortsPlayerStartsAtTheFirstShortTheWatchCanPlay() {
        val blocked = short(1).copy(embeddable = false)
        backend.pages[Feed.ShortsSearch.key] = { Fetched(TubePage(listOf(blocked) + (2..5).map { short(it) }, null)) }
        val a = open()
        tapTab(a, TubeTab.SHORTS)
        val url = started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty()
        assertTrue("저절로 열 때도 틀 수 있는 것부터: $url", url.contains("#ids=short000002,") && url.contains("&i=0&"))
        assertFalse("막힌 것은 목록에서 뺀다", url.contains("short000001"))
        assertTrue("폰은 열지 않는다", phoneOpened.isEmpty())
        tapAction(a, TubeRows.A_SHORTS_PLAY)
        assertTrue(started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty().contains("#ids=short000002,"))
        assertTrue(phoneOpened.isEmpty())
    }

    @Test
    fun whenNoShortCanPlayOnTheWatchNothingOpensByItself() {
        backend.pages[Feed.ShortsSearch.key] = { Fetched(TubePage((1..3).map { short(it).copy(embeddable = false) }, null)) }
        val a = open()
        tapTab(a, TubeTab.SHORTS)
        assertNull("저절로 열지 않는다", started(a))
        assertTrue("폰도 저절로 열지 않는다", phoneOpened.isEmpty())
        tapAction(a, TubeRows.A_SHORTS_PLAY)
        assertEquals("누르면 폰의 Shorts 로", listOf(TubeLinks.phoneShortsUrl("short000001")), phoneOpened)
    }

    /** 재생 화면이 알려 준 본 Shorts 는 Shorts 목록에서 뒤로 간다 */
    @Test
    fun seenShortsGoToTheBack() {
        backend.pages[Feed.ShortsSearch.key] = { Fetched(TubePage((1..4).map { short(it) }, null)) }
        TubeInbox.of(context).post("kakaowatch://tube/seen?v=short000001")
        val controller = Robolectric.buildActivity(TubeActivity::class.java).setup()
        val a = controller.get()
        settle { !opening(a) }
        tapTab(a, TubeTab.SHORTS)
        val ids = Uri.parse(started(a)?.getStringExtra(TubePlayerActivity.EXTRA_URL)?.replace("#", "?")).getQueryParameter("ids").orEmpty().split(',')
        assertEquals("본 것은 맨 뒤", "short000001", ids.last())
        assertEquals(4, ids.size)
    }

    // ---------------- 검색 화면 ----------------

    /** 검색 화면 (유튜브 앱처럼): 열면 곧바로 말·키보드, 취소하면 최근 검색어 — 누르면 그 검색(검색 화면 자리에), 검색 기록 지우기 */
    @Test
    fun theSearchPageShowsRecentSearchesAndCanClearThem() {
        TubeState(context).addSearch("뉴진스")
        TubeState(context).addSearch("아이유")
        backend.pages[Feed.Search("뉴진스").key] = { Fetched(TubePage(listOf(v("searchaaaa1")), null), TubeSource.YOUTUBE) }
        val a = open()
        tapAction(a, TubeRows.A_SEARCH)
        assertEquals("검색", titles(a).first())
        val input = shadowOf(a).nextStartedActivityForResult
        assertNotNull("검색 화면을 열면 곧바로 말·키보드 입력", input)
        shadowOf(a).receiveResult(input.intent, Activity.RESULT_CANCELED, Intent())
        settle()
        assertEquals("취소하면 검색 화면 그대로", "검색", titles(a).first())
        val recent = rows(a).filterIsInstance<Row.Action>().filter { it.id.startsWith(TubeRows.A_QUERY) }.map { it.text }
        assertEquals(listOf("아이유", "뉴진스"), recent)
        tapAction(a, TubeRows.A_QUERY + "뉴진스")
        assertEquals("‘뉴진스’", titles(a).first())
        assertEquals("검색 화면 자리가 결과로", 2, a.nav.depth)
        back(a)
        assertEquals("유튜브", titles(a).first())
        tapAction(a, TubeRows.A_SEARCH)
        shadowOf(a).nextStartedActivityForResult?.let { shadowOf(a).receiveResult(it.intent, Activity.RESULT_CANCELED, Intent()) }
        settle()
        tapAction(a, TubeRows.A_CLEAR_SEARCHES)
        assertTrue(TubeState(context).searches.isEmpty())
        assertFalse(rows(a).any { it is Row.Action && it.id.startsWith(TubeRows.A_QUERY) })
    }

    // ---------------- 로그인 ----------------

    @Test
    fun signingInShowsACodeAndFinishesByItselfWhenAllowedOnThePhone() {
        backend.pages[Feed.SubsFeed.key] = { Fetched(TubePage(listOf(v("feedaaaaaa1")), null)) }
        backend.pages[Feed.SubsChannels.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.Account.key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        val a = open()
        tapTab(a, TubeTab.SUBS)
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
        tapTab(a, TubeTab.YOU)
        tapAction(a, TubeRows.A_ACCOUNT)
        assertEquals("1theK (원더케이)", titles(a).first())
        tapAction(a, TubeRows.A_LOGOUT)
        settle { posts.any { it.first.endsWith("/revoke") } }
        assertNull(store.t)
        assertEquals("RT", posts.last { it.first.endsWith("/revoke") }.second["token"])
        assertEquals("나", titles(a).first())
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
        tapTab(a, TubeTab.YOU)
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
        if (a.nav.top != Page.Library) tapTab(a, TubeTab.YOU)
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
        tapTab(a, TubeTab.SUBS)
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
        tapTab(a, TubeTab.YOU)
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        tokenReply = Http.Response(503, "")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle()
        assertTrue("코드를 그대로 보여 주며 기다림", rows(a).any { it is Row.Code })
        tokenReply = Http.Response(200, """{"access_token":"AT","expires_in":3599,"refresh_token":"RT","scope":"https://www.googleapis.com/auth/youtube.readonly"}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        settle { titles(a).firstOrNull() != "로그인" }
        assertEquals("나", titles(a).first())
        assertNotNull(store.t)
    }

    /** 화면이 꺼지기 직전 보낸 물음으로 이미 로그인됐는데 다음 물음이 '거절'로 오면(같은 코드) 거절이 아니라 로그인된 것 */
    @Test
    fun aLateAnswerAfterSigningInIsNotShownAsDenied() {
        val a = open()
        tapTab(a, TubeTab.YOU)
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        tokenReply = Http.Response(403, """{"error":"access_denied"}""")
        // 물음이 오가는 사이 앞서 보낸 물음이 끝나 로그인됨
        onTokenAsked = { store.t = Tokens("AT", "RT", now + 3_600_000) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle { titles(a).firstOrNull() != "로그인" }
        assertFalse(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_login_denied) })
        assertEquals("나", titles(a).first())
    }

    @Test
    fun ifSignInFinishedWhileTheScreenWasOffItSaysSoInsteadOfCodeExpired() {
        val a = open()
        tapTab(a, TubeTab.YOU)
        tapAction(a, TubeRows.A_LOGIN)
        settle { rows(a).any { it is Row.Code } }
        // 화면이 꺼지기 직전 보낸 물음이 끝나 로그인됨 → 그 코드는 이미 쓰였다 (다시 물으면 invalid_grant)
        store.t = Tokens("AT", "RT", now + 3_600_000)
        tokenReply = Http.Response(400, """{"error":"invalid_grant"}""")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        settle { titles(a).firstOrNull() != "로그인" }
        assertEquals("로그인 화면은 닫히고 보던 화면으로", "나", titles(a).first())
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

    /**
     * 재생 화면의 채널 이름을 누름 → 그 동영상의 채널 화면. 본 동영상이면 기록의 채널로 바로, 아니면 찾아서(1단위).
     * 못 찾으면 알리고 그대로, 다른 꼴의 링크는 따르지 않는다
     */
    @Test
    fun theChannelNameInThePlayerOpensThatChannel() {
        backend.pages[Feed.VideoChannel("zzzaaaaaaa1").key] = { Fetched(TubePage(emptyList(), null, channels = listOf(TubeChannel(channel.id, channel.title, null)))) }
        backend.pages[Feed.ChannelInfo(channel.id).key] = { Fetched(TubePage(emptyList(), null, channels = listOf(channel))) }
        backend.pages[Feed.ChannelUploads(channel.id, Uploads.LONG).key] = { Fetched(TubePage(listOf(v("chanaaaaaa1")), null)) }
        val controller = Robolectric.buildActivity(TubeActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/channel?v=zzzaaaaaaa1"))).setup()
        val a = controller.get()
        settle { titles(a).firstOrNull() == channel.title }
        assertEquals(channel.title, titles(a).first())
        assertTrue(cards(a).contains("chanaaaaaa1"))
        back(a)
        assertEquals("유튜브", titles(a).first())

        // 본 동영상이면 찾지 않고 바로
        TubeState(context).addPlayed(v("seenaaaaaa1"))
        backend.asked.clear()
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/channel?v=seenaaaaaa1")))
        settle { !opening(a) }
        assertEquals(channel.title, titles(a).first())
        assertFalse(backend.asked.any { it.startsWith("vchannel/") })
        back(a)

        // 못 찾으면 알리고 그대로
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/channel?v=nonenonenon")))
        settle { !opening(a) }
        assertEquals("유튜브", titles(a).first())
        assertEquals(context.getString(R.string.tube_channel_not_found), ShadowToast.getTextOfLatestToast())

        // 다른 곳·다른 꼴의 링크
        backend.asked.clear()
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://evil/channel?v=zzzaaaaaaa1")))
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube:99/channel?v=zzzaaaaaaa1")))
        settle { !opening(a) }
        assertEquals("유튜브", titles(a).first())
        assertFalse(backend.asked.any { it.startsWith("vchannel/") })
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
