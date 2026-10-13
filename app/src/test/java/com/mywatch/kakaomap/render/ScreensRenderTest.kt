package com.mywatch.kakaomap.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.PathStep
import com.mywatch.kakaomap.model.RouteChoices
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitSort
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RouteRows
import com.mywatch.kakaomap.ui.WearList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import android.view.Window
import java.io.File
import java.io.FileOutputStream

/**
 * 워치9 44mm(480×480 원형, 밀도 2.125 ≈ 340dpi) 크기로 목록 화면을 실제 안드로이드 레이아웃으로 그려 PNG 로 남긴다.
 * 글자 넘침·잘림을 눈으로 확인하는 용도 — ./gradlew testDebugUnitTest -Prender, 결과는 app/build/render/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w226dp-h226dp-round-340dpi", application = Application::class)
class ScreensRenderTest {

    private val out = File("build/render").apply { mkdirs() }

    private fun route(mode: TravelMode, m: Int, s: Int, vararg names: String) = SimpleRoute(
        mode, m, s,
        names.mapIndexed { i, n ->
            PathStep(n, m / names.size, s / names.size, Coord(36.35 + i * 0.001, 127.38), listOf(Coord(36.35 + i * 0.001, 127.38), Coord(36.351 + i * 0.001, 127.381)))
        },
    )

    private val bikeChoices = RouteChoices.merge(listOf(
        RouteOption.BIKE_ROAD to route(TravelMode.BIKE, 4384, 996, "출발", "우회전", "자전거도로 진입", "좌회전"),
        RouteOption.BIKE_SHORTEST to route(TravelMode.BIKE, 3104, 781, "출발", "좌회전", "직진"),
        RouteOption.BIKE_COMFORT to route(TravelMode.BIKE, 3220, 799, "출발", "우회전", "직진"),
    ))

    private val walkChoices = RouteChoices.merge(listOf(
        RouteOption.WALK_BROAD to route(TravelMode.WALK, 5538, 5115, "출발", "횡단보도", "우회전"),
        RouteOption.WALK_COMFORT to route(TravelMode.WALK, 5538, 5115, "출발", "횡단보도", "우회전"),
        RouteOption.WALK_SHORTEST to route(TravelMode.WALK, 5410, 4920, "출발", "계단", "좌회전"),
    ))

    private fun step(type: TransitStepType, min: Int, vehicle: Vehicle? = null, guidance: String = "") = TransitStep(
        type, guidance, 1000, min * 60, stops = listOf("대전광역시청", "둔산경찰서", "대전역"),
        vehicles = listOfNotNull(vehicle), points = listOf(Coord(36.35, 127.38), Coord(36.33, 127.43)),
    )

    private val transit = listOf(
        TransitRoute("BUS", 6200, 31 * 60, 0, 1500, listOf(
            step(TransitStepType.WALK, 4), step(TransitStepType.BUS, 22, Vehicle("간선", "707"), "간선 707 (대전광역시청 > 대전역)"), step(TransitStepType.WALK, 5),
        )),
        TransitRoute("BUS_AND_SUBWAY", 6900, 27 * 60, 1, 1500, listOf(
            step(TransitStepType.WALK, 6), step(TransitStepType.SUBWAY, 12, Vehicle("1호선", "대전 1호선"), "대전 1호선 (시청 > 대전역)"),
            step(TransitStepType.BUS, 6, Vehicle("지선", "311")), step(TransitStepType.WALK, 3),
        )),
    )

    private fun head(mode: TravelMode, seconds: Map<TravelMode, Int>) = listOf(
        Row.Header("→ 한밭수목원", "내 위치에서 출발"),
        Row.ModeTabs(mode, seconds, RouteChoices.fastestMode(seconds)),
    )

    private fun shoot(name: String, rows: List<Row>, pages: Int = 3, fontScale: Float = 1f) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)   // 워치 테마엔 제목 막대가 없다
        if (fontScale != 1f) {
            val conf = activity.resources.configuration
            conf.fontScale = fontScale
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(conf, activity.resources.displayMetrics)
        }
        controller.setup()
        activity.setTheme(R.style.Theme_KakaoWatchMap)
        activity.setContentView(R.layout.activity_list)
        val ui = WearList(activity, {})
        ui.rows = rows
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val size = activity.resources.displayMetrics.widthPixels
        for (page in 0 until pages) {
            if (page > 0) {
                ui.list.scrollBy(0, (size * 0.8f).toInt())
                shadowOf(Looper.getMainLooper()).idle()
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            root.draw(c)
            // 둥근 화면 밖은 검게 + 테두리 표시
            val mask = Path().apply {
                addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
            }
            c.drawPath(mask, Paint().apply { color = Color.rgb(40, 40, 40) })
            FileOutputStream(File(out, "${name}_$page.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        controller.pause().stop().destroy()
    }

    private fun simpleRows(choices: List<com.mywatch.kakaomap.model.RouteChoice>, missing: String? = null): List<Row> {
        val picked = RouteChoices.pick(choices, null)!!
        val fastest = RouteChoices.fastest(choices)
        return choices.mapIndexed { i, c -> Row.OptionCard(i, c, selected = c == picked, fastest = c == fastest) } +
            (missing?.let { listOf(Row.Note("$it 경로를 받지 못했어요"), Row.Action("retry_options", R.drawable.ic_refresh, "다시 받기")) } ?: emptyList()) +
            Row.Action("navigate", R.drawable.ic_navigation, "안내 시작", accent = true) +
            Row.Action("map", R.drawable.ic_map, "지도에서 보기") +
            RouteRows.simpleSteps(picked.route, "한밭수목원")
    }

    private val times = mapOf(TravelMode.TRANSIT to 27 * 60, TravelMode.WALK to 4920, TravelMode.BIKE to 781)

    @Test
    fun bikeOptions() = shoot("bike", head(TravelMode.BIKE, times) + simpleRows(bikeChoices))

    @Test
    fun walkOptionsLongTimes() = shoot("walk", head(TravelMode.WALK, times) + simpleRows(walkChoices, missing = "최단 거리"))

    @Test
    fun bikeOptionsLargeFont() = shoot("bike_font115", head(TravelMode.BIKE, times) + simpleRows(bikeChoices), fontScale = 1.15f)

    private fun place(name: String, category: String, address: String, m: Int?) =
        com.mywatch.kakaomap.model.Place("1", name, category, address, address, Coord(36.36, 127.39), m)

    @Test
    fun searchHome() = shoot(
        "search",
        listOf(
            Row.Header("길찾기"),
            Row.Action("input", R.drawable.ic_search, "도착지 검색", accent = true),
            Row.FavoriteItem(com.mywatch.kakaomap.data.FavoriteSlot(com.mywatch.kakaomap.data.Favorite.HOME, place("선비마을아파트", "아파트", "대전 대덕구 송촌동", null))),
            Row.FavoriteItem(com.mywatch.kakaomap.data.FavoriteSlot(com.mywatch.kakaomap.data.Favorite.WORK, null)),
            Row.Note("길게 누르면 집·회사로 등록할 수 있어요"),
            Row.Header("최근 목적지"),
            Row.PlaceItem(place("한밭수목원", "식물원,수목원", "대전 서구 둔산대로 169", 7300), recent = true),
            Row.PlaceItem(place("대전역 KTX 고속철도 승강장", "기차역", "대전 동구 중앙로 215", 6100), recent = true),
            Row.Action("clear", R.drawable.ic_delete, "최근 목적지 지우기"),
            Row.Action("update", R.drawable.ic_info, "앱 정보 · 업데이트"),
        ),
    )

    @Test
    fun transitDetail() = shoot(
        "detail",
        listOf(
            Row.Header("27분 · 오전 11:58 도착", "1,500원 · 환승 1회 · 6.9km"),
            Row.Action("navigate", R.drawable.ic_navigation, "안내 시작", accent = true),
            Row.Action("map", R.drawable.ic_map, "지도에서 보기"),
        ) + RouteRows.transitTimeline(transit[1], "한밭수목원", true),
        pages = 4,
    )

    @Test
    fun appInfoWithErrors() {
        val now = System.currentTimeMillis()
        val e = com.mywatch.kakaomap.data.ErrorLogFormat
        shoot(
            "info",
            listOf(
                Row.Header("카카오 지도", "버전 2.12 (112)"),
                Row.Note("최신 버전이에요"),
                Row.Action("check", R.drawable.ic_refresh, "업데이트 확인"),
                Row.Header("최근 오류 기록"),
                Row.Note(e.line(com.mywatch.kakaomap.data.ErrorEntry(now, "실시간 버스", "공공데이터 키가 이 기능에 아직 등록되지 않았어요", "2.12", 3))),
                Row.Note(e.line(com.mywatch.kakaomap.data.ErrorEntry(now - 3_600_000, "앱 멈춤", "IllegalStateException: boom\n← DirectionsActivity.render:210\n← WearList.a:12", "2.11"))),
                Row.Action("clear_errors", R.drawable.ic_delete, "오류 기록 지우기"),
                Row.Note("실시간 버스(공공데이터) 키: 들어 있음"),
            ),
        )
    }

    @Test
    fun transitCards() = shoot(
        "transit",
        head(TravelMode.TRANSIT, times) + Row.TransitChoice(TransitFilter.ALL, 2, true, TransitSort.FASTEST) +
            Row.TransitCard(1, transit[1], "1호선 3분 후", fastest = true) +
            Row.TransitCard(0, transit[0], "707번 12분 후 (7정거장 전)", fastest = false),
    )
}
