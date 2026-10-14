package com.mywatch.kakaomap.ui

import android.app.AlertDialog
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.OrientationHelper
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.widget.ConfirmationOverlay
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.tile.TubeTileService
import com.mywatch.kakaomap.tube.DeviceCode
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
import com.mywatch.kakaomap.tube.TubePlay
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.ui.TubeRows.LoginState
import com.mywatch.kakaomap.ui.tube.Feed
import com.mywatch.kakaomap.ui.tube.LiveBackend
import com.mywatch.kakaomap.ui.tube.Page
import com.mywatch.kakaomap.ui.tube.TubeBackend
import com.mywatch.kakaomap.ui.tube.TubeLoader
import com.mywatch.kakaomap.ui.tube.TubeNav
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 워치용 유튜브 앱 (워치 앱 목록의 '유튜브'): 유튜브 앱처럼 홈(이어서 보기·구독 새 동영상·인기 음악·인기 동영상) · 검색(동영상·채널·재생목록) ·
 * 구독 · 보관함(기록·좋아요·내 재생목록) · 채널 · 재생목록. 구글 계정으로 로그인하면 내 구독·좋아요·재생목록 ([TubeAccount] — 코드는 폰에서).
 * 동영상을 누르면 그 동영상부터 목록 그대로 앱 안의 재생 화면([TubePlayerActivity] — 유튜브 공식 플레이어)에서 튼다.
 * 앱 안 엔진이 최근에 죽었으면 워치 인터넷 앱, 올린 사람이 막아 둔 동영상·재생 화면에 닿지 않으면 폰 유튜브로 ([TubePlay.route]).
 * 뒤로(버튼·쓸어 넘기기)는 늘 한 화면씩 ([TubeNav]), 홈에서 뒤로 가면 닫힌다.
 */
class TubeActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var state: TubeState
    private lateinit var loader: TubeLoader
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
    private var visible = false
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
            shortcut == EXTRA_VOICE && nav.top == Page.Home -> finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        state = TubeState(this)
        ui = WearList(this, ::onRow, ::onLongRow, Look.TUBE)
        loader = TubeLoader(lifecycleScope, backendOverride ?: LiveBackend(applicationContext)) { feed ->
            loader.takeMoreError(feed)?.let(::toast)
            if (!isFinishing) render()
        }
        onBackPressedDispatcher.addCallback(this, back)
        ambient = AmbientCover(this, ::ambientSummary)
        wasSignedIn = signedIn
        show()
        if (savedInstanceState == null) handleIntent(intent)
    }

    private fun ambientSummary(): String =
        listOfNotNull(getString(R.string.tube_app_name), state.lastPlayed()?.title).joinToString("\n")

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
        if (nav.top == Page.Home && !opening) render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        resumeLogin()
    }

    override fun onResume() {
        super.onResume()
        // 다녀온 사이 기록·로그인·새 버전이 바뀌었을 수 있다
        if (!opening) show()
        UpdateManager.prefsForListening(this).registerOnSharedPreferenceChangeListener(updateListener)
        UpdateWorker.onAppOpened(this)
        prewarm()
    }

    override fun onPause() {
        UpdateManager.prefsForListening(this).unregisterOnSharedPreferenceChangeListener(updateListener)
        super.onPause()
    }

    override fun onStop() {
        visible = false
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
            intent.dataString?.let(TubeLinks::handoff)?.let(::showHandoff)
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

    /** 이 화면이 받을 것들 */
    private fun feedsOf(p: Page): List<Feed> = when (p) {
        Page.Home -> listOfNotNull(
            Feed.Popular(MUSIC), Feed.Popular(null),
            if (signedIn) Feed.SubsFeed else null, if (signedIn) Feed.Account else null,
        )
        is Page.Search -> listOf(Feed.Search(p.query))
        Page.Subscriptions -> if (signedIn) listOf(Feed.SubsFeed, Feed.SubsChannels) else emptyList()
        Page.Library, Page.Account -> if (signedIn) listOf(Feed.Account) else emptyList()
        Page.Liked -> listOf(Feed.Liked)
        Page.MyPlaylists -> listOf(Feed.MyPlaylists)
        is Page.Chart -> listOf(Feed.Popular(p.category))
        is Page.Channel -> listOf(Feed.ChannelInfo(p.id), Feed.ChannelVideos(p.id), Feed.ChannelPlaylists(p.id))
        is Page.Playlist -> listOf(Feed.PlaylistVideos(p.id, p.own))
        Page.History, is Page.Menu, Page.Login, is Page.Handoff, Page.Terms -> emptyList()
    }

    /** '더 보기'로 이어 받을 것 */
    private fun moreFeed(p: Page): Feed? = when (p) {
        is Page.Search -> Feed.Search(p.query)
        Page.Subscriptions -> Feed.SubsChannels
        Page.Liked -> Feed.Liked
        Page.MyPlaylists -> Feed.MyPlaylists
        is Page.Chart -> Feed.Popular(p.category)
        is Page.Channel -> Feed.ChannelVideos(p.id)
        is Page.Playlist -> Feed.PlaylistVideos(p.id, p.own)
        else -> null
    }

    /** 지금 화면을 받을 것을 받고(없거나 오래됐으면) 그린다. 약관에 동의하기 전엔 약관 화면만 (유튜브에 묻지 않음) */
    private fun show() {
        if (!state.agreed && nav.top != Page.Terms) {
            stopLogin()
            nav.reset(Page.Terms)
            scrolls.clear()
        }
        val nowSignedIn = signedIn
        if (wasSignedIn && !nowSignedIn) {
            loader.clearMine()
            nav.dropMine()
        }
        wasSignedIn = nowSignedIn
        feedsOf(nav.top).forEach { loader.load(it) }
        render()
        if (nav.top == Page.Login && login == LoginState.Starting && loginJob == null) startLogin()
    }

    private fun render() {
        back.isEnabled = nav.depth > 1
        // 오른쪽으로 쓸기도 한 화면씩 뒤로 (홈에서는 시스템이 닫는다)
        ui.swipeBack = if (back.isEnabled) back::handleOnBackPressed else null
        if (opening) {
            ui.showStatus(getString(openingText))
            return
        }
        val now = System.currentTimeMillis()
        ui.rows = when (val p = nav.top) {
            Page.Home -> TubeRows.home(
                this, TubeRows.HomeData(
                    history = state.played(now),
                    searches = state.searches,
                    feed = if (signedIn) loader.slot(Feed.SubsFeed) else null,
                    music = loader.slot(Feed.Popular(MUSIC)),
                    popular = loader.slot(Feed.Popular(null)),
                    account = account(),
                    update = runCatching { UpdateManager.hasUpdate(this) }.getOrDefault(false),
                    now = now,
                ),
            )
            is Page.Search -> TubeRows.search(this, p.query, loader.slot(Feed.Search(p.query)), now)
            Page.Subscriptions -> TubeRows.subscriptions(this, account(), loader.slot(Feed.SubsFeed), loader.slot(Feed.SubsChannels), now)
            Page.Library -> TubeRows.library(this, state.played(now).size, account())
            Page.History -> TubeRows.history(this, state.played(now), now)
            Page.Liked -> TubeRows.liked(this, loader.slot(Feed.Liked), now)
            Page.MyPlaylists -> TubeRows.myPlaylists(this, loader.slot(Feed.MyPlaylists))
            is Page.Chart -> TubeRows.chart(this, p.title, loader.slot(Feed.Popular(p.category)), now)
            is Page.Channel -> TubeRows.channel(
                this, p.title, loader.slot(Feed.ChannelInfo(p.id)), loader.slot(Feed.ChannelVideos(p.id)), loader.slot(Feed.ChannelPlaylists(p.id)), now,
            )
            is Page.Playlist -> TubeRows.playlist(this, p.title, p.id, p.own, loader.slot(Feed.PlaylistVideos(p.id, p.own)), now)
            is Page.Menu -> TubeRows.menu(this, p.video, inHistory = state.played(now).any { it.video.id == p.video.id })
            Page.Login -> TubeRows.login(this, login)
            Page.Account -> TubeRows.account(this, account())
            is Page.Handoff -> TubeRows.handoff(this, p.handoff)
            Page.Terms -> TubeRows.terms(this, state.agreed, privacy = TubeLinks.privacyUrl(BuildConfig.UPDATE_REPO) != null)
        }
    }

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
        spot()?.let { scrolls[nav.top] = it }
        if (nav.top == Page.Login) stopLogin()
        nav.push(p)
        if (p == Page.Login) login = LoginState.Starting
        show()
        ui.list.scrollToPosition(0)
    }

    /** 한 화면 뒤로 (그 화면에서 보던 자리로). 홈이면 닫는다 */
    private fun goBack() {
        if (nav.top == Page.Login) stopLogin()
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

    private fun goHome() {
        stopLogin()
        nav.reset()
        scrolls.clear()
        show()
        ui.list.scrollToPosition(0)
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
            is Row.PlaylistItem -> go(Page.Playlist(row.playlist.id, row.playlist.title, row.own))
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
            id == TubeRows.A_SEARCH -> openInput()
            id.startsWith(TubeRows.A_QUERY) -> search(id.removePrefix(TubeRows.A_QUERY))
            id == TubeRows.A_SUBS || id == TubeRows.A_SUBS_MORE -> go(Page.Subscriptions)
            id == TubeRows.A_LIBRARY -> go(Page.Library)
            id == TubeRows.A_HISTORY -> go(Page.History)
            id == TubeRows.A_LIKED -> go(Page.Liked)
            id == TubeRows.A_MY_PLAYLISTS -> go(Page.MyPlaylists)
            id == TubeRows.A_ACCOUNT -> go(Page.Account)
            id == TubeRows.A_CHART_MUSIC -> go(Page.Chart(MUSIC, getString(R.string.tube_chart_music)))
            id == TubeRows.A_CHART_POPULAR -> go(Page.Chart(null, getString(R.string.tube_chart_popular)))
            id == TubeRows.A_MORE -> moreFeed(top)?.let(loader::more)
            id == TubeRows.A_MORE_PLAYLISTS -> (top as? Page.Channel)?.let { loader.more(Feed.ChannelPlaylists(it.id)) }
            id == TubeRows.A_RETRY -> feedsOf(top).filter { loader.slot(it).error != null }.forEach { loader.load(it, force = true) }
            id == TubeRows.A_CLEAR -> {
                state.clear()
                TubeTileService.refresh(this)
                render()
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
            id == TubeRows.A_LOGIN_PHONE -> (login as? LoginState.Waiting)?.let { openOnPhone(it.code.verificationUrl, R.string.tube_opened_phone_page) {} }
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

    private fun search(text: String) {
        val q = SearchHistory.clean(text)
        if (q.isEmpty()) return
        state.addSearch(q)
        // 같은 검색을 다시 하면(15분 안) 기억한 결과로 — 검색은 하루 100번까지라 아낀다
        if (nav.top == Page.Search(q)) show() else go(Page.Search(q))
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

    /** 폰에서 허용할 때까지 [DeviceCode.intervalSec] 마다 묻는다 (천천히 하라면 더 천천히). 잠깐 끊기면 계속 기다린다 */
    private suspend fun waitForApproval(code: DeviceCode) {
        var interval = code.intervalSec
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
                login = LoginState.Failed(e.message ?: getString(R.string.tube_phone_failed))
                render()
                return
            }
            when (r) {
                Poll.Pending -> Unit
                is Poll.SlowDown -> interval = com.mywatch.kakaomap.tube.TubeAuth.nextInterval(interval, r)
                Poll.Denied -> {
                    login = LoginState.Denied
                    render()
                    return
                }
                Poll.Expired -> {
                    login = LoginState.Expired
                    render()
                    return
                }
                Poll.Granted -> {
                    signedInNow()
                    return
                }
            }
        }
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

    /** 로그아웃: 워치에서 바로 지우고(화면도 바로), 구글에 철회는 뒤에서. 받아 둔 내 데이터·내 화면도 지운다 */
    private fun signOut() {
        val tokens = auth.forget()
        lifecycleScope.launch(Dispatchers.IO) { auth.revoke(tokens) }
        loader.clearMine()
        if (nav.top == Page.Account) nav.back()
        nav.dropMine()
        show()
        toast(getString(R.string.tube_logged_out))
    }

    // ---------------- 재생 ----------------

    private fun playAll(p: Page.Playlist) {
        val videos = loader.slot(Feed.PlaylistVideos(p.id, p.own)).page?.videos.orEmpty()
        val first = videos.firstOrNull { it.embeddable } ?: videos.firstOrNull() ?: return
        play(PlayRequest(first, if (p.own) TubePlay.fromList(videos, videos.indexOf(first)).queue else Queue.Playlist(p.id)))
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
        lifecycleScope.launch {
            val reachable = try {
                WatchPlayer.reachable()
            } finally {
                opening = false
            }
            if (!reachable) {
                render()
                toast(getString(R.string.tube_watch_unreachable))
                playOnPhone(req)
                return@launch
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
                return@launch
            }
            if (route == PlayRoute.BROWSER && crashed) toast(getString(R.string.tube_engine_browser))
            played(req.video)
            // 인터넷 앱으로 틀었고 타일에서 바로 온 일이면 이 앱은 닫는다 (재생 화면이 앞에). 앱 안 재생 화면은 이 앱 위에 있다
            if (shortcut != null) {
                shortcut = null
                if (route == PlayRoute.BROWSER) finishAndRemoveTask()
            }
        }
    }

    /** 폰 유튜브에서 (믹스·재생목록이면 그대로) */
    private fun playOnPhone(req: PlayRequest) {
        val list = (req.queue as? Queue.Playlist)?.id
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
        /** 유튜브 '음악' 분류 (인기 음악) */
        private const val MUSIC = "10"
        /** 구글 계정 > 서드 파티 연결 (이 앱의 접근 권한을 끊는 곳) */
        private const val PERMISSIONS_URL = "https://myaccount.google.com/connections"
        /** 타일에서 바로 온 일을 마치고 닫기까지 ('폰에서 열림' 애니메이션 길이쯤) */
        private const val SHORTCUT_CLOSE_MS = 1_800L

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
