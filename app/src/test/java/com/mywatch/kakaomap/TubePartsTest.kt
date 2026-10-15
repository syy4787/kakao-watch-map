package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.Look
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.ui.tube.TubeTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 유튜브 화면의 새 부품: 맨 위 탭 · 가로로 넘기는 칩 줄 · Shorts 선반·격자(세로 카드) · 채널 사진 줄 · 믹스 표시.
 * 누르면 무엇이 불리는지, 고른 것이 읽어 주기에 '선택됨'인지, 재생을 시작하는 그림이 120×70 이상인지
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)
class TubePartsTest {

    private val clicks = mutableListOf<Row>()
    private val longs = mutableListOf<Row>()
    private lateinit var list: RecyclerView
    private lateinit var adapter: RowAdapter

    private fun v(n: Int) = TubeVideo("short" + n.toString().padStart(6, '0'), "Shorts $n", "채널 $n", 30, channelId = "UC" + n.toString().padStart(22, '0'), portrait = true)
    private fun ch(n: Int) = TubeChannel("UC" + n.toString().padStart(22, '1'), "채널 $n", null)

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        adapter = RowAdapter({ clicks += it }, { longs += it }, Look.TUBE)
        list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = this@TubePartsTest.adapter
        }
        activity.setContentView(FrameLayout(activity).apply { addView(list, 480, 480) })
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
    }

    private fun show(vararg rows: Row): View {
        adapter.rows = rows.toList()
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        list.measure(spec, View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, 480, list.measuredHeight)
        return list.findViewHolderForAdapterPosition(0)!!.itemView
    }

    /** 누를 수 있는 칸들 (보이는 것, 읽어 주기 글이 있는 것) */
    private fun tappables(v: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE) return
            if (x.isClickable && x.contentDescription != null) out += x
            if (x is ViewGroup) (0 until x.childCount).forEach { walk(x.getChildAt(it)) }
        }
        walk(v)
        return out
    }

    @Test
    fun tabsMarkTheCurrentOneAndSayWhichWasTapped() {
        val row = show(Row.Tabs(TubeTab.SUBS))
        val tabs = tappables(row)
        assertEquals(listOf("홈", "Shorts", "구독, 선택됨", "나"), tabs.map { it.contentDescription.toString() })
        assertTrue(tabs[2].isSelected)
        assertFalse(tabs[0].isSelected)
        tabs[1].performClick()
        assertEquals("tab:SHORTS", (clicks.single() as Row.Action).id)
    }

    @Test
    fun theChipBarScrollsSidewaysAndSaysWhichChip() {
        val chips = listOf("ALL" to "전체", "MIXES" to "믹스", "MUSIC" to "음악", "GAMING" to "게임", "LIVE" to "실시간", "NEWS" to "뉴스")
        val row = show(Row.ChipBar("home", chips, selected = "MUSIC"))
        assertTrue("가로로 넘기는 줄", row is android.widget.HorizontalScrollView)
        val views = tappables(row)
        assertEquals(chips.size, views.size)
        assertEquals("음악, 선택됨", views[2].contentDescription.toString())
        assertTrue(views[2].isSelected)
        assertEquals("전체", views[0].contentDescription.toString())
        views[3].performClick()
        assertEquals("chip:home:GAMING", (clicks.single() as Row.Action).id)
    }

    @Test
    fun aShortsShelfHasTallCardsThatOpenTheShortsPlayerAtThatPlace() {
        val shorts = (1..4).map(::v)
        val row = show(Row.ShortsShelf("Shorts", shorts))
        assertEquals("Shorts", row.findViewById<TextView>(R.id.title).text.toString())
        val cards = tappables(row)
        assertEquals(4, cards.size)
        val thumb = cards[0].findViewById<ImageView>(R.id.thumb)
        assertTrue("세로 카드 (${thumb.width}×${thumb.height})", thumb.height > thumb.width)
        assertTrue("재생을 시작하는 그림은 120×70 이상", thumb.width >= 120 && thumb.height >= 70)
        cards[2].performClick()
        assertEquals(Row.ShortCard(shorts, 2), clicks.single())
        cards[1].performLongClick()
        assertEquals("길게 누르면 메뉴", shorts[1], (longs.single() as Row.VideoCard).video)
    }

    @Test
    fun aShortsGridRowHasTwoCardsAndHidesAMissingSecond() {
        val shorts = (1..3).map(::v)
        val full = tappables(show(Row.ShortsPair(shorts, 0)))
        assertEquals(2, full.size)
        full[1].performClick()
        assertEquals(Row.ShortCard(shorts, 1), clicks.last())
        val last = tappables(show(Row.ShortsPair(shorts, 2)))
        assertEquals("셋째 줄은 하나만", 1, last.size)
        last[0].performClick()
        assertEquals(Row.ShortCard(shorts, 2), clicks.last())
    }

    @Test
    fun theAvatarStripOpensAChannelOrTheWholeList() {
        val channels = (1..3).map(::ch)
        val row = show(Row.AvatarStrip(channels, more = true))
        val items = tappables(row)
        assertEquals(listOf("채널 1", "채널 2", "채널 3", "전체"), items.map { it.contentDescription.toString() })
        items[1].performClick()
        assertEquals(channels[1], (clicks.last() as Row.ChannelItem).channel)
        items[3].performClick()
        assertEquals(TubeRows.A_SUBS_CHANNELS, (clicks.last() as Row.Action).id)
        assertEquals("전체가 없으면 채널만", 3, tappables(show(Row.AvatarStrip(channels, more = false))).size)
    }

    @Test
    fun aMixCardShowsItsBadgeInsteadOfTheLength() {
        val video = TubeVideo("BzYnNdJhZQw", "믹스 – 밤편지", "아이유 외", 254)
        val row = show(Row.VideoCard(video, "YouTube 믹스", Queue.Mix, badge = "믹스"))
        assertEquals("믹스", row.findViewById<TextView>(R.id.badge).text.toString())
        val plain = show(Row.VideoCard(video, "아이유", Queue.Mix))
        assertEquals("4:14", plain.findViewById<TextView>(R.id.badge).text.toString())
    }
}
