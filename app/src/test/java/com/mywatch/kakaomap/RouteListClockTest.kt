package com.mywatch.kakaomap

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Fix
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.RouteChoice
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.ui.DirectionsActivity
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * 리뷰에서 찾음: 경로 카드의 'HH:MM 도착'은 그릴 때 시계로 정하는데, 길찾기 목록은 1분마다 기차·버스 시간표 경로가 있을 때만
 * 다시 그렸다 — 도보·자전거·시내 대중교통 목록은 10:00 에 열면 10:20 에도 '10:25 도착'. 이제 1분마다(손목을 내린 동안은 쉼) 다시 넣고,
 * 바뀐 카드(도착 시각을 보여 주는 카드)만 다시 그린다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)
class RouteListClockTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val here = Coord(36.3504, 127.3849)
    private val place = Place("p1", "한밭수목원", "공원", "대전 서구", "대전 서구 둔산대로 169", Coord(36.3670, 127.3880), null, "AT4")

    @Before
    fun setUp() {
        RouteRepository.init(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After
    fun tearDown() {
        RouteRepository.destination = null
        RouteRepository.clearRoutes()
        RouteRepository.routeOrigin = null
    }

    @Test
    fun theWalkListRedrawsItsArrivalTimesEveryMinute() {
        // 도보 탭으로, 찾아 둔 경로 그대로 (네트워크 없이)
        RouteRepository.selectDestination(place)
        context.getSharedPreferences("directions", Context.MODE_PRIVATE).edit { putString("mode", TravelMode.WALK.name) }
        // 앞선 시험이 남긴 위치보다 새 위치가 되게 (부팅 후 시각은 시험마다 처음부터 — 앱의 마지막 위치는 남아 있다)
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        RouteRepository.updateFix(Fix(here, 5f, SystemClock.elapsedRealtime()))
        RouteRepository.routeOrigin = here
        RouteRepository.walkChoices = listOf(RouteChoice(listOf(RouteOption.WALK_BROAD), SimpleRoute(TravelMode.WALK, 2_000, 1_500, emptyList())))
        RouteRepository.bikeChoices = listOf(RouteChoice(listOf(RouteOption.BIKE_ROAD), SimpleRoute(TravelMode.BIKE, 2_100, 600, emptyList())))
        RouteRepository.transitRoutes = emptyList()

        val a = Robolectric.buildActivity(DirectionsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val adapter = a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter
        val card = adapter.rows.indexOfFirst { it is Row.OptionCard }
        assertTrue("도보 경로 카드: ${adapter.rows}", card >= 0)
        val redrawn = ArrayList<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                repeat(itemCount) { redrawn += positionStart + it }
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = onItemRangeChanged(positionStart, itemCount)
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue("1분 뒤 도착 시각 카드를 다시 그림: $redrawn", card in redrawn)
    }
}
