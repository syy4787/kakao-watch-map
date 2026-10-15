package com.mywatch.kakaomap.ui

import android.app.AlertDialog
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.widget.ConfirmationOverlay
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.tile.TubeTileService
import com.mywatch.kakaomap.tube.DeviceCode
import com.mywatch.kakaomap.tube.HomeChip
import com.mywatch.kakaomap.tube.MixHarvests
import com.mywatch.kakaomap.tube.NetworkException
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayRequest
import com.mywatch.kakaomap.tube.PlayRoute
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.tube.Poll
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.SearchHistory
import com.mywatch.kakaomap.tube.TubeAccount
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlay
import com.mywatch.kakaomap.tube.TubeRevokeWorker
import com.mywatch.kakaomap.tube.TubeInbox
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.Uploads
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.ui.TubeRows.LoginState
import com.mywatch.kakaomap.ui.tube.ChannelTab
import com.mywatch.kakaomap.ui.tube.Feed
import com.mywatch.kakaomap.ui.tube.LiveBackend
import com.mywatch.kakaomap.ui.tube.Page
import com.mywatch.kakaomap.ui.tube.SubsChip
import com.mywatch.kakaomap.ui.tube.TubeBackend
import com.mywatch.kakaomap.ui.tube.TubeFeeds
import com.mywatch.kakaomap.ui.tube.TubeLoader
import com.mywatch.kakaomap.ui.tube.TubeNav
import com.mywatch.kakaomap.ui.tube.TubeTab
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 워치용 유튜브 앱 (워치 앱 목록의 '유튜브'): 유튜브 앱처럼 맨 위 탭(홈 · Shorts · 구독 · 나), 홈의 칩(전체 · 믹스 · 음악 · 게임 · 실시간 · 뉴스 ·
 * 최근 업로드 · 감상한 동영상 · 새로운 맞춤 동영상 — '전체'는 유튜브 믹스 추천·구독·본 채널·인기를 섞은 것, [TubeFeeds]),
 * 검색 화면(최근 검색어) · 검색 결과 · 구독(채널 사진 줄·칩) · 나(기록·내 재생목록·좋아요·계정) · 채널(동영상·Shorts·실시간·재생목록) · 재생목록.
 * 구글 계정으로 로그인하면 내 구독·좋아요·재생목록 ([TubeAccount] — 코드는 폰에서).
 * 동영상을 누르면 그 동영상부터 유튜브 믹스로(재생목록·좋아요는 목록 순서대로) 앱 안의 재생 화면([TubePlayerActivity] — 유튜브 공식 플레이어)에서,
 * Shorts 는 따로 된 Shorts 재생 화면(한 장씩 넘기기)에서 튼다. 재생 화면이 알려 준 믹스 추천·본 Shorts 는 받은 편지([TubeInbox])로 받는다.
 * 앱 안 엔진이 최근에 죽었으면 워치 인터넷 앱, 올린 사람이 막아 둔 동영상·재생 화면에 닿지 않으면 폰 유튜브로 ([TubePlay.route]).
 * 뒤로(버튼·쓸어 넘기기)는 늘 한 화면씩 ([TubeNav]), 홈에서 뒤로 가면 닫힌다.
 */
class TubeActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var state: TubeState
    private lateinit var loader: TubeLoader
    private lateinit var feeds: TubeFeeds
    private lateinit var backend: TubeBackend
    private val handler = Handler(Looper.getMainLooper())
    /** 받을 거리를 다시 정하는 일이 이미 걸려 있음 (받을 때마다 한 번으로 모은다) */
    private var feedsQueued = false
    /**
     * Shorts 탭으로 들어옴 — Shorts 가 준비되는 대로 Shorts 재생 화면을 한 번 바로 연다 (보고 있을 때만, [SHORTS_AUTO_MS] 안).
     * 재생 화면에서 돌아오거나 다른 화면으로 가면 끝
     */
    private var shortsAutoAt = 0L
    private val phone by lazy { PhoneOpener(this) }
    private val auth by lazy { TubeAccount.auth(this) }
    /** 화면 쌓기 (시험에서 지금 어느 화면인지 본다) */
    @VisibleForTesting
    internal val nav = TubeNav()
    /** 화면마다 보던 자리 (뒤로 오면 그 자리로) */
    private val scrolls = HashMap<Page, Spot>()

    /**
     * 보던 자리: 맨 위에 보이던 줄을 그 위의 가장 가까운 제목(구역)으로부터 몇 번째인지로 기억한다 —
     * 다녀온 사이 위쪽 구역이 늘어도(방금 본 동영상이 '이어서 보기'에 들어감) 같은 것이 보이게. [offset] = 그 줄이 위로 넘어간 만큼
     */
    private data class Spot(val section: String?, val inSection: Int, val pos: Int, val offset: Int)

    /** 여는 중 (두 번 눌러 두 번 열리지 않게, 가운데에 '여는 중…') */
    private var opening = false
    @StringRes private var openingText = R.string.tube_opening

    private var login: LoginState = LoginState.Starting
    private var loginJob: Job? = null
    /**
     * 홈·다른 앱으로 떠났음 (화면이 켜진 채 가려짐). 그 사이 끝난 일(더 받기·확인)로는 재생 화면을 띄우지 않는다:
     * 안드로이드는 앱이 막 가려진 뒤 몇 초는 화면을 띄울 수 있어 안 보는 사이 저절로 틀 수 있었다
     */
    private var left = false
    /** 손목을 내려 화면이 꺼짐(저전력 화면 포함) — 그 사이 끝난 '틀기'는 다시 볼 때 연다 ([deferred]) */
    private var asleep = false
    /** 화면이 꺼진 사이 끝난 '틀기'와 그 시각 (부팅 후 ms) — 다시 볼 때 [DEFERRED_PLAY_MS] 안이면 연다 */
    private var deferred: Pair<Long, () -> Unit>? = null
    /** 지난번에 볼 때 로그인돼 있었는지 — 계정에서 앱 접근을 끊는 등으로 로그아웃되면 내 데이터·내 화면을 걷어 낸다 */
    private var wasSignedIn = false

    /**
     * 타일에서 바로 들어온 한 번의 일([EXTRA_VOICE]·[EXTRA_MIX]). 폰에서 열면 잠깐 알려 주고 닫는다(워치 화면으로).
     * 홈으로 돌아가는 등 앱을 직접 쓰기 시작하면 잊는다
     */
    private var shortcut: String? = null
    private var closeJob: Job? = null
    /** 손목을 내리면 밝은 목록(미리보기 그림) 대신 검은 화면에 짧은 글만 */
    private var ambient: AmbientCover? = null

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            shortcut = null
            goBack()
        }
    }

    private val inputLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_QUERY) }?.toString()
        when {
            !text.isNullOrBlank() -> search(text)
            // 타일의 '검색'을 눌렀다가 취소 → 워치 화면으로
            shortcut == EXTRA_VOICE && nav.top is Page.Home -> finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        state = TubeState(this)
        ui = WearList(this, ::onRow, ::onLongRow, Look.TUBE)
        backend = backendOverride ?: LiveBackend(applicationContext)
        loader = TubeLoader(lifecycleScope, backend) { feed ->
            loader.takeMoreError(feed)?.let(::toast)
            if (!isFinishing) {
                // 받은 것으로 새로 정할 수 있게 된 받을 거리(구독 채널 → Shorts 등)는 이 받기가 끝난 뒤에
                queueFeeds()
                render()
                maybeOpenShorts()
            }
        }
        feeds = TubeFeeds(loader, state) { signedIn }
        onBackPressedDispatcher.addCallback(this, back)
        // 베젤·크라운으로 끝까지 내리면 다음 쪽을 저절로 (싼 목록만)
        ui.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) autoMore()
            }
        })
        ambient = AmbientCover(this, ::ambientSummary) { inAmbient ->
            asleep = inAmbient
            if (!inAmbient) {
                runDeferred()
                maybeOpenShorts()
            }
        }
        wasSignedIn = signedIn
        // 재생 화면을 보는 사이 이 화면이 정리됐다가 다시 만들어졌으면 다녀온 화면들 그대로 (데이터는 새로 받는다)
        savedInstanceState?.getString(STATE_NAV)?.let(nav::restore)
        show()
        // 로그아웃 직후 앱이 꺼져 철회를 못 보냈으면 이제라도 (인터넷이 되면)
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { if (runCatching { auth.hasPendingRevokes }.getOrDefault(false)) TubeRevokeWorker.enqueue(app) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAV, nav.save())
    }

    /** 손목을 내렸을 때: 로그인 코드를 기다리는 중이면 코드와 주소, 아니면 앱 이름 + 마지막에 본 것 */
    private fun ambientSummary(): CharSequence =
        TubeRows.ambient(this, state.lastPlayed()?.title, if (nav.top == Page.Login) login else null)

    /** 타일에서 온 일을 마쳐 곧 닫히려는데 화면을 만졌으면 계속 쓰려는 것 — 닫지 않는다 */
    override fun onUserInteraction() {
        super.onUserInteraction()
        closeJob?.cancel()
        closeJob = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 맡긴 확인이 새 버전을 찾으면 홈 맨 위에 '새 버전 업데이트'를 바로 띄운다 */
    private val updateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (nav.top is Page.Home && !opening) render()
    }

    override fun onStart() {
        super.onStart()
        left = false
        asleep = false
        resumeLogin()
        runDeferred()
    }

    /** 홈·최근 앱으로 (사용자가 떠남 — 화면이 꺼지는 것과 다름) */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        left = true
    }

    override fun onResume() {
        super.onResume()
        // 재생 화면이 남긴 것(믹스 추천·본 Shorts)을 받는다 — 홈 '전체'에 바로 섞인다
        runCatching { state.ingest(TubeInbox.of(this).drain()) }.onFailure { ErrorLog.record("유튜브·받은 편지", it) }
        // 다녀온 사이 기록·로그인·새 버전이 바뀌었을 수 있다
        if (!opening) show()
        // 손목을 내린 사이 Shorts 가 준비됐으면 이제 연다 (onStart 에선 아직 '보이는 중'이 아니라 여기서)
        maybeOpenShorts()
        UpdateManager.prefsForListening(this).registerOnSharedPreferenceChangeListener(updateListener)
        UpdateWorker.onAppOpened(this)
        prewarm()
    }

    override fun onPause() {
        UpdateManager.prefsForListening(this).unregisterOnSharedPreferenceChangeListener(updateListener)
        super.onPause()
    }

    override fun onStop() {
        // 화면이 켜진 채 가려졌으면 떠난 것, 꺼져서 안 보이면(손목을 내림) 잠깐 안 보는 것
        if (getSystemService(PowerManager::class.java)?.isInteractive != false) left = true else asleep = true
        if (left) shortsAutoAt = 0L
        // 로그인 기다리기는 화면이 보일 때만 (돌아오면 같은 코드로 이어서)
        loginJob?.cancel()
        loginJob = null
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // 약관에 동의하기 전엔 타일·링크의 일도 하지 않는다 (먼저 묻는 화면)
        if (!state.agreed) {
            shortcut = null
            return
        }
        // '최근 앱'에서 다시 열면 처음 열 때의 요청이 그대로 다시 온다 → 그냥 홈
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        // 재생 화면의 링크로 열렸으면 끼워 넣은 값(타일용)은 따르지 않는다. '목록으로'는 그냥 이 화면, '폰에서 이어 보기'는 한 번 묻기
        if (intent.action == Intent.ACTION_VIEW) {
            shortcut = null
            closeJob?.cancel()
            closeJob = null
            val link = intent.dataString
            link?.let(TubeLinks::handoff)?.let(::showHandoff)
            link?.let(TubeLinks::channelLink)?.let(::openChannelOf)
            return
        }
        // 타일의 요청은 주소 없는 인텐트로만 온다 — 주소(data)나 BROWSABLE 이 붙은 건 웹에서 왔을 수 있어 따르지 않는다
        if (intent.data != null || intent.hasCategory(Intent.CATEGORY_BROWSABLE)) return
        when {
            intent.getBooleanExtra(EXTRA_VOICE, false) -> {
                shortcut = EXTRA_VOICE
                goHome()
                openInput()
            }
            intent.getBooleanExtra(EXTRA_MIX, false) -> {
                goHome()
                val last = state.lastPlayed()
                if (last != null) {
                    shortcut = EXTRA_MIX
                    play(PlayRequest(last, Queue.Mix))
                } else {
                    // 아직 본 것이 없으면 말로 찾기 — 취소하면 '검색'처럼 워치 화면으로
                    shortcut = EXTRA_VOICE
                    openInput()
                }
            }
        }
    }

    // ---------------- 화면 ----------------

    private val signedIn: Boolean get() = runCatching { auth.signedIn }.getOrDefault(false)

    private fun account() = TubeRows.Account(
        configured = auth.configured,
        signedIn = signedIn,
        channel = loader.slot(Feed.Account).page?.channels?.firstOrNull(),
    )

    /** 이 화면이 받을 것들 (믹스 추천·본 채널·Shorts 처럼 워치 안의 것·구독 채널로 정해지는 것까지 — [TubeFeeds]) */
    private fun feedsOf(p: Page): List<Feed> = feeds.feedsFor(p, System.currentTimeMillis())

    /** '더 보기'로 이어 받을 것 */
    private fun moreFeed(p: Page): Feed? = feeds.moreFor(p)

    /**
     * 받은 것으로 새로 정할 수 있게 된 받을 거리(구독 채널 → Shorts 등)를 받는다 — 받기가 끝난 다음 차례에 한 번
     * (받는 중에 같은 것을 또 받지 않게). 새것만: 실패한 것까지 받으면 받기 → 실패 → 또 받기가 끝없이 돈다
     */
    private fun queueFeeds() {
        if (feedsQueued) return
        feedsQueued = true
        handler.post {
            feedsQueued = false
            if (!isFinishing && !isDestroyed && state.agreed) feedsOf(nav.top).forEach { loader.loadNew(it) }
        }
    }

    /**
     * 로그인이 풀렸으면(로그아웃 · 구글 계정에서 접근을 끊어 다음 받기에서 토큰이 지워짐) 내 데이터·내 화면을 걷어 낸다.
     * 새 화면을 쌓기 전에 해야 한다 — 쌓은 뒤에 하면 방금 연 '로그인' 화면까지 걷어 내 처음 누른 '로그인'이 홈으로 튀었다
     */
    private fun syncSignIn() {
        val nowSignedIn = signedIn
        if (wasSignedIn && !nowSignedIn) {
            loader.clearMine()
            nav.dropMine()
        }
        wasSignedIn = nowSignedIn
    }

    /** 저절로 더 받은 쪽 (같은 쪽을 두 번 저절로 묻지 않게 — 실패하면 '더 보기'를 눌러서) */
    private var autoAsked: String? = null

    /**
     * 목록 끝 몇 줄이 보이면 다음 쪽을 저절로 — 1~2단위로 싼 목록(차트·채널·재생목록·좋아요·구독)만.
     * 검색 '더 보기'는 100단위(하루 100번 한도)라 누를 때만
     */
    private fun autoMore() {
        if (opening) return
        val feed = moreFeed(nav.top) ?: return
        if (feed is Feed.Search) return
        val slot = loader.slot(feed)
        val token = slot.page?.next ?: return
        if (slot.loading || slot.loadingMore) return
        val lm = ui.list.layoutManager as? LinearLayoutManager ?: return
        val last = lm.findLastVisibleItemPosition()
        if (last < 0 || last < ui.rows.size - AUTO_MORE_ROWS) return
        val key = feed.key + "@" + token
        if (autoAsked == key) return
        autoAsked = key
        loader.more(feed)
    }

    /** 지금 화면을 받을 것을 받고(없거나 오래됐으면) 그린다. 약관에 동의하기 전엔 약관 화면만 (유튜브에 묻지 않음) */
    private fun show() {
        if (!state.agreed && nav.top != Page.Terms) {
            stopLogin()
            nav.reset(Page.Terms)
            scrolls.clear()
        }
        syncSignIn()
        feedsOf(nav.top).forEach { loader.load(it) }
        render()
        if (nav.top == Page.Login && login == LoginState.Starting && loginJob == null) startLogin()
    }

    private fun render() {
        // 홈에서 '전체'가 아닌 칩이면 뒤로 = '전체'로 (유튜브 앱처럼) — 닫지 않는다
        back.isEnabled = nav.depth > 1 || (nav.top as? Page.Home)?.chip.let { it != null && it != HomeChip.ALL }
        // 오른쪽으로 쓸기도 한 화면씩 뒤로 (홈 '전체'에서는 시스템이 닫는다)
        ui.swipeBack = if (back.isEnabled) back::handleOnBackPressed else null
        if (opening) {
            ui.showStatus(getString(openingText))
            return
        }
        val now = System.currentTimeMillis()
        ui.rows = when (val p = nav.top) {
            is Page.Home -> {
                val (feed, load, more) = feeds.home(p.chip, now)
                TubeRows.home(this, TubeRows.HomeData(p.chip, feed, load, more, hasUpdate(), now))
            }
            Page.Shorts -> feeds.shorts(now).let { (list, load) -> TubeRows.shorts(this, list, load) }
            Page.Find -> TubeRows.find(this, state.searches)
            is Page.Search -> TubeRows.search(this, p.query, loader.slot(Feed.Search(p.query)), now)
            is Page.Subscriptions -> TubeRows.subscriptions(
                this, account(), p.chip, loader.slot(Feed.SubsChannels), loader.slot(Feed.SubsFeed),
                subsSlot(p, SubsChip.SHORTS), subsSlot(p, SubsChip.LIVE), now,
            )
            Page.SubsChannels -> TubeRows.subsChannels(this, loader.slot(Feed.SubsChannels))
            Page.Library -> TubeRows.library(
                this, account(), state.played(now), recs = MixHarvests.order(state.harvests(now), Int.MAX_VALUE).size,
                dismissed = state.dismissedVideos(now).size + state.dismissedChannels(now).size, update = hasUpdate(), now = now,
            )
            Page.History -> TubeRows.history(this, state.played(now), now)
            Page.Liked -> TubeRows.liked(this, loader.slot(Feed.Liked), now)
            Page.MyPlaylists -> TubeRows.myPlaylists(this, loader.slot(Feed.MyPlaylists))
            is Page.Channel -> TubeRows.channel(
                this, p.title, p.section, loader.slot(Feed.ChannelInfo(p.id)),
                loader.slot(feeds.channelList(p)), loader.slot(Feed.ChannelPlaylists(p.id)), now,
            )
            is Page.Playlist -> TubeRows.playlist(this, p.title, p.id, p.own, loader.slot(Feed.PlaylistVideos(p.id, p.own)), now, p.private)
            is Page.Menu -> TubeRows.menu(this, p.video, inHistory = state.played(now).any { it.video.id == p.video.id })
            Page.Login -> TubeRows.login(this, login)
            Page.Account -> TubeRows.account(this, account())
            is Page.Handoff -> TubeRows.handoff(this, p.handoff)
            Page.Terms -> TubeRows.terms(this, state.agreed, privacy = TubeLinks.privacyUrl(BuildConfig.UPDATE_REPO) != null)
        }
    }

    private fun hasUpdate() = runCatching { UpdateManager.hasUpdate(this) }.getOrDefault(false)

    /** 구독 화면의 Shorts·실시간 칩이 받은 것 (그 칩을 받는 중이 아니면 null) */
    private fun subsSlot(p: Page.Subscriptions, chip: SubsChip) =
        feedsOf(p).firstOrNull { (chip == SubsChip.SHORTS && it is Feed.SubsShorts) || (chip == SubsChip.LIVE && it is Feed.SubsLive) }?.let(loader::slot)

    private fun spot(): Spot? {
        val lm = ui.list.layoutManager as? LinearLayoutManager ?: return null
        val rows = ui.rows
        val pos = lm.findFirstVisibleItemPosition().takeIf { it in rows.indices } ?: return null
        val header = (pos downTo 0).firstOrNull { rows[it] is Row.Header }
        // 줄의 바깥 여백까지 친 위치 (되돌릴 때 scrollToPositionWithOffset 이 쓰는 기준과 같게)
        val helper = OrientationHelper.createVerticalHelper(lm)
        val offset = lm.findViewByPosition(pos)?.let { helper.getDecoratedStart(it) - helper.startAfterPadding } ?: 0
        return Spot((header?.let { rows[it] } as? Row.Header)?.title, if (header == null) pos else pos - header, pos, offset)
    }

    /** 보던 자리로 (없으면 맨 위). 그 구역이 그대로 있으면 구역을 기준으로 */
    private fun restore(spot: Spot?) {
        val lm = ui.list.layoutManager as? LinearLayoutManager
        val rows = ui.rows
        if (spot == null || lm == null || rows.isEmpty()) {
            ui.list.scrollToPosition(0)
            return
        }
        val header = spot.section?.let { t -> rows.indexOfFirst { it is Row.Header && it.title == t } }?.takeIf { it >= 0 }
        val pos = (if (header != null) header + spot.inSection else spot.pos).coerceIn(0, rows.lastIndex)
        lm.scrollToPositionWithOffset(pos, spot.offset)
    }

    /** 새 화면으로 (맨 위부터) */
    private fun go(p: Page) {
        if (p == nav.top) return
        if (p != Page.Shorts) shortsAutoAt = 0L
        spot()?.let { scrolls[nav.top] = it }
        if (nav.top == Page.Login) stopLogin()
        syncSignIn()
        if (p == nav.top) {
            show()
            return
        }
        nav.push(p)
        if (p == Page.Login) login = LoginState.Starting
        show()
        ui.list.scrollToPosition(0)
    }

    /** 한 화면 뒤로 (그 화면에서 보던 자리로). 홈에서 '전체'가 아닌 칩이면 먼저 '전체'로, 홈이면 닫는다 */
    private fun goBack() {
        if (nav.top == Page.Login) stopLogin()
        shortsAutoAt = 0L
        val top = nav.top
        if (top is Page.Home && top.chip != HomeChip.ALL) {
            pickChip(TubeRows.BAR_HOME, HomeChip.ALL.name)
            return
        }
        if (!nav.back()) {
            finish()
            return
        }
        show()
        restore(scrolls.remove(nav.top))
    }

    /** 메뉴를 닫고 그 아래 목록의 보던 자리로 */
    private fun closeMenu() {
        if (nav.top !is Page.Menu) return
        nav.back()
        show()
        restore(scrolls.remove(nav.top))
    }

    /**
     * 메뉴에서 다른 화면으로: 메뉴 자리에 연다 (뒤로 가면 메뉴가 아니라 원래 목록의 보던 자리).
     * 바로 아래가 이미 그 화면(같은 채널)이면 메뉴만 닫는다
     */
    private fun replaceMenu(p: Page) {
        nav.back()
        syncSignIn()
        val same = nav.top == p || (p is Page.Channel && (nav.top as? Page.Channel)?.id == p.id)
        if (same) {
            show()
            restore(scrolls.remove(nav.top))
            return
        }
        nav.push(p)
        show()
        ui.list.scrollToPosition(0)
    }

    /** 홈으로 (고른 칩 그대로, 맨 위부터) */
    private fun goHome() {
        stopLogin()
        shortsAutoAt = 0L
        nav.reset(nav.bottom as? Page.Home ?: Page.Home())
        scrolls.clear()
        show()
        ui.list.scrollToPosition(0)
    }

    /**
     * 맨 위 탭: 홈 = 홈으로(이미 홈이면 맨 위로), 나머지 = 홈 위에 그 화면 하나 (탭끼리는 자리만 바꿔 뒤로 = 홈).
     * Shorts 는 준비되는 대로 Shorts 재생 화면을 바로 연다 (이미 Shorts 면 지금)
     */
    private fun pickTab(tab: TubeTab) {
        val target = when (tab) {
            TubeTab.HOME -> return goHome()
            TubeTab.SHORTS -> Page.Shorts
            TubeTab.SUBS -> Page.Subscriptions()
            TubeTab.YOU -> Page.Library
        }
        val top = nav.top
        if (top == target || (target is Page.Subscriptions && top is Page.Subscriptions)) {
            if (target == Page.Shorts) playShortsFromTab() else ui.list.scrollToPosition(0)
            return
        }
        // 0 은 '열지 않음'이라 켠 직후 시계(0)도 1 로
        if (target == Page.Shorts) shortsAutoAt = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        if (top.tab != null && top !is Page.Home) {
            // 탭 화면끼리: 자리만 바꾼다 (뒤로 = 홈)
            spot()?.let { scrolls[top] = it }
            nav.swapTop(target)
            syncSignIn()
            show()
            ui.list.scrollToPosition(0)
        } else {
            go(target)
        }
        maybeOpenShorts()
    }

    /**
     * 칩 줄에서 칩을 고름: 그 화면 자리만 바뀐다 (뒤로 가기에 쌓이지 않음). 칩 줄이 맨 위에 오게 —
     * 바뀐 목록을 위에서부터 보게 (유튜브 앱처럼 칩 줄은 그대로 보임)
     */
    private fun pickChip(bar: String, id: String) {
        val top = nav.top
        val next = when {
            bar == TubeRows.BAR_HOME && top is Page.Home -> HomeChip.entries.firstOrNull { it.name == id }?.let { Page.Home(it) }
            bar == TubeRows.BAR_SUBS && top is Page.Subscriptions -> SubsChip.entries.firstOrNull { it.name == id }?.let { Page.Subscriptions(it) }
            bar == TubeRows.BAR_CHANNEL && top is Page.Channel -> ChannelTab.entries.firstOrNull { it.name == id }?.let { top.copy(section = it) }
            else -> null
        } ?: return
        if (next == top) return
        nav.swapTop(next)
        show()
        val at = ui.rows.indexOfFirst { it is Row.ChipBar }
        (ui.list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(at.coerceAtLeast(0), 0)
    }

    private fun showHandoff(h: com.mywatch.kakaomap.tube.Handoff) {
        shortcut = null
        closeJob?.cancel()
        // 이미 본 동영상이면 워치에 저장된 진짜 이름·길이로 (링크의 제목은 웹 페이지가 붙인 것)
        val known = state.played().firstOrNull { it.video.id == h.video.id }?.video
        go(Page.Handoff(if (known != null) h.copy(video = known) else h))
    }

    /** 폰 유튜브에서 그 동영상을 그 시각부터 (믹스·재생목록이었으면 그대로) — 열리면 기록하고 앞 화면으로 */
    private fun doHandoff(h: com.mywatch.kakaomap.tube.Handoff) {
        openOnPhone(TubeLinks.watchUrl(h.video.id, h.mix, h.startSec, h.list), R.string.tube_opened_on_phone) {
            state.addPlayed(h.video.copy(title = h.video.title.ifBlank { getString(R.string.tube_handoff_untitled) }))
            TubeTileService.refresh(this)
            // 그 사이 재생 화면에서 또 넘겨 왔으면 그것은 남겨 둔다 (다시 묻도록)
            if ((nav.top as? Page.Handoff)?.handoff == h) goBack()
        }
    }

    /** 재생 화면이 열리는지 미리 확인 — 동영상을 누를 때 기다리지 않게 */
    private fun prewarm() {
        if (WatchPlayer.pageBase() != null && WatchPlayer.needsCheck(this)) lifecycleScope.launch { WatchPlayer.prewarm() }
    }

    // ---------------- 누르기 ----------------

    private fun onRow(row: Row) {
        if (opening) return
        when (row) {
            is Row.VideoCard -> play(PlayRequest(row.video, row.queue))
            is Row.ChannelItem -> go(Page.Channel(row.channel.id, row.channel.title))
            is Row.PlaylistItem -> go(
                Page.Playlist(row.playlist.id, row.playlist.title, row.own, private = row.own && row.playlist.privacy !in OPEN_PLAYLISTS),
            )
            is Row.ShortCard -> playShorts(row.list, row.index)
            is Row.Action -> onAction(row.id)
            else -> Unit
        }
    }

    private fun onLongRow(row: Row) {
        if (row is Row.VideoCard && nav.top !is Page.Menu && !opening) go(Page.Menu(row.video))
    }

    private fun onAction(id: String) {
        val top = nav.top
        when {
            id == TubeRows.A_UPDATE -> startActivity(UpdateActivity.updateNowIntent(this))
            id.startsWith(TAB) -> TubeTab.entries.firstOrNull { it.name == id.removePrefix(TAB) }?.let(::pickTab)
            id.startsWith(CHIP) -> id.removePrefix(CHIP).split(':', limit = 2).takeIf { it.size == 2 }?.let { (bar, chip) -> pickChip(bar, chip) }
            id == TubeRows.A_SEARCH -> openFind()
            id == TubeRows.A_INPUT -> openInput()
            id == TubeRows.A_CLEAR_SEARCHES -> {
                state.clearSearches()
                render()
            }
            id == TubeRows.A_SHORTS_PLAY -> playShortsFromTab()
            id == TubeRows.A_SUBS_CHANNELS -> go(Page.SubsChannels)
            id.startsWith(TubeRows.A_QUERY) -> search(id.removePrefix(TubeRows.A_QUERY))
            id == TubeRows.A_SUBS || id == TubeRows.A_SUBS_MORE -> pickTab(TubeTab.SUBS)
            id == TubeRows.A_LIBRARY -> pickTab(TubeTab.YOU)
            id == TubeRows.A_HISTORY -> go(Page.History)
            id == TubeRows.A_LIKED -> go(Page.Liked)
            id == TubeRows.A_MY_PLAYLISTS -> go(Page.MyPlaylists)
            id == TubeRows.A_ACCOUNT -> go(Page.Account)
            id == TubeRows.A_MORE -> moreFeed(top)?.let(loader::more)
            id == TubeRows.A_MORE_PLAYLISTS -> (top as? Page.Channel)?.let { loader.more(Feed.ChannelPlaylists(it.id)) }
            id == TubeRows.A_NOT_INTERESTED -> (top as? Page.Menu)?.let {
                state.dismissVideo(it.video.id)
                goBack()
                toast(getString(R.string.tube_dismissed))
            }
            id == TubeRows.A_NO_CHANNEL -> (top as? Page.Menu)?.video?.channelId?.let { cid ->
                state.dismissChannel(cid)
                goBack()
                toast(getString(R.string.tube_channel_dismissed))
            }
            id == TubeRows.A_RETRY -> feedsOf(top).filter { loader.slot(it).error != null }.forEach { loader.load(it, force = true) }
            id == TubeRows.A_CLEAR -> {
                // 본 동영상·검색어·믹스 추천·관심 없음·본 Shorts — 받아 둔 추천 정보도 메모리에서 버린다
                state.clear()
                loader.forget("recs/", null)
                loader.forget("watched/", null)
                TubeTileService.refresh(this)
                show()
                toast(getString(R.string.tube_cleared))
            }
            id == TubeRows.A_PHONE_SEARCH -> (top as? Page.Search)?.let { openOnPhone(TubeLinks.searchUrl(it.query), R.string.tube_opened_search_on_phone) {} }
            id == TubeRows.A_PLAY_ALL -> (top as? Page.Playlist)?.let(::playAll)
            id == TubeRows.A_PLAY -> (top as? Page.Menu)?.let { play(PlayRequest(it.video, Queue.Single)) }
            id == TubeRows.A_CHANNEL -> (top as? Page.Menu)?.video?.let { v ->
                v.channelId?.let { cid -> replaceMenu(Page.Channel(cid, v.channel)) }
            }
            id == TubeRows.A_ON_PHONE -> (top as? Page.Menu)?.let { m -> playOnPhone(PlayRequest(m.video, Queue.Single)) }
            id == TubeRows.A_FORGET -> (top as? Page.Menu)?.let {
                state.removePlayed(it.video.id)
                TubeTileService.refresh(this)
                goBack()
            }
            id == TubeRows.A_LOGIN -> if (top == Page.Login) startLogin() else go(Page.Login)
            id == TubeRows.A_LOGIN_PHONE -> (login as? LoginState.Waiting)?.let(::openLoginOnPhone)
            id == TubeRows.A_LOGOUT -> signOut()
            id == TubeRows.A_PERMISSIONS -> openOnPhone(PERMISSIONS_URL, R.string.tube_opened_phone_page) {}
            id == TubeRows.A_HANDOFF -> (top as? Page.Handoff)?.let { doHandoff(it.handoff) }
            id == TubeRows.A_CANCEL -> goBack()
            id == TubeRows.A_AGREE -> {
                state.agree()
                goHome()
            }
            id == TubeRows.A_TERMS -> go(Page.Terms)
            id == TubeRows.A_YT_TERMS -> openOnPhone(TubeLinks.YOUTUBE_TERMS, R.string.tube_opened_phone_page) {}
            id == TubeRows.A_PRIVACY -> TubeLinks.privacyUrl(BuildConfig.UPDATE_REPO)?.let { openOnPhone(it, R.string.tube_opened_phone_page) {} }
        }
    }

    // ---------------- 검색 ----------------

    /** Wear OS 기본 입력 화면(음성·키보드·손글씨). 없으면 글자 입력 창 */
    private fun openInput() {
        val remoteInput = RemoteInput.Builder(KEY_QUERY).setLabel(getString(R.string.tube_prompt)).build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        RemoteInputIntentHelper.putTitleExtra(intent, getString(R.string.tube_search))
        try {
            inputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = getString(R.string.tube_prompt)
            }
            AlertDialog.Builder(this)
                .setView(edit)
                .setPositiveButton(android.R.string.ok) { _, _ -> edit.text?.toString()?.let(::search) }
                .show()
        }
    }

    /** 검색 화면(최근 검색어)을 열고 곧바로 말·키보드 입력 (유튜브 앱처럼 검색창에 바로) */
    private fun openFind() {
        if (nav.top != Page.Find) go(Page.Find)
        openInput()
    }

    /** 검색: 검색 화면에서 했으면 그 자리를 결과로 바꾼다 (뒤로 = 검색 전 화면) */
    private fun search(text: String) {
        val q = SearchHistory.clean(text)
        if (q.isEmpty()) return
        state.addSearch(q)
        // 같은 검색을 다시 하면(3시간 안) 기억한 결과로 — 검색은 하루 100번까지라 아낀다
        when (nav.top) {
            Page.Search(q) -> show()
            Page.Find -> {
                nav.swapTop(Page.Search(q))
                show()
                ui.list.scrollToPosition(0)
            }
            else -> go(Page.Search(q))
        }
    }

    // ---------------- 로그인 ----------------

    private fun startLogin() {
        stopLogin()
        login = LoginState.Starting
        render()
        loginJob = lifecycleScope.launch {
            val code = try {
                withContext(Dispatchers.IO) { auth.start() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("유튜브·로그인", e)
                login = LoginState.Failed(e.message ?: getString(R.string.tube_phone_failed))
                render()
                return@launch
            }
            login = LoginState.Waiting(code)
            render()
            waitForApproval(code)
        }
    }

    /**
     * '폰에서 주소 열기': 코드는 그대로 두고(폰에 넣으려면 봐야 한다) 코드 아래 한 줄로 '여는 중…' → '열었어요'.
     * 예전엔 화면 전체가 '여는 중…'이 됐다가 큰 확인 그림이 코드를 덮었다
     */
    private fun openLoginOnPhone(w: LoginState.Waiting) {
        if (w.phone == TubeRows.PhoneStep.SENDING) return
        login = w.copy(phone = TubeRows.PhoneStep.SENDING)
        render()
        lifecycleScope.launch {
            val result = phone.open(w.code.verificationUrl)
            val now = login as? LoginState.Waiting ?: return@launch
            if (now.code != w.code) return@launch
            login = now.copy(
                phone = when (result) {
                    PhoneOpener.Result.OPENED -> TubeRows.PhoneStep.OPENED
                    PhoneOpener.Result.NO_PHONE -> TubeRows.PhoneStep.NO_PHONE
                    PhoneOpener.Result.FAILED -> TubeRows.PhoneStep.FAILED
                },
            )
            if (nav.top == Page.Login) render()
        }
    }

    /** 화면이 다시 보이면 같은 코드로 이어서 기다린다 (꺼지기 직전 보낸 물음으로 이미 로그인됐으면 끝) */
    private fun resumeLogin() {
        val w = login as? LoginState.Waiting ?: return
        if (nav.top != Page.Login || loginJob?.isActive == true) return
        if (signedIn) {
            signedInNow()
            return
        }
        loginJob = lifecycleScope.launch { waitForApproval(w.code) }
    }

    private fun stopLogin() {
        loginJob?.cancel()
        loginJob = null
    }

    /**
     * 폰에서 허용할 때까지 [DeviceCode.intervalSec] 마다 묻는다 (천천히 하라면 더 천천히 — 화면이 꺼졌다 켜져도 늘린 간격 그대로).
     * 잠깐 끊기거나 구글이 잠깐 안 되면 계속 기다린다
     */
    private suspend fun waitForApproval(code: DeviceCode) {
        var interval = (login as? LoginState.Waiting)?.takeIf { it.code == code }?.intervalSec ?: code.intervalSec
        while (true) {
            delay(interval * 1000L)
            // 그 사이 로그인됨 (화면이 꺼지기 직전 보낸 물음이 끝남) — 쓰인 코드로 또 묻지 않는다
            if (signedIn) {
                signedInNow()
                return
            }
            val r = try {
                withContext(Dispatchers.IO) { auth.poll(code) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NetworkException) {
                continue
            } catch (e: Exception) {
                endLogin(LoginState.Failed(e.message ?: getString(R.string.tube_phone_failed)))
                return
            }
            when (r) {
                Poll.Pending -> Unit
                is Poll.SlowDown -> {
                    interval = com.mywatch.kakaomap.tube.TubeAuth.nextInterval(interval, r)
                    (login as? LoginState.Waiting)?.let { if (it.code == code) login = it.copy(intervalSec = interval) }
                }
                Poll.Denied -> {
                    endLogin(LoginState.Denied)
                    return
                }
                Poll.Expired -> {
                    endLogin(LoginState.Expired)
                    return
                }
                Poll.Granted -> {
                    signedInNow()
                    return
                }
            }
        }
    }

    /**
     * 기다리기를 끝낸다 — 그런데 그 사이 앞서 보낸 물음으로 이미 로그인됐으면(같은 코드라 다음 물음은 거절·만료로 옴)
     * 거절·만료·오류가 아니라 로그인된 것
     */
    private fun endLogin(state: LoginState) {
        if (signedIn) {
            signedInNow()
            return
        }
        login = state
        render()
    }

    /** 로그인 끝: 로그인 화면은 닫고 보던 화면으로 — 이제 내 데이터도 받는다 */
    private fun signedInNow() {
        loginJob = null
        login = LoginState.Starting
        // 다른 계정이었을 수 있다 — 받아 둔 내 데이터는 버리고 새로
        loader.clearMine()
        wasSignedIn = true
        loader.load(Feed.Account, force = true)
        if (nav.top == Page.Login) {
            nav.back()
            show()
            restore(scrolls.remove(nav.top))
        } else {
            show()
        }
        toast(getString(R.string.tube_login_done))
    }

    /**
     * 로그아웃: 워치에서 바로 지우고(화면도 바로), 구글에 철회는 뒤에서. 받아 둔 내 데이터·내 화면도 지운다.
     * 철회를 못 보내면(인터넷 없음) 인터넷이 될 때 다시 ([TubeRevokeWorker])
     */
    private fun signOut() {
        val tokens = auth.forget()
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            if (!auth.revoke(tokens) || auth.hasPendingRevokes) TubeRevokeWorker.enqueue(app)
        }
        loader.clearMine()
        if (nav.top == Page.Account) nav.back()
        nav.dropMine()
        show()
        toast(getString(R.string.tube_logged_out))
    }

    // ---------------- 재생 ----------------

    /**
     * '모두 재생': 공개·일부 공개 목록은 유튜브가 끝까지 이어 준다. 비공개 내 재생목록은 재생 화면이 목록을 못 열어
     * 동영상 번호로 넘기는데, 받은 첫 쪽(20개)만 이어 틀고 멈췄다(점검 C8) — 재생 화면이 받는 만큼(50개) 더 받아서 튼다
     */
    private fun playAll(p: Page.Playlist) {
        val feed = Feed.PlaylistVideos(p.id, p.own)
        val page = loader.slot(feed).page ?: return
        val first = page.videos.firstOrNull { it.embeddable } ?: page.videos.firstOrNull() ?: return
        if (!(p.own && p.private)) {
            play(PlayRequest(first, Queue.Playlist(p.id)))
            return
        }
        if (page.next == null || page.videos.count { it.embeddable } >= TubeLinks.MAX_QUEUE) {
            playPrivate(page, p.id)
            return
        }
        if (opening) return
        opening = true
        openingText = R.string.tube_opening_list
        render()
        val at = nav.top
        lifecycleScope.launch {
            var all: TubePage = page
            try {
                // 끝까지 내려 다음 쪽을 저절로 받는 중이면 그것부터 기다린다 (같은 쪽을 또 받지 않게 — 사용량)
                withTimeoutOrNull(MORE_WAIT_MS) { loader.settled(feed) }
                all = loader.slot(feed).page ?: page
                for (k in 0 until PLAY_ALL_MORE_PAGES) {
                    val token = all.next ?: break
                    if (all.videos.count { it.embeddable } >= TubeLinks.MAX_QUEUE) break
                    all = TubePage.append(all, backend.fetch(feed, token, null).page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("유튜브·모두 재생", e)   // 받은 만큼만 튼다
            } finally {
                opening = false
            }
            loader.offer(feed, all)
            // 받는 사이 나갔거나 뒤로 갔으면 받은 것만 목록에 두고 틀지 않는다 (화면만 꺼졌으면 다시 볼 때)
            whenWatching(at) { playPrivate(all, p.id) }
        }
    }

    /**
     * 기다리던 일(더 받기·확인)을 마치고 틀 때: 시작한 화면([at])을 그대로 보고 있으면 바로, 손목을 내려 화면만 꺼졌으면
     * 다시 볼 때([DEFERRED_PLAY_MS] 안 — 보일 때만 재생), 떠났거나(홈·다른 앱) 뒤로 갔으면 틀지 않는다 (타일에서 온 일도 끝)
     */
    private fun whenWatching(at: Page, play: () -> Unit) {
        when {
            left || isFinishing || nav.top != at -> {
                shortcut = null
                closeJob?.cancel()
                closeJob = null
                render()
            }
            asleep -> {
                deferred = SystemClock.elapsedRealtime() to play
                render()
            }
            else -> play()
        }
    }

    /** 화면이 꺼진 사이 끝난 '틀기'를 다시 볼 때 연다 (너무 오래 지났으면 잊는다) */
    private fun runDeferred() {
        val (at, play) = deferred ?: return
        deferred = null
        if (SystemClock.elapsedRealtime() - at <= DEFERRED_PLAY_MS) play() else shortcut = null
    }

    /** 비공개 내 재생목록을 받은 동영상 번호로 (폰에선 그 재생목록으로) */
    private fun playPrivate(page: TubePage, id: String) {
        val videos = page.videos
        val i = videos.indexOfFirst { it.embeddable }.takeIf { it >= 0 } ?: 0
        val next = (TubePlay.fromList(videos, i).queue as? Queue.Ids)?.next.orEmpty()
        play(PlayRequest(videos[i], Queue.Ids(next, list = id)))
    }

    /**
     * 틀기: 앱 안 재생 화면 → (엔진이 최근에 죽었으면) 워치 인터넷 앱 → 폰 유튜브 ([TubePlay.route]).
     * 재생 화면에 닿지 못하면 알리고 폰으로
     */
    private fun play(req: PlayRequest) {
        if (opening) return
        val guard = PlayerGuard.of(this)
        // 재생 화면 프로세스가 네이티브로 죽었으면 시스템 기록에 남는다 — 다시 누르면 또 죽는 고리를 끊는다
        guard.checkSystemExits(this)
        val crashed = guard.crashedRecently()
        val route = TubePlay.route(
            req.video,
            page = WatchPlayer.pageBase() != null,
            engine = engineCheck(),
            engineCrashed = crashed,
            browser = WatchPlayer.available(this),
        )
        if (route == PlayRoute.PHONE) playOnPhone(req) else openOnWatch(req, route, crashed)
    }

    private fun openOnWatch(req: PlayRequest, route: PlayRoute, crashed: Boolean) {
        val base = WatchPlayer.pageBase() ?: return playOnPhone(req)
        opening = true
        openingText = R.string.tube_opening
        render()
        val at = nav.top
        lifecycleScope.launch {
            val reachable = try {
                WatchPlayer.reachable()
            } finally {
                opening = false
            }
            // 확인하는 사이 나갔거나 뒤로 갔으면 틀지 않는다 (안 보는 사이 저절로 재생되지 않게 — 화면만 꺼졌으면 다시 볼 때)
            whenWatching(at) { startPlayer(req, route, crashed, base, reachable) }
        }
    }

    private fun startPlayer(req: PlayRequest, route: PlayRoute, crashed: Boolean, base: String, reachable: Boolean) {
        if (!reachable) {
            render()
            toast(getString(R.string.tube_watch_unreachable))
            playOnPhone(req)
            return
        }
        val now = System.currentTimeMillis()
        val link = if (route == PlayRoute.IN_APP) {
            TubePlayerActivity.intent(this@TubeActivity, TubeLinks.playerUrl(base, req.video, req.queue, nonce = now, inApp = true))
        } else {
            WatchPlayer.intent(this@TubeActivity, req.video, req.queue, now)
        }
        val ok = link != null && try {
            startActivity(link)
            true
        } catch (e: ActivityNotFoundException) {
            ErrorLog.record("유튜브·워치에서 열기", e)
            false
        } catch (e: SecurityException) {
            ErrorLog.record("유튜브·워치에서 열기", e)
            false
        }
        if (!ok) {
            render()
            playOnPhone(req)
            return
        }
        if (route == PlayRoute.BROWSER && crashed) toast(getString(R.string.tube_engine_browser))
        played(req.video)
        // 인터넷 앱으로 틀었고 타일에서 바로 온 일이면 이 앱은 닫는다 (재생 화면이 앞에). 앱 안 재생 화면은 이 앱 위에 있다
        if (shortcut != null) {
            shortcut = null
            if (route == PlayRoute.BROWSER) finishAndRemoveTask()
        }
    }

    // ---------------- Shorts ----------------

    /**
     * Shorts 탭의 '▶ Shorts 보기'·탭을 다시 누름: 지금 Shorts 목록을 처음부터 — 워치에서 틀 수 있는 첫 것부터
     * (하나도 없으면 첫 것을 폰에서)
     */
    private fun playShortsFromTab() {
        val (list, _) = feeds.shorts(System.currentTimeMillis())
        if (list.isNotEmpty()) playShorts(list, firstPlayable(list) ?: 0)
    }

    /** 워치에서 틀 수 있는 첫 Shorts 의 자리 (없으면 null) */
    private fun firstPlayable(list: List<TubeVideo>): Int? = list.indexOfFirst { it.embeddable }.takeIf { it >= 0 }

    /**
     * Shorts 탭으로 들어왔고 Shorts 가 준비됐으면 한 번 바로 연다 — 보고 있을 때만 (손목을 내렸으면 다시 볼 때, 떠났으면 안 엶),
     * 들어온 지 [SHORTS_AUTO_MS] 안에만. 아직 받는 중이면 받는 대로 다시 불린다
     */
    private fun maybeOpenShorts() {
        val at = shortsAutoAt
        if (at == 0L || nav.top != Page.Shorts || opening || isFinishing) return
        if (SystemClock.elapsedRealtime() - at > SHORTS_AUTO_MS) {
            shortsAutoAt = 0L
            return
        }
        if (left || asleep || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val (list, load) = feeds.shorts(System.currentTimeMillis())
        // 워치에서 틀 수 있는 것부터 — 하나도 없으면 저절로 열지 않는다 (폰이 저절로 열리지 않게, 누르면 폰으로)
        val start = firstPlayable(list)
        if (start == null) {
            if (list.isNotEmpty() && !load.loading) shortsAutoAt = 0L
            return
        }
        shortsAutoAt = 0L
        playShorts(list, start)
    }

    /**
     * Shorts 재생 화면(web/tube/shorts.html, 한 장씩 넘기기)으로 [list] 를 [index] 번째부터 — 앱 안 → 워치 인터넷 앱 → 폰 유튜브의 Shorts
     * ([TubePlay.route] 와 같은 차례). 재생 화면에 닿지 못하면 알리고 폰으로
     */
    private fun playShorts(list: List<TubeVideo>, index: Int) {
        if (opening || list.isEmpty()) return
        val video = list[index.coerceIn(0, list.lastIndex)]
        val guard = PlayerGuard.of(this)
        guard.checkSystemExits(this)
        val crashed = guard.crashedRecently()
        val route = TubePlay.route(
            video, page = WatchPlayer.pageBase() != null, engine = engineCheck(), engineCrashed = crashed, browser = WatchPlayer.available(this),
        )
        val base = WatchPlayer.pageBase()
        if (route == PlayRoute.PHONE || base == null) {
            shortsOnPhone(video)
            return
        }
        // 워치에서 못 트는 것은 빼고 (고른 것은 그대로 — 막혀 있으면 재생 화면이 알리고 넘긴다)
        val playable = list.filterIndexed { i, v -> v.embeddable || v == video }
        val at = playable.indexOf(video)
        opening = true
        openingText = R.string.tube_opening
        render()
        val page = nav.top
        lifecycleScope.launch {
            val reachable = try {
                WatchPlayer.reachable()
            } finally {
                opening = false
            }
            whenWatching(page) { startShorts(playable, at, route, crashed, base, reachable) }
        }
    }

    private fun startShorts(list: List<TubeVideo>, at: Int, route: PlayRoute, crashed: Boolean, base: String, reachable: Boolean) {
        val video = list[at]
        if (!reachable) {
            render()
            toast(getString(R.string.tube_watch_unreachable))
            shortsOnPhone(video)
            return
        }
        val url = TubeLinks.shortsUrl(base, list.map { it.id }, at, nonce = System.currentTimeMillis(), inApp = route == PlayRoute.IN_APP)
        val link = if (route == PlayRoute.IN_APP) TubePlayerActivity.intent(this, url) else WatchPlayer.browserIntent(this, url)
        val ok = link != null && try {
            startActivity(link)
            true
        } catch (e: ActivityNotFoundException) {
            ErrorLog.record("유튜브·Shorts 열기", e)
            false
        } catch (e: SecurityException) {
            ErrorLog.record("유튜브·Shorts 열기", e)
            false
        }
        if (!ok) {
            render()
            shortsOnPhone(video)
            return
        }
        // 인터넷 앱의 재생 화면은 본 Shorts 를 알려 주지 않는다 — 처음 것만이라도
        if (route == PlayRoute.BROWSER) {
            state.markSeen(video.id)
            if (crashed) toast(getString(R.string.tube_engine_browser))
        }
        render()
    }

    private fun shortsOnPhone(video: TubeVideo) =
        openOnPhone(TubeLinks.phoneShortsUrl(video.id), R.string.tube_opened_on_phone) { state.markSeen(video.id) }

    /**
     * 재생 화면에서 채널 이름을 누름: 그 동영상의 채널 화면 — 워치 기록에 그 동영상이 있으면 바로, 없으면 채널을 찾아서 (1단위).
     * 찾는 사이 다른 화면으로 갔으면 열지 않는다
     */
    private fun openChannelOf(video: String) {
        shortcut = null
        closeJob?.cancel()
        closeJob = null
        state.played().firstOrNull { it.video.id == video }?.video?.let { v ->
            v.channelId?.let {
                go(Page.Channel(it, v.channel))
                return
            }
        }
        if (opening) return
        opening = true
        openingText = R.string.tube_opening
        render()
        val at = nav.top
        lifecycleScope.launch {
            val ch = try {
                backend.fetch(Feed.VideoChannel(video), null, null).page.channels.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("유튜브·채널 찾기", e)
                null
            } finally {
                opening = false
            }
            when {
                ch == null -> {
                    render()
                    toast(getString(R.string.tube_channel_not_found))
                }
                nav.top == at && !isFinishing -> go(Page.Channel(ch.id, ch.title))
                else -> render()
            }
        }
    }

    /** 폰 유튜브에서 (믹스·재생목록이면 그대로) */
    private fun playOnPhone(req: PlayRequest) {
        val list = (req.queue as? Queue.Playlist)?.id ?: (req.queue as? Queue.Ids)?.list
        openOnPhone(TubeLinks.watchUrl(req.video.id, req.queue == Queue.Mix, list = list), R.string.tube_opened_on_phone) { played(req.video) }
    }

    /** 틀었음: 기록하고, 메뉴에서 틀었으면 메뉴는 닫는다 (재생 화면에서 돌아오면 목록의 보던 자리가 보이게) */
    private fun played(video: TubeVideo) {
        state.addPlayed(video)
        TubeTileService.refresh(this)
        if (nav.top is Page.Menu) closeMenu() else render()
    }

    private fun openOnPhone(url: String, @StringRes done: Int, onOpened: () -> Unit) {
        if (opening) return
        opening = true
        openingText = R.string.tube_sending
        render()   // 바로 '폰 유튜브에서 여는 중…' — 누른 게 먹었는지 보이게
        lifecycleScope.launch {
            val result = try {
                phone.open(url)
            } finally {
                opening = false
            }
            if (result == PhoneOpener.Result.OPENED) onOpened()
            render()
            when (result) {
                PhoneOpener.Result.OPENED -> confirm(getString(done))
                PhoneOpener.Result.NO_PHONE -> failed(R.string.tube_no_phone)
                PhoneOpener.Result.FAILED -> failed(R.string.tube_phone_failed)
            }
        }
    }

    /**
     * '폰에서 열림' 애니메이션 (끝을 기다리지 않는다 — 손목을 내리면 애니메이션이 멈춤).
     * 타일에서 바로 온 일이면 잠깐 보여 주고 닫는다
     */
    private fun confirm(message: String) {
        if (isFinishing || isDestroyed) return
        ConfirmationOverlay()
            .setType(ConfirmationOverlay.OPEN_ON_PHONE_ANIMATION)
            .setMessage(message as CharSequence)
            .showOn(this)
        if (shortcut != null) {
            shortcut = null
            closeJob?.cancel()
            closeJob = lifecycleScope.launch {
                delay(SHORTCUT_CLOSE_MS)
                finishAndRemoveTask()
            }
        }
    }

    /** 실패하면 알리고 화면은 그대로 둔다 (타일에서 왔어도 닫지 않음 — 다시 시도할 수 있게) */
    private fun failed(@StringRes message: Int) {
        shortcut = null
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun toast(message: String?) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message ?: getString(R.string.no_results), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val STATE_NAV = "nav"
        /** 구글 계정 > 서드 파티 연결 (이 앱의 접근 권한을 끊는 곳) */
        private const val PERMISSIONS_URL = "https://myaccount.google.com/connections"
        /** 목록 끝에서 이만큼 안쪽 줄이 보이면 다음 쪽을 저절로 */
        private const val AUTO_MORE_ROWS = 4
        /** 공개 범위가 이것이면 재생 화면이 목록(list=)으로 열 수 있다 */
        private val OPEN_PLAYLISTS = setOf("public", "unlisted")
        /** 비공개 내 재생목록 '모두 재생'에서 더 받는 쪽 수 (20개씩 — 재생 화면이 받는 50개까지) */
        private const val PLAY_ALL_MORE_PAGES = 2
        /** '모두 재생' 전에 저절로 받던 다음 쪽을 기다리는 최대 시간 */
        private const val MORE_WAIT_MS = 15_000L
        /** 손목을 내린 사이 끝난 '틀기'를 다시 볼 때 여는 최대 시간 (그보다 오래면 잊는다) */
        private const val DEFERRED_PLAY_MS = 60_000L
        /** 타일에서 바로 온 일을 마치고 닫기까지 ('폰에서 열림' 애니메이션 길이쯤) */
        private const val SHORTCUT_CLOSE_MS = 1_800L
        /** Shorts 탭으로 들어온 뒤 이만큼 안에 Shorts 가 준비되면 재생 화면을 바로 연다 */
        private const val SHORTS_AUTO_MS = 60_000L
        /** 탭·칩을 누른 줄 (Row.Action 의 id 앞머리 — [Row.Tabs]·[Row.ChipBar]) */
        private const val TAB = "tab:"
        private const val CHIP = "chip:"

        /** 타일의 '검색' — 바로 음성·키보드 입력 */
        const val EXTRA_VOICE = "voice"
        /** 타일의 '믹스 이어 듣기' — 최근 동영상으로 바로 믹스 */
        const val EXTRA_MIX = "mix"

        /** 시험에서 유튜브 대신 */
        @VisibleForTesting
        @Volatile internal var backendOverride: TubeBackend? = null

        @VisibleForTesting
        internal val defaultEngineCheck: () -> Boolean = { TubePlay.engineSupported() }

        /** 이 워치에서 앱 안 엔진을 쓸 수 있는지 (시험에선 바꿔 끼운다) */
        @VisibleForTesting
        @Volatile internal var engineCheck: () -> Boolean = defaultEngineCheck
    }
}
