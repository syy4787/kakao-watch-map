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
import com.mywatch.kakaomap.tube.PhoneOpener
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
import com.mywatch.kakaomap.ui.TubeRows.A_PHONE_SEARCH
import com.mywatch.kakaomap.ui.TubeRows.A_PLAY_MIX
import com.mywatch.kakaomap.ui.TubeRows.A_PLAY_ONE
import com.mywatch.kakaomap.ui.TubeRows.A_QUERY
import com.mywatch.kakaomap.ui.TubeRows.A_SEARCH
import com.mywatch.kakaomap.ui.TubeRows.A_UPDATE
import com.mywatch.kakaomap.ui.TubeRows.A_WATCH_MIX
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
 * 유튜브 음악 (워치 앱 목록의 '유튜브 음악'):
 * 말·키보드로 검색 → 유튜브 영상 목록 → 누르면 그 곡으로 시작하는 '믹스'를 튼다.
 *  · 워치에 인터넷 앱(삼성 인터넷)이 있으면 워치에서: 둥근 화면용 재생 화면(유튜브 공식 플레이어) — [WatchPlayer]
 *  · 없거나 재생 화면에 닿지 못하면 폰에서: 폰의 유튜브 앱(내 계정의 알고리즘, 프리미엄이면 화면이 꺼져도 계속)
 * 길게 누르면 워치/폰 · 믹스/이 곡만을 고른다. 최근 들은 곡·검색어는 처음 화면에서 바로 다시 틀 수 있다.
 */
class TubeActivity : ComponentActivity() {

    private enum class Screen { HOME, RESULTS, MENU, HANDOFF }

    private lateinit var ui: WearList
    private lateinit var state: TubeState
    private val phone by lazy { PhoneOpener(this) }

    private var screen = Screen.HOME
    /** 메뉴를 닫으면 돌아갈 화면 */
    private var menuFrom = Screen.HOME
    private var menuVideo: TubeVideo? = null
    /** 재생 화면의 '폰에서 이어 듣기'로 온 일 (한 번 묻고 연다) */
    private var handoff: Handoff? = null

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
        ui = WearList(this, ::onRow, ::onLongRow)
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
        // 타일에서 틀었거나 날이 지나 기록이 바뀌었을 수 있다
        if (screen == Screen.HOME && !opening) render()
        UpdateManager.prefsForListening(this).registerOnSharedPreferenceChangeListener(updateListener)
        // 타일로 바로 열어 첫 화면을 거치지 않아도 새 버전 확인
        UpdateWorker.onAppOpened(this)
        // 워치 재생 화면이 열리는지 미리 확인 — 곡을 누를 때 기다리지 않게
        if (WatchPlayer.needsCheck(this)) lifecycleScope.launch { WatchPlayer.prewarm() }
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
            // 링크로 돌아왔으면 새로 쓰기 시작한 것 — 타일에서 온 한 번의 일(마치면 닫기)은 잊는다
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
                watch = WatchPlayer.available(this),
            )
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
                if (v == null) closeMenu() else ui.rows = TubeRows.menu(this, v, fromHome = menuFrom == Screen.HOME, watch = WatchPlayer.available(this))
            }
        }
    }

    private fun goHome() {
        handoff = null
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
        openOnPhone(TubeLinks.watchUrl(h.video.id, h.mix, h.startSec), R.string.tube_opened_on_phone) {
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
                row.id == A_WATCH_MIX -> menuVideo?.let { playOnWatch(it, mix = true) }
                row.id == A_WATCH_ONE -> menuVideo?.let { playOnWatch(it, mix = false) }
                row.id == A_PLAY_MIX -> menuVideo?.let { playOnPhone(it, mix = true) }
                row.id == A_PLAY_ONE -> menuVideo?.let { playOnPhone(it, mix = false) }
                row.id == A_FORGET -> menuVideo?.let {
                    state.removePlayed(it.id)
                    TubeTileService.refresh(this)
                    closeMenu()
                }
                row.id == A_HANDOFF -> handoff?.let(::doHandoff)
                row.id == A_CANCEL -> if (screen == Screen.HANDOFF) cancelHandoff() else closeMenu()
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

    /** 곡 틀기: 워치에 인터넷 앱이 있으면 워치 재생 화면으로, 없으면 폰 유튜브로. [mix] = 이 곡으로 시작하는 믹스 */
    private fun play(video: TubeVideo, mix: Boolean) {
        if (WatchPlayer.available(this)) playOnWatch(video, mix) else playOnPhone(video, mix)
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
     * 재생 화면에 닿지 못하거나 열 앱이 없으면 알리고 폰으로. 타일에서 바로 온 일이면 이 앱은 닫는다(재생 화면이 앞에)
     */
    private fun playOnWatch(video: TubeVideo, mix: Boolean) {
        if (opening) return
        val link = WatchPlayer.intent(this, video, mix) ?: return playOnPhone(video, mix)
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
                toast(getString(R.string.tube_watch_unreachable))
                playOnPhone(video, mix)
                return@launch
            }
            played(video)
            render()
            if (shortcut != null) {
                shortcut = null
                closeJob?.cancel()
                finishAndRemoveTask()
            }
        }
    }

    /** 폰의 유튜브 앱이 이 곡을 연다. [mix] = 이 곡으로 시작하는 믹스(내 계정의 추천으로 다음 곡이 계속 이어짐) */
    private fun playOnPhone(video: TubeVideo, mix: Boolean) =
        openOnPhone(TubeLinks.watchUrl(video.id, mix), R.string.tube_opened_on_phone) { played(video) }

    /** 워치 검색에 원하는 곡이 없을 때: 폰 유튜브 앱의 검색 결과(내 계정 기준)로 */
    private fun searchOnPhone(q: String) = openOnPhone(TubeLinks.searchUrl(q), R.string.tube_opened_search_on_phone) {}

    private fun openOnPhone(url: String, @StringRes done: Int, onOpened: () -> Unit) {
        if (opening) return
        opening = true
        openingText = R.string.tube_sending
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
                PhoneOpener.Result.NO_PHONE -> failed(R.string.tube_no_phone)
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
        /** 타일에서 바로 온 일을 마치고 닫기까지 ('폰에서 열림' 애니메이션 길이쯤) */
        private const val SHORTCUT_CLOSE_MS = 1_800L

        /** 타일의 '노래 검색' — 바로 음성·키보드 입력 */
        const val EXTRA_VOICE = "voice"
        /** 타일의 '믹스 이어 듣기' — 최근 곡으로 바로 믹스 */
        const val EXTRA_MIX = "mix"
    }
}
