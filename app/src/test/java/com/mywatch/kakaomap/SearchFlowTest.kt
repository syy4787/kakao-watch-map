package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.SwipeDismissFrameLayout
import com.mywatch.kakaomap.data.Favorite
import com.mywatch.kakaomap.data.Favorites
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.NearbyKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.net.PlaceSearch
import com.mywatch.kakaomap.ui.DirectionsActivity
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.SearchActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 도착지 검색 화면을 실제로 써 보듯이 (점검 A8 + 내 주변):
 * 예전엔 결과·메뉴에서 뒤로를 누르면 검색 화면이 통째로 닫혔다 → 메뉴 → 결과 → 처음 화면 → 닫기, 한 단계씩.
 * '내 주변'은 편의점·약국·화장실·카페·지하철역을 가까운 순으로.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)
class SearchFlowTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val cityHall = Coord(36.3504, 127.3849)
    private var center: Coord? = cityHall
    private val searched = mutableListOf<String>()
    private val nearbyAsked = mutableListOf<NearbyKind>()

    private fun p(name: String, category: String, m: Int) = Place(name, name, category, "대전 서구 둔산동", "", Coord(36.35, 127.38), m)

    private val starbucks = listOf(p("스타벅스 대전시청역8번출구점", "스타벅스", 200), p("스타벅스 대전둔산남로점", "스타벅스", 400))
    private val stores = listOf(p("GS25 둔산리치점", "GS25", 191), p("CU 대전시청역점", "CU", 197))

    @Before
    fun setUp() {
        RouteRepository.init(context)
        RouteRepository.clearRecents(context)
        Favorite.entries.forEach { Favorites.clear(context, it) }
        SearchActivity.finderFor = {
            PlaceSearch(
                recent = { center },
                locate = { null },
                keyword = { q, _ -> searched += q; if (q == "스타벅스") starbucks else emptyList() },
                nearby = { kind, _ -> nearbyAsked += kind; if (kind == NearbyKind.STORE) stores else emptyList() },
            )
        }
    }

    @After
    fun tearDown() {
        SearchActivity.finderFor = SearchActivity.liveFinder
    }

    // ---------------- 도우미 ----------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle(a: SearchActivity) {
        val deadline = System.currentTimeMillis() + 3_000
        idle()
        while (System.currentTimeMillis() < deadline && a.findViewById<View>(R.id.status).visibility == View.VISIBLE) {
            Thread.sleep(5)
            idle()
        }
    }

    private fun open(intent: Intent? = null): SearchActivity {
        val a = (if (intent == null) Robolectric.buildActivity(SearchActivity::class.java) else Robolectric.buildActivity(SearchActivity::class.java, intent)).setup().get()
        settle(a)
        return a
    }

    private fun rows(a: SearchActivity): List<Row> = (a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter).rows

    private fun titles(a: SearchActivity) = rows(a).filterIsInstance<Row.Header>().map { it.title }

    private fun notes(a: SearchActivity) = rows(a).filterIsInstance<Row.Note>().map { it.text }

    private fun tap(a: SearchActivity, long: Boolean = false, which: (Row) -> Boolean) {
        val rv = a.findViewById<RecyclerView>(R.id.list)
        val i = rows(a).indexOfFirst(which)
        assertTrue("누를 줄이 있다: ${rows(a)}", i >= 0)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        rv.scrollToPosition(i)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        val v = rv.findViewHolderForAdapterPosition(i)!!.itemView
        if (long) v.performLongClick() else v.performClick()
        settle(a)
    }

    private fun tapAction(a: SearchActivity, id: String) = tap(a) { it is Row.Action && it.id == id }

    private fun tapPlace(a: SearchActivity, name: String, long: Boolean = false) = tap(a, long) { it is Row.PlaceItem && it.place.name == name }

    /** 말·키보드로 [text] 를 넣음 */
    private fun say(a: SearchActivity, text: String?) {
        val input = shadowOf(a).nextStartedActivityForResult
        assertNotNull("말·키보드 입력 화면", input)
        val result = Intent()
        if (text != null) {
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), result, Bundle().apply { putCharSequence("query", text) })
        }
        shadowOf(a).receiveResult(input.intent, if (text != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        settle(a)
    }

    private fun search(a: SearchActivity, text: String) {
        tapAction(a, SearchActivity.ACTION_INPUT)
        say(a, text)
    }

    private fun back(a: SearchActivity) {
        a.onBackPressedDispatcher.onBackPressed()
        settle(a)
    }

    private val home get() = context.getString(R.string.search_title)

    // ---------------- 뒤로 ----------------

    @Test
    fun backStepsFromAMenuToTheResultsThenHomeThenCloses() {
        val a = open()
        search(a, "스타벅스")
        assertEquals("‘스타벅스’", titles(a).first())
        tapPlace(a, "스타벅스 대전시청역8번출구점", long = true)
        assertEquals("스타벅스 대전시청역8번출구점", titles(a).first())   // 집·회사로 등록 메뉴
        back(a)
        assertEquals("메뉴 → 결과", "‘스타벅스’", titles(a).first())
        assertEquals("다시 찾지 않는다", listOf("스타벅스"), searched)
        back(a)
        assertEquals("결과 → 처음 화면", home, titles(a).first())
        assertFalse(a.isFinishing)
        back(a)
        assertTrue("처음 화면에서 뒤로 → 닫기", a.isFinishing)
    }

    /**
     * Wear OS 는 오른쪽으로 쓸면 액티비티를 그냥 닫는다('뒤로' 처리를 부르지 않음 — developer.android.com/training/wearables/views/navigation:
     * "Otherwise, when the user performs a swipe, they exit the entire app"). 돌아갈 곳이 있을 때만 목록이 쓸기를 받아 한 단계 뒤로
     */
    @Test
    fun swipingRightStepsBackInsideAndOnlyClosesFromHome() {
        val a = open()
        val swipe = a.findViewById<SwipeDismissFrameLayout>(R.id.swipe)
        assertFalse("처음 화면: 시스템이 닫게 둔다", swipe.isSwipeable)
        search(a, "스타벅스")
        assertTrue(swipe.isSwipeable)
        swiped(swipe)
        settle(a)
        assertEquals(home, titles(a).first())
        assertFalse(a.isFinishing)
        assertFalse(swipe.isSwipeable)
    }

    private fun swiped(swipe: SwipeDismissFrameLayout) {
        val m = SwipeDismissFrameLayout::class.java.getDeclaredMethod("performDismissFinishedCallbacks")
        m.isAccessible = true
        m.invoke(swipe)
    }

    @Test
    fun theMenusCancelIsOneStepBackToo() {
        val a = open()
        search(a, "스타벅스")
        tapPlace(a, "스타벅스 대전둔산남로점", long = true)
        tapAction(a, SearchActivity.ACTION_CANCEL)
        assertEquals("‘스타벅스’", titles(a).first())
    }

    @Test
    fun searchingAgainFromResultsReplacesThemSoBackIsHome() {
        val a = open()
        search(a, "스타벅스")
        search(a, "없는곳")
        assertEquals("‘없는곳’", titles(a).first())
        assertEquals(listOf(context.getString(R.string.no_results)), notes(a))
        back(a)
        assertEquals(home, titles(a).first())
    }

    @Test
    fun comingBackFromDirectionsShowsTheSameResults() {
        val a = open()
        search(a, "스타벅스")
        tapPlace(a, "스타벅스 대전둔산남로점")
        assertEquals(DirectionsActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        assertEquals("스타벅스 대전둔산남로점", RouteRepository.destination?.name)
        assertEquals("‘스타벅스’", titles(a).first())
        back(a)
        assertEquals(home, titles(a).first())
    }

    @Test
    fun registeringStepsBackToTheRegisterScreenThenHome() {
        val a = open(SearchActivity.favoriteIntent(context, Favorite.HOME))
        say(a, "스타벅스")
        assertEquals(context.getString(R.string.fav_pick, Favorite.HOME.toward), titles(a).first())
        assertEquals(2, rows(a).count { it is Row.PlaceItem })
        back(a)
        assertEquals("결과 → 등록 화면", context.getString(R.string.fav_pick, Favorite.HOME.toward), titles(a).first())
        assertTrue(rows(a).none { it is Row.PlaceItem })
        back(a)
        assertEquals(home, titles(a).first())
    }

    @Test
    fun cancellingTheInputWhileRegisteringGoesHome() {
        val a = open(SearchActivity.favoriteIntent(context, Favorite.WORK))
        say(a, null)
        assertEquals(home, titles(a).first())
    }

    @Test
    fun aSearchWithoutAnyLocationSaysItSearchedNationwide() {
        center = null
        val a = open()
        search(a, "스타벅스")
        assertEquals(listOf(context.getString(R.string.search_nationwide)), notes(a))
    }

    // ---------------- 내 주변 ----------------

    @Test
    fun nearbyListsTheKindsAndShowsTheClosestFirst() {
        val a = open()
        tapAction(a, SearchActivity.ACTION_NEARBY)
        assertEquals(context.getString(R.string.nearby_title), titles(a).first())
        assertEquals(
            listOf("편의점", "약국", "화장실", "카페", "지하철역"),
            rows(a).filterIsInstance<Row.Action>().filter { it.id.startsWith(SearchActivity.ACTION_NEARBY_KIND) }.map { it.text },
        )
        tapAction(a, SearchActivity.nearbyAction(NearbyKind.STORE))
        assertEquals(listOf(NearbyKind.STORE), nearbyAsked)
        assertEquals("내 주변 편의점", titles(a).first())
        assertEquals(listOf("GS25 둔산리치점", "CU 대전시청역점"), rows(a).filterIsInstance<Row.PlaceItem>().map { it.place.name })
        back(a)
        assertEquals(context.getString(R.string.nearby_title), titles(a).first())
        back(a)
        assertEquals(home, titles(a).first())
    }

    @Test
    fun nearbyWithoutALocationSaysSoAndCanRetry() {
        center = null
        val a = open()
        tapAction(a, SearchActivity.ACTION_NEARBY)
        tapAction(a, SearchActivity.nearbyAction(NearbyKind.PHARMACY))
        assertEquals(listOf(context.getString(R.string.nearby_no_location)), notes(a))
        center = cityHall
        tapAction(a, SearchActivity.nearbyAction(NearbyKind.PHARMACY))
        assertEquals(listOf(NearbyKind.PHARMACY), nearbyAsked)
        assertEquals(listOf(context.getString(R.string.nearby_none)), notes(a))
        back(a)
        assertEquals("다시 시도는 쌓이지 않음", context.getString(R.string.nearby_title), titles(a).first())
    }
}
