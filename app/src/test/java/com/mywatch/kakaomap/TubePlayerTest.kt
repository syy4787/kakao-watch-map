package com.mywatch.kakaomap

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.tube.playerDir
import com.mywatch.kakaomap.ui.TubePlayerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 앱 안 재생 화면: 다른 곳의 주소는 열지 않고, 엔진을 못 띄우면(이 시험 환경처럼 엔진이 없음) 기억해 두고
 * 지금 고른 것은 워치 인터넷 앱으로 이어서 연다 — 인터넷 앱도 없으면 알리고 목록으로
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubePlayerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val browser = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")
    private val video = TubeVideo("BzYnNdJhZQw", "밤편지", "IU")

    @Before
    fun setUp() {
        WatchPlayer.base = base
        playerDir(context).deleteRecursively()
        // 이 시험 환경엔 엔진(네이티브 라이브러리)이 없다 — 워치에서 엔진을 못 띄운 것처럼
        TubePlayerActivity.engine = { throw UnsatisfiedLinkError("no libxul") }
    }

    @After
    fun tearDown() {
        WatchPlayer.reset()
        TubePlayerActivity.engine = defaultEngine
    }

    private val defaultEngine = TubePlayerActivity.engine

    private fun installBrowser() {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        })
    }

    @Test
    fun aPageFromSomewhereElseIsNeverOpened() {
        val a = Robolectric.buildActivity(TubePlayerActivity::class.java, TubePlayerActivity.intent(context, "https://evil.example/tube/#v=x")).setup().get()
        assertTrue(a.isFinishing)
        assertNull(shadowOf(a).nextStartedActivity)
        assertFalse("엔진 탓이 아니다", PlayerGuard.of(context).crashedRecently())
    }

    @Test
    fun whenTheEngineCannotStartItContinuesInTheWatchBrowserAndRemembers() {
        installBrowser()
        val url = TubeLinks.playerUrl(base, video, Queue.Ids(listOf("pSUydWEqKwE")), nonce = 5, inApp = true)
        val a = Robolectric.buildActivity(TubePlayerActivity::class.java, TubePlayerActivity.intent(context, url)).setup().get()
        assertTrue(a.isFinishing)
        assertTrue("한동안은 처음부터 인터넷 앱으로", PlayerGuard.of(context).crashedRecently())
        val started = shadowOf(a).nextStartedActivity
        assertEquals("인터넷 앱용 주소로 (app=1 없이 — 링크가 intent:// 로)", url.removeSuffix("&app=1"), started?.dataString)
        assertEquals(context.getString(R.string.player_engine_to_browser), ShadowToast.getTextOfLatestToast())
    }

    /** Shorts 재생 화면도 같다: 엔진을 못 띄우면 인터넷 앱으로 (같은 목록·같은 자리, app=1 없이) */
    @Test
    fun theShortsPageAlsoContinuesInTheWatchBrowser() {
        installBrowser()
        val url = TubeLinks.shortsUrl(base, listOf("BzYnNdJhZQw", "pSUydWEqKwE"), start = 1, nonce = 5, inApp = true)
        val a = Robolectric.buildActivity(TubePlayerActivity::class.java, TubePlayerActivity.intent(context, url)).setup().get()
        assertTrue(a.isFinishing)
        assertEquals(base + "shorts.html#ids=BzYnNdJhZQw,pSUydWEqKwE&i=1&n=5", shadowOf(a).nextStartedActivity?.dataString)
    }

    @Test
    fun withoutABrowserItSaysToUseThePhone() {
        val url = TubeLinks.playerUrl(base, video, Queue.Single, inApp = true)
        val a = Robolectric.buildActivity(TubePlayerActivity::class.java, TubePlayerActivity.intent(context, url)).setup().get()
        assertTrue(a.isFinishing)
        assertNull(shadowOf(a).nextStartedActivity)
        assertEquals(context.getString(R.string.player_engine_failed), ShadowToast.getTextOfLatestToast())
    }
}
