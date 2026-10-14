package com.mywatch.kakaomap

import android.app.Application
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mywatch.kakaomap.tube.DeviceCode
import com.mywatch.kakaomap.ui.Look
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.WearList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * 로그인 코드가 워치 화면에 끝까지 다 보이는지 — 실제 글자 폭으로 잰다.
 * 구글이 실제로 주는 코드는 'PSQ-RWD-XKZ' 처럼 11자인데(문서 예시는 9자 'GQVQ-JKEC'), 한 줄짜리 큰 글자라 줄이 넘쳐
 * 하이픈에서 잘리고 'PSQ-RWD-' 만 보였다(사용자 사진).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w226dp-h226dp-round-340dpi", application = Application::class)
class LoginCodeFitTest {

    private fun showCode(userCode: String, fontScale: Float = 1f): Pair<TextView, View> {
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
        val ui = WearList(activity, {}, null, Look.TUBE)
        val code = DeviceCode("dc", userCode, "https://www.google.com/device", 1_760_001_800_000L, 5)
        ui.rows = TubeRows.login(activity, TubeRows.LoginState.Waiting(code))
        shadowOf(Looper.getMainLooper()).idle()
        val tv = activity.window.decorView.findViewById<TextView>(R.id.code)
        return tv to activity.window.decorView
    }

    private fun assertWholeCodeVisible(userCode: String, fontScale: Float = 1f) {
        val (tv, root) = showCode(userCode, fontScale)
        assertEquals(userCode, tv.text.toString())
        val layout = tv.layout
        assertEquals("'$userCode' 가 한 줄에 다 들어가야 (줄 끝 ${layout.getLineEnd(0)}/${userCode.length})", userCode.length, layout.getLineEnd(0))
        assertEquals(0, layout.getEllipsisCount(0))
        val inner = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        val lineWidth = layout.getLineWidth(0)
        assertTrue("글자 폭 $lineWidth > 칸 $inner", lineWidth <= inner + 0.5f)
        // 읽을 수 있을 만큼 크게 (폰을 보며 따라 넣는 코드)
        val sp = tv.textSize / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, tv.resources.displayMetrics)
        assertTrue("글자가 너무 작음: ${sp}sp", sp >= 17f)
        // 둥근 화면 안에: 글자 줄의 양 끝이 원 안에 있어야
        val at = IntArray(2)
        tv.getLocationInWindow(at)
        val left = at[0] + tv.totalPaddingLeft + layout.getLineLeft(0)
        val right = at[0] + tv.totalPaddingLeft + layout.getLineRight(0)
        val midY = at[1] + tv.totalPaddingTop + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        val r = root.width / 2f
        for (x in listOf(left, right)) {
            assertTrue("코드 끝(x=$x, y=$midY)이 둥근 화면 밖", hypot(x - r, midY - r) <= r)
        }
    }

    @Test
    fun realElevenLetterCodeFitsOnOneLine() = assertWholeCodeVisible("PSQ-RWD-XKZ")

    @Test
    fun widestLettersStillFit() = assertWholeCodeVisible("WMW-MWM-WWM")

    @Test
    fun withLargerSystemFontStillFits() = assertWholeCodeVisible("PSQ-RWD-XKZ", fontScale = 1.3f)

    @Test
    fun shortDocsStyleCodeStaysBig() {
        val (tv, _) = showCode("GQVQ-JKEC")
        val sp = tv.textSize / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, tv.resources.displayMetrics)
        assertTrue("짧은 코드는 크게: ${sp}sp", sp >= 22f)
    }
}
