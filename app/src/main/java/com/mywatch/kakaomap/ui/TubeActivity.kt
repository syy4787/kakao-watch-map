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
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.widget.ConfirmationOverlay
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.tile.TubeTileService
import com.mywatch.kakaomap.tube.Handoff
import com.mywatch.kakaomap.tube.MusicApp
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayPlaces
import com.mywatch.kakaomap.tube.PlayTarget
import com.mywatch.kakaomap.tube.SearchHistory
import com.mywatch.kakaomap.tube.TubeApi
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.tube.YouTubeQuotaException
import com.mywatch.kakaomap.ui.TubeRows.A_CANCEL
import com.mywatch.kakaomap.ui.TubeRows.A_CLEAR
import com.mywatch.kakaomap.ui.TubeRows.A_FORGET
import com.mywatch.kakaomap.ui.TubeRows.A_HANDOFF
import com.mywatch.kakaomap.ui.TubeRows.A_HOME
import com.mywatch.kakaomap.ui.TubeRows.A_MIX
import com.mywatch.kakaomap.ui.TubeRows.A_MORE
import com.mywatch.kakaomap.ui.TubeRows.A_INSTALL_MUSIC
import com.mywatch.kakaomap.ui.TubeRows.A_OPEN_MUSIC
import com.mywatch.kakaomap.ui.TubeRows.A_PHONE_MUSIC
import com.mywatch.kakaomap.ui.TubeRows.A_PHONE_SEARCH
import com.mywatch.kakaomap.ui.TubeRows.A_PREMIUM
import com.mywatch.kakaomap.ui.TubeRows.A_QUERY
import com.mywatch.kakaomap.ui.TubeRows.A_SEARCH
import com.mywatch.kakaomap.ui.TubeRows.A_TARGET
import com.mywatch.kakaomap.ui.TubeRows.A_TO
import com.mywatch.kakaomap.ui.TubeRows.A_UPDATE
import com.mywatch.kakaomap.ui.TubeRows.A_WATCH_MIX
import com.mywatch.kakaomap.ui.TubeRows.A_WATCH_MUSIC
import com.mywatch.kakaomap.ui.TubeRows.A_WATCH_ONE
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
 * 유튜브 음악 (워치 앱 목록의 '유튜브 음악', 유튜브 뮤직처럼 생긴 화면):
 * 말·키보드로 검색 → 유튜브 곡 목록 → 누르면 그 곡으로 시작하는 '믹스'를 '듣는 곳'에서 튼다 ([PlayTarget]).
 *  · 워치 유튜브 뮤직 (곡 링크를 받을 때) / 폰 유튜브 뮤직 — 내 계정 그대로라 프리미엄(광고 없음·화면이 꺼져도 계속)
 *  · 워치 화면 플레이어 — 워치 인터넷 앱의 둥근 재생 화면(유튜브 공식 플레이어, [WatchPlayer]). 로그인이 안 돼 무료로만
 * 듣는 곳은 처음 곡을 틀 때 한 번 묻고(고르면 그 곡을 바로 그곳에서) 기억한다 — 처음 화면의 '듣는 곳'에서 바꿀 수 있다.
 * 길게 누르면 이번 곡만 다른 곳에서. 최근 들은 곡·검색어는 처음 화면에서 바로 다시 틀 수 있다.
 */
class TubeActivity : ComponentActivity() {

    private enum class Screen { HOME, RESULTS, MENU, HANDOFF, TARGETS, PREMIUM }

    private lateinit var ui: WearList
    private lateinit var state: TubeState
    private val phone by lazy { PhoneOpener(this) }

    private var screen = Screen.HOME
    /** 메뉴를 닫으면 돌아갈 화면 */
    private var menuFrom = Screen.HOME
    private var menuVideo: TubeVideo? = null
    /** 재생 화면의 '폰에서 이어 듣기'로 온 일 (한 번 묻고 연다) */
    private var handoff: Handoff? = null
    /** '프리미엄으로 듣는 법'을 닫으면 돌아갈 화면 */
    private var helpFrom = Screen.HOME
    /** '듣는 곳' 고르는 화면을 닫으면 돌아갈 화면 (처음 화면 · 검색 결과) */
    private var targetsFrom = Screen.HOME
    /** 아직 듣는 곳을 안 골라 묻는 중인 곡 — 고르면 그곳에서 바로 튼다 */
    private var pendingPlay: Pending? = null

    private data class Pending(val video: TubeVideo, val mix: Boolean)

    // 검색 결과 화면
    private var query: String? = null
    private var results: List<TubeVideo> = emptyList()
    private var source: TubeSource? = null
    private var next: String? = null
    private var note: String? = null
    private var error: String? = null
    private var searching = false
    private var loadingMore = false
    private var searchJob: Job? = null

    /** 여는 중 (두 번 눌러 두 번 열리지 않게, 가운데에 '폰에서 여는 중…'/'워치에서 여는 중…') */
    private var opening = false
    @StringRes private var openingText = R.string.tube_sending

    /**
     * 타일에서 바로 들어온 한 번의 일([EXTRA_VOICE]·[EXTRA_MIX]). 그 곡을 폰에서 열면 잠깐 알려 주고 닫는다(워치 화면으로).
     * 처음 화면으로 돌아가는 등 앱을 직접 쓰기 시작하면 잊는다
     */
    private var shortcut: String? = null
    /** 타일에서 온 일을 마치고 잠시 뒤 닫기 — 그 사이 화면을 만지면 닫지 않는다 */
    private var closeJob: Job? = null
    /** 손목을 내리면 밝은 목록(미리보기 그림) 대신 검은 화면에 짧은 글만 */
    private var ambient: AmbientCover? = null

    /** 뒤로(버튼·쓸어 넘기기): 메뉴 → 보던 화면, 검색 결과 → 처음 화면 */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (screen) {
                Screen.HANDOFF -> cancelHandoff()
                Screen.MENU -> closeMenu()
                Screen.TARGETS -> {
                    shortcut = null   // 묻는 화면에서 뒤로 — 앱을 직접 쓰기 시작한 것 (타일에서 온 일은 끝)
                    closeTargets()
                }
                Screen.PREMIUM -> closeHelp()
                Screen.RESULTS -> {
                    shortcut = null   // 처음 화면으로 돌아와 직접 쓰기 시작 — 다음에 곡을 틀어도 닫지 않는다
                    goHome()
                }
                Screen.HOME -> Unit
            }
        }
    }

    private val inputLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_QUERY) }?.toString()
        when {
            !text.isNullOrBlank() -> search(text)
            // 타일의 '노래 검색'을 눌렀다가 취소 → 워치 화면으로
            shortcut == EXTRA_VOICE && screen == Screen.HOME -> finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        state = TubeState(this)
        ui = WearList(this, ::onRow, ::onLongRow, Look.TUBE)
        onBackPressedDispatcher.addCallback(this, back)
        // Wear OS 6+ 는 손목을 내려도 이 화면을 켜 둔 채 둔다(길안내 중이면 워치 화면으로 돌아가지도 않음)
        ambient = AmbientCover(this, ::ambientSummary)
        render()
        if (savedInstanceState == null) handleShortcut(intent)
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
        handleShortcut(intent)
    }

    /** 맡긴 확인이 새 버전을 찾으면 첫 화면 맨 위에 '새 버전 업데이트'를 바로 띄운다 */
    private val updateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (screen == Screen.HOME && !opening) render()
    }

    override fun onResume() {
        super.onResume()
        // 타일에서 틀었거나 날이 지나 기록이 바뀌었을 수 있고, Play 스토어·유튜브 뮤직에 다녀왔으면 깔린 앱이 바뀌었을 수 있다
        if (screen in REFRESH_ON_RESUME && !opening) render()
        UpdateManager.prefsForListening(this).registerOnSharedPreferenceChangeListener(updateListener)
        // 타일로 바로 열어 첫 화면을 거치지 않아도 새 버전 확인
        UpdateWorker.onAppOpened(this)
        prewarm()
    }

    override fun onPause() {
        UpdateManager.prefsForListening(this).unregisterOnSharedPreferenceChangeListener(updateListener)
        super.onPause()
    }

    private fun handleShortcut(intent: Intent?) {
        if (intent == null) return
        // '최근 앱'에서 다시 열면 처음 열 때의 요청(믹스 틀기 등)이 그대로 다시 온다 → 그냥 처음 화면
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        // 재생 화면의 링크(웹)로 열렸으면 끼워 넣은 값(타일용 믹스·말 입력)은 따르지 않는다.
        // '다른 곡 찾기'는 그냥 이 화면, '폰에서 이어 듣기'는 한 번 묻는 화면
        if (intent.action == Intent.ACTION_VIEW) {
            // 링크로 돌아왔으면 새로 쓰기 시작한 것 — 타일에서 온 한 번의 일(마치면 닫기)·묻던 곡은 잊는다
            shortcut = null
            closeJob?.cancel()
            closeJob = null
            pendingPlay = null
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
                    play(last, mix = true)
                } else {
                    // 아직 들은 곡이 없으면 말로 찾기 — 취소하면 '노래 검색'처럼 워치 화면으로
                    shortcut = EXTRA_VOICE
                    openInput()
                }
            }
        }
    }

    // ---------------- 화면 ----------------

    private fun render() {
        back.isEnabled = screen != Screen.HOME
        if (opening) {
            ui.showStatus(getString(openingText))
            return
        }
        when (screen) {
            Screen.HOME -> ui.rows = TubeRows.home(
                this, state.played(), state.searches,
                update = runCatching { UpdateManager.hasUpdate(this) }.getOrDefault(false),
                places = places(),
            )
            Screen.TARGETS -> ui.rows = TubeRows.targets(this, places())
            Screen.PREMIUM -> ui.rows = TubeRows.premium(this, places())
            Screen.RESULTS -> {
                val q = query.orEmpty()
                val err = error
                when {
                    searching -> ui.showStatus(getString(R.string.tube_searching))
                    err != null -> ui.rows = TubeRows.error(this, q, err)
                    else -> ui.rows = TubeRows.results(this, q, source, results, hasMore = next != null, note = note, loadingMore = loadingMore)
                }
            }
            Screen.HANDOFF -> {
                val h = handoff
                if (h == null) goHome() else ui.rows = TubeRows.handoff(this, h)
            }
            Screen.MENU -> {
                val v = menuVideo
                if (v == null) closeMenu() else ui.rows = TubeRows.menu(this, v, fromHome = menuFrom == Screen.HOME, places = places())
            }
        }
    }

    private fun goHome() {
        handoff = null
        pendingPlay = null
        searchJob?.cancel()
        searching = false
        loadingMore = false
        query = null
        menuVideo = null
        screen = Screen.HOME
        render()
    }

    private fun showHandoff(h: Handoff) {
        shortcut = null
        closeJob?.cancel()
        menuVideo = null
        pendingPlay = null
        // 이미 들은 곡이면 워치에 저장된 진짜 이름·길이로 (링크의 제목은 웹 페이지가 붙인 것)
        val known = state.played().firstOrNull { it.video.id == h.video.id }?.video
        handoff = if (known != null) h.copy(video = known) else h
        screen = Screen.HANDOFF
        render()
    }

    private fun cancelHandoff() {
        handoff = null
        goHome()
    }

    /** 폰 유튜브에서 그 곡을 그 시각부터 (믹스였으면 믹스로) — 열리면 기록하고 처음 화면, 안 되면 알리고 그대로(다시 누를 수 있게) */
    private fun doHandoff(h: Handoff) {
        openOnPhone(TubeLinks.watchUrl(h.video.id, h.mix, h.startSec), R.string.tube_sending, R.string.tube_opened_on_phone) {
            state.addPlayed(h.video.copy(title = h.video.title.ifBlank { getString(R.string.tube_handoff_untitled) }))
            TubeTileService.refresh(this)
            // 그 사이 재생 화면에서 또 넘겨 왔으면 그것은 남겨 둔다 (다시 묻도록)
            if (handoff === h) {
                handoff = null
                screen = Screen.HOME
            }
        }
    }

    private fun showMenu(video: TubeVideo) {
        menuFrom = screen
        menuVideo = video
        screen = Screen.MENU
        render()
    }

    private fun closeMenu() {
        menuVideo = null
        screen = if (menuFrom == Screen.RESULTS && query != null) Screen.RESULTS else Screen.HOME
        render()
    }

    /** 지금 틀 수 있는 곳과 실제로 틀 곳 (앱을 깔거나 지웠을 수 있어 그때그때 본다) */
    private fun places(): PlayPlaces = PlayPlaces.of(
        state.target,
        watchMusic = MusicApp.canPlaySongs(this),
        musicApp = MusicApp.installed(this),
        watchWeb = WatchPlayer.available(this),
    )

    /** 워치 화면 플레이어로 들을 때만: 재생 화면이 열리는지 미리 확인 — 곡을 누를 때 기다리지 않게 */
    private fun prewarm() {
        if (places().target == PlayTarget.WATCH_WEB && WatchPlayer.needsCheck(this)) lifecycleScope.launch { WatchPlayer.prewarm() }
    }

    /** '듣는 곳' 고르기. 처음 화면·검색 결과에서 열린다 (닫으면 그리로) */
    private fun showTargets() {
        if (screen == Screen.HOME || screen == Screen.RESULTS) targetsFrom = screen
        screen = Screen.TARGETS
        render()
    }

    /** 고르지 않고 닫기: 묻던 곡은 틀지 않고 보던 화면으로 */
    private fun closeTargets() {
        pendingPlay = null
        if (targetsFrom == Screen.RESULTS && query != null) {
            screen = Screen.RESULTS
            render()
        } else {
            goHome()
        }
    }

    /**
     * 듣는 곳을 고르면 기억하고 보던 화면으로. 묻던 곡이 있으면 바로 그곳에서 틀고,
     * 없으면(처음 화면의 '듣는 곳'·프리미엄 안내에서 바꿈) 어디서 틀지 잠깐 알린다
     */
    private fun chooseTarget(t: PlayTarget) {
        state.target = t
        val pending = pendingPlay
        closeTargets()
        prewarm()
        if (pending != null) play(pending.video, pending.mix) else toast(getString(R.string.tube_target_set, TubeRows.targetName(this, t)))
    }

    private fun showHelp() {
        helpFrom = if (screen == Screen.TARGETS) Screen.TARGETS else Screen.HOME
        screen = Screen.PREMIUM
        render()
    }

    private fun closeHelp() {
        if (helpFrom == Screen.TARGETS) showTargets() else goHome()
    }

    /** 워치 YouTube Music 열기 (계정 전환·라이브러리·믹스). 없으면 설치 화면 */
    private fun openMusicApp() {
        val open = MusicApp.openIntent(this) ?: return openStore()
        try {
            startActivity(open)
        } catch (e: ActivityNotFoundException) {
            ErrorLog.record("유튜브·워치 YouTube Music 열기", e)
            openStore()
        } catch (e: SecurityException) {
            ErrorLog.record("유튜브·워치 YouTube Music 열기", e)
            openStore()
        }
    }

    /** 워치 Play 스토어의 YouTube Music 페이지 */
    private fun openStore() {
        try {
            startActivity(MusicApp.storeIntent())
        } catch (e: ActivityNotFoundException) {
            ErrorLog.record("유튜브·Play 스토어 열기", e)
            toast(getString(R.string.tube_store_failed))
        } catch (e: SecurityException) {
            ErrorLog.record("유튜브·Play 스토어 열기", e)
            toast(getString(R.string.tube_store_failed))
        }
    }

    // ---------------- 누르기 ----------------

    private fun onRow(row: Row) {
        when (row) {
            is Row.VideoItem -> play(row.video, mix = true)
            is Row.Action -> when {
                row.id == A_UPDATE -> startActivity(UpdateActivity.updateNowIntent(this))
                row.id == A_SEARCH -> openInput()
                row.id == A_MIX -> state.lastPlayed()?.let { play(it, mix = true) }
                row.id.startsWith(A_QUERY) -> search(row.id.removePrefix(A_QUERY))
                row.id == A_CLEAR -> {
                    state.clear()
                    TubeTileService.refresh(this)
                    render()
                }
                row.id == A_MORE -> more()
                row.id == A_PHONE_SEARCH -> query?.let(::searchOnPhone)
                row.id == A_HOME -> {
                    shortcut = null
                    goHome()
                }
                row.id == A_WATCH_MUSIC -> menuVideo?.let { playOnWatchMusic(it, mix = true) }
                row.id == A_PHONE_MUSIC -> menuVideo?.let { playOnPhoneMusic(it, mix = true) }
                row.id == A_WATCH_MIX -> menuVideo?.let { playOnWatch(it, mix = true) }
                row.id == A_WATCH_ONE -> menuVideo?.let { playOnWatch(it, mix = false) }
                row.id == A_TARGET -> showTargets()
                row.id.startsWith(A_TO) -> PlayTarget.of(row.id.removePrefix(A_TO))?.let(::chooseTarget)
                row.id == A_PREMIUM -> showHelp()
                row.id == A_OPEN_MUSIC -> openMusicApp()
                row.id == A_INSTALL_MUSIC -> openStore()
                row.id == A_FORGET -> menuVideo?.let {
                    state.removePlayed(it.id)
                    TubeTileService.refresh(this)
                    closeMenu()
                }
                row.id == A_HANDOFF -> handoff?.let(::doHandoff)
                row.id == A_CANCEL -> when (screen) {
                    Screen.HANDOFF -> cancelHandoff()
                    Screen.PREMIUM -> closeHelp()
                    else -> closeMenu()
                }
            }
            else -> Unit
        }
    }

    private fun onLongRow(row: Row) {
        if (row is Row.VideoItem && screen != Screen.MENU && !opening) showMenu(row.video)
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
        searchJob?.cancel()
        query = q
        results = emptyList()
        source = null
        next = null
        note = null
        error = null
        loadingMore = false
        searching = true
        screen = Screen.RESULTS
        render()
        searchJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { TubeApi.search(applicationContext, q) }
                results = r.page.videos
                next = r.page.next
                source = r.source
                note = r.note
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("유튜브 검색", e)
                error = e.message ?: getString(R.string.no_results)
            }
            searching = false
            refreshResults()
        }
    }

    /** '더 보기': 같은 곳(유튜브·카카오)에서 다음 쪽을 받아 아래에 붙인다 */
    private fun more() {
        val q = query ?: return
        val token = next ?: return
        if (loadingMore || searching) return
        val from = source
        loadingMore = true
        render()
        searchJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { TubeApi.search(applicationContext, q, token, from) }
                results = (results + r.page.videos).distinctBy { it.id }
                next = r.page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeQuotaException) {
                next = null   // 오늘은 유튜브에서 더 볼 수 없다 → '더 보기'를 없앤다
                toast(e.message)
            } catch (e: Exception) {
                ErrorLog.record("유튜브 검색·더 보기", e)
                toast(e.message)
            } finally {
                loadingMore = false
            }
            refreshResults()
        }
    }

    /** 검색이 끝났을 때: 결과 화면을 보고 있을 때만 다시 그린다 (그 사이 메뉴를 열었으면 메뉴를 닫을 때 그린다) */
    private fun refreshResults() {
        if (screen == Screen.RESULTS) render()
    }

    // ---------------- 재생 (워치 또는 폰) ----------------

    /** 곡 틀기: '듣는 곳'에서 (아직 안 골랐으면 한 번 묻고, 고르면 그곳에서). [mix] = 이 곡으로 시작하는 믹스 */
    private fun play(video: TubeVideo, mix: Boolean) {
        val places = places()
        if (!places.chosen) {
            pendingPlay = Pending(video, mix)
            showTargets()
            return
        }
        when (places.target) {
            PlayTarget.WATCH_MUSIC -> playOnWatchMusic(video, mix)
            PlayTarget.PHONE_MUSIC -> playOnPhoneMusic(video, mix)
            PlayTarget.WATCH_WEB -> playOnWatch(video, mix)
        }
    }

    /** 틀었음: 기록하고, 메뉴에서 틀었으면 보던 화면으로 (기록이 바뀌었으니 처음 화면은 다시 그림) */
    private fun played(video: TubeVideo) {
        state.addPlayed(video)
        TubeTileService.refresh(this)
        if (screen == Screen.MENU) {
            menuVideo = null
            screen = if (menuFrom == Screen.RESULTS && query != null) Screen.RESULTS else Screen.HOME
        }
    }

    /**
     * 워치의 인터넷 앱이 둥근 화면용 재생 화면을 연다 (그 안의 유튜브 공식 플레이어가 재생).
     * 재생 화면에 닿지 못하거나 열 앱이 없으면 알리고 폰으로. 타일에서 바로 온 일이면 이 앱은 닫는다(재생 화면이 앞에).
     * [instead] = 폰과 연결돼 있지 않아 대신 온 것 — 열리면 그렇다고 알리고, 안 열리면 폰이 없다고만 (다시 폰으로 보내지 않음)
     */
    private fun playOnWatch(video: TubeVideo, mix: Boolean, instead: Boolean = false) {
        if (opening) return
        // 열 수 없으면: 폰 대신 온 것이면 폰이 없다고만 알리고, 아니면 폰으로
        val cannot = { if (instead) failed(R.string.tube_no_phone) else playOnPhoneMusic(video, mix, watchIfNoPhone = false) }
        // 메뉴를 보는 사이 인터넷 앱을 지웠을 수도 — 열 앱이 없으면 바로
        if (!WatchPlayer.available(this)) return cannot()
        val link = WatchPlayer.intent(this, video, mix) ?: return cannot()
        opening = true
        openingText = R.string.tube_opening_watch
        render()
        lifecycleScope.launch {
            val reachable = try {
                WatchPlayer.reachable()
            } finally {
                opening = false
            }
            val opened = reachable && try {
                startActivity(link)
                true
            } catch (e: ActivityNotFoundException) {
                ErrorLog.record("유튜브·워치에서 열기", e)
                false
            } catch (e: SecurityException) {
                ErrorLog.record("유튜브·워치에서 열기", e)
                false
            }
            if (!opened) {
                render()
                if (instead) {
                    failed(R.string.tube_no_phone)
                } else {
                    toast(getString(R.string.tube_watch_unreachable))
                    playOnPhoneMusic(video, mix, watchIfNoPhone = false)
                }
                return@launch
            }
            played(video)
            render()
            if (instead) toast(getString(R.string.tube_no_phone_watch))
            if (shortcut != null) {
                shortcut = null
                closeJob?.cancel()
                finishAndRemoveTask()
            }
        }
    }

    /**
     * 워치 YouTube Music 이 이 곡을 튼다 (워치에 로그인된 내 계정 — 프리미엄이면 광고 없이, 화면이 꺼져도 계속).
     * 열 수 없으면 알리고 폰 YouTube Music 으로. 타일에서 바로 온 일이면 이 앱은 닫는다(YouTube Music 이 앞에)
     */
    private fun playOnWatchMusic(video: TubeVideo, mix: Boolean) {
        if (opening) return
        // 메뉴를 보는 사이 앱을 지웠을 수도 — 받을 앱이 없으면 열어 보지 않고 바로 폰으로
        val opened = MusicApp.canPlaySongs(this) && try {
            startActivity(MusicApp.songIntent(video.id, mix))
            true
        } catch (e: ActivityNotFoundException) {
            ErrorLog.record("유튜브·워치 YouTube Music 에서 열기", e)
            false
        } catch (e: SecurityException) {
            ErrorLog.record("유튜브·워치 YouTube Music 에서 열기", e)
            false
        }
        if (!opened) {
            toast(getString(R.string.tube_music_failed))
            playOnPhoneMusic(video, mix)
            return
        }
        played(video)
        render()
        if (shortcut != null) {
            shortcut = null
            closeJob?.cancel()
            finishAndRemoveTask()
        }
    }

    /**
     * 폰 유튜브 뮤직이 이 곡을 튼다 (폰에 로그인된 내 계정·프리미엄). [mix] = 이 곡으로 시작하는 노래 라디오.
     * 폰과 연결돼 있지 않으면 워치 화면 플레이어(있으면)로 대신 — 음악이 아예 안 나오는 것보다 낫게.
     * [watchIfNoPhone] = false: 워치 화면 플레이어를 열지 못해 온 것이라 다시 그리로 보내지 않는다
     */
    private fun playOnPhoneMusic(video: TubeVideo, mix: Boolean, watchIfNoPhone: Boolean = true) {
        val noPhone: (() -> Unit)? = if (watchIfNoPhone && WatchPlayer.available(this)) {
            { playOnWatch(video, mix, instead = true) }
        } else {
            null
        }
        openOnPhone(TubeLinks.musicUrl(video.id, mix), R.string.tube_sending_music, R.string.tube_opened_on_phone_music, noPhone) { played(video) }
    }

    /** 워치 검색에 원하는 곡이 없을 때: 폰 유튜브 앱의 검색 결과(내 계정 기준)로 */
    private fun searchOnPhone(q: String) = openOnPhone(TubeLinks.searchUrl(q), R.string.tube_sending, R.string.tube_opened_search_on_phone) {}

    private fun openOnPhone(url: String, @StringRes sending: Int, @StringRes done: Int, onNoPhone: (() -> Unit)? = null, onOpened: () -> Unit) {
        if (opening) return
        opening = true
        openingText = sending
        render()   // 바로 '폰에서 여는 중…' — 누른 게 먹었는지 보이게
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
                PhoneOpener.Result.NO_PHONE -> onNoPhone?.invoke() ?: failed(R.string.tube_no_phone)
                PhoneOpener.Result.FAILED -> failed(R.string.tube_phone_failed)
            }
        }
    }

    /**
     * '폰에서 열림' 애니메이션. 화면 전환은 이미 했으므로 애니메이션 끝을 기다리지 않는다
     * (손목을 내려 화면이 꺼지면 애니메이션이 멈춰 끝 신호가 한참 뒤에야 온다).
     * 타일에서 바로 온 일이면 잠깐 보여 주고 닫는다 — 화면이 꺼져 있어도 시간만으로 닫힌다
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
        /** 다시 보이면 새로 그리는 화면 — 기록이 바뀌었거나, 다녀온 사이 유튜브 뮤직·삼성 인터넷을 깔거나 지웠을 수 있다 */
        private val REFRESH_ON_RESUME = setOf(Screen.HOME, Screen.TARGETS, Screen.PREMIUM, Screen.MENU)
        /** 타일에서 바로 온 일을 마치고 닫기까지 ('폰에서 열림' 애니메이션 길이쯤) */
        private const val SHORTCUT_CLOSE_MS = 1_800L

        /** 타일의 '노래 검색' — 바로 음성·키보드 입력 */
        const val EXTRA_VOICE = "voice"
        /** 타일의 '믹스 이어 듣기' — 최근 곡으로 바로 믹스 */
        const val EXTRA_MIX = "mix"
    }
}
