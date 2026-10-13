package com.mywatch.kakaomap.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.nav.Navigator
import com.mywatch.kakaomap.net.BusArrivalService
import com.mywatch.kakaomap.net.TagoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 경로 상세.
 * · 길찾기 목록에서 연 대중교통 경로: 출발 → 도보 → 버스(실시간 도착) → 지하철 → … → 도착 타임라인 + 안내 시작/지도에서 보기
 * · 지도 아래 경로 카드에서 연 경우: 지금 지도에 띄운 '바로 그 경로'(대중교통·도보·자전거)의 상세 + 안내 시작/종료
 */
class RouteDetailActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var place: Place
    private var origin: Coord? = null
    private var transit: TransitRoute? = null
    private var simple: SimpleRoute? = null
    private var fromActive = false
    private var ambient: AmbientCover? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromActive = intent.getBooleanExtra(EXTRA_ACTIVE, false)
        if (fromActive) {
            val a = RouteRepository.activeRoute
            if (a == null) {
                finish()
                return
            }
            place = a.place
            origin = a.origin
            transit = a.transit
            simple = a.simple
        } else {
            val r = RouteRepository.transitRoutes?.getOrNull(intent.getIntExtra(EXTRA_INDEX, 0))
            val d = RouteRepository.destination
            if (r == null || d == null) {
                finish()
                return
            }
            place = d
            origin = RouteRepository.routeOrigin
            transit = r
        }
        if (transit == null && simple == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        ui.rows = buildRows()
        // 손목을 내리면 검은 화면에 요약만 (Wear OS 6+ 는 이 화면을 켜 둔 채 둔다), 그동안 새로 고침은 쉰다
        ambient = AmbientCover(this, ::ambientSummary) { inAmbient ->
            if (!inAmbient && transit != null && TagoApi.isConfigured &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) lifecycleScope.launch { refreshLive() }
        }

        // 화면을 보는 동안 30초마다 실시간 버스 도착 정보를 새로 고침 (손목을 내린 동안은 쉼)
        if (transit != null && TagoApi.isConfigured) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (ambient?.inAmbient != true) refreshLive()
                    delay(30_000)
                }
            }
        }
    }

    private fun ambientSummary(): String {
        val t = transit
        val s = simple
        return when {
            t != null -> t.secondsFrom(System.currentTimeMillis()).let { sec ->
                "→ ${place.name}\n${Format.duration(sec)} · ${Format.clockAfter(sec)} 도착"
            }
            s != null -> "→ ${place.name}\n${RoutePresentation.simpleSummary(s)}"
            else -> "→ ${place.name}"
        }
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val t = transit
        val s = simple
        if (t != null) {
            // 기차·버스 시간표 경로는 도착 시각이 정해져 있다 — 지금 기준 남은 시간
            val sec = t.secondsFrom(System.currentTimeMillis())
            rows += Row.Header(
                "${Format.duration(sec)} · ${Format.clockAfter(sec)} 도착",
                listOfNotNull(t.fare?.let { Format.fare(it) }, Format.transfers(t.transfers), Format.distance(t.totalDistanceM))
                    .joinToString(" · "),
            )
        } else if (s != null) {
            rows += Row.Header("→ ${place.name}", RoutePresentation.simpleSummary(s))
        }
        rows += actions()
        if (t != null) {
            rows += RouteRows.transitTimeline(t, place.name, TagoApi.isConfigured)
            if (!TagoApi.isConfigured) rows += Row.Note(getString(R.string.tago_missing))
        } else if (s != null) {
            rows += RouteRows.simpleSteps(s, place.name)
        }
        return rows
    }

    private fun actions(): List<Row> {
        val active = RouteRepository.activeRoute
        if (!fromActive) {
            return listOf(
                Row.Action(ACTION_NAVIGATE, R.drawable.ic_navigation, getString(R.string.start_navigation), accent = true),
                Row.Action(ACTION_MAP, R.drawable.ic_map, getString(R.string.show_on_map)),
                Row.Note(getString(R.string.transit_nav_note)),
            )
        }
        val list = mutableListOf<Row>()
        if (active != null && !active.startNavigation) {
            list += Row.Action(ACTION_NAVIGATE, R.drawable.ic_navigation, getString(R.string.start_navigation), accent = true)
        }
        list += Row.Action(ACTION_BACK, R.drawable.ic_map, getString(R.string.back_to_map))
        list += Row.Action(ACTION_END, R.drawable.ic_close, getString(R.string.end_route))
        return list
    }

    private suspend fun refreshLive() {
        val t = transit ?: return
        // 기차·고속버스 경로는 타러 가는 버스만 (내려서 탈 버스는 몇 시간 뒤라 지금 도착 정보는 엉뚱함)
        TransitGuide.liveBusIndexes(t).forEach { i ->
            val s = t.steps[i]
            val text = withContext(Dispatchers.IO) { BusArrivalService.describe(s) } ?: return@forEach
            val rows = ui.adapter.rows
            val pos = rows.indexOfFirst { it is Row.Step && it.key == "s$i" }
            if (pos >= 0) ui.adapter.replace(pos, (rows[pos] as Row.Step).copy(live = text))
        }
    }

    private fun onRow(row: Row) {
        if (row !is Row.Action) return
        when (row.id) {
            ACTION_NAVIGATE, ACTION_MAP -> show(navigate = row.id == ACTION_NAVIGATE)
            ACTION_BACK -> MainActivity.open(this)
            ACTION_END -> {
                RouteRepository.activeRoute = null
                Navigator.sync()
                NavigationService.stop(this)
                MainActivity.open(this)
            }
        }
    }

    /** 이 경로를 지도에 띄운다 (navigate = 화면이 꺼져도 따라가며 진동 안내) */
    private fun show(navigate: Boolean) {
        val active = RouteRepository.activeRoute
        if (fromActive && active != null) {
            RouteRepository.activeRoute = active.copy(startNavigation = navigate || active.startNavigation)
        } else {
            val t = transit ?: return
            val o = origin ?: return
            RouteRepository.activeRoute = RoutePresentation.activeFromTransit(t, place, o, navigate)
        }
        MainActivity.open(this)
    }

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_ACTIVE = "active"
        private const val ACTION_MAP = "map"
        private const val ACTION_NAVIGATE = "navigate"
        private const val ACTION_BACK = "back"
        private const val ACTION_END = "end"

        /** 길찾기 목록의 n번째 대중교통 경로 */
        fun intent(context: Context, index: Int) =
            Intent(context, RouteDetailActivity::class.java).putExtra(EXTRA_INDEX, index)

        /** 지금 지도에 띄운 경로 */
        fun intentActive(context: Context) =
            Intent(context, RouteDetailActivity::class.java).putExtra(EXTRA_ACTIVE, true)
    }
}
