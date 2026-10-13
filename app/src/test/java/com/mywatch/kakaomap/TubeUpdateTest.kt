package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.wear.widget.WearableRecyclerView
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.update.FakeReleaseServer
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 타일로 유튜브를 바로 열어도(첫 화면을 안 거쳐도) 새 버전이 있으면 맨 위에 '새 버전 업데이트' 버튼 — 누르면 바로 업데이트 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeUpdateTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        TubeState(context).clear()
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
        UpdateManager.baseUrl = UpdateManager.GITHUB
    }

    @Test
    fun youtubeHomeShowsTheUpdateButtonFirstOnlyWhenANewVersionIsOut() {
        val withUpdate = TubeRows.home(context, emptyList(), emptyList(), update = true)
        assertEquals("새 버전 업데이트", withUpdate.filterIsInstance<Row.Action>().first().text)
        val without = TubeRows.home(context, emptyList(), emptyList(), update = false)
        assertTrue(without.none { it is Row.Action && it.text == "새 버전 업데이트" })
    }

    @Test
    fun tappingItStartsTheUpdateRightAway() {
        FakeReleaseServer().use { server ->
            server.updateJson = server.updateJsonFor(999)
            UpdateManager.baseUrl = server.base
            UpdateManager.setOwner(context, "tester")
            UpdateManager.check(context)   // 새 버전을 알고 있음
            val activity = Robolectric.buildActivity(TubeActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val list = activity.findViewById<WearableRecyclerView>(R.id.list)
            val rows = (list.adapter as RowAdapter).rows
            val pos = rows.indexOfFirst { it is Row.Action && it.text == "새 버전 업데이트" }
            assertTrue("유튜브 첫 화면에 업데이트 버튼이 없음: $rows", pos >= 0)
            list.measure(480, 480)
            list.layout(0, 0, 480, 480)
            list.scrollToPosition(pos)
            list.measure(480, 480)   // 스크롤은 다시 재고 놓아야 적용된다
            list.layout(0, 0, 480, 480)
            list.findViewHolderForAdapterPosition(pos)!!.itemView.performClick()
            val started = shadowOf(activity).nextStartedActivity
            assertEquals(UpdateActivity::class.java.name, started?.component?.className)
            assertTrue(started.getBooleanExtra(UpdateActivity.EXTRA_UPDATE_NOW, false))
        }
    }
}
