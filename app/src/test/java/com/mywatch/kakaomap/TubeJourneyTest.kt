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
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.SignInNeededException
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
import com.mywatch.kakaomap.ui.tube.Page
import com.mywatch.kakaomap.ui.tube.TubeBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.random.Random

/**
 * 아무렇게나 눌러 보기 (여러 사람이 마구 쓰듯이, 씨앗마다 같은 순서): 어떤 순서로 눌러도
 *  · 멈추지 않는다 — 늘 목록이 보이고('여는 중…'에 갇히지 않음), 보이는 화면이 지금 화면과 맞다 (예전 화면이 남지 않음)
 *  · 뒤로는 늘 한 화면씩, 바로 앞에 보던 화면으로 (홈에서 뒤로 = 닫힘)
 *  · 로그인하지 않았으면 내 데이터(좋아요·구독·내 재생목록·내 채널)가 어디에도 보이지 않는다 (로그아웃 직후도)
 *  · 동영상을 누르면 그 동영상부터 목록 그대로 이어 튼다 (워치에서 못 트는 것은 빼고, 공개 재생목록은 유튜브가 이어서),
 *    못 트는 것은 폰으로 — 그리고 '이어서 보기' 맨 앞에 들어간다
 * 네트워크는 가끔 실패한다 (다시 시도·더 보기 실패도 섞임).
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
    private val me = TubeChannel("UCmmmmmmmmmmmmmmmmmmmmmm", "내 채널", "https://yt3.ggpht.com/m=s240")
    private val channels = listOf(chA, chB, chC)

    private var serial = 0
    /** 동영상 번호(11자): 앞 3글자가 어디서 온 것인지 — lik·sub·myp 는 내 계정 데이터 */
    private fun v(kind: String, ch: TubeChannel, embeddable: Boolean = true, live: Boolean = false): TubeVideo {
        val id = kind + "%08d".format(++serial)
        return TubeVideo(id, "제목 $id", ch.title, if (live) null else 200 + serial, live = live, channelId = ch.id, views = 1_000L * serial,
            publishedAt = now - 3_600_000L * serial, embeddable = embeddable)
    }

    private fun page(kind: String, n: Int, blockedEvery: Int = 4) = List(n) { i -> v(kind, channels[i % channels.size], embeddable = (i + 1) % blockedEvery != 0, live = i == 2) }

    private val musicP1 = page("pub", 6)
    private val musicP2 = page("pub", 4)
    private val popular = page("pub", 5)
    private val searchP1 = page("pub", 5)
    private val searchP2 = page("pub", 3)
    private val chanVideos = channels.associate { it.id to (page("pub", 4) to page("pub", 2)) }
    private val plA = TubePlaylist("PLaaaaaaaaaaaaaaaaaa", "모음가", chA.title, null, 5)
    private val plB = TubePlaylist("PLbbbbbbbbbbbbbbbbbb", "모음나", chB.title, null, 3)
    private val plC = TubePlaylist("PLcccccccccccccccccc", "모음다", chC.title, null, 2)
    private val listVideos = listOf(plA, plB, plC).associate { it.id to page("pub", 5) }
    private val subsFeed = page("sub", 3)
    private val liked = page("lik", 4)
    private val myPl = TubePlaylist("PLmmmmmmmmmmmmmmmmmm", "내 모음", me.title, null, 4)
    private val myPlVideos = page("myp", 4)

    /** 로그인해야만 보이는 것들 */
    private val mineVideoIds get() = (subsFeed + liked + myPlVideos).map { it.id }.toSet()

    private var random = Random(0)
    private var failRate = 0.1

    private inner class FakeBackend : TubeBackend {
        override suspend fun fetch(feed: Feed, token: String?, source: TubeSource?): Fetched {
            if (feed.mine && store.t == null) throw SignInNeededException()
            if (random.nextDouble() < failRate) throw ApiException("네트워크 연결을 확인하세요")
            return when (feed) {
                is Feed.Popular -> if (feed.category == "10") {
                    if (token == null) Fetched(TubePage(musicP1, "M2")) else Fetched(TubePage(musicP2, null))
                } else Fetched(TubePage(popular, null))
                is Feed.Search -> if (token == null) {
                    Fetched(TubePage(searchP1, "S2", channels = listOf(chA), playlists = listOf(plA)), TubeSource.YOUTUBE)
                } else Fetched(TubePage(searchP2, null), TubeSource.YOUTUBE)
                Feed.SubsFeed -> Fetched(TubePage(subsFeed, null))
                Feed.SubsChannels -> if (token == null) Fetched(TubePage(emptyList(), "SC2", channels = listOf(chA, chB))) else Fetched(TubePage(emptyList(), null, channels = listOf(chC)))
                Feed.Liked -> Fetched(TubePage(liked, null))
                Feed.MyPlaylists -> Fetched(TubePage(emptyList(), null, playlists = listOf(myPl)))
                Feed.Account -> Fetched(TubePage(emptyList(), null, channels = listOf(me)))
                is Feed.ChannelInfo -> Fetched(TubePage(emptyList(), null, channels = listOfNotNull(channels.firstOrNull { it.id == feed.id })))
                is Feed.ChannelVideos -> chanVideos.getValue(feed.id).let { (p1, p2) -> if (token == null) Fetched(TubePage(p1, "C2")) else Fetched(TubePage(p2, null)) }
                is Feed.ChannelPlaylists -> if (token == null) Fetched(TubePage(emptyList(), "P2", playlists = listOf(plA, plB))) else Fetched(TubePage(emptyList(), null, playlists = listOf(plC)))
                is Feed.PlaylistVideos -> Fetched(TubePage(if (feed.id == myPl.id) myPlVideos else listVideos.getValue(feed.id), null))
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

    private fun signedIn() = store.t != null

    /** 지금 화면이면 보여야 할 첫 제목 */
    private fun expectedTitle(p: Page): String? = when (p) {
        Page.Home -> "유튜브"
        is Page.Search -> "‘${p.query}’"
        Page.Subscriptions -> "구독"
        Page.Library -> "보관함"
        Page.History -> "시청 기록"
        Page.Liked -> "좋아요 표시한 동영상"
        Page.MyPlaylists -> "내 재생목록"
        is Page.Chart -> p.title
        is Page.Channel -> p.title
        is Page.Playlist -> p.title
        is Page.Menu -> p.video.title
        Page.Login -> "로그인"
        Page.Account -> null   // 내 채널 이름 (받기 전엔 '내 계정')
        is Page.Handoff -> p.handoff.video.title
        Page.Terms -> "약관·개인정보"
    }

    // ---------------- 한 걸음 ----------------

    private sealed class Step {
        data class Tap(val row: Row, val chip: Row.Chip? = null) : Step()
        data class Long(val card: Row.VideoCard) : Step()
        data object Back : Step()
    }

    private fun pick(a: TubeActivity): Step {
        val shown = rows(a)
        val tappable = shown.filter { it is Row.VideoCard || it is Row.ChannelItem || it is Row.PlaylistItem || it is Row.Action || it is Row.Chips }
        val cards = shown.filterIsInstance<Row.VideoCard>()
        // 아직 안 눌러 본 버튼이 있으면 가끔은 그것부터 (골고루 가 보게)
        val fresh = shown.flatMap { r ->
            when (r) {
                is Row.Action -> if ("action:" + r.id !in visited) listOf(Step.Tap(r)) else emptyList()
                is Row.Chips -> r.items.filter { "action:" + it.id !in visited }.map { Step.Tap(r, it) }
                else -> emptyList()
            }
        }
        val r = random.nextDouble()
        return when {
            r < 0.22 -> Step.Back
            r < 0.34 && cards.isNotEmpty() -> Step.Long(cards.random(random))
            tappable.isEmpty() -> Step.Back
            r < 0.5 && fresh.isNotEmpty() -> fresh.random(random)
            else -> {
                val row = tappable.random(random)
                if (row is Row.Chips) Step.Tap(row, row.items.random(random)) else Step.Tap(row)
            }
        }
    }

    private fun describe(s: Step): String = when (s) {
        is Step.Tap -> "누름 " + when (val r = s.row) {
            is Row.VideoCard -> "동영상 ${r.video.id}"
            is Row.ChannelItem -> "채널 ${r.channel.title}"
            is Row.PlaylistItem -> "재생목록 ${r.playlist.title}"
            is Row.Action -> "버튼 ${r.id}"
            is Row.Chips -> "칩 ${s.chip?.id}"
            else -> r.toString()
        }
        is Step.Long -> "길게 ${s.card.video.id}"
        Step.Back -> "뒤로"
    }

    // ---------------- 여정 ----------------

    /** 들러 본 화면·누른 버튼 (골고루 눌러 봤는지) */
    private val visited = HashSet<String>()

    @Test
    fun anyOrderOfTapsNeverGetsStuckAndAlwaysPlaysTheRightThing() {
        var steps = 0
        for (seed in 1..20) {
            random = Random(seed)
            failRate = if (seed % 3 == 0) 0.3 else 0.08
            store.t = if (seed % 2 == 0) Tokens("AT", "RT", now + 3_600_000) else null
            tokenReply = Http.Response(428, """{"error":"authorization_pending"}""")
            TubeState(context).clear()
            TubeState(context).agree()
            playerDir(context).deleteRecursively()
            steps += journey(seed, 70)
        }
        assertTrue("충분히 눌러 봤다: $steps", steps >= 1_400)
        println("들러 본 것: " + visited.sorted())
        val pages = listOf("Home", "Search", "Subscriptions", "Library", "History", "Liked", "MyPlaylists", "Chart", "Channel", "Playlist", "Menu", "Login", "Account", "Handoff", "Terms")
        val missing = pages.filter { "page:$it" !in visited }
        assertTrue("모든 화면에 가 봤다 (못 간 곳: $missing)", missing.isEmpty())
        val actions = listOf(TubeRows.A_MORE, TubeRows.A_RETRY, TubeRows.A_PLAY, TubeRows.A_PLAY_ALL, TubeRows.A_CHANNEL, TubeRows.A_LOGIN, TubeRows.A_LOGOUT, TubeRows.A_SEARCH, TubeRows.A_ON_PHONE)
        val notPressed = actions.filter { "action:$it" !in visited }
        assertTrue("중요한 버튼은 다 눌러 봤다 (못 누른 것: $notPressed)", notPressed.isEmpty())
        assertTrue("못 트는 동영상도 눌러 봤다", "blocked" in visited)
        assertTrue("로그인해 봤다", "signedIn" in visited)
    }

    /** 한 사람이 앱을 열어 [n] 번 누른다 (홈에서 뒤로 가 닫히면 다시 연다). 누른 수 */
    private fun journey(seed: Int, n: Int): Int {
        val log = mutableListOf<String>()
        fun check(cond: Boolean, what: String) {
            if (!cond) fail("씨앗 $seed, ${log.size}번째에서: $what\n지나온 길:\n" + log.takeLast(25).joinToString("\n"))
        }

        var a = start(seed, log)
        var done = 0
        while (done < n) {
            done++
            val nav = a.nav
            val depthBefore = nav.depth
            val below = if (nav.depth > 1) seenBelow(a) else null
            val step = pick(a)
            log += "${nav.top::class.simpleName}(${firstTitle(a)}) → ${describe(step)}"
            visited += "page:" + nav.top::class.simpleName
            when (step) {
                is Step.Tap -> {
                    (step.row as? Row.Action)?.let { visited += "action:" + it.id }
                    step.chip?.let { visited += "action:" + it.id }
                    if ((step.row as? Row.VideoCard)?.video?.embeddable == false) visited += "blocked"
                }
                else -> Unit
            }
            phoneOpened.clear()
            val playedBefore = TubeState(context).lastPlayed()?.id
            when (step) {
                Step.Back -> {
                    a.onBackPressedDispatcher.onBackPressed()
                    settle { !opening(a) }
                    if (depthBefore == 1) {
                        check(a.isFinishing, "홈에서 뒤로 = 닫힘")
                        a = start(seed, log)
                        continue
                    }
                    check(nav.depth == depthBefore - 1, "뒤로는 한 칸 (${depthBefore} → ${nav.depth})")
                    check(nav.top == below, "바로 앞 화면으로 ($below 이어야 하는데 ${nav.top})")
                }
                is Step.Long -> {
                    val i = rows(a).indexOf(step.card)
                    holderView(a, i).performLongClick()
                    settle { !opening(a) }
                    check(nav.top == Page.Menu(step.card.video), "길게 누르면 그 동영상의 메뉴")
                }
                is Step.Tap -> {
                    val i = rows(a).indexOf(step.row)
                    var target = holderView(a, i)
                    if (step.chip != null) target = (target as ViewGroup).getChildAt((step.row as Row.Chips).items.indexOf(step.chip))
                    val startedBefore = shadowOf(a).nextStartedActivity   // 앞에서 남은 것 비우기
                    check(startedBefore == null, "앞에서 열린 화면이 처리되지 않음: $startedBefore")
                    val topBefore = nav.top
                    target.performClick()
                    settle { !opening(a) }
                    afterTap(a, step, topBefore, playedBefore, ::check)
                }
            }
            // 공통: 멈추지 않았고, 보이는 것이 지금 화면이다
            if (a.isFinishing) {
                a = start(seed, log)
                continue
            }
            check(!opening(a), "'여는 중…'에 갇히지 않는다")
            check(rows(a).isNotEmpty(), "빈 화면이 아니다")
            expectedTitle(a.nav.top)?.let { check(firstTitle(a) == it, "보이는 화면이 지금 화면 (${a.nav.top}) — 첫 제목 '${firstTitle(a)}'") }
            check(a.onBackPressedDispatcher.hasEnabledCallbacks() == (a.nav.depth > 1), "뒤로 가기는 홈이 아닐 때만 화면 안에서")
            // 로그인 안 했으면 내 데이터는 어디에도 없다
            if (!signedIn()) {
                val shownIds = rows(a).flatMap { r ->
                    when (r) {
                        is Row.VideoCard -> listOf(r.video.id)
                        is Row.Header -> listOfNotNull(r.art)
                        else -> emptyList()
                    }
                }
                val history = TubeState(context).played().map { it.video.id }.toSet()
                // 이 앱에서 본 기록(워치에 남김)은 내 것이어도 보여도 된다
                check(shownIds.none { it in mineVideoIds && it !in history }, "로그인 안 했는데 내 동영상이 보임")
                check(rows(a).none { it is Row.PlaylistItem && it.playlist.id == myPl.id }, "로그인 안 했는데 내 재생목록이 보임")
                check(rows(a).none { it is Row.Action && it.id in setOf(TubeRows.A_LIKED, TubeRows.A_MY_PLAYLISTS, TubeRows.A_LOGOUT, TubeRows.A_ACCOUNT) }, "로그인 안 했는데 내 계정 버튼")
                check(rows(a).none { it is Row.Header && it.title == me.title } && rows(a).none { it is Row.Action && it.text == me.title }, "로그인 안 했는데 내 채널")
                check(a.nav.top !in setOf(Page.Liked, Page.MyPlaylists, Page.Account) && (a.nav.top as? Page.Playlist)?.own != true, "로그인 안 했는데 내 화면")
            }
            // 화면의 모든 동영상: 이어 틀 목록이 올바르다
            rows(a).filterIsInstance<Row.VideoCard>().forEach { card -> checkQueue(card, rows(a), ::check) }
        }
        a.finish()
        idle()
        return done
    }

    private fun start(seed: Int, log: MutableList<String>): TubeActivity {
        // 가끔은 타일·재생 화면의 링크로 연다
        val intent = when (random.nextInt(6)) {
            0 -> Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)
            1 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/phone?v=${popular[0].id}&t=30&title=%EC%A0%9C%EB%AA%A9"))
            else -> null
        }
        log += "== 열기 (씨앗 $seed) ${intent?.let { it.action ?: "타일 믹스" } ?: ""}"
        val a = (if (intent == null) Robolectric.buildActivity(TubeActivity::class.java) else Robolectric.buildActivity(TubeActivity::class.java, intent)).setup().get()
        settle { !opening(a) }
        // 타일 믹스로 열려 바로 재생했으면 그 화면은 비운다 / 아직 본 게 없으면 말로 찾기가 열린다
        shadowOf(a).nextStartedActivity
        answerInput(a)
        return a
    }

    /** 말·키보드 입력이 열렸으면 대답한다 (가끔은 취소) */
    private fun answerInput(a: TubeActivity) {
        val input = shadowOf(a).nextStartedActivityForResult ?: return
        if (random.nextInt(5) == 0) {
            shadowOf(a).receiveResult(input.intent, Activity.RESULT_CANCELED, Intent())
        } else {
            val result = Intent()
            val q = listOf("아이유", "뉴진스", "lofi").random(random)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), result, Bundle().apply { putCharSequence("query", q) })
            shadowOf(a).receiveResult(input.intent, Activity.RESULT_OK, result)
        }
        settle { !opening(a) }
    }

    /** 뒤로 가면 나와야 할 화면: 지금 쌓인 것의 바로 아래 */
    private fun seenBelow(a: TubeActivity): Page {
        val stack = a.nav.javaClass.getDeclaredField("stack").apply { isAccessible = true }.get(a.nav) as List<*>
        return stack[stack.size - 2] as Page
    }

    private fun afterTap(a: TubeActivity, step: Step.Tap, topBefore: Page, playedBefore: String?, check: (Boolean, String) -> Unit) {
        val row = step.row
        val started = shadowOf(a).nextStartedActivity
        when {
            row is Row.VideoCard -> {
                val v = row.video
                if (v.embeddable) {
                    check(started?.component?.className == TubePlayerActivity::class.java.name, "누른 동영상은 앱 안 재생 화면에서 (${started})")
                    val url = started!!.getStringExtra(TubePlayerActivity.EXTRA_URL)!!.replace(Regex("&n=\\d+"), "")
                    check(url == TubeLinks.playerUrl(base, v, row.queue, inApp = true), "재생 주소가 그 동영상과 목록 그대로: $url")
                } else {
                    check(started == null, "못 트는 동영상은 워치에서 열지 않는다")
                    // 재생목록에서 왔으면 폰에서도 그 목록으로 (비공개 내 재생목록이면 번호 목록에 실린 재생목록 번호)
                    val list = (row.queue as? Queue.Playlist)?.id ?: (row.queue as? Queue.Ids)?.list
                    check(phoneOpened == listOf(TubeLinks.watchUrl(v.id, row.queue == Queue.Mix, list = list)), "못 트는 동영상은 폰 유튜브로: $phoneOpened")
                }
                check(TubeState(context).lastPlayed()?.id == v.id, "튼 동영상이 '이어서 보기' 맨 앞에")
            }
            row is Row.Action && row.id == TubeRows.A_PLAY -> {
                val video = (topBefore as Page.Menu).video
                check(started?.component?.className == TubePlayerActivity::class.java.name, "메뉴의 재생 = 앱 안 재생 화면")
                check(started!!.getStringExtra(TubePlayerActivity.EXTRA_URL)!!.replace(Regex("&n=\\d+"), "") == TubeLinks.playerUrl(base, video, Queue.Single, inApp = true), "메뉴의 재생 = 그 동영상 하나")
                check(TubeState(context).lastPlayed()?.id == video.id, "튼 동영상이 '이어서 보기' 맨 앞에")
                check(a.nav.top !is Page.Menu, "틀면 메뉴는 닫힌다")
            }
            row is Row.Action && row.id == TubeRows.A_PLAY_ALL -> {
                check(started?.component?.className == TubePlayerActivity::class.java.name, "모두 재생 = 앱 안 재생 화면")
                val p = a.nav.top as Page.Playlist
                val url = started!!.getStringExtra(TubePlayerActivity.EXTRA_URL)!!
                if (p.own) check(url.contains("&ids=") || !url.contains("&list="), "내 재생목록은 받은 목록으로: $url")
                else check(url.contains("&list=${p.id}"), "공개 재생목록은 유튜브 재생목록으로: $url")
            }
            row is Row.Action && row.id == TubeRows.A_LOGIN -> {
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
            row is Row.Action && row.id == TubeRows.A_LOGOUT -> {
                check(!signedIn(), "로그아웃하면 토큰이 지워진다")
            }
            row is Row.Action && row.id == TubeRows.A_SEARCH -> answerInput(a)
            else -> check(started == null || started.component?.className == TubePlayerActivity::class.java.name || started.action == Intent.ACTION_VIEW, "엉뚱한 화면이 열리지 않는다: $started")
        }
        if (row !is Row.VideoCard && !(row is Row.Action && row.id in setOf(TubeRows.A_PLAY, TubeRows.A_PLAY_ALL, TubeRows.A_ON_PHONE, TubeRows.A_HANDOFF))) {
            check(TubeState(context).lastPlayed()?.id == playedBefore || row is Row.Action && row.id in setOf(TubeRows.A_CLEAR, TubeRows.A_FORGET), "틀지 않았는데 기록이 바뀌지 않는다")
        }
    }

    /** 이어 틀 목록: 자기 자신·못 트는 것은 없고, 49개까지 */
    private fun checkQueue(card: Row.VideoCard, shown: List<Row>, check: (Boolean, String) -> Unit) {
        when (val q = card.queue) {
            is Queue.Ids -> {
                check(card.video.id !in q.next, "이어 틀 목록에 자기 자신이 없다 (${card.video.id})")
                check(q.next.size <= TubeLinks.MAX_QUEUE - 1, "이어 틀 목록은 49개까지")
                val blocked = shown.filterIsInstance<Row.VideoCard>().filter { !it.video.embeddable }.map { it.video.id }.toSet()
                check(q.next.none { it in blocked }, "이어 틀 목록에 워치에서 못 트는 동영상이 없다")
                check(q.next.distinct().size == q.next.size, "이어 틀 목록에 같은 동영상이 두 번 없다")
            }
            is Queue.Playlist -> check(q.id.startsWith("PL"), "공개 재생목록: ${q.id}")
            Queue.Single, Queue.Mix -> Unit
        }
    }
}
