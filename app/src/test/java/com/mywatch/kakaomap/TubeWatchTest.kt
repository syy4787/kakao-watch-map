package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Browser
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayTarget
import com.mywatch.kakaomap.tube.TubeApi
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeResult
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
import kotlinx.coroutines.delay
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
 * 워치 화면 플레이어('듣는 곳'으로 고른 경우): 곡을 누르면 워치의 인터넷 앱(삼성 인터넷)이 둥근 화면용 재생 화면을 연다
 * (그 안의 유튜브 공식 플레이어가 재생). 인터넷 앱이 없거나 재생 화면에 닿지 못하면 폰 YouTube Music 으로.
 * 기본 '듣는 곳'(프리미엄)은 [TubeMusicTest].
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
        // 이 시험들은 '듣는 곳'을 워치 화면 플레이어로 고른 사용자
        TubeState(context).target = PlayTarget.WATCH_WEB
    }

    @After
    fun tearDown() {
        TubeState(context).target = null
        TubeThumbs.enabled = true
        WatchPlayer.reset()
        PhoneOpener.override = null
        TubeApi.override = null
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
        // 스크롤은 다음 배치 때 적용된다 — 다시 재고(measure) 놓아야(layout) 그 줄이 그려진다
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        val v = rv.findViewHolderForAdapterPosition(i)!!.itemView
        if (long) v.performLongClick() else v.performClick()
        idle()
    }

    private fun startedLink(a: TubeActivity): Intent? = shadowOf(a).nextStartedActivity

    /** IO 스레드의 검색이 끝나 화면에 돌아올 때까지 (실제 시간으로, 최대 5초 — 바쁜 컴퓨터에서도 같은 결과) */
    private fun waitForSearch(a: TubeActivity) {
        val deadline = System.currentTimeMillis() + 5_000
        idle()
        while (System.currentTimeMillis() < deadline && a.findViewById<View>(R.id.status).visibility == View.VISIBLE) {
            Thread.sleep(5)
            idle()
        }
    }

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
    fun openingTheScreenChecksThePlayerPageAheadSoATapDoesNotWait() {
        installBrowser()
        open()
        // 곡을 누르기 전에 미리 한 번 확인해 둔다
        assertEquals(1, probed)
        // 인터넷 앱이 없으면 확인하지 않는다
        WatchPlayer.okUntil = 0L
        val pm = shadowOf(context.packageManager)
        pm.removeActivity(browser)
        open()
        assertEquals(1, probed)
    }

    @Test
    fun theHomeScreenSaysSongsPlayOnTheWatch() {
        installBrowser()
        val a = open()
        val chip = rows(a).filterIsInstance<Row.Action>().first { it.id == TubeRows.A_TARGET }
        assertEquals(context.getString(R.string.tube_target_watch_web), chip.text)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_how_watch_web) })
    }

    @Test
    fun withoutABrowserOnTheWatchSongsStillGoToThePhoneAndItSaysHowToPlayOnTheWatch() {
        TubeState(context).addPlayed(song)
        val a = open()
        val chip = rows(a).filterIsInstance<Row.Action>().first { it.id == TubeRows.A_TARGET }
        assertEquals(context.getString(R.string.tube_target_phone_music), chip.text)
        // '듣는 곳'을 고르는 화면에서 워치 화면 플레이어에 무엇이 필요한지 알려 준다
        tap(a) { it is Row.Action && it.id == TubeRows.A_TARGET }
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_watch_needs_browser) })
        a.onBackPressedDispatcher.onBackPressed()
        idle()
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
    fun aFailedCheckWhileOpeningTheScreenDoesNotSendTheNextTapToThePhone() {
        // 워치가 막 깨어 인터넷이 다시 잡히는 중이면 화면을 열 때 미리 한 확인은 실패할 수 있다 —
        // 그 실패를 2분 동안 믿고 곧바로 누른 곡을 폰으로 보내지 않는다 (누를 때 다시 확인)
        installBrowser()
        TubeState(context).addPlayed(song)
        WatchPlayer.probe = { probed++; probed > 1 }   // 미리 한 첫 확인만 실패
        val a = open()
        assertEquals(1, probed)
        tap(a) { it is Row.VideoItem }
        assertEquals(TubeLinks.playerUrl(base, song, mix = true), startedLink(a)?.page())
        assertEquals(2, probed)
        assertFalse(ShadowToast.showedToast(context.getString(R.string.tube_watch_unreachable)))
    }

    @Test
    fun aTapDuringTheOpeningCheckUsesItsFreshFailureInsteadOfWaitingTwice() {
        // 화면을 열며 시작한 확인이 끝나기 전에 곡을 누르면 그 결과를 기다린다 — 그게 방금 실패했으면
        // (같은 순간의 인터넷 상태) 또 확인하느라 두 배로 기다리게 하지 않고 바로 폰으로
        installBrowser()
        TubeState(context).addPlayed(song)
        fakePhone()
        WatchPlayer.probe = { probed++; delay(2_000); false }
        val a = open()
        tap(a) { it is Row.VideoItem }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(1, probed)
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
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
            listOf(TubeRows.A_PHONE_MUSIC, TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE, TubeRows.A_FORGET, TubeRows.A_CANCEL),
            ids,
        )
        // 평소 듣는 곳(워치 화면 플레이어)이 흰 버튼
        assertEquals(listOf(TubeRows.A_WATCH_MIX), rows(a).filterIsInstance<Row.Action>().filter { it.accent }.map { it.id })
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
        assertEquals(listOf(TubeRows.A_PHONE_MUSIC, TubeRows.A_FORGET, TubeRows.A_CANCEL), ids)
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
    }

    /** 폰에서 연 주소 (가짜 폰 — 실제 워치에선 폰의 유튜브 앱이 연다) */
    private val phoneOpened = mutableListOf<String>()

    private fun fakePhone(result: PhoneOpener.Result = PhoneOpener.Result.OPENED) {
        PhoneOpener.override = { url -> phoneOpened += url; result }
    }

    private fun handoffLink(query: String) = Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/phone?$query")).addCategory(Intent.CATEGORY_BROWSABLE)

    @Test
    fun handOffFromThePlayerPageAsksOnceThenOpensOnThePhoneAtThatTime() {
        fakePhone()
        val a = Robolectric.buildActivity(TubeActivity::class.java, handoffLink("v=BzYnNdJhZQw&t=83&mix=1&title=%EB%B0%A4%ED%8E%B8%EC%A7%80&channel=IU")).setup().get()
        idle()
        // 웹 페이지가 보낸 링크라 바로 틀지 않고 한 번 묻는다
        assertTrue(phoneOpened.isEmpty())
        val header = rows(a).first() as Row.Header
        assertEquals("밤편지", header.title)
        assertEquals(context.getString(R.string.tube_handoff_sub, "1:23"), header.subtitle)
        assertEquals(listOf(TubeRows.A_HANDOFF, TubeRows.A_CANCEL), rows(a).filterIsInstance<Row.Action>().map { it.id })
        tap(a) { it is Row.Action && it.id == TubeRows.A_HANDOFF }
        idle()
        assertEquals(listOf(TubeLinks.watchUrl("BzYnNdJhZQw", mix = true, startSec = 83)), phoneOpened)
        assertEquals("BzYnNdJhZQw", TubeState(context).lastPlayed()?.id)
        // 다 하면 처음 화면
        assertTrue(rows(a).first() is Row.Header && (rows(a).first() as Row.Header).title == context.getString(R.string.tube_title))
        assertFalse(a.isFinishing)
    }

    @Test
    fun cancellingAHandOffGoesHomeWithoutOpeningAnything() {
        fakePhone()
        val a = Robolectric.buildActivity(TubeActivity::class.java, handoffLink("v=BzYnNdJhZQw&t=10")).setup().get()
        idle()
        val header = rows(a).first() as Row.Header
        assertEquals(context.getString(R.string.tube_handoff_untitled), header.title)
        tap(a) { it is Row.Action && it.id == TubeRows.A_CANCEL }
        assertTrue(phoneOpened.isEmpty())
        assertEquals(context.getString(R.string.tube_title), (rows(a).first() as Row.Header).title)
        assertNull(TubeState(context).lastPlayed())
    }

    @Test
    fun aHandOffForASongYouAlreadyHaveShowsAndKeepsItsRealName() {
        // 이어 듣기 링크의 제목은 웹 페이지가 붙인 것 — 이미 들은 곡이면 워치에 저장된 진짜 이름·길이를 쓴다
        fakePhone()
        TubeState(context).addPlayed(song)
        val a = Robolectric.buildActivity(TubeActivity::class.java, handoffLink("v=${song.id}&t=30&title=%EA%B0%80%EC%A7%9C%20%EC%A0%9C%EB%AA%A9")).setup().get()
        idle()
        assertEquals(song.title, (rows(a).first() as Row.Header).title)
        tap(a) { it is Row.Action && it.id == TubeRows.A_HANDOFF }
        idle()
        val kept = TubeState(context).lastPlayed()!!
        assertEquals(song.title, kept.title)
        assertEquals(song.channel, kept.channel)
        assertEquals(294, kept.durationSec)
    }

    @Test
    fun aNewerHandOffIsNotClearedWhenAnOlderOneFinishesOpening() {
        // 폰에서 여는 사이 재생 화면에서 또 '폰에서 이어 듣기'가 오면, 앞의 것이 끝나도 새로 온 것은 남아 묻는다
        PhoneOpener.override = { url -> delay(1_000); phoneOpened += url; PhoneOpener.Result.OPENED }
        val c = Robolectric.buildActivity(TubeActivity::class.java, handoffLink("v=BzYnNdJhZQw&t=5")).setup()
        idle()
        val a = c.get()
        tap(a) { it is Row.Action && it.id == TubeRows.A_HANDOFF }
        c.newIntent(handoffLink("v=pSUydWEqKwE&t=7&title=Ditto"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(listOf(TubeLinks.watchUrl("BzYnNdJhZQw", mix = false, startSec = 5)), phoneOpened)
        assertEquals("Ditto", (rows(a).first() as Row.Header).title)
        assertEquals(listOf(TubeRows.A_HANDOFF, TubeRows.A_CANCEL), rows(a).filterIsInstance<Row.Action>().map { it.id })
    }

    @Test
    fun tileRequestsArriveOnlyAsPlainIntentsNotWithLinkData() {
        // 타일의 '믹스 이어 듣기'는 주소 없는 인텐트로만 온다 — 주소(data)나 BROWSABLE 이 붙은 건 따르지 않는다
        installBrowser()
        TubeState(context).addPlayed(song)
        val sneaky = Intent(context, TubeActivity::class.java).setData(Uri.parse("kakaowatch://tube")).putExtra(TubeActivity.EXTRA_MIX, true)
        val a = Robolectric.buildActivity(TubeActivity::class.java, sneaky).setup().get()
        idle()
        assertNull(startedLink(a))
        val browsable = Intent(context, TubeActivity::class.java).addCategory(Intent.CATEGORY_BROWSABLE).putExtra(TubeActivity.EXTRA_VOICE, true)
        val b = Robolectric.buildActivity(TubeActivity::class.java, browsable).setup().get()
        idle()
        assertNull(shadowOf(b).nextStartedActivityForResult)
        assertFalse(b.isFinishing)
    }

    @Test
    fun aHandOffWithBadValuesJustOpensHome() {
        fakePhone()
        val a = Robolectric.buildActivity(TubeActivity::class.java, handoffLink("v=%3Cscript%3E&t=1")).setup().get()
        idle()
        assertEquals(context.getString(R.string.tube_title), (rows(a).first() as Row.Header).title)
        assertTrue(phoneOpened.isEmpty())
    }

    @Test
    fun aHandOffArrivingWhileTheScreenIsOpenAlsoAsks() {
        fakePhone()
        val c = Robolectric.buildActivity(TubeActivity::class.java).setup()
        idle()
        c.newIntent(handoffLink("v=BzYnNdJhZQw&t=5"))
        idle()
        val a = c.get()
        assertEquals(listOf(TubeRows.A_HANDOFF, TubeRows.A_CANCEL), rows(a).filterIsInstance<Row.Action>().map { it.id })
        // 뒤로 가기 = 취소
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(context.getString(R.string.tube_title), (rows(a).first() as Row.Header).title)
        assertTrue(phoneOpened.isEmpty())
    }

    @Test
    fun aFailedHandOffSaysSoAndLetsYouTryAgain() {
        fakePhone(PhoneOpener.Result.NO_PHONE)
        val a = Robolectric.buildActivity(TubeActivity::class.java, handoffLink("v=BzYnNdJhZQw&t=5")).setup().get()
        idle()
        tap(a) { it is Row.Action && it.id == TubeRows.A_HANDOFF }
        idle()
        assertTrue(ShadowToast.showedToast(context.getString(R.string.tube_no_phone)))
        // 다시 누를 수 있게 그 화면 그대로, 기록도 안 함
        assertEquals(listOf(TubeRows.A_HANDOFF, TubeRows.A_CANCEL), rows(a).filterIsInstance<Row.Action>().map { it.id })
        assertNull(TubeState(context).lastPlayed())
    }

    @Test
    fun comingBackThroughALinkEndsTheTilesOneOffJob() {
        // 타일 '노래 검색'으로 열었다가(마치면 닫히는 한 번의 일) 재생 화면의 '다른 곡 찾기'로 돌아와 곡을 고르면 닫히지 않는다
        fakePhone()
        TubeApi.override = { _, _, _ -> TubeResult(TubePage(listOf(song), null), TubeSource.YOUTUBE) }
        val c = Robolectric.buildActivity(TubeActivity::class.java, Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_VOICE, true)).setup()
        idle()
        val a = c.get()
        val input = shadowOf(a).nextStartedActivityForResult
        val data = Intent()
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), data, Bundle().apply { putCharSequence("query", "밤편지") })
        shadowOf(a).receiveResult(input.intent, Activity.RESULT_OK, data)
        waitForSearch(a)
        c.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube")).addCategory(Intent.CATEGORY_BROWSABLE))
        idle()
        tap(a) { it is Row.VideoItem }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        // 워치에 인터넷 앱이 없으니 고른 워치 화면 플레이어 대신 폰 YouTube Music (프리미엄)
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertFalse(a.isFinishing)
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
