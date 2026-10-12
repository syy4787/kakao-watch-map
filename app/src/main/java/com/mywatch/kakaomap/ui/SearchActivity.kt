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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.Favorite
import com.mywatch.kakaomap.data.Favorites
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.update.UpdateActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 도착지 검색 첫 화면 (카카오맵 길찾기 입력 화면처럼):
 * 검색 → 집·회사 바로가기 → 최근 목적지.
 * 집·회사는 등록 안 됐으면 톡 → 검색해서 등록, 등록됐으면 톡 → 바로 길찾기, 길게 누르면 바꾸기·지우기.
 * 최근 목적지·검색 결과를 길게 누르면 집·회사로 등록.
 */
class SearchActivity : ComponentActivity() {

    private lateinit var ui: WearList
    /** 집·회사를 등록하려고 검색하는 중이면 그 종류 */
    private var registering: Favorite? = null
    /** 길게 눌러 연 메뉴의 대상 */
    private var menuPlace: Place? = null
    private var menuFavorite: Favorite? = null

    private val inputLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_QUERY) }?.toString()
        if (!text.isNullOrBlank()) search(text.trim())
        else if (registering != null && ui.rows.none { it is Row.PlaceItem }) {
            // 등록하려다 입력을 취소했으면 첫 화면으로
            registering = null
            showHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow, ::onLongRow)
        registering = savedInstanceState?.getString(STATE_REGISTERING)?.let { runCatching { Favorite.valueOf(it) }.getOrNull() }
        showHome()
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
        registering?.let { outState.putString(STATE_REGISTERING, it.name) }
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

    private fun showHome() {
        menuPlace = null
        menuFavorite = null
        val recents = RouteRepository.recents(this)
        val slots = Favorites.slots(this)
        val rows = mutableListOf<Row>(
            Row.Header(getString(R.string.search_title)),
            Row.Action(ACTION_INPUT, R.drawable.ic_search, getString(R.string.search_hint), accent = true),
        )
        rows += slots.map { Row.FavoriteItem(it) }
        if (recents.isNotEmpty()) {
            if (slots.any { it.place == null }) rows += Row.Note(getString(R.string.fav_hint))
            rows += Row.Header(getString(R.string.recent_places))
            rows += recents.map { Row.PlaceItem(it, recent = true) }
            rows += Row.Action(ACTION_CLEAR, R.drawable.ic_delete, getString(R.string.clear_recents))
        }
        rows += Row.Action(ACTION_UPDATE, R.drawable.ic_info, getString(R.string.app_info_update))
        ui.rows = rows
    }

    private fun onRow(row: Row) {
        when (row) {
            is Row.Action -> when (row.id) {
                ACTION_INPUT -> openInput()
                ACTION_CLEAR -> {
                    RouteRepository.clearRecents(this)
                    showHome()
                }
                ACTION_UPDATE -> startActivity(Intent(this, UpdateActivity::class.java))
                ACTION_CANCEL -> {
                    registering = null
                    showHome()
                }
                ACTION_SET_HOME, ACTION_SET_WORK -> menuPlace?.let { register(if (row.id == ACTION_SET_HOME) Favorite.HOME else Favorite.WORK, it) }
                ACTION_FAV_CHANGE -> menuFavorite?.let { startRegister(it) }
                ACTION_FAV_REMOVE -> menuFavorite?.let {
                    Favorites.clear(this, it)
                    showHome()
                }
            }
            is Row.FavoriteItem -> {
                val place = row.slot.place
                if (place != null) goFavorite(row.slot.kind, place) else startRegister(row.slot.kind)
            }
            is Row.PlaceItem -> {
                val kind = registering
                if (kind != null) register(kind, row.place) else choose(row.place)
            }
            else -> Unit
        }
    }

    private fun onLongRow(row: Row) {
        when (row) {
            is Row.PlaceItem -> if (registering == null) showPlaceMenu(row.place)
            is Row.FavoriteItem -> row.slot.place?.let { showFavoriteMenu(row.slot.kind, it) }
            else -> Unit
        }
    }

    /** 장소를 길게 눌렀을 때: 집으로·회사로 등록 */
    private fun showPlaceMenu(place: Place) {
        menuPlace = place
        menuFavorite = null
        ui.rows = listOf(
            Row.Header(place.name, place.displayAddress.ifBlank { null }),
            Row.Action(ACTION_SET_HOME, R.drawable.ic_home, getString(R.string.fav_set, Favorite.HOME.toward), accent = true),
            Row.Action(ACTION_SET_WORK, R.drawable.ic_work, getString(R.string.fav_set, Favorite.WORK.toward)),
            Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel)),
        )
    }

    /** 등록된 집·회사를 길게 눌렀을 때: 바꾸기·지우기 */
    private fun showFavoriteMenu(kind: Favorite, place: Place) {
        menuFavorite = kind
        menuPlace = null
        ui.rows = listOf(
            Row.Header(kind.label, place.name),
            Row.Action(ACTION_FAV_CHANGE, R.drawable.ic_edit, getString(R.string.fav_change), accent = true),
            Row.Action(ACTION_FAV_REMOVE, R.drawable.ic_delete, getString(R.string.fav_remove, kind.label)),
            Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel)),
        )
    }

    private fun startRegister(kind: Favorite) {
        registering = kind
        menuFavorite = null
        menuPlace = null
        ui.rows = listOf(
            Row.Header(getString(R.string.fav_pick, kind.toward)),
            Row.Action(ACTION_INPUT, R.drawable.ic_search, getString(R.string.search_hint), accent = true),
            Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel)),
        )
        openInput()
    }

    private fun register(kind: Favorite, place: Place) {
        Favorites.set(this, kind, place)
        registering = null
        Toast.makeText(this, getString(R.string.fav_registered, kind.toward), Toast.LENGTH_SHORT).show()
        showHome()
    }

    /** Wear OS 기본 입력 화면(음성·키보드·손글씨). 없으면 글자 입력 창 */
    private fun openInput() {
        val kind = registering
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
        ui.showStatus(getString(R.string.searching))
        lifecycleScope.launch {
            val near = RouteRepository.freshLocation(30 * 60_000L)
            val result = withContext(Dispatchers.IO) { runCatching { KakaoApi.searchPlaces(query, near) } }
            val kind = registering
            val title = if (kind != null) getString(R.string.fav_pick, kind.toward) else "‘$query’"
            result.onSuccess { places ->
                val rows = mutableListOf<Row>(Row.Header(title, if (kind != null) "‘$query’ ${places.size}곳" else "${places.size}곳"))
                if (places.isEmpty()) rows += Row.Note(getString(R.string.no_results))
                rows += places.map { Row.PlaceItem(it) }
                rows += Row.Action(ACTION_INPUT, R.drawable.ic_search, "다시 검색")
                if (kind != null) rows += Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel))
                ui.rows = rows
            }.onFailure { e ->
                ui.rows = listOfNotNull(
                    Row.Header(title),
                    Row.Note(e.message ?: "검색하지 못했어요"),
                    Row.Action(ACTION_INPUT, R.drawable.ic_search, "다시 검색"),
                    if (kind != null) Row.Action(ACTION_CANCEL, R.drawable.ic_close, getString(R.string.cancel)) else null,
                )
            }
        }
    }

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
        private const val ACTION_INPUT = "input"
        private const val ACTION_CLEAR = "clear"
        private const val ACTION_UPDATE = "update"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_SET_HOME = "set_home"
        private const val ACTION_SET_WORK = "set_work"
        private const val ACTION_FAV_CHANGE = "fav_change"
        private const val ACTION_FAV_REMOVE = "fav_remove"

        /** 타일에서 '집으로'·'회사로' (Favorite 이름) */
        const val EXTRA_FAVORITE = "favorite"
        /** 타일에서 '검색' — 바로 음성·키보드 입력 */
        const val EXTRA_SEARCH = "search"

        fun favoriteIntent(context: Context, kind: Favorite) =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_FAVORITE, kind.name)

        fun searchIntent(context: Context) = Intent(context, SearchActivity::class.java).putExtra(EXTRA_SEARCH, true)
    }
}
