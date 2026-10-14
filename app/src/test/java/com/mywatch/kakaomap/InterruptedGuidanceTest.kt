package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.TravelMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 안내 중에 앱 본체 프로세스가 꺼지면(2GB 워치에서 동영상 재생 화면이 메모리를 많이 쓸 때 등) 경로·안내가 말없이 사라졌다.
 * 안내를 시작한 목적지는 기기에 적어 두고, 정상으로 끝나면(도착·종료·스스로 끝냄) 지운다 —
 * 다음에 지도를 열 때 남아 있으면 '끊긴 안내'로 그 목적지를 다시 찾아 준다 (6시간 안).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InterruptedGuidanceTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val place = Place("p1", "한밭수목원", "공원", "대전 서구", "대전 서구 둔산대로 169", Coord(36.367, 127.388), null, "AT4")
    private var now = 1_760_000_000_000L

    private fun route(navigate: Boolean, mode: TravelMode = TravelMode.WALK) =
        ActiveRoute(mode, place, "요약", Coord(36.35, 127.38), emptyList(), emptyList(), startNavigation = navigate)

    @Before
    fun setUp() {
        RouteRepository.init(context, clock = { now })
        RouteRepository.activeRoute = null
    }

    @After
    fun tearDown() {
        RouteRepository.activeRoute = null
    }

    @Test
    fun guidanceThatDiedWithTheProcessIsOfferedAgain() {
        RouteRepository.activeRoute = route(navigate = true, mode = TravelMode.BIKE)
        // (여기서 프로세스가 꺼짐 — 메모리의 경로는 사라지고 기록만 남음)
        val back = RouteRepository.takeInterrupted(context)
        assertEquals(place.name, back?.place?.name)
        assertEquals(place.coord, back?.place?.coord)
        assertEquals(TravelMode.BIKE, back?.mode)
        assertNull("한 번만", RouteRepository.takeInterrupted(context))
    }

    @Test
    fun guidanceThatEndedNormallyIsNotOffered() {
        RouteRepository.activeRoute = route(navigate = true)
        RouteRepository.activeRoute = null   // 도착·종료·스스로 끝냄
        assertNull(RouteRepository.takeInterrupted(context))
    }

    @Test
    fun aRouteOnlyShownOnTheMapIsNotGuidance() {
        RouteRepository.activeRoute = route(navigate = false)
        assertNull(RouteRepository.takeInterrupted(context))
    }

    @Test
    fun aLongAgoInterruptionIsForgotten() {
        RouteRepository.activeRoute = route(navigate = true)
        now += 7 * 3_600_000L
        assertNull(RouteRepository.takeInterrupted(context))
    }
}
