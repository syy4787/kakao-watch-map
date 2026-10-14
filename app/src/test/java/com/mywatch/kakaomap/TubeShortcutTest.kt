package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubeThumbs
import org.junit.After
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

/** 타일에서 바로 온 한 번의 일(말로 찾기·믹스 이어 듣기): 그 일을 마치거나 취소하면 워치 화면으로 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeShortcutTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        TubeState(context).clear()
        TubeState(context).agree()   // 약관 동의는 처음 한 번 (TubeAppTest 에서 확인)
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
    }

    private fun cancelInput(activity: TubeActivity) {
        val input = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("말·키보드 입력 화면을 연다", input)
        shadowOf(activity).receiveResult(input.intent, Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun voiceSearchCancelledGoesBackToTheWatchFace() {
        val intent = Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_VOICE, true)
        val activity = Robolectric.buildActivity(TubeActivity::class.java, intent).setup().get()
        cancelInput(activity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun mixWithNoRecentSongAsksForOneAndCancellingAlsoCloses() {
        // 아직 들은 곡이 없으면 '믹스 이어 듣기'는 말로 찾기로 — 취소하면 '노래 검색'처럼 닫힌다
        val intent = Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)
        val activity = Robolectric.buildActivity(TubeActivity::class.java, intent).setup().get()
        cancelInput(activity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun openedNormallyItDoesNotAskOrClose() {
        val activity = Robolectric.buildActivity(TubeActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(activity).nextStartedActivityForResult == null)
        assertFalse(activity.isFinishing)
    }
}
