package com.mywatch.kakaomap.ui

import android.app.AlertDialog
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.data.Favorite
import com.mywatch.kakaomap.data.Favorites
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.NearbyKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.net.PlaceSearch
import com.mywatch.kakaomap.tile.ShortcutTileService
import com.mywatch.kakaomap.update.UpdateActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 도착지 검색 첫 화면 (카카오맵 길찾기 입력 화면처럼):
 * 검색 → 집·회사 바로가기 → 내 주변(편의점·약국·화장실·카페·지하철역) → 최근 목적지.
 * 집·회사는 등록 안 됐으면 톡 → 검색해서 등록, 등록됐으면 톡 → 바로 길찾기, 길게 누르면 바꾸기·지우기.
 * 최근 목적지·검색 결과를 길게 누르면 집·회사로 등록.
 * 뒤로(옆 버튼·오른쪽으로 쓸기)는 한 단계씩: 메뉴 → 결과 → 처음 화면 → 닫기.
 */
class SearchActivity : ComponentActivity() {

    /** 화면 한 장 — 보여 줄 줄과, 그 화면에서 누른 것을 어떻게 다룰지 */
    private data class Page(
        val rows: List<Row> = emptyList(),
        /** 집·회사를 등록하는 중이면 그 종류 (장소를 누르면 등록) */
        val registering: Favorite? = null,
        /** 검색 결과(또는 검색 중·실패) — 여기서 다시 찾으면 결과를 바꾼다 (뒤로 → 결과 아래 화면) */
        val results: Boolean = false,
        /** 길게 눌러 연 메뉴의 대상 */
        val menuPlace: Place? = null,
        val menuFavorite: Favorite? = null,
        /** '내 주변' 결과의 종류 (다시 시도는 이 화면을 바꾼다) */
        val nearby: NearbyKind? = null,
        /** 줄 대신 가운데에 보여 줄 글 (검색 중…) */
        val status: String? = null,
    )

    private lateinit var ui: WearList
    private var page = Page()
    /** 지금 화면 아래에 쌓인 화면들 — 뒤로 누르면 한 단계씩 */
    private val below = ArrayDeque<Page>()
    private val backStep = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }
    private var searchJob: Job? = null
    private val finder by lazy { finderFor(applicationContext) }

    private val inputLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_QUERY) }?.toString()
        if (!text.isNullOrBlank()) search(text.trim())
        else if (page.registering != null && !page.results) {
            // 등록하려다 입력을 취소했으면 첫 화면으로
            showHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow, ::onLongRow)
        onBackPressedDispatcher.addCallback(this, backStep)
        // 등록하던 중에 화면이 다시 만들어졌으면(음성 입력 중 앱이 정리됨 등) 등록 화면으로 이어 간다
        val restored = savedInstanceState?.getString(STATE_REGISTERING)?.let { runCatching { Favorite.valueOf(it) }.getOrNull() }
        if (restored != null) showRegister(restored) else showHome()
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome()
        handleShortcut(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        page.registering?.let { outState.putString(STATE_REGISTERING, it.name) }
    }

    /** 타일 등에서 '집으로'·'회사로'·'검색'으로 바로 들어온 경우 */
    private fun handleShortcut(intent: Intent?) {
        val kind = intent?.getStringExtra(EXTRA_FAVORITE)?.let { runCatching { Favorite.valueOf(it) }.getOrNull() }
        when {
            kind != null -> {
                val place = Favorites.get(this, kind)
                if (place != null) goFavorite(kind, place) else startRegister(kind)
            }
            intent?.getBooleanExtra(EXTRA_SEARCH, false) == true -> openInput()
        }
    }

    // ───────── 화면 쌓기 ─────────

    /** [next] 를 보여 준다. [push] = 지금 화면을 아래에 쌓음 (뒤로 → 지금 화면). '찾는 중' 화면은 쌓지 않는다 */
    private fun show(next: Page, push: Boolean) {
        if (push && page.status == null) below.addLast(page)
        page = next
        if (next.status != null) ui.showStatus(next.status) else ui.rows = next.rows
        val canStep = below.isNotEmpty()
        backStep.isEnabled = canStep
        ui.swipeBack = if (canStep) ::goBack else null
    }

    /** 한 단계 뒤로 (찾는 중이면 그만두고). 더 돌아갈 곳이 없으면 닫는다 */
    private fun goBack() {
        searchJob?.cancel()
        val prev = below.removeLastOrNull()
        if (prev == null) {
            finish()
            return
        }
        show(prev, push = false)
    }

    private fun showHome() {
        searchJob?.cancel()
        below.clear()
        show(Page(rows = homeRows()), push = false)
    }

    private fun homeRows(): List<Row> {
        val recents = RouteRepository.recents(this)
        val slots = Favorites.slots(this)
        val rows = mutableListOf<Row>(
            Row.Header(getString(R.string.search_title)),
            Row.Action(ACTION_INPUT, R.drawable.ic_search, getString(R.string.search_hint), accent = true),
        )
        rows += slots.map { Row.FavoriteItem(it) }
        rows += Row.Action(ACTION_NEARBY, R.drawable.ic_my_location, getString(R.string.nearby_title))
        if (recents.isNotEmpty()) {
            if (slots.any { it.place == null }) rows += Row.Note(getString(R.string.fav_hint))
            rows += Row.Header(getString(R.string.recent_places))
            rows += recents.map { Row.PlaceItem(it, recent = true) }
            rows += Row.Action(ACTION_CLEAR, R.drawable.ic_delete, getString(R.string.clear_recents))
        }
        rows += Row.Action(ACTION_UPDATE, R.drawable.ic_info, getString(R.string.app_info_update))
        return rows
    }

    // ───────── 누르기 ─────────

    private fun onRow(row: Row) {
        when (row) {
            is Row.Action -> when (row.id) {
                ACTION_INPUT -> openInput()
                ACTION_CLEAR -> {
                    RouteRepository.clearRecents(this)
                    showHome()
                }
                ACTION_UPDATE -> startActivity(Intent(this, UpdateActivity::class.java))
                ACTION_CANCEL -> goBack()
                ACTION_STOP_REGISTER -> showHome()
                ACTION_SET_HOME, ACTION_SET_WORK -> page.menuPlace?.let { register(if (row.id == ACTION_SET_HOME) Favorite.HOME else Favorite.WORK, it) }
                ACTION_FAV_CHANGE -> page.menuFavorite?.let { startRegister(it) }
                ACTION_FAV_REMOVE -> page.menuFavorite?.let {
                    Favorites.clear(this, it)
                    ShortcutTileService.refresh(this)
                    showHome()
                }
                ACTION_NEARBY -> showNearbyKinds()
                else -> nearbyKindOf(row.id)?.let(::findNearby)
            }
            is Row.FavoriteItem -> {
                val place = row.slot.place
                if (place != null) goFavorite(row.slot.kind, place) else startRegister(row.slot.kind)
            }
            is Row.PlaceItem -> {
                val kind = page.registering
                if (kind != null) register(kind, row.place) else choose(row.place)
            }
            else -> Unit
        }
    }

    private fun onLongRow(row: Row) {
        when (row) {
            is Row.PlaceItem -> if (page.registering == null) showPlaceMenu(row.place)
            is Row.FavoriteItem -> row.slot.place?.let { showFavoriteMenu(row.slot.kind, it) }
            else -> Unit
        }
    }

    /** 장소를 길게 눌렀을 때: 집으로·회사로 등록 */
    private fun showPlaceMenu(place: Place) {
        show(
            Page(
                rows = listOf(
                    Row.Header(place.name, place.displayAddress.ifBlank { null }),
                    Row.Action(ACTION_SET_HOME, R.drawable.ic_home, getString(R.string.fav_set, Favorite.HOME.toward), accent = true),
                    Row.Action(ACTION_SET_WORK, R.drawable.ic_work, getString(R.string.fav_set, Favorite.WORK.toward)),
                    Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel)),
                ),
                menuPlace = place,
            ),
            push = true,
        )
    }

    /** 등록된 집·회사를 길게 눌렀을 때: 바꾸기·지우기 */
    private fun showFavoriteMenu(kind: Favorite, place: Place) {
        show(
            Page(
                rows = listOf(
                    Row.Header(kind.label, place.name),
                    Row.Action(ACTION_FAV_CHANGE, R.drawable.ic_edit, getString(R.string.fav_change), accent = true),
                    Row.Action(ACTION_FAV_REMOVE, R.drawable.ic_delete, getString(R.string.fav_remove, kind.label)),
                    Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel)),
                ),
                menuFavorite = kind,
            ),
            push = true,
        )
    }

    private fun startRegister(kind: Favorite) {
        showRegister(kind)
        openInput()
    }

    /** 집·회사 등록 화면 (검색해서 고르면 등록). 새로 시작하는 일이라 그 아래는 처음 화면 하나 */
    private fun showRegister(kind: Favorite) {
        searchJob?.cancel()
        below.clear()
        page = Page(rows = homeRows())
        show(
            Page(
                rows = listOf(
                    Row.Header(getString(R.string.fav_pick, kind.toward)),
                    Row.Action(ACTION_INPUT, R.drawable.ic_search, getString(R.string.search_hint), accent = true),
                    Row.Action(ACTION_STOP_REGISTER, R.drawable.ic_close, getString(R.string.cancel)),
                ),
                registering = kind,
            ),
            push = true,
        )
    }

    private fun register(kind: Favorite, place: Place) {
        Favorites.set(this, kind, place)
        ShortcutTileService.refresh(this)
        Toast.makeText(this, getString(R.string.fav_registered, kind.toward), Toast.LENGTH_SHORT).show()
        showHome()
    }

    // ───────── 검색 ─────────

    /** Wear OS 기본 입력 화면(음성·키보드·손글씨). 없으면 글자 입력 창 */
    private fun openInput() {
        val kind = page.registering
        val label = if (kind != null) getString(R.string.fav_prompt, kind.toward) else getString(R.string.search_prompt)
        val remoteInput = RemoteInput.Builder(KEY_QUERY).setLabel(label).build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        RemoteInputIntentHelper.putTitleExtra(intent, if (kind != null) getString(R.string.fav_pick, kind.toward) else getString(R.string.search_hint))
        try {
            inputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = label
            }
            AlertDialog.Builder(this)
                .setView(edit)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    edit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::search)
                }
                .show()
        }
    }

    private fun search(query: String) {
        searchJob?.cancel()
        val kind = page.registering
        // 결과 위에서 다시 찾으면 결과를 바꾼다 (뒤로 → 처음 화면·등록 화면). 그 밖엔 지금 화면 위에 쌓는다
        show(Page(registering = kind, results = true, status = getString(R.string.searching)), push = !page.results)
        searchJob = lifecycleScope.launch {
            // 내 주변부터 — 최근 위치가 없으면 대략적 위치를 잠깐 잡아 보고 찾는다 (위치 없이 찾으면 전국에서 아무 데나 나옴)
            val result = withContext(Dispatchers.IO) { runCatching { finder.search(query) } }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val title = if (kind != null) getString(R.string.fav_pick, kind.toward) else "‘$query’"
            val rows = mutableListOf<Row>()
            result.onSuccess { found ->
                val places = found.places
                rows += Row.Header(title, if (kind != null) "‘$query’ ${places.size}곳" else "${places.size}곳")
                if (places.isEmpty()) rows += Row.Note(getString(R.string.no_results))
                else if (found.center == null) rows += Row.Note(getString(R.string.search_nationwide))
                rows += places.map { Row.PlaceItem(it) }
            }.onFailure { e ->
                ErrorLog.record("검색", e)
                rows += Row.Header(title)
                rows += Row.Note(e.message ?: "검색하지 못했어요")
            }
            rows += Row.Action(ACTION_INPUT, R.drawable.ic_search, "다시 검색")
            if (kind != null) rows += Row.Action(ACTION_STOP_REGISTER, R.drawable.ic_close, getString(R.string.cancel))
            show(Page(rows = rows, registering = kind, results = true), push = false)
        }
    }

    // ───────── 내 주변 ─────────

    private fun showNearbyKinds() {
        val rows = mutableListOf<Row>(Row.Header(getString(R.string.nearby_title), getString(R.string.nearby_sub)))
        rows += NearbyKind.entries.map { Row.Action(nearbyAction(it), iconOf(it), it.label) }
        show(Page(rows = rows), push = true)
    }

    /** 내 주변 [kind] 를 가까운 순으로 (다시 시도면 그 화면을 바꾼다) */
    private fun findNearby(kind: NearbyKind) {
        searchJob?.cancel()
        show(Page(nearby = kind, status = getString(R.string.nearby_searching, kind.label)), push = page.nearby != kind)
        searchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { finder.nearby(kind) } }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val title = getString(R.string.nearby_kind_title, kind.label)
            val retry = Row.Action(nearbyAction(kind), R.drawable.ic_refresh, getString(R.string.retry))
            val found = result.getOrNull()
            val error = result.exceptionOrNull()
            val rows = when {
                error != null -> {
                    ErrorLog.record("내 주변", error)
                    listOf(Row.Header(title), Row.Note(error.message ?: getString(R.string.nearby_failed)), retry)
                }
                found == null -> listOf(Row.Header(title), Row.Note(getString(R.string.nearby_no_location)), retry)
                found.places.isEmpty() -> listOf(Row.Header(title), Row.Note(getString(R.string.nearby_none)), retry)
                else -> listOf(Row.Header(title, getString(R.string.nearby_count, found.places.size))) + found.places.map { Row.PlaceItem(it) }
            }
            show(Page(rows = rows, nearby = kind), push = false)
        }
    }

    @DrawableRes
    private fun iconOf(kind: NearbyKind): Int = when (kind) {
        NearbyKind.STORE -> R.drawable.ic_store
        NearbyKind.PHARMACY -> R.drawable.ic_pharmacy
        NearbyKind.TOILET -> R.drawable.ic_wc
        NearbyKind.CAFE -> R.drawable.ic_cafe
        NearbyKind.SUBWAY -> R.drawable.ic_subway
    }

    // ───────── 길찾기로 ─────────

    private fun choose(place: Place) {
        RouteRepository.selectDestination(place)
        RouteRepository.addRecent(this, place)
        startActivity(Intent(this, DirectionsActivity::class.java))
    }

    /** 집·회사로 바로 길찾기 (최근 목적지에는 넣지 않는다 — 이미 위에 있으므로) */
    private fun goFavorite(kind: Favorite, place: Place) {
        RouteRepository.selectDestination(kind.asDestination(place))
        startActivity(Intent(this, DirectionsActivity::class.java))
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val STATE_REGISTERING = "registering"
        internal const val ACTION_INPUT = "input"
        private const val ACTION_CLEAR = "clear"
        private const val ACTION_UPDATE = "update"
        /** 메뉴의 '취소' — 한 단계 뒤로 */
        internal const val ACTION_CANCEL = "cancel"
        /** 등록 화면의 '취소' — 등록을 그만두고 처음 화면으로 */
        private const val ACTION_STOP_REGISTER = "stop_register"
        private const val ACTION_SET_HOME = "set_home"
        private const val ACTION_SET_WORK = "set_work"
        private const val ACTION_FAV_CHANGE = "fav_change"
        private const val ACTION_FAV_REMOVE = "fav_remove"
        internal const val ACTION_NEARBY = "nearby"
        internal const val ACTION_NEARBY_KIND = "nearby:"

        internal fun nearbyAction(kind: NearbyKind) = ACTION_NEARBY_KIND + kind.name

        private fun nearbyKindOf(id: String): NearbyKind? =
            if (id.startsWith(ACTION_NEARBY_KIND)) runCatching { NearbyKind.valueOf(id.removePrefix(ACTION_NEARBY_KIND)) }.getOrNull() else null

        internal val liveFinder: (Context) -> PlaceSearch = { PlaceSearch.live(it) }

        /** 검색기 (테스트에서 바꿔 끼움) */
        @VisibleForTesting
        internal var finderFor: (Context) -> PlaceSearch = liveFinder

        /** 타일에서 '집으로'·'회사로' (Favorite 이름) */
        const val EXTRA_FAVORITE = "favorite"
        /** 타일에서 '검색' — 바로 음성·키보드 입력 */
        const val EXTRA_SEARCH = "search"

        fun favoriteIntent(context: Context, kind: Favorite) =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_FAVORITE, kind.name)

        fun searchIntent(context: Context) = Intent(context, SearchActivity::class.java).putExtra(EXTRA_SEARCH, true)
    }
}
