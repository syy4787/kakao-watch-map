package com.mywatch.kakaomap

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.tube.MusicApp
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayPlaces
import com.mywatch.kakaomap.tube.PlayTarget
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
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
import java.time.Duration

/**
 * 프리미엄으로 듣기: 워치 화면 플레이어(삼성 인터넷 안의 유튜브 임베드)는 유튜브에 로그인할 수 없어 프리미엄이 안 된다.
 * 그래서 곡은 기본으로 YouTube Music 앱(내 계정·프리미엄)이 튼다 — 워치 YouTube Music 이 곡 링크를 받으면 워치에서,
 * 아니면 휴대폰 YouTube Music 에서. '듣는 곳'은 첫 화면에서 바꿀 수 있고(무료 워치 화면 플레이어 포함) 다음에도 그대로.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeMusicTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val song = TubeVideo("BzYnNdJhZQw", "[MV] IU(아이유) _ Through the Night(밤편지)", "1theK (원더케이)", 294)
    private val browser = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")
    private val music = ComponentName(MusicApp.PACKAGE, "com.google.android.apps.youtube.music.wear.MainActivity")
    private val phoneOpened = mutableListOf<String>()
    private var probed = 0

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        TubeState(context).clear()
        TubeState(context).target = null
        WatchPlayer.base = base
        WatchPlayer.okUntil = 0L
        WatchPlayer.failUntil = 0L
        WatchPlayer.probe = { probed++; true }
        PhoneOpener.override = { url -> phoneOpened += url; PhoneOpener.Result.OPENED }
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
        WatchPlayer.reset()
        PhoneOpener.override = null
        TubeState(context).target = null
    }

    private fun installBrowser() {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        })
    }

    /** 워치에 YouTube Music 을 깐다. [songLinks] = 곡 링크(music.youtube.com)를 받는지 */
    private fun installMusic(songLinks: Boolean) {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(music)
        pm.addIntentFilterForActivity(music, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        if (songLinks) {
            pm.addIntentFilterForActivity(music, IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("music.youtube.com", null)
            })
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(intent: Intent? = null): TubeActivity {
        val c = if (intent == null) Robolectric.buildActivity(TubeActivity::class.java) else Robolectric.buildActivity(TubeActivity::class.java, intent)
        val a = c.setup().get()
        idle()
        return a
    }

    private fun list(a: TubeActivity): RecyclerView = a.findViewById(R.id.list)
    private fun rows(a: TubeActivity): List<Row> = (list(a).adapter as RowAdapter).rows
    private fun actions(a: TubeActivity): List<Row.Action> = rows(a).filterIsInstance<Row.Action>()
    private fun action(a: TubeActivity, id: String): Row.Action? = actions(a).firstOrNull { it.id == id }

    private fun tap(a: TubeActivity, long: Boolean = false, which: (Row) -> Boolean) {
        val rv = list(a)
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
        idle()
    }

    private fun tapAction(a: TubeActivity, id: String) = tap(a) { it is Row.Action && it.id == id }

    // ---------- 계산 ----------

    @Test
    fun youTubeMusicLinksStartASongRadio() {
        assertEquals("https://music.youtube.com/watch?v=BzYnNdJhZQw&list=RDAMVMBzYnNdJhZQw", TubeLinks.musicUrl("BzYnNdJhZQw", mix = true))
        assertEquals("https://music.youtube.com/watch?v=BzYnNdJhZQw", TubeLinks.musicUrl("BzYnNdJhZQw", mix = false))
    }

    @Test
    fun songsGoToAPremiumAppUnlessYouPickedSomewhereThatStillWorks() {
        // 아무것도 안 골랐으면: 워치 YouTube Music 이 곡을 받으면 워치, 아니면 휴대폰 (워치 화면 플레이어는 프리미엄이 안 됨)
        assertEquals(PlayTarget.WATCH_MUSIC, PlayTarget.choose(null, watchMusic = true, watchWeb = true))
        assertEquals(PlayTarget.PHONE_MUSIC, PlayTarget.choose(null, watchMusic = false, watchWeb = true))
        assertEquals(PlayTarget.PHONE_MUSIC, PlayTarget.choose(null, watchMusic = false, watchWeb = false))
        // 고른 곳이 지금도 되면 그곳
        assertEquals(PlayTarget.WATCH_WEB, PlayTarget.choose(PlayTarget.WATCH_WEB, watchMusic = true, watchWeb = true))
        assertEquals(PlayTarget.PHONE_MUSIC, PlayTarget.choose(PlayTarget.PHONE_MUSIC, watchMusic = true, watchWeb = true))
        // 고른 곳이 이제 안 되면(앱을 지움) 프리미엄이 되는 곳으로
        assertEquals(PlayTarget.PHONE_MUSIC, PlayTarget.choose(PlayTarget.WATCH_WEB, watchMusic = false, watchWeb = false))
        assertEquals(PlayTarget.PHONE_MUSIC, PlayTarget.choose(PlayTarget.WATCH_MUSIC, watchMusic = false, watchWeb = true))
        assertEquals(PlayTarget.WATCH_MUSIC, PlayTarget.choose(PlayTarget.WATCH_WEB, watchMusic = true, watchWeb = false))
        // 저장한 값이 이상하면 없는 것처럼
        assertNull(PlayTarget.of("nonsense"))
        assertEquals(PlayTarget.WATCH_WEB, PlayTarget.of(PlayTarget.WATCH_WEB.key))
    }

    @Test
    fun theChosenPlaceIsRememberedButClearingHistoryKeepsIt() {
        val state = TubeState(context)
        assertNull(state.target)
        state.target = PlayTarget.WATCH_WEB
        assertEquals(PlayTarget.WATCH_WEB, TubeState(context).target)
        state.addPlayed(song)
        state.clear()   // '기록 지우기'는 들은 곡·검색만
        assertEquals(PlayTarget.WATCH_WEB, TubeState(context).target)
    }

    @Test
    fun theWatchMusicAppIsFoundOnlyWhenItIsInstalled() {
        assertFalse(MusicApp.installed(context))
        assertFalse(MusicApp.canPlaySongs(context))
        assertNull(MusicApp.openIntent(context))
        installMusic(songLinks = false)
        assertTrue(MusicApp.installed(context))
        assertFalse("곡 링크를 받지 않으면 곡을 넘길 수 없다", MusicApp.canPlaySongs(context))
        assertEquals(MusicApp.PACKAGE, MusicApp.openIntent(context)?.component?.packageName ?: MusicApp.openIntent(context)?.`package`)
        installMusic(songLinks = true)
        assertTrue(MusicApp.canPlaySongs(context))
        val i = MusicApp.songIntent(song.id, mix = true)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(MusicApp.PACKAGE, i.`package`)
        assertEquals(TubeLinks.musicUrl(song.id, mix = true), i.dataString)
        assertTrue((i.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    // ---------- 곡 누르기 ----------

    private val other = TubeVideo("pSUydWEqKwE", "NewJeans (뉴진스) 'Ditto' Official MV (side A)", "HYBE LABELS", 186)

    /** 최근 들은 곡: [song] 을 먼저, [other] 를 나중에 — 목록 맨 위는 other 라서 song 을 틀면 맨 위가 바뀐다 */
    private fun twoPlayed() {
        TubeState(context).addPlayed(song, 1_000L.coerceAtLeast(System.currentTimeMillis() - 60_000))
        TubeState(context).addPlayed(other)
    }

    private fun tapSong(a: TubeActivity) = tap(a) { it is Row.VideoItem && it.video.id == song.id }

    private fun header(a: TubeActivity) = (rows(a).first() as Row.Header).title

    @Test
    fun theFirstSongAsksWhereToListenThenPlaysThereAndRemembers() {
        installBrowser()
        twoPlayed()
        val a = open()
        // 아직 안 골랐다 — 처음 화면은 '듣는 곳 고르기', 쓰지 않을 워치 화면 플레이어는 미리 확인하지 않는다
        assertEquals(context.getString(R.string.tube_target_pick), action(a, TubeRows.A_TARGET)!!.text)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_how_unset) })
        assertEquals(0, probed)
        tapSong(a)
        // 틀기 전에 한 번 묻는다 (아무것도 안 틀고, 아무것도 미리 골라 두지 않음)
        assertEquals(context.getString(R.string.tube_targets_title), header(a))
        assertTrue(phoneOpened.isEmpty())
        assertNull(shadowOf(a).nextStartedActivity)
        assertTrue(actions(a).none { it.accent })
        assertEquals(other.id, TubeState(context).lastPlayed()?.id)
        // 폰 유튜브 뮤직을 고르면 그 곡을 바로 거기서, 그리고 기억
        tapAction(a, TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertEquals(song.id, TubeState(context).lastPlayed()?.id)
        assertEquals(PlayTarget.PHONE_MUSIC, TubeState(context).target)
        assertEquals(context.getString(R.string.tube_title), header(a))
        // 다음 곡은 묻지 않고 바로
        phoneOpened.clear()
        tap(a) { it is Row.VideoItem && it.video.id == other.id }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(other.id, mix = true)), phoneOpened)
        assertNull(shadowOf(a).nextStartedActivity)
    }

    @Test
    fun withYouTubeMusicOnTheWatchChosenASongPlaysThereWithPremium() {
        installBrowser()
        installMusic(songLinks = true)
        TubeState(context).target = PlayTarget.WATCH_MUSIC
        twoPlayed()
        val a = open()
        tapSong(a)
        val started = shadowOf(a).nextStartedActivity
        assertNotNull(started)
        assertEquals(MusicApp.PACKAGE, started!!.`package`)
        assertEquals(TubeLinks.musicUrl(song.id, mix = true), started.dataString)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue(phoneOpened.isEmpty())
        assertEquals(song.id, TubeState(context).lastPlayed()?.id)
        assertEquals(0, probed)
    }

    @Test
    fun withoutThePhoneASongPlaysOnTheWatchPlayerInstead() {
        installBrowser()
        TubeState(context).target = PlayTarget.PHONE_MUSIC
        PhoneOpener.override = { url -> phoneOpened += url; PhoneOpener.Result.NO_PHONE }
        twoPlayed()
        val a = open()
        tapSong(a)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertTrue(ShadowToast.showedToast(context.getString(R.string.tube_no_phone_watch)))
        val started = shadowOf(a).nextStartedActivity
        assertEquals(TubeLinks.playerUrl(base, song, mix = true), started?.dataString?.replace(Regex("&n=\\d+$"), ""))
        assertEquals(song.id, TubeState(context).lastPlayed()?.id)
    }

    @Test
    fun withoutThePhoneOrABrowserItSaysThePhoneIsNotConnected() {
        TubeState(context).target = PlayTarget.PHONE_MUSIC
        PhoneOpener.override = { url -> phoneOpened += url; PhoneOpener.Result.NO_PHONE }
        twoPlayed()
        val a = open()
        tapSong(a)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(ShadowToast.showedToast(context.getString(R.string.tube_no_phone)))
        assertNull(shadowOf(a).nextStartedActivity)
        assertEquals(other.id, TubeState(context).lastPlayed()?.id)
    }

    @Test
    fun ifTheWatchPlayerCannotBeReachedAndThereIsNoPhoneItDoesNotBounceBack() {
        // 워치 화면 플레이어 → 못 닿아 폰으로 → 폰도 없음: 다시 워치로 보내지 않고 알리고 끝 (끝없이 오가지 않게)
        installBrowser()
        TubeState(context).target = PlayTarget.WATCH_WEB
        WatchPlayer.probe = { probed++; false }
        PhoneOpener.override = { url -> phoneOpened += url; PhoneOpener.Result.NO_PHONE }
        twoPlayed()
        val a = open()
        tapSong(a)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertTrue(ShadowToast.showedToast(context.getString(R.string.tube_no_phone)))
        assertNull(shadowOf(a).nextStartedActivity)
    }

    @Test
    fun theTileMixFollowsWhereSongsPlayAndThenCloses() {
        TubeState(context).target = PlayTarget.PHONE_MUSIC
        TubeState(context).addPlayed(song)
        val a = open(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertTrue(a.isFinishing)
    }

    @Test
    fun theTileMixAsksFirstWhenNothingIsChosenThenPlaysAndCloses() {
        TubeState(context).addPlayed(song)
        val a = open(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
        assertEquals(context.getString(R.string.tube_targets_title), header(a))
        assertTrue(phoneOpened.isEmpty())
        assertFalse(a.isFinishing)
        tapAction(a, TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertTrue("타일에서 온 일을 마쳤으니 닫힌다", a.isFinishing)
    }

    @Test
    fun backingOutOfTheTileQuestionKeepsTheAppOpenForDirectUse() {
        TubeState(context).addPlayed(song)
        val a = open(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(context.getString(R.string.tube_title), header(a))
        assertNull(TubeState(context).target)
        // 이제 직접 쓰는 것 — 곡을 골라 틀어도 앱은 닫히지 않는다
        TubeState(context).target = PlayTarget.PHONE_MUSIC
        tapSong(a)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertFalse(a.isFinishing)
    }

    // ---------- 듣는 곳 ----------

    @Test
    fun theHomeScreenShowsWhereSongsPlayAndYouCanChangeIt() {
        installBrowser()
        twoPlayed()
        val a = open()
        tapAction(a, TubeRows.A_TARGET)
        // 고를 수 있는 곳: 폰(프리미엄) · 워치 화면 플레이어 (워치 유튜브 뮤직이 없으니 그건 없음). 아직 고른 곳 없음
        val ids = actions(a).map { it.id }
        assertEquals(listOf(TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key, TubeRows.A_TO + PlayTarget.WATCH_WEB.key, TubeRows.A_PREMIUM), ids)
        assertTrue(actions(a).none { it.accent || it.selected })

        tapAction(a, TubeRows.A_TO + PlayTarget.WATCH_WEB.key)
        // 처음 화면으로, '듣는 곳'이 바뀌었고 어디서 틀지 알림 — 다음에 열어도 그대로
        assertTrue(ShadowToast.showedToast(context.getString(R.string.tube_target_set, context.getString(R.string.tube_target_watch_web))))
        val chip = action(a, TubeRows.A_TARGET)!!
        assertEquals(context.getString(R.string.tube_target_watch_web), chip.text)
        assertEquals(context.getString(R.string.tube_target_label), chip.sub)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_how_watch_web) })
        assertEquals(PlayTarget.WATCH_WEB, TubeState(context).target)
        assertTrue(phoneOpened.isEmpty())
        tapSong(a)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(TubeLinks.playerUrl(base, song, mix = true), started?.dataString?.replace(Regex("&n=\\d+$"), ""))
        assertTrue(phoneOpened.isEmpty())
        // 다시 열면 고른 곳에 ✓ (흰 버튼, 읽어 주기에도 '선택됨')
        tapAction(a, TubeRows.A_TARGET)
        assertEquals(listOf(TubeRows.A_TO + PlayTarget.WATCH_WEB.key), actions(a).filter { it.accent && it.selected }.map { it.id })
    }

    @Test
    fun backFromTheQuestionPlaysNothingAndForgetsTheSong() {
        installBrowser()
        twoPlayed()
        val a = open()
        tapSong(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(context.getString(R.string.tube_title), header(a))
        assertNull(TubeState(context).target)
        // 나중에 '듣는 곳'에서 고르면 그때 그 곡을 틀지 않는다 (잊었음)
        tapAction(a, TubeRows.A_TARGET)
        tapAction(a, TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(phoneOpened.isEmpty())
        assertNull(shadowOf(a).nextStartedActivity)
        assertEquals(other.id, TubeState(context).lastPlayed()?.id)
    }

    @Test
    fun theQuestionAfterASearchGoesBackToTheResults() {
        com.mywatch.kakaomap.tube.TubeApi.override = { _, _, _ ->
            com.mywatch.kakaomap.tube.TubeResult(com.mywatch.kakaomap.tube.TubePage(listOf(song, other), null), com.mywatch.kakaomap.tube.TubeSource.YOUTUBE)
        }
        try {
            TubeState(context).addSearch("밤편지")
            val a = open()
            tapAction(a, TubeRows.A_QUERY + "밤편지")
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && a.findViewById<View>(R.id.status).visibility == View.VISIBLE) {
                Thread.sleep(5)
                idle()
            }
            assertEquals("‘밤편지’", header(a))
            tapSong(a)
            assertEquals(context.getString(R.string.tube_targets_title), header(a))
            // 뒤로 — 결과 그대로 (다른 곡을 고를 수 있게)
            a.onBackPressedDispatcher.onBackPressed()
            idle()
            assertEquals("‘밤편지’", header(a))
            // 다시 눌러 고르면 결과 화면으로 돌아와 그 곡을 튼다
            tapSong(a)
            tapAction(a, TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
            assertEquals("‘밤편지’", header(a))
        } finally {
            com.mywatch.kakaomap.tube.TubeApi.override = null
        }
    }

    @Test
    fun ifTheChosenWatchPlayerIsGoneSongsGoBackToPremium() {
        TubeState(context).target = PlayTarget.WATCH_WEB   // 예전에 골랐지만 지금은 삼성 인터넷이 없다
        twoPlayed()
        val a = open()
        assertEquals(context.getString(R.string.tube_target_phone_music), action(a, TubeRows.A_TARGET)!!.text)
        tapSong(a)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
    }

    @Test
    fun withTheWatchPlayerChosenTheScreenChecksItAhead() {
        installBrowser()
        TubeState(context).target = PlayTarget.WATCH_WEB
        open()
        assertEquals(1, probed)
    }

    // ---------- 길게 누르기 ----------

    @Test
    fun longPressOffersEveryPlaceWithTheUsualOneInWhite() {
        installBrowser()
        installMusic(songLinks = true)
        TubeState(context).target = PlayTarget.WATCH_MUSIC
        TubeState(context).addPlayed(song)
        val a = open()
        tap(a, long = true) { it is Row.VideoItem }
        assertEquals(
            listOf(TubeRows.A_WATCH_MUSIC, TubeRows.A_PHONE_MUSIC, TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE, TubeRows.A_FORGET, TubeRows.A_CANCEL),
            actions(a).map { it.id },
        )
        assertEquals(listOf(TubeRows.A_WATCH_MUSIC), actions(a).filter { it.accent }.map { it.id })
        // 메뉴 맨 위엔 그 곡의 큰 그림
        assertEquals(song.id, (rows(a).first() as Row.Header).art)
        tapAction(a, TubeRows.A_PHONE_MUSIC)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
    }

    @Test
    fun longPressWithNothingOnTheWatchOffersThePhone() {
        TubeState(context).addPlayed(song)
        val a = open()
        tap(a, long = true) { it is Row.VideoItem }
        assertEquals(listOf(TubeRows.A_PHONE_MUSIC, TubeRows.A_FORGET, TubeRows.A_CANCEL), actions(a).map { it.id })
        // 아직 듣는 곳을 안 골랐으면 흰 버튼 없음, 메뉴에서 고른 곳은 묻지 않고 바로
        assertTrue(actions(a).none { it.accent })
        tapAction(a, TubeRows.A_PHONE_MUSIC)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(TubeLinks.musicUrl(song.id, mix = true)), phoneOpened)
        assertNull("메뉴에서 한 번 고른 건 '듣는 곳'으로 저장하지 않음", TubeState(context).target)
    }

    // ---------- 프리미엄으로 듣는 법 ----------

    @Test
    fun premiumHelpOffersToInstallYouTubeMusicOnTheWatch() {
        val a = open()
        tapAction(a, TubeRows.A_PREMIUM)
        assertEquals(context.getString(R.string.tube_premium_title), (rows(a).first() as Row.Header).title)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_premium_why) })
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_premium_steps) })
        assertNotNull("아직 안 골랐으니 폰 유튜브 뮤직으로 바꾸기도", action(a, TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key))
        assertNull(action(a, TubeRows.A_OPEN_MUSIC))
        tapAction(a, TubeRows.A_INSTALL_MUSIC)
        val store = shadowOf(a).nextStartedActivity
        assertEquals("market://details?id=${MusicApp.PACKAGE}", store?.dataString)
        // 뒤로 = 처음 화면
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertNotNull(action(a, TubeRows.A_TARGET))
    }

    @Test
    fun premiumHelpOpensTheWatchAppToSwitchAccountsAndSaysItHasNoSearch() {
        installMusic(songLinks = false)
        val a = open()
        tapAction(a, TubeRows.A_PREMIUM)
        assertNull(action(a, TubeRows.A_INSTALL_MUSIC))
        assertEquals(context.getString(R.string.tube_open_music_sub), action(a, TubeRows.A_OPEN_MUSIC)!!.sub)
        assertTrue(rows(a).any { it is Row.Note && it.text == context.getString(R.string.tube_premium_no_search) })
        tapAction(a, TubeRows.A_OPEN_MUSIC)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(MusicApp.PACKAGE, started?.component?.packageName ?: started?.`package`)
        assertNull("곡 링크가 아니라 앱을 연다", started?.data)
    }

    @Test
    fun premiumHelpCanSwitchToThePhoneAppInOneTap() {
        installBrowser()
        TubeState(context).target = PlayTarget.WATCH_WEB
        val a = open()
        tapAction(a, TubeRows.A_PREMIUM)
        tapAction(a, TubeRows.A_TO + PlayTarget.PHONE_MUSIC.key)
        assertEquals(PlayTarget.PHONE_MUSIC, TubeState(context).target)
        assertEquals(context.getString(R.string.tube_target_phone_music), action(a, TubeRows.A_TARGET)!!.text)
    }

    @Test
    fun theWatchAppIsOfferedOnHomeWhenInstalled() {
        val before = open()
        assertNull(action(before, TubeRows.A_OPEN_MUSIC))
        installMusic(songLinks = false)
        val a = open()
        assertEquals(context.getString(R.string.tube_open_music_home_sub), action(a, TubeRows.A_OPEN_MUSIC)!!.sub)
        tapAction(a, TubeRows.A_OPEN_MUSIC)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(MusicApp.PACKAGE, started?.component?.packageName ?: started?.`package`)
        // 곡 링크를 안 받는 앱이면 워치 유튜브 뮤직은 '듣는 곳'에 없다 (앱만 열 수 있음)
        assertFalse(PlayPlaces.of(null, watchMusic = false, musicApp = true, watchWeb = false).watchMusic)
        tapAction(a, TubeRows.A_TARGET)
        assertNull(action(a, TubeRows.A_TO + PlayTarget.WATCH_MUSIC.key))
    }

    @Test
    fun screensOpenBeforeInstallingYouTubeMusicUpdateWhenYouComeBack() {
        // 프리미엄 안내에서 '워치에 유튜브 뮤직 설치' → Play 스토어에서 설치하고 돌아오면 '열기'·'바꾸기'가 보인다
        val c = Robolectric.buildActivity(TubeActivity::class.java).setup()
        idle()
        val a = c.get()
        tapAction(a, TubeRows.A_PREMIUM)
        tapAction(a, TubeRows.A_INSTALL_MUSIC)
        c.pause().stop()
        installMusic(songLinks = true)
        c.restart().start().resume()
        idle()
        assertNull(action(a, TubeRows.A_INSTALL_MUSIC))
        assertNotNull(action(a, TubeRows.A_OPEN_MUSIC))
        assertNotNull(action(a, TubeRows.A_TO + PlayTarget.WATCH_MUSIC.key))
    }
}
