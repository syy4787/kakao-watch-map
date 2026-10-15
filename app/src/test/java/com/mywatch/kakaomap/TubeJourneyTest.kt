package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.HomeChip
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.SignInNeededException
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
import com.mywatch.kakaomap.ui.tube.TubeNav
import com.mywatch.kakaomap.ui.tube.TubeTab
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.random.Random

/**
 * 아무렇게나 눌러 보기 (여러 사람이 마구 쓰듯이, 씨앗마다 같은 순서): 어떤 순서로 눌러도
 *  · 멈추지 않는다 — 늘 목록이 보이고('여는 중…'에 갇히지 않음), 보이는 화면이 지금 화면과 맞다 (예전 화면이 남지 않음)
 *  · 뒤로는 늘 한 화면씩, 바로 앞에 보던 화면으로 — 홈에서 '전체'가 아닌 칩이면 먼저 '전체'로, 홈 '전체'에서 뒤로 = 닫힘.
 *    맨 위 탭·칩은 뒤로 가기에 쌓이지 않는다 (탭끼리는 자리만 바꾸고, 칩은 그 화면 자리만)
 *  · 로그인하지 않았으면 내 데이터(좋아요·구독·내 재생목록·내 채널)가 어디에도 보이지 않는다 (로그아웃 직후도)
 *  · 동영상을 누르면 그 동영상부터: 홈·검색·구독·채널·기록은 유튜브 믹스로, 재생목록·좋아요는 목록 그대로 (못 트는 것은 폰으로)
 *    — 그리고 '시청 기록' 맨 앞에 들어간다. Shorts 세로 카드는 Shorts 재생 화면이 그 자리부터 (못 트는 것은 폰의 Shorts 로)
 *  · 홈에는 관심 없음·채널 추천 안 함이 없고, '전체'에는 이미 본 동영상과 Shorts 카드가 없다 (Shorts 는 선반에)
 * 재생 화면은 가끔 유튜브 믹스 추천·본 Shorts 를 알려 준다(받은 편지 — 돌아오면 읽는다). 네트워크는 가끔 실패한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)
class TubeJourneyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val now = System.currentTimeMillis()

    // ---------------- 가짜 유튜브 ----------------

    private val chA = TubeChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "채널가", "https://yt3.ggpht.com/a=s240", 1_200_000, 300, "UUaaaaaaaaaaaaaaaaaaaaaa")
    private val chB = TubeChannel("UCbbbbbbbbbbbbbbbbbbbbbb", "채널나", null, null, 12, "UUbbbbbbbbbbbbbbbbbbbbbb")
    private val chC = TubeChannel("UCcccccccccccccccccccccc", "채널다", null, 5_000, null, "UUcccccccccccccccccccccc")
    /** 믹스 추천으로만 만나는 채널 ('새로운 맞춤 동영상') */
    private val chD = TubeChannel("UCdddddddddddddddddddddd", "채널라", null)
    private val me = TubeChannel("UCmmmmmmmmmmmmmmmmmmmmmm", "내 채널", "https://yt3.ggpht.com/m=s240")
    private val channels = listOf(chA, chB, chC)
    private val allChannels = channels + chD

    private var serial = 0
    /** 동영상 번호(11자): 앞 3글자가 어디서 온 것인지 — sub·lik·myp 는 내 계정 데이터 */
    private fun v(kind: String, ch: TubeChannel, embeddable: Boolean = true, live: Boolean = false, cat: String? = null, short: Boolean = false): TubeVideo {
        val id = kind + "%08d".format(++serial)
        return TubeVideo(
            id, "제목 $id", ch.title, if (live) null else if (short) 20 + serial % 40 else 200 + serial, live = live, channelId = ch.id,
            views = 1_000L * serial, publishedAt = now - 3_600_000L * serial, embeddable = embeddable, categoryId = cat, portrait = if (live) null else short,
        )
    }

    private fun page(kind: String, n: Int, blockedEvery: Int = 4, cat: String? = null, chs: List<TubeChannel> = channels) =
        List(n) { i -> v(kind, chs[i % chs.size], embeddable = (i + 1) % blockedEvery != 0, live = i == 2, cat = cat) }

    private fun shorts(kind: String, n: Int, chs: List<TubeChannel> = channels, blockedEvery: Int = 0, cat: String? = null) =
        List(n) { i -> v(kind, chs[i % chs.size], embeddable = blockedEvery == 0 || (i + 1) % blockedEvery != 0, short = true, cat = cat) }

    private val musicP1 = page("pub", 6, cat = "10") + shorts("pub", 2, cat = "10")
    private val musicP2 = page("pub", 4, cat = "10")
    private val gaming = page("pub", 4, cat = "20")
    private val news = page("pub", 3, cat = "25")
    private val trendP1 = page("pub", 5, cat = "24") + musicP1.take(2) + gaming.take(1)
    private val trendP2 = page("pub", 3, cat = "10")
    private val searchP1 = page("pub", 5) + shorts("pub", 3)
    private val searchP2 = page("pub", 3)
    private val chanVideos = allChannels.associate { it.id to (page("pub", 4, chs = listOf(it)) to page("pub", 2, chs = listOf(it))) }
    private val chanShorts = allChannels.associate { it.id to shorts("sho", 5, chs = listOf(it), blockedEvery = 4) }
    private val chanLive = allChannels.associate { it.id to listOf(v("liv", it, live = true)) }
    private val recVideos = (page("rec", 8, chs = allChannels, cat = "10") + shorts("rec", 3, chs = allChannels)).associateBy { it.id }
    private val searchShorts = shorts("shs", 8, blockedEvery = 5)
    private val liveNow = channels.map { v("liv", it, live = true) }
    private val plA = TubePlaylist("PLaaaaaaaaaaaaaaaaaa", "모음가", chA.title, null, 5)
    private val plB = TubePlaylist("PLbbbbbbbbbbbbbbbbbb", "모음나", chB.title, null, 3)
    private val plC = TubePlaylist("PLcccccccccccccccccc", "모음다", chC.title, null, 2)
    private val listVideos = listOf(plA, plB, plC).associate { it.id to page("pub", 5) }
    private val subsFeed = page("sub", 3) + shorts("sub", 2)
    private val subsShorts = shorts("sub", 4)
    private val subsLive = channels.map { v("sub", it, live = true) }
    private val liked = page("lik", 4)
    private val myPl = TubePlaylist("PLmmmmmmmmmmmmmmmmmm", "내 모음", me.title, null, 4)
    private val myPlVideos = page("myp", 4)

    /** 로그인해야만 보이는 것들 */
    private val mineVideoIds by lazy { (subsFeed + subsShorts + subsLive + liked + myPlVideos).map { it.id }.toSet() }

    /** 번호로 동영상 (가짜 유튜브가 아는 것 모두) */
    private val everything by lazy {
        (musicP1 + musicP2 + gaming + news + trendP1 + trendP2 + searchP1 + searchP2 + chanVideos.values.flatMap { it.first + it.second } +
            chanShorts.values.flatten() + chanLive.values.flatten() + recVideos.values + searchShorts + liveNow + listVideos.values.flatten() +
            subsFeed + subsShorts + subsLive + liked + myPlVideos).associateBy { it.id }
    }

    private var random = Random(0)
    private var failRate = 0.1

    private inner class FakeBackend : TubeBackend {
        override suspend fun fetch(feed: Feed, token: String?, source: TubeSource?): Fetched {
            if (feed.mine && store.t == null) throw SignInNeededException()
            if (random.nextDouble() < failRate) throw ApiException("네트워크 연결을 확인하세요")
            return when (feed) {
                is Feed.Popular -> when (feed.category) {
                    "10" -> if (token == null) Fetched(TubePage(musicP1, "M2")) else Fetched(TubePage(musicP2, null))
                    "20" -> Fetched(TubePage(gaming, null))
                    "25" -> Fetched(TubePage(news, null))
                    else -> if (token == null) Fetched(TubePage(trendP1, "T2")) else Fetched(TubePage(trendP2, null))
                }
                is Feed.Search -> if (token == null) {
                    Fetched(TubePage(searchP1, "S2", channels = listOf(chA), playlists = listOf(plA)), TubeSource.YOUTUBE)
                } else Fetched(TubePage(searchP2, null), TubeSource.YOUTUBE)
                Feed.SubsFeed -> Fetched(TubePage(subsFeed, null))
                Feed.SubsChannels -> if (token == null) Fetched(TubePage(emptyList(), "SC2", channels = listOf(chA, chB))) else Fetched(TubePage(emptyList(), null, channels = listOf(chC)))
                is Feed.SubsShorts -> Fetched(TubePage(subsShorts.filter { it.channelId in feed.channels }, null))
                is Feed.SubsLive -> Fetched(TubePage(subsLive.filter { it.channelId in feed.channels }, null))
                Feed.Liked -> Fetched(TubePage(liked, null))
                Feed.MyPlaylists -> Fetched(TubePage(emptyList(), null, playlists = listOf(myPl)))
                Feed.Account -> Fetched(TubePage(emptyList(), null, channels = listOf(me)))
                is Feed.ChannelInfo -> Fetched(TubePage(emptyList(), null, channels = listOfNotNull(allChannels.firstOrNull { it.id == feed.id })))
                is Feed.ChannelUploads -> when (feed.kind) {
                    Uploads.SHORTS -> Fetched(TubePage(chanShorts[feed.id].orEmpty(), null))
                    Uploads.LIVE -> Fetched(TubePage(chanLive[feed.id].orEmpty(), null))
                    else -> chanVideos.getValue(feed.id).let { (p1, p2) -> if (token == null) Fetched(TubePage(p1, "C2")) else Fetched(TubePage(p2, null)) }
                }
                is Feed.ChannelPlaylists -> if (token == null) Fetched(TubePage(emptyList(), "P2", playlists = listOf(plA, plB))) else Fetched(TubePage(emptyList(), null, playlists = listOf(plC)))
                is Feed.PlaylistVideos -> Fetched(TubePage(if (feed.id == myPl.id) myPlVideos else listVideos.getValue(feed.id), null))
                is Feed.Recs -> Fetched(TubePage(feed.ids.mapNotNull { recVideos[it] }, null))
                is Feed.Watched -> Fetched(TubePage(feed.channels.flatMap { id -> chanVideos[id]?.first.orEmpty().filterNot { it.live }.take(3) }, null))
                is Feed.ShortsPool -> Fetched(TubePage(feed.channels.flatMap { chanShorts[it].orEmpty().take(4) }, null))
                Feed.ShortsSearch -> Fetched(TubePage(searchShorts, null))
                Feed.Live -> Fetched(TubePage(liveNow, null))
                is Feed.VideoChannel -> Fetched(
                    TubePage(emptyList(), null, channels = listOfNotNull(everything[feed.video]?.channelId?.let { id -> allChannels.firstOrNull { it.id == id } })),
                )
            }
        }
    }

    private class MemoryStore : TokenStore {
        var t: Tokens? = null
        override fun load() = t
        override fun save(t: Tokens) { this.t = t }
        override fun clear() { t = null }
    }

    private val store = MemoryStore()
    private var tokenReply: Http.Response = Http.Response(428, """{"error":"authorization_pending"}""")
    private val phoneOpened = mutableListOf<String>()

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        WatchPlayer.base = base
        WatchPlayer.probe = { true }
        WatchPlayer.okUntil = 0L
        WatchPlayer.failUntil = 0L
        TubeActivity.backendOverride = FakeBackend()
        TubeActivity.engineCheck = { true }
        TubeAccount.override = TubeAuth("123-abc.apps.googleusercontent.com", "secret", { url, _ ->
            when {
                url.endsWith("/device/code") -> Http.Response(200, """{"device_code":"DC","user_code":"GQVQ-JKEC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""")
                url.endsWith("/token") -> tokenReply
                else -> Http.Response(200, "")
            }
        }, store)
        PhoneOpener.override = { url -> phoneOpened += url; PhoneOpener.Result.OPENED }
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

    private fun settle(until: () -> Boolean = { false }) {
        val deadline = System.currentTimeMillis() + 3_000
        idle()
        while (System.currentTimeMillis() < deadline && !until()) {
            Thread.sleep(5)
            idle()
        }
    }

    private fun opening(a: TubeActivity) = a.findViewById<View>(R.id.status).visibility == View.VISIBLE
    private fun list(a: TubeActivity) = a.findViewById<RecyclerView>(R.id.list)
    private fun rows(a: TubeActivity): List<Row> = (list(a).adapter as RowAdapter).rows
    private fun firstTitle(a: TubeActivity) = rows(a).filterIsInstance<Row.Header>().firstOrNull()?.title

    private fun holderView(a: TubeActivity, i: Int): View {
        val rv = list(a)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.scrollToPosition(i)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        return rv.findViewHolderForAdapterPosition(i)!!.itemView
    }

    /** 한 줄 안에서 누를 수 있는 칸들 (탭·칩·채널 사진·세로 카드) — 차례대로 */
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

    private fun signedIn() = store.t != null

    /** 지금 화면이면 보여야 할 첫 제목 (모르면 null) */
    private fun expectedTitle(p: Page): String? = when (p) {
        is Page.Home -> "유튜브"
        Page.Shorts -> "Shorts"
        Page.Find -> "검색"
        is Page.Search -> "‘${p.query}’"
        is Page.Subscriptions -> "구독"
        Page.SubsChannels -> "구독 채널"
        Page.Library -> if (signedIn()) null else "나"   // 로그인했으면 내 채널 이름 (받기 전엔 '나')
        Page.History -> "시청 기록"
        Page.Liked -> "좋아요 표시한 동영상"
        Page.MyPlaylists -> "내 재생목록"
        is Page.Channel -> p.title
        is Page.Playlist -> p.title
        is Page.Menu -> p.video.title
        Page.Login -> "로그인"
        Page.Account -> null   // 내 채널 이름 (받기 전엔 '내 계정')
        is Page.Handoff -> p.handoff.video.title
        Page.Terms -> "약관·개인정보"
    }

    /** 뒤로 가기를 이 화면 안에서 받아야 하는지: 홈이 아니거나, 홈의 '전체'가 아닌 칩 */
    private fun backInside(nav: TubeNav) = nav.depth > 1 || (nav.top as? Page.Home)?.chip.let { it != null && it != HomeChip.ALL }

    // ---------------- 한 걸음 ----------------

    private sealed class Step {
        /** 줄 하나를 통째로 (동영상 카드·채널·재생목록·버튼) */
        data class Tap(val row: Row) : Step()
        /** 줄 안의 칸 하나 (탭·칩·채널 사진·세로 카드) */
        data class Part(val row: Row, val part: Int) : Step()
        /** 길게 — 동영상 카드([part] -1) 또는 세로 카드 */
        data class Long(val row: Row, val part: Int = -1) : Step()
        data object Back : Step()
    }

    /** 줄 안의 칸 수 (칸으로 누르는 줄이 아니면 0) */
    private fun parts(r: Row): Int = when (r) {
        is Row.Tabs -> TubeTab.entries.size
        is Row.ChipBar -> r.chips.size
        is Row.AvatarStrip -> r.channels.size + if (r.more) 1 else 0
        is Row.ShortsShelf -> r.videos.size
        is Row.ShortsPair -> if (r.index + 1 < r.list.size) 2 else 1
        else -> 0
    }

    /** 세로 카드 칸이 무엇인지 */
    private fun shortAt(r: Row, part: Int): Pair<List<TubeVideo>, Int>? = when (r) {
        is Row.ShortsShelf -> r.videos to part
        is Row.ShortsPair -> r.list to r.index + part
        else -> null
    }

    /** 골고루 눌러 봤는지 셀 이름 */
    private fun partKey(r: Row, part: Int): String = when (r) {
        is Row.Tabs -> "tab:" + TubeTab.entries[part].name
        is Row.ChipBar -> "chip:" + r.bar + ":" + r.chips[part].first
        is Row.AvatarStrip -> if (part < r.channels.size) "avatar" else "action:" + TubeRows.A_SUBS_CHANNELS
        else -> "short"
    }

    private fun pick(a: TubeActivity): Step {
        val shown = rows(a)
        val whole = shown.filter { it is Row.VideoCard || it is Row.ChannelItem || it is Row.PlaylistItem || it is Row.Action }
        val composite = shown.filter { parts(it) > 0 }
        val shortRows = shown.filter { it is Row.ShortsShelf || it is Row.ShortsPair }
        val cards = shown.filterIsInstance<Row.VideoCard>()
        // 아직 안 눌러 본 버튼·탭·칩이 있으면 가끔은 그것부터 (골고루 가 보게)
        val fresh = shown.flatMap { r ->
            when (r) {
                is Row.Action -> if ("action:" + r.id !in visited) listOf(Step.Tap(r)) else emptyList()
                is Row.Tabs, is Row.ChipBar, is Row.AvatarStrip -> (0 until parts(r)).filter { partKey(r, it) !in visited }.map { Step.Part(r, it) }
                else -> emptyList()
            }
        }
        val x = random.nextDouble()
        return when {
            x < 0.18 -> Step.Back
            x < 0.27 && cards.isNotEmpty() -> Step.Long(cards.random(random))
            x < 0.31 && shortRows.isNotEmpty() -> shortRows.random(random).let { Step.Long(it, random.nextInt(parts(it))) }
            x < 0.50 && fresh.isNotEmpty() -> fresh.random(random)
            x < 0.66 && composite.isNotEmpty() -> composite.random(random).let { Step.Part(it, random.nextInt(parts(it))) }
            whole.isEmpty() -> Step.Back
            else -> Step.Tap(whole.random(random))
        }
    }

    private fun describe(s: Step): String = when (s) {
        is Step.Tap -> "누름 " + when (val r = s.row) {
            is Row.VideoCard -> "동영상 ${r.video.id}"
            is Row.ChannelItem -> "채널 ${r.channel.title}"
            is Row.PlaylistItem -> "재생목록 ${r.playlist.title}"
            is Row.Action -> "버튼 ${r.id}"
            else -> r.toString()
        }
        is Step.Part -> "칸 " + partKey(s.row, s.part) + (shortAt(s.row, s.part)?.let { (l, i) -> " " + l[i].id } ?: "")
        is Step.Long -> "길게 " + ((s.row as? Row.VideoCard)?.video?.id ?: shortAt(s.row, s.part)?.let { (l, i) -> l[i].id })
        Step.Back -> "뒤로"
    }

    // ---------------- 여정 ----------------

    /** 들러 본 화면·누른 버튼·탭·칩 (골고루 눌러 봤는지) */
    private val visited = HashSet<String>()

    @Test
    fun anyOrderOfTapsNeverGetsStuckAndAlwaysPlaysTheRightThing() {
        var steps = 0
        for (seed in 1..24) {
            random = Random(seed)
            failRate = if (seed % 3 == 0) 0.3 else 0.08
            store.t = if (seed % 2 == 0) Tokens("AT", "RT", now + 3_600_000) else null
            tokenReply = Http.Response(428, """{"error":"authorization_pending"}""")
            TubeState(context).clear()
            TubeState(context).agree()
            playerDir(context).deleteRecursively()
            steps += journey(seed, 80)
        }
        assertTrue("충분히 눌러 봤다: $steps", steps >= 1_900)
        println("들러 본 것: " + visited.sorted())
        val pages = listOf(
            "Home", "Shorts", "Find", "Search", "Subscriptions", "SubsChannels", "Library", "History", "Liked", "MyPlaylists",
            "Channel", "Playlist", "Menu", "Login", "Account", "Handoff", "Terms",
        )
        val missing = pages.filter { "page:$it" !in visited }
        assertTrue("모든 화면에 가 봤다 (못 간 곳: $missing)", missing.isEmpty())
        val actions = listOf(
            TubeRows.A_MORE, TubeRows.A_RETRY, TubeRows.A_PLAY, TubeRows.A_PLAY_ALL, TubeRows.A_CHANNEL, TubeRows.A_LOGIN, TubeRows.A_LOGOUT,
            TubeRows.A_SEARCH, TubeRows.A_INPUT, TubeRows.A_ON_PHONE, TubeRows.A_NOT_INTERESTED, TubeRows.A_NO_CHANNEL, TubeRows.A_SHORTS_PLAY,
            TubeRows.A_SUBS_CHANNELS, TubeRows.A_CLEAR, TubeRows.A_CLEAR_SEARCHES,
        )
        val notPressed = actions.filter { "action:$it" !in visited }
        assertTrue("중요한 버튼은 다 눌러 봤다 (못 누른 것: $notPressed)", notPressed.isEmpty())
        val chips = TubeTab.entries.map { "tab:" + it.name } + HomeChip.entries.map { "chip:" + TubeRows.BAR_HOME + ":" + it.name }
        val notTried = chips.filter { it !in visited }
        assertTrue("탭·홈 칩은 다 눌러 봤다 (못 누른 것: $notTried)", notTried.isEmpty())
        listOf("blocked", "short", "blockedShort", "signedIn", "recs", "seen", "recsShown", "avatar").forEach {
            assertTrue("해 봤다: $it", it in visited)
        }
        assertTrue("최근 검색어를 눌러 봤다", visited.any { it.startsWith("action:" + TubeRows.A_QUERY) })
    }

    /** 앱 하나 (화면 + 그 생명 주기 — 재생 화면에 다녀오는 것을 흉내 낸다) */
    private class App(val controller: ActivityController<TubeActivity>) {
        val a: TubeActivity get() = controller.get()
    }

    /** 한 사람이 앱을 열어 [n] 번 누른다 (홈에서 뒤로 가 닫히면 다시 연다). 누른 수 */
    private fun journey(seed: Int, n: Int): Int {
        val log = mutableListOf<String>()
        fun check(cond: Boolean, what: String) {
            if (!cond) fail("씨앗 $seed, ${log.size}번째에서: $what\n지나온 길:\n" + log.takeLast(25).joinToString("\n"))
        }

        var app = start(seed, log, ::check)
        var done = 0
        while (done < n) {
            done++
            val a = app.a
            val nav = a.nav
            val depthBefore = nav.depth
            val topBefore = nav.top
            val bottomBefore = nav.bottom
            val below = if (nav.depth > 1) seenBelow(a) else null
            val step = pick(a)
            log += "${topBefore::class.simpleName}(${firstTitle(a)}) → ${describe(step)}"
            visited += "page:" + topBefore::class.simpleName
            when (step) {
                is Step.Tap -> {
                    (step.row as? Row.Action)?.let { visited += "action:" + it.id }
                    if ((step.row as? Row.VideoCard)?.video?.embeddable == false) visited += "blocked"
                }
                is Step.Part -> visited += partKey(step.row, step.part)
                else -> Unit
            }
            phoneOpened.clear()
            val playedBefore = TubeState(context).lastPlayed()?.id
            check(drain(a).first.isEmpty(), "앞에서 열린 화면이 처리되지 않음")
            when (step) {
                Step.Back -> {
                    a.onBackPressedDispatcher.onBackPressed()
                    settle { !opening(a) }
                    if (topBefore is Page.Home && topBefore.chip != HomeChip.ALL) {
                        check(!a.isFinishing && nav.depth == 1 && nav.top == Page.Home(HomeChip.ALL), "홈의 칩에서 뒤로 = '전체' (${nav.top})")
                    } else if (depthBefore == 1) {
                        check(a.isFinishing, "홈 '전체'에서 뒤로 = 닫힘")
                        app = start(seed, log, ::check)
                        continue
                    } else {
                        check(nav.depth == depthBefore - 1, "뒤로는 한 칸 (${depthBefore} → ${nav.depth})")
                        check(nav.top == below, "바로 앞 화면으로 ($below 이어야 하는데 ${nav.top})")
                    }
                }
                is Step.Long -> {
                    val view = holderView(a, rows(a).indexOf(step.row))
                    val video = if (step.part < 0) (step.row as Row.VideoCard).video else shortAt(step.row, step.part)!!.let { (l, i) -> l[i] }
                    (if (step.part < 0) view else tappables(view)[step.part]).performLongClick()
                    settle { !opening(a) }
                    check(nav.top == Page.Menu(video), "길게 누르면 그 동영상의 메뉴")
                }
                is Step.Tap -> {
                    holderView(a, rows(a).indexOf(step.row)).performClick()
                    settle { !opening(a) }
                    afterTap(app, step.row, topBefore, below, playedBefore, ::check)
                }
                is Step.Part -> {
                    tappables(holderView(a, rows(a).indexOf(step.row)))[step.part].performClick()
                    settle { !opening(a) }
                    afterPart(app, step, topBefore, bottomBefore, depthBefore, ::check)
                    check(TubeState(context).lastPlayed()?.id == playedBefore, "탭·칩·Shorts 는 시청 기록을 바꾸지 않는다")
                }
            }
            // 공통: 멈추지 않았고, 보이는 것이 지금 화면이다
            if (app.a.isFinishing) {
                app = start(seed, log, ::check)
                continue
            }
            invariants(app.a, ::check)
        }
        app.a.finish()
        idle()
        return done
    }

    /** 어느 화면에서든 늘 맞아야 하는 것 */
    private fun invariants(a: TubeActivity, check: (Boolean, String) -> Unit) {
        val nav = a.nav
        val shown = rows(a)
        check(!opening(a), "'여는 중…'에 갇히지 않는다")
        check(shown.isNotEmpty(), "빈 화면이 아니다")
        expectedTitle(nav.top)?.let { check(firstTitle(a) == it, "보이는 화면이 지금 화면 (${nav.top}) — 첫 제목 '${firstTitle(a)}'") }
        check(a.onBackPressedDispatcher.hasEnabledCallbacks() == backInside(nav), "뒤로 가기는 홈 '전체'가 아닐 때만 화면 안에서")
        val cards = shown.filterIsInstance<Row.VideoCard>()
        val shortsShown = shown.flatMap { r ->
            when (r) {
                is Row.ShortsShelf -> r.videos
                is Row.ShortsPair -> listOfNotNull(r.list.getOrNull(r.index), r.list.getOrNull(r.index + 1))
                else -> emptyList()
            }
        }
        check(shortsShown.all { it.isShort }, "선반·격자에는 Shorts 만")
        val state = TubeState(context)
        val t = System.currentTimeMillis()
        val history = state.played(t).map { it.video.id }.toSet()
        // 로그인 안 했으면 내 데이터는 어디에도 없다
        if (!signedIn()) {
            val shownIds = cards.map { it.video.id } + shortsShown.map { it.id } + shown.filterIsInstance<Row.Header>().mapNotNull { it.art }
            // 이 앱에서 본 기록(워치에 남김)은 내 것이어도 보여도 된다
            check(shownIds.none { it in mineVideoIds && it !in history }, "로그인 안 했는데 내 동영상이 보임")
            check(shown.none { it is Row.PlaylistItem && it.playlist.id == myPl.id }, "로그인 안 했는데 내 재생목록이 보임")
            check(shown.none { it is Row.Action && it.id in setOf(TubeRows.A_LIKED, TubeRows.A_MY_PLAYLISTS, TubeRows.A_LOGOUT, TubeRows.A_ACCOUNT) }, "로그인 안 했는데 내 계정 버튼")
            check(shown.none { it is Row.Header && it.title == me.title } && shown.none { it is Row.Action && it.text == me.title }, "로그인 안 했는데 내 채널")
            check(shown.none { it is Row.AvatarStrip }, "로그인 안 했는데 구독 채널")
            check(nav.top !in setOf(Page.Liked, Page.MyPlaylists, Page.Account, Page.SubsChannels) && (nav.top as? Page.Playlist)?.own != true, "로그인 안 했는데 내 화면")
        }
        // 홈: 관심 없음·채널 추천 안 함은 없다. '전체'·'최근 업로드'·'새로운 맞춤'에는 본 것이 없다. Shorts 는 카드가 아니라 선반에
        (nav.top as? Page.Home)?.let { home ->
            val gone = state.dismissedVideos(t)
            val goneChannels = state.dismissedChannels(t)
            check((cards.map { it.video } + shortsShown).none { it.id in gone || it.channelId in goneChannels }, "홈에 관심 없음이 보임 (${home.chip})")
            if (home.chip in setOf(HomeChip.ALL, HomeChip.RECENT, HomeChip.NEW)) check(cards.none { it.video.id in history }, "${home.chip} 에 이미 본 동영상")
            if (home.chip != HomeChip.WATCHED) check(cards.none { it.video.isShort }, "${home.chip} 에 Shorts 카드")
            if (home.chip == HomeChip.ALL && cards.any { it.video.id.startsWith("rec") }) visited += "recsShown"
        }
        if (nav.top == Page.Shorts) {
            val gone = state.dismissedVideos(t)
            check(shortsShown.none { it.id in gone || it.channelId in state.dismissedChannels(t) }, "Shorts 에 관심 없음이 보임")
        }
        // 화면의 모든 동영상: 이어 틀 목록이 올바르다
        cards.forEach { card -> checkQueue(nav.top, card, shown, check) }
    }

    /** 앱을 연다 (가끔은 타일·재생 화면의 링크로). 말·키보드 입력이 열렸으면 대답, 바로 닫혔으면 다시 */
    private fun start(seed: Int, log: MutableList<String>, check: (Boolean, String) -> Unit): App {
        while (true) {
            val intent = when (random.nextInt(8)) {
                0 -> Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)
                1 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/phone?v=${trendP1[0].id}&t=30&title=%EC%A0%9C%EB%AA%A9"))
                2 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/channel?v=" + everything.keys.random(random)))
                else -> null
            }
            log += "== 열기 (씨앗 $seed) ${intent?.let { it.dataString ?: "타일 믹스" } ?: ""}"
            val controller = (if (intent == null) Robolectric.buildActivity(TubeActivity::class.java) else Robolectric.buildActivity(TubeActivity::class.java, intent)).setup()
            val app = App(controller)
            settle { !opening(app.a) }
            // 타일 믹스로 열려 바로 재생했으면 다녀온다 / 아직 본 게 없으면 말로 찾기가 열린다
            afterStarts(app, check)
            if (!app.a.isFinishing) return app
            log += "(닫힘 — 다시 연다)"
        }
    }

    /** 이번에 열린 것: (열린 화면들 — 먼저 열린 것부터, 말·키보드 입력) */
    private fun drain(a: TubeActivity): Pair<List<Intent>, List<Intent>> {
        val forResult = generateSequence { shadowOf(a).nextStartedActivityForResult }.toList()
        val all = generateSequence { shadowOf(a).nextStartedActivity }.toList()
        val inputs = forResult.filter { it.requestCode >= 0 }.map { it.intent }
        return all.filter { i -> inputs.none { it === i } }.reversed() to inputs
    }

    /**
     * 열린 화면 처리: 말·키보드 입력이면 대답(가끔 취소), 재생 화면이면 거기서 본 것(믹스 추천·본 Shorts)을 가끔 받은 편지로 남기고 돌아온다.
     * 열린 재생 화면들
     */
    private fun afterStarts(app: App, check: (Boolean, String) -> Unit): List<Intent> {
        val a = app.a
        val (players, inputs) = drain(a)
        players.forEach { s ->
            check(s.component?.className == TubePlayerActivity::class.java.name, "열린 것은 앱 안 재생 화면: $s")
            val url = s.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty()
            check(url.startsWith(base + "#v=") || url.startsWith(base + "shorts.html#ids="), "재생 화면 주소: $url")
            check(url.endsWith("&app=1"), "앱 안 재생 화면이라는 표시: $url")
        }
        inputs.forEach { input -> answerInput(a, input) }
        if (players.isNotEmpty() && !a.isFinishing) {
            players.forEach(::reportFromPlayer)
            if (random.nextInt(5) != 0) {
                // 재생 화면에 다녀옴 — 받은 편지를 읽는다
                app.controller.pause().stop()
                app.controller.start().resume()
                settle { !opening(a) }
                // 돌아와서 저절로 열린 것은 없다 (Shorts 탭 바로 열기는 한 번뿐)
                val again = drain(a)
                check(again.first.isEmpty() && again.second.isEmpty(), "돌아오면 저절로 열리지 않는다: ${again.first}")
            }
        }
        return players
    }

    /** 재생 화면이 하는 일: 믹스면 유튜브가 고른 다음 동영상들을, Shorts 면 처음 본 것을 앱에 알린다 (가끔) */
    private fun reportFromPlayer(s: Intent) {
        val url = s.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty()
        val inbox = TubeInbox.of(context)
        if (url.contains("shorts.html#")) {
            if (random.nextBoolean()) {
                val q = Uri.parse(url.replace("#", "?"))
                val ids = q.getQueryParameter("ids").orEmpty().split(',')
                ids.getOrNull(q.getQueryParameter("i")?.toIntOrNull() ?: 0)?.let {
                    inbox.post("kakaowatch://tube/seen?v=$it")
                    visited += "seen"
                }
            }
        } else if (url.contains("&mix=1") && random.nextInt(10) < 6) {
            val seed = Uri.parse(url.replace("#", "?")).getQueryParameter("v") ?: return
            val ids = recVideos.keys.shuffled(random).take(3 + random.nextInt(4)).filter { it != seed }
            inbox.post("kakaowatch://tube/recs?seed=$seed&ids=" + ids.joinToString(","))
            visited += "recs"
        }
    }

    /** 말·키보드 입력에 대답한다 (셋에 하나는 취소 — 검색 화면의 최근 검색어도 눌러 보게) */
    private fun answerInput(a: TubeActivity, input: Intent) {
        if (random.nextInt(3) == 0) {
            shadowOf(a).receiveResult(input, Activity.RESULT_CANCELED, Intent())
        } else {
            val result = Intent()
            val q = listOf("아이유", "뉴진스", "lofi").random(random)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), result, Bundle().apply { putCharSequence("query", q) })
            shadowOf(a).receiveResult(input, Activity.RESULT_OK, result)
        }
        settle { !opening(a) }
    }

    /** 뒤로 가면 나와야 할 화면: 지금 쌓인 것의 바로 아래 */
    private fun seenBelow(a: TubeActivity): Page {
        val stack = a.nav.javaClass.getDeclaredField("stack").apply { isAccessible = true }.get(a.nav) as List<*>
        return stack[stack.size - 2] as Page
    }

    private fun afterTap(app: App, row: Row, topBefore: Page, below: Page?, playedBefore: String?, check: (Boolean, String) -> Unit) {
        val a = app.a
        val players = afterStarts(app, check)
        val started = players.firstOrNull()
        when {
            row is Row.VideoCard -> {
                val v = row.video
                if (v.embeddable) {
                    check(players.size == 1, "누른 동영상은 앱 안 재생 화면에서 ($players)")
                    val url = started!!.getStringExtra(TubePlayerActivity.EXTRA_URL)!!.replace(Regex("&n=\\d+"), "")
                    check(url == TubeLinks.playerUrl(base, v, row.queue, inApp = true), "재생 주소가 그 동영상과 목록 그대로: $url")
                } else {
                    check(players.isEmpty(), "못 트는 동영상은 워치에서 열지 않는다")
                    // 재생목록에서 왔으면 폰에서도 그 목록으로 (비공개 내 재생목록이면 번호 목록에 실린 재생목록 번호)
                    val list = (row.queue as? Queue.Playlist)?.id ?: (row.queue as? Queue.Ids)?.list
                    check(phoneOpened == listOf(TubeLinks.watchUrl(v.id, row.queue == Queue.Mix, list = list)), "못 트는 동영상은 폰 유튜브로: $phoneOpened")
                }
                check(TubeState(context).lastPlayed()?.id == v.id, "튼 동영상이 '시청 기록' 맨 앞에")
            }
            row is Row.ChannelItem -> check(a.nav.top == Page.Channel(row.channel.id, row.channel.title), "채널을 누르면 그 채널 (${a.nav.top})")
            row !is Row.Action -> Unit
            row.id == TubeRows.A_PLAY -> {
                val video = (topBefore as Page.Menu).video
                check(started?.getStringExtra(TubePlayerActivity.EXTRA_URL)?.replace(Regex("&n=\\d+"), "") == TubeLinks.playerUrl(base, video, Queue.Single, inApp = true), "메뉴의 재생 = 그 동영상 하나")
                check(TubeState(context).lastPlayed()?.id == video.id, "튼 동영상이 '시청 기록' 맨 앞에")
                check(a.nav.top !is Page.Menu, "틀면 메뉴는 닫힌다")
            }
            row.id == TubeRows.A_PLAY_ALL -> {
                check(started != null, "모두 재생 = 앱 안 재생 화면")
                val p = a.nav.top as Page.Playlist
                val url = started!!.getStringExtra(TubePlayerActivity.EXTRA_URL)!!
                if (p.own) check(url.contains("&ids=") || !url.contains("&list="), "내 재생목록은 받은 목록으로: $url")
                else check(url.contains("&list=${p.id}"), "공개 재생목록은 유튜브 재생목록으로: $url")
            }
            row.id == TubeRows.A_SHORTS_PLAY -> {
                val url = started?.getStringExtra(TubePlayerActivity.EXTRA_URL)
                if (url != null) check(url.contains("shorts.html#ids=") && url.contains("&i=0&"), "Shorts 보기 = 틀 수 있는 첫 것부터: $url")
                else check(phoneOpened.size == 1 && phoneOpened[0].startsWith("https://www.youtube.com/shorts/"), "워치에서 틀 것이 없으면 폰의 Shorts 로: $phoneOpened")
            }
            row.id == TubeRows.A_NOT_INTERESTED -> {
                val video = (topBefore as Page.Menu).video
                check(video.id in TubeState(context).dismissedVideos(System.currentTimeMillis()), "관심 없음이 남는다")
                check(a.nav.top == below, "관심 없음 → 메뉴를 닫고 앞 화면으로")
            }
            row.id == TubeRows.A_NO_CHANNEL -> {
                val video = (topBefore as Page.Menu).video
                check(video.channelId in TubeState(context).dismissedChannels(System.currentTimeMillis()), "채널 추천 안 함이 남는다")
                check(a.nav.top == below, "채널 추천 안 함 → 메뉴를 닫고 앞 화면으로")
            }
            row.id == TubeRows.A_CLEAR -> {
                val t = System.currentTimeMillis()
                val s = TubeState(context)
                check(s.played(t).isEmpty() && s.harvests(t).isEmpty() && s.dismissedVideos(t).isEmpty() && s.seenShorts(t).isEmpty(), "기록 지우기 = 모두")
            }
            row.id == TubeRows.A_SUBS_CHANNELS -> check(a.nav.top == Page.SubsChannels, "구독 채널 모두")
            row.id.startsWith(TubeRows.A_QUERY) -> check(a.nav.top == Page.Search(row.id.removePrefix(TubeRows.A_QUERY)), "최근 검색어 = 그 검색")
            row.id == TubeRows.A_LOGIN -> {
                settle { rows(a).any { it is Row.Code || (it is Row.Note && it.text.contains("실패")) } }
                if (rows(a).any { it is Row.Code } && random.nextBoolean()) {
                    // 폰에서 허용 → 저절로 로그인되고 로그인 화면은 닫힌다
                    tokenReply = Http.Response(200, """{"access_token":"AT","expires_in":3599,"refresh_token":"RT","scope":"https://www.googleapis.com/auth/youtube.readonly"}""")
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
                    settle { a.nav.top != Page.Login }
                    check(signedIn(), "허용하면 로그인된다")
                    visited += "signedIn"
                    check(a.nav.top != Page.Login, "로그인되면 로그인 화면은 닫힌다")
                    tokenReply = Http.Response(428, """{"error":"authorization_pending"}""")
                }
            }
            row.id == TubeRows.A_LOGOUT -> check(!signedIn(), "로그아웃하면 토큰이 지워진다")
            else -> Unit
        }
        val plays = row is Row.VideoCard || (row is Row.Action && row.id in setOf(TubeRows.A_PLAY, TubeRows.A_PLAY_ALL, TubeRows.A_ON_PHONE, TubeRows.A_HANDOFF))
        if (!plays) {
            check(TubeState(context).lastPlayed()?.id == playedBefore || row is Row.Action && row.id in setOf(TubeRows.A_CLEAR, TubeRows.A_FORGET), "틀지 않았는데 기록이 바뀌지 않는다")
        }
    }

    /** 탭·칩·채널 사진·세로 카드를 누른 뒤 */
    private fun afterPart(app: App, step: Step.Part, topBefore: Page, bottomBefore: Page, depthBefore: Int, check: (Boolean, String) -> Unit) {
        val a = app.a
        val nav = a.nav
        val players = afterStarts(app, check)
        when (val r = step.row) {
            is Row.Tabs -> {
                val tab = TubeTab.entries[step.part]
                val target = when (tab) {
                    TubeTab.HOME -> null
                    TubeTab.SHORTS -> Page.Shorts
                    TubeTab.SUBS -> Page.Subscriptions()
                    TubeTab.YOU -> Page.Library
                }
                when {
                    target == null -> check(nav.depth == 1 && nav.top == bottomBefore, "홈 탭 = 홈 (고른 칩 그대로)")
                    topBefore == target || (target is Page.Subscriptions && topBefore is Page.Subscriptions) ->
                        check(nav.top == topBefore && nav.depth == depthBefore, "같은 탭을 다시 누르면 그대로")
                    topBefore.tab != null && topBefore !is Page.Home -> check(nav.top == target && nav.depth == depthBefore, "탭끼리는 자리만 바뀐다 (${nav.top}, ${nav.depth})")
                    else -> check(nav.top == target && nav.depth == minOf(depthBefore + 1, TubeNav.MAX), "탭 화면이 한 칸 위에 (${nav.top}, ${nav.depth})")
                }
                players.forEach { check(it.getStringExtra(TubePlayerActivity.EXTRA_URL).orEmpty().contains("shorts.html#ids="), "탭으로 열리는 것은 Shorts 재생 화면뿐") }
                if (players.isNotEmpty()) check(tab == TubeTab.SHORTS, "Shorts 탭에서만 저절로 연다")
                check(phoneOpened.isEmpty() || (tab == TubeTab.SHORTS && topBefore == Page.Shorts), "탭은 폰을 저절로 열지 않는다: $phoneOpened")
            }
            is Row.ChipBar -> {
                val id = r.chips[step.part].first
                val expected: Page = when (topBefore) {
                    is Page.Home -> Page.Home(HomeChip.valueOf(id))
                    is Page.Subscriptions -> Page.Subscriptions(com.mywatch.kakaomap.ui.tube.SubsChip.valueOf(id))
                    is Page.Channel -> topBefore.copy(section = com.mywatch.kakaomap.ui.tube.ChannelTab.valueOf(id))
                    else -> topBefore
                }
                check(nav.top == expected && nav.depth == depthBefore, "칩은 그 화면 자리만 바꾼다 (${nav.top})")
                check(players.isEmpty(), "칩으로는 재생 화면이 열리지 않는다")
            }
            is Row.AvatarStrip -> {
                if (step.part < r.channels.size) {
                    val ch = r.channels[step.part]
                    check(nav.top == Page.Channel(ch.id, ch.title), "채널 사진 = 그 채널")
                    visited += "avatar"
                } else {
                    check(nav.top == Page.SubsChannels, "'전체' = 구독 채널 모두")
                }
            }
            else -> {
                val (list, i) = shortAt(r, step.part)!!
                val video = list[i]
                visited += "short"
                if (!video.embeddable) {
                    visited += "blockedShort"
                    check(players.isEmpty() && phoneOpened == listOf(TubeLinks.phoneShortsUrl(video.id)), "못 트는 Shorts 는 폰 유튜브의 Shorts 로: $phoneOpened")
                } else {
                    check(players.size == 1, "세로 카드 = Shorts 재생 화면 ($players)")
                    val q = Uri.parse(players[0].getStringExtra(TubePlayerActivity.EXTRA_URL)!!.replace("#", "?"))
                    val ids = q.getQueryParameter("ids").orEmpty().split(',')
                    val at = q.getQueryParameter("i")?.toIntOrNull() ?: -1
                    check(ids.getOrNull(at) == video.id, "누른 Shorts 부터 ($ids, $at, ${video.id})")
                    check(ids.all { id -> everything[id]?.embeddable != false }, "워치에서 못 트는 Shorts 는 목록에서 뺀다")
                    check(ids.size <= TubeLinks.MAX_QUEUE && ids.distinct().size == ids.size, "Shorts 목록은 50개까지, 겹치지 않게")
                }
            }
        }
    }

    /** 이어 틀 목록: 피드는 유튜브 믹스, 재생목록은 그 목록, 좋아요·비공개 내 재생목록은 받은 번호로 (자기 자신·못 트는 것 없이 49개까지) */
    private fun checkQueue(page: Page, card: Row.VideoCard, shown: List<Row>, check: (Boolean, String) -> Unit) {
        val q = card.queue
        when (page) {
            is Page.Playlist -> if (page.own && page.private) check(q is Queue.Ids && q.list == page.id, "비공개 내 재생목록은 받은 번호로 ($q)")
            else check(q == Queue.Playlist(page.id), "재생목록은 그 목록 그대로 ($q)")
            Page.Liked -> {
                // 뒤에 워치에서 틀 것이 없으면 그것 하나만
                val cards = shown.filterIsInstance<Row.VideoCard>()
                val rest = cards.drop(cards.indexOf(card) + 1).any { it.video.embeddable }
                check(q is Queue.Ids || (!rest && q == Queue.Single), "좋아요는 받은 목록대로 ($q)")
            }
            else -> check(q == Queue.Mix, "${page::class.simpleName} 의 동영상은 유튜브 믹스로 ($q)")
        }
        if (q is Queue.Ids) {
            check(card.video.id !in q.next, "이어 틀 목록에 자기 자신이 없다 (${card.video.id})")
            check(q.next.size <= TubeLinks.MAX_QUEUE - 1, "이어 틀 목록은 49개까지")
            val blocked = shown.filterIsInstance<Row.VideoCard>().filter { !it.video.embeddable }.map { it.video.id }.toSet()
            check(q.next.none { it in blocked }, "이어 틀 목록에 워치에서 못 트는 동영상이 없다")
            check(q.next.distinct().size == q.next.size, "이어 틀 목록에 같은 동영상이 두 번 없다")
        }
    }
}
