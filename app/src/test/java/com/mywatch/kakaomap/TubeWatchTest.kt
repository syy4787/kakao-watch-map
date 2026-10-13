package com.mywatch.kakaomap

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import android.provider.Browser
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 워치에서 바로 듣기: 곡을 누르면 워치의 인터넷 앱(삼성 인터넷)이 둥근 화면용 재생 화면을 연다
 * (그 안의 유튜브 공식 플레이어가 재생). 인터넷 앱이 없거나 재생 화면에 닿지 못하면 지금처럼 폰 유튜브로.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeWatchTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val song = TubeVideo("BzYnNdJhZQw", "[MV] IU(아이유) _ Through the Night(밤편지)", "1theK (원더케이)", 294)
    private val browser = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")
    private var probed = 0

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        TubeState(context).clear()
        WatchPlayer.base = base
        WatchPlayer.okUntil = 0L
        WatchPlayer.failUntil = 0L
        WatchPlayer.probe = { probed++; true }
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
        WatchPlayer.reset()
    }

    /** 워치에 인터넷 앱(https 링크를 여는 앱)을 깐다 */
    private fun installBrowser() {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        })
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): TubeActivity {
        val a = Robolectric.buildActivity(TubeActivity::class.java).setup().get()
        idle()
        return a
    }

    private fun list(a: TubeActivity): RecyclerView = a.findViewById(R.id.list)

    private fun rows(a: TubeActivity): List<Row> = (list(a).adapter as RowAdapter).rows

    /** 목록에서 그 줄을 찾아 누른다 (화면에 그려진 줄을 실제로 누름) */
    private fun tap(a: TubeActivity, long: Boolean = false, which: (Row) -> Boolean) {
        val rv = list(a)
        val i = rows(a).indexOfFirst(which)
        assertTrue("누를 줄이 있다", i >= 0)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        rv.scrollToPosition(i)
        rv.layout(0, 0, 480, 480)
        val v = rv.findViewHolderForAdapterPosition(i)!!.itemView
        if (long) v.performLongClick() else v.performClick()
        idle()
    }

    private fun startedLink(a: TubeActivity): Intent? = shadowOf(a).nextStartedActivity

    /** 재생 화면 주소에서 매번 달라지는 &n=(같은 곡도 다시 틀게)를 뺀 것 */
    private fun Intent.page(): String? = dataString?.replace(Regex("&n=\\d+$"), "")

    @Test
    fun tappingASongOpensTheRoundPlayerPageOnTheWatchAsAMix() {
        installBrowser()
        TubeState(context).addPlayed(song)
        val a = open()
        tap(a) { it is Row.VideoItem }

        val started = startedLink(a)
        assertNotNull("워치에서 연다", started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(TubeLinks.playerUrl(base, song, mix = true), started.page())
        assertTrue("같은 곡을 다시 골라도 다시 틀게 매번 다른 표시", started.dataString!!.contains(Regex("&n=\\d+$")))
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        // 인터넷 앱은 제 작업에서 (이 앱을 닫아도 재생 화면이 같이 닫히지 않게)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        // 같은 앱이 연 링크는 같은 탭에 — 곡을 고를 때마다 탭이 쌓이지 않게
        assertEquals(context.packageName, started.getStringExtra(Browser.EXTRA_APPLICATION_ID))
        assertEquals(song.id, TubeState(context).lastPlayed()?.id)
        assertEquals(1, probed)
    }

    @Test
    fun theHomeScreenSaysSongsPlayOnTheWatch() {
        installBrowser()
        val a = open()
        val header = rows(a).first() as Row.Header
        assertEquals(context.getString(R.string.tube_subtitle_watch), header.subtitle)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_how_watch) })
    }

    @Test
    fun withoutABrowserOnTheWatchSongsStillGoToThePhoneAndItSaysHowToPlayOnTheWatch() {
        TubeState(context).addPlayed(song)
        val a = open()
        val header = rows(a).first() as Row.Header
        assertEquals(context.getString(R.string.tube_subtitle), header.subtitle)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_watch_needs_browser) })
        tap(a) { it is Row.VideoItem }
        // 워치에서 열지 않는다 (폰으로 보낸다)
        val started = startedLink(a)
        assertTrue(started == null || started.dataString?.startsWith(base) != true)
        assertEquals(0, probed)
    }

    @Test
    fun ifThePlayerPageCannotBeReachedItPlaysOnThePhoneInstead() {
        installBrowser()
        WatchPlayer.probe = { probed++; false }
        TubeState(context).addPlayed(song)
        val a = open()
        tap(a) { it is Row.VideoItem }
        val started = startedLink(a)
        assertTrue(started == null || started.dataString?.startsWith(base) != true)
        // 알리고 폰으로 보낸다 (폰 쪽 결과 알림이 뒤에 또 뜰 수 있어 '보였는지'로 확인)
        assertTrue(ShadowToast.showedToast(context.getString(R.string.tube_watch_unreachable)))
    }

    @Test
    fun aWorkingPlayerPageIsNotCheckedAgainForEverySong() {
        installBrowser()
        TubeState(context).addPlayed(song)
        TubeState(context).addPlayed(TubeVideo("pSUydWEqKwE", "NewJeans (뉴진스) 'Ditto' Official MV (side A)", "HYBE LABELS", 186))
        val a = open()
        tap(a) { it is Row.VideoItem }
        assertNotNull(startedLink(a))
        tap(a) { it is Row.VideoItem }
        assertNotNull(startedLink(a))
        assertEquals(1, probed)
    }

    @Test
    fun longPressOffersWatchOrPhone() {
        installBrowser()
        TubeState(context).addPlayed(song)
        val a = open()
        tap(a, long = true) { it is Row.VideoItem }
        val ids = rows(a).filterIsInstance<Row.Action>().map { it.id }
        assertEquals(
            listOf(TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE, TubeRows.A_PLAY_MIX, TubeRows.A_PLAY_ONE, TubeRows.A_FORGET, TubeRows.A_CANCEL),
            ids,
        )
        // '워치에서 이 곡만' → 믹스 없이
        tap(a) { it is Row.Action && it.id == TubeRows.A_WATCH_ONE }
        assertEquals(TubeLinks.playerUrl(base, song, mix = false), startedLink(a)?.page())
    }

    @Test
    fun longPressWithoutABrowserKeepsThePhoneChoicesOnly() {
        TubeState(context).addPlayed(song)
        val a = open()
        tap(a, long = true) { it is Row.VideoItem }
        val ids = rows(a).filterIsInstance<Row.Action>().map { it.id }
        assertEquals(listOf(TubeRows.A_PLAY_MIX, TubeRows.A_PLAY_ONE, TubeRows.A_FORGET, TubeRows.A_CANCEL), ids)
    }

    @Test
    fun theTileMixPlaysOnTheWatchAndLeavesTheBrowserInFront() {
        installBrowser()
        TubeState(context).addPlayed(song)
        val intent = Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)
        val a = Robolectric.buildActivity(TubeActivity::class.java, intent).setup().get()
        idle()
        assertEquals(TubeLinks.playerUrl(base, song, mix = true), startedLink(a)?.page())
        // 타일에서 바로 온 일 — 재생 화면을 띄웠으니 이 앱은 닫는다 (뒤로 가면 워치 화면)
        assertTrue(a.isFinishing)
    }

    @Test
    fun aMissingPlayerPageIsNotCheckedAgainForTwoMinutes() = runBlocking {
        // 재생 화면이 안 열리면 2분 동안은 확인하느라 기다리지 않고 바로 폰으로, 그 뒤엔 다시 확인
        WatchPlayer.probe = { probed++; false }
        val t0 = 1_000_000L
        assertFalse(WatchPlayer.reachable(t0))
        assertFalse(WatchPlayer.reachable(t0 + 60_000))
        assertEquals(1, probed)
        WatchPlayer.probe = { probed++; true }
        assertTrue(WatchPlayer.reachable(t0 + 2 * 60_000 + 1))
        assertEquals(2, probed)
        // 열리면 30분은 다시 묻지 않는다
        assertTrue(WatchPlayer.reachable(t0 + 20 * 60_000))
        assertEquals(2, probed)
    }

    @Test
    fun aWebPageCannotMakeThisScreenPlayOrListen() {
        // 아무 웹 페이지나 intent://tube#Intent;scheme=kakaowatch;B.mix=true;end 로 이 화면을 열 수 있다 —
        // 그때 끼워 넣은 '믹스 이어 듣기'·'말로 찾기'는 따르지 않는다
        installBrowser()
        TubeState(context).addPlayed(song)
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube")).addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra(TubeActivity.EXTRA_MIX, true).putExtra(TubeActivity.EXTRA_VOICE, true)
        val a = Robolectric.buildActivity(TubeActivity::class.java, link).setup().get()
        idle()
        assertNull(startedLink(a))
        assertNull(shadowOf(a).nextStartedActivityForResult)
        assertFalse(a.isFinishing)
        assertEquals(0, probed)
    }

    @Test
    fun thePlayerPagesFindAnotherSongButtonOpensThisScreen() {
        // 재생 화면의 '다른 곡 찾기' = intent://tube#Intent;scheme=kakaowatch;package=…;end
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        val found = context.packageManager.resolveActivity(link, 0)
        assertEquals(TubeActivity::class.java.name, found?.activityInfo?.name)
        val a = Robolectric.buildActivity(TubeActivity::class.java, link).setup().get()
        idle()
        assertFalse(a.isFinishing)
        assertNull(startedLink(a))
        assertTrue(rows(a).first() is Row.Header)
    }
}
