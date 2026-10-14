package com.mywatch.kakaomap

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.model.RouteChoice
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.Look
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.WearList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 점검 C3: 위쪽 구역(인기 음악)이 다 받아지면 회색 자리 둘이 카드 넷 + '더 보기'로 바뀌며 아래 목록이 통째로 밀려
 * 보던(누르려던) 동영상이 손가락 아래에서 다른 것으로 바뀌었다 — 목록은 바뀐 줄만 고치고, 보던 줄은 그 자리에
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)
class ListStaysPutTest {

    private fun card(id: String) = Row.VideoCard(TubeVideo(id, "제목 $id", "채널"), "채널 · 조회수 1만회", Queue.Single)

    private fun open(): Pair<ComponentActivity, WearList> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        controller.setup()
        activity.setTheme(R.style.Theme_KakaoWatchMap)
        activity.setContentView(R.layout.activity_list)
        return activity to WearList(activity, {}, null, Look.TUBE)
    }

    private fun layout(ui: WearList) {
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        ui.list.measure(spec, spec)
        ui.list.layout(0, 0, 480, 480)
        shadowOf(Looper.getMainLooper()).idle()
        ui.list.measure(spec, spec)
        ui.list.layout(0, 0, 480, 480)
    }

    private fun firstShown(ui: WearList): Row {
        val lm = ui.list.layoutManager as LinearLayoutManager
        return ui.rows[lm.findFirstVisibleItemPosition()]
    }

    @Test
    fun aSectionAboveFinishingLoadingDoesNotMoveWhatYouAreLookingAt() {
        val (_, ui) = open()
        val below = (1..8).map { card("popular%04d".format(it)) }
        ui.rows = listOf(Row.Header("유튜브"), Row.Header("인기 음악"), Row.Skeleton(0), Row.Skeleton(1), Row.Header("인기 동영상")) + below
        layout(ui)
        (ui.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(7, 0)
        layout(ui)
        val looking = firstShown(ui)
        assertEquals(card("popular0003"), looking)
        // 위쪽 '인기 음악'이 다 받아짐: 회색 자리 둘 → 카드 넷 + 더 보기
        val music = (1..4).map { card("musicaaa%03d".format(it)) } + Row.Action("chart_music", R.drawable.ic_expand_more, "더 보기")
        ui.rows = listOf(Row.Header("유튜브"), Row.Header("인기 음악")) + music + Row.Header("인기 동영상") + below
        layout(ui)
        assertEquals("보던 동영상이 그대로 맨 위에", looking, firstShown(ui))
    }

    /**
     * 리뷰에서 찾음: 바뀐 줄만 고치게 한 뒤로 경로 카드의 'HH:MM 도착'이 그대로 남았다 — 그 시각은 그릴 때 시계로 정해지는데
     * 카드 내용(경로)은 같아서 1분마다 목록을 다시 넣어도 다시 그리지 않았다 (10:00 에 연 목록이 10:20 에도 '10:25 도착')
     */
    @Test
    fun cardsThatShowAnArrivalClockAreRedrawnWhenTheListIsSetAgain() {
        val adapter = RowAdapter({})
        val redrawn = ArrayList<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                repeat(itemCount) { redrawn += positionStart + it }
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = onItemRangeChanged(positionStart, itemCount)
        })
        val bus = TransitRoute(
            "BUS", 1_000, 1_200, 0, 1_500,
            listOf(TransitStep(TransitStepType.BUS, "", 1_000, 1_200, emptyList(), listOf(Vehicle("간선", "106")), emptyList())),
        )
        val walk = RouteChoice(listOf(RouteOption.WALK_BROAD), SimpleRoute(TravelMode.WALK, 800, 600, emptyList()))
        val rows = listOf(Row.Header("→ 대전역"), Row.TransitCard(0, bus), Row.OptionCard(0, walk, selected = false, fastest = true), Row.Note("끝"))
        adapter.rows = rows
        redrawn.clear()
        adapter.rows = rows.toList()   // 1분마다 같은 경로로 다시 넣음
        assertEquals("도착 시각을 보여 주는 카드만 다시 그림", listOf(1, 2), redrawn)
    }

    @Test
    fun aRowThatChangedIsUpdatedInPlace() {
        val (_, ui) = open()
        ui.rows = listOf(Row.Header("→ 대전역"), Row.Note("실시간 받는 중"), Row.Note("끝"))
        layout(ui)
        ui.adapter.replace(1, Row.Note("106번 3분"))
        layout(ui)
        assertEquals(Row.Note("106번 3분"), ui.rows[1])
        assertTrue(ui.list.itemAnimator == null)
    }
}
