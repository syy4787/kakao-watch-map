package com.mywatch.kakaomap.ui

import android.app.AlertDialog
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.update.UpdateActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 도착지 검색: 최근 목적지 → 음성/키보드 입력 → 장소 목록 → 길찾기 */
class SearchActivity : ComponentActivity() {

    private lateinit var ui: WearList

    private val inputLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_QUERY) }?.toString()
        if (!text.isNullOrBlank()) search(text.trim())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        showHome()
    }

    private fun showHome() {
        val recents = RouteRepository.recents(this)
        val rows = mutableListOf<Row>(
            Row.Header(getString(R.string.search_title)),
            Row.Action(ACTION_INPUT, R.drawable.ic_search, getString(R.string.search_hint), accent = true),
        )
        if (recents.isNotEmpty()) {
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
            }
            is Row.PlaceItem -> choose(row.place)
            else -> Unit
        }
    }

    /** Wear OS 기본 입력 화면(음성·키보드·손글씨). 없으면 글자 입력 창 */
    private fun openInput() {
        val remoteInput = RemoteInput.Builder(KEY_QUERY).setLabel(getString(R.string.search_prompt)).build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        RemoteInputIntentHelper.putTitleExtra(intent, getString(R.string.search_hint))
        try {
            inputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = getString(R.string.search_hint)
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
            result.onSuccess { places ->
                val rows = mutableListOf<Row>(Row.Header("‘$query’", "${places.size}곳"))
                if (places.isEmpty()) rows += Row.Note(getString(R.string.no_results))
                rows += places.map { Row.PlaceItem(it) }
                rows += Row.Action(ACTION_INPUT, R.drawable.ic_search, "다시 검색")
                ui.rows = rows
            }.onFailure { e ->
                ui.rows = listOf(
                    Row.Header("‘$query’"),
                    Row.Note(e.message ?: "검색하지 못했어요"),
                    Row.Action(ACTION_INPUT, R.drawable.ic_search, "다시 검색"),
                )
            }
        }
    }

    private fun choose(place: Place) {
        RouteRepository.selectDestination(place)
        RouteRepository.addRecent(this, place)
        startActivity(Intent(this, DirectionsActivity::class.java))
    }

    private companion object {
        const val KEY_QUERY = "query"
        const val ACTION_INPUT = "input"
        const val ACTION_CLEAR = "clear"
        const val ACTION_UPDATE = "update"
    }
}
