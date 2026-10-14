package com.mywatch.kakaomap

import android.app.Application
import android.os.Looper
import android.view.Window
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.WearList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 대중교통 카드의 요금·환승·도보 줄이 끝까지 보이는지 (점검 A6) — 실제 글자 폭으로 잰다.
 * 거리 따라 달라지는 요금("1,500~3,150원")이면 한 줄을 넘어 '도보 12분'이 '도보…'로 잘렸다 → 두 줄까지, 항목은 통째로 줄바꿈
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w226dp-h226dp-round-340dpi", application = Application::class)
class TransitCardFitTest {

    private val route = TransitRoute(
        type = "BUS", totalDistanceM = 14_000, totalTimeSec = 58 * 60, transfers = 2, fare = 1500, fareMax = 3150,
        steps = listOf(
            TransitStep(TransitStepType.WALK, "정류장까지", 500, 12 * 60, emptyList(), emptyList(), emptyList()),
            TransitStep(TransitStepType.BUS, "", 10_000, 40 * 60, listOf("가", "나"), listOf(Vehicle("광역", "1002")), emptyList()),
        ),
    )

    private fun meta(fontScale: Float): TextView {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
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
        ui.rows = listOf(Row.TransitCard(0, route))
        shadowOf(Looper.getMainLooper()).idle()
        val rv = activity.findViewById<RecyclerView>(R.id.list)
        return rv.findViewHolderForAdapterPosition(0)!!.itemView.findViewById(R.id.meta)
    }

    private fun assertWhole(fontScale: Float) {
        val tv = meta(fontScale)
        val text = tv.text.toString()
        assertEquals("1,500~3,150원 · 환승 2회 · 도보 12분", text.replace(' ', ' '))
        val layout = tv.layout
        assertTrue("두 줄까지 (${layout.lineCount}줄)", layout.lineCount <= 2)
        for (line in 0 until layout.lineCount) assertEquals("줄 $line 이 잘림", 0, layout.getEllipsisCount(line))
        assertEquals("끝까지", text.length, layout.getLineEnd(layout.lineCount - 1))
        // 한 항목이 두 줄로 갈라지지 않는다 ('도보' / '12분')
        val lines = (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(' ', ' ').trim() }
        assertFalse("갈라짐: $lines", lines.any { it.endsWith("도보") || it.endsWith("환승") })
    }

    @Test
    fun fareTransfersAndWalkAllShow() = assertWhole(1f)

    @Test
    fun evenWithBigFonts() = assertWhole(1.3f)
}
