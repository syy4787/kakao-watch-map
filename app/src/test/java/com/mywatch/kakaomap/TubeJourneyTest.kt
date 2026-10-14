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
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.tube.MusicApp
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayTarget
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeApi
import com.mywatch.kakaomap.tube.TubeResult
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.WatchPlayer
import com.mywatch.kakaomap.tube.YouTubeQuotaException
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.update.UpdateActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.random.Random

/**
 * 유튜브 음악 화면을 '아무렇게나 쓰는 사용자'로 수백 번 돌려 본다 (씨앗 고정 — 실패하면 같은 씨앗으로 다시 재현).
 * 들어오는 길(첫 화면·타일 믹스·타일 말 찾기·재생 화면 링크·이어 듣기 링크·최근 앱), 환경(인터넷 앱 있음/없음, 재생 화면이
 * 열림/안 열림, 워치 YouTube Music 없음/있음/곡 링크까지 받음, 고른 '듣는 곳', 폰 연결됨/없음/실패, 검색 성공/빈 결과/오류/한도),
 * 동작(곡 누르기·길게·메뉴·듣는 곳 바꾸기·프리미엄 안내·뒤로·검색·더 보기·기록 지우기·새 링크·멈췄다 다시·시간 흐름)을 섞고,
 * 매 동작 뒤 지켜야 할 것을 확인한다:
 *  · 죽지 않는다 · '여는 중…'에 갇히지 않는다 · 첫 줄은 늘 제목
 *  · 곡은 '듣는 곳'에서 튼다: 워치 유튜브 뮤직(곡 링크를 받을 때만) / 폰 유튜브 뮤직 / 워치 화면 플레이어(인터넷 앱이 있고
 *    재생 화면이 열릴 때만 — 안 열리면 폰). 고른 곳이 이제 안 되면 프리미엄이 되는 곳으로, 안 골랐으면 먼저 묻고(고르면 그 곡을),
 *    폰이 연결돼 있지 않으면 워치 화면 플레이어가 열릴 때 그리로 대신
 *  · 웹 링크·뒤로·멈췄다 다시·듣는 곳 바꾸기는 저절로 아무것도 틀지 않는다 (이어 듣기는 확인을 눌러야)
 *  · 워치 유튜브 뮤직 앱·Play 스토어는 그 버튼을 눌렀을 때만 연다 · 모든 길(묻기·워치 유튜브 뮤직·폰·폰 없을 때 워치·워치 화면
 *    플레이어·앱 열기·스토어)을 실제로 한 번 이상 거쳤는지도 확인
 *  · '최근 들은 곡'은 실제로 틀었을 때만 바뀐다 · 앱은 뒤로(처음 화면)나 타일 바로가기 일을 마쳤을 때만 닫힌다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeJourneyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val browser = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")
    private val music = ComponentName(MusicApp.PACKAGE, "com.google.android.apps.youtube.music.wear.MainActivity")

    // 환경 (걸음마다 바뀔 수 있음)
    private var browserInstalled = false
    /** 워치 YouTube Music: 0 없음 · 1 있음(곡 링크는 안 받음) · 2 곡 링크까지 받음 */
    private var musicMode = 0
    private var pageOk = true
    private var phone = PhoneOpener.Result.OPENED
    private var searchMode = 0   // 0 결과 · 1 빈 결과 · 2 오류 · 3 한도

    // 이번 걸음에 일어난 일
    private val phoneOpened = mutableListOf<String>()
    private var probes = 0
    /** 마지막으로 재생 화면을 확인했을 때 열렸는지 (앱은 열리면 30분 동안 다시 확인하지 않는다 — 그 사이엔 그 결과대로) */
    private var lastProbe: Boolean? = null

    private val MUSIC_WATCH = "https://music.youtube.com/watch?v="

    private val songs = (1..12).map { i ->
        TubeVideo("song${i.toString().padStart(7, '0')}".take(11).padEnd(11, 'x'), "노래 $i — 아주 긴 제목이 붙은 곡 (Official Video)", "채널 $i", 60 * i)
    }

    @Before
    fun setUp() {
        TubeThumbs.enabled = false
        TubeState(context).clear()
        WatchPlayer.base = base
        WatchPlayer.okUntil = 0L
        WatchPlayer.failUntil = 0L
        WatchPlayer.probe = { probes++; lastProbe = pageOk; pageOk }
        PhoneOpener.override = { url -> phoneOpened += url; phone }
        TubeApi.override = { q, next, src ->
            when (searchMode) {
                1 -> TubeResult(TubePage(emptyList(), null), TubeSource.YOUTUBE)
                2 -> throw ApiException("유튜브 검색 오류 (500)")
                3 -> throw YouTubeQuotaException()
                else -> {
                    val from = next?.toIntOrNull() ?: 0
                    val page = songs.drop(from).take(5)
                    TubeResult(TubePage(page, if (from + 5 < songs.size) (from + 5).toString() else null), src ?: TubeSource.YOUTUBE)
                }
            }
        }
    }

    @After
    fun tearDown() {
        TubeThumbs.enabled = true
        WatchPlayer.reset()
        PhoneOpener.override = null
        TubeApi.override = null
    }

    private fun setBrowser(on: Boolean) {
        val pm = shadowOf(context.packageManager)
        if (on && !browserInstalled) {
            pm.addActivityIfNotPresent(browser)
            pm.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            })
        } else if (!on && browserInstalled) {
            pm.removeActivity(browser)
        }
        browserInstalled = on
    }

    private fun settle(a: Activity) {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        // 검색은 IO 스레드에서 돈다 — '검색 중…'이 끝나 화면에 돌아올 때까지 실제 시간으로 기다린다 (바쁜 컴퓨터에서도 같은 결과)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !a.isFinishing && a.findViewById<View>(R.id.status).visibility == View.VISIBLE) {
            Thread.sleep(5)
            looper.idle()
        }
        // 폰 확인 애니메이션·타일 일 마친 뒤 닫기(1.8초) 같은 지연도 흘려보낸다
        looper.idleFor(Duration.ofSeconds(3))
        looper.idle()
    }

    private fun setMusic(mode: Int) {
        val pm = shadowOf(context.packageManager)
        if (musicMode != 0) pm.removeActivity(music)
        if (mode >= 1) {
            pm.addActivityIfNotPresent(music)
            pm.addIntentFilterForActivity(music, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        if (mode == 2) {
            pm.addIntentFilterForActivity(music, IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("music.youtube.com", null)
            })
        }
        musicMode = mode
    }

    private fun list(a: Activity): RecyclerView = a.findViewById(R.id.list)
    private fun rows(a: Activity): List<Row> = (list(a).adapter as RowAdapter).rows

    /** 그 줄을 실제로 누른다. 화면에 못 그려 누르지 못했으면 false (그 걸음은 아무 일도 안 한 것) */
    private fun press(a: Activity, i: Int, long: Boolean): Boolean {
        val rv = list(a)
        val spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        rv.scrollToPosition(i)
        // 스크롤은 다음 배치 때 적용된다 — 다시 재고(measure) 놓아야(layout) 그 줄이 그려진다
        rv.measure(spec, spec)
        rv.layout(0, 0, 480, 480)
        val v = rv.findViewHolderForAdapterPosition(i)?.itemView ?: return false
        if (long) v.performLongClick() else v.performClick()
        return true
    }

    /** 누르지 못한 걸음 수 (너무 많으면 시험이 제 일을 못 한 것) */
    private var misses = 0
    private var presses = 0
    /** 확인한 재생 (어디서 틀어야 했는지별) — 시험이 모든 곳을 실제로 거쳤는지 */
    private val checked = sortedMapOf<String, Int>()

    private class Run(val seed: Int, val log: MutableList<String> = mutableListOf()) {
        fun tail() = (listOf(log.first()) + log.drop(1).takeLast(5)).joinToString(" | ")
    }

    /**
     * 이번 걸음에 꼭 일어나야 하는 일 — [place]: web 워치 화면 플레이어 · music 워치 유튜브 뮤직 · phone 폰 유튜브 뮤직 ·
     * ask 듣는 곳을 묻는 화면. [webAfterNoPhone] = 폰과 연결돼 있지 않아 워치 화면 플레이어로 대신 틀어야 함
     */
    private data class Expect(val place: String, val webAfterNoPhone: Boolean = false)

    private fun journey(seed: Int, steps: Int) {
        val r = Random(seed)
        val run = Run(seed)
        setBrowser(r.nextInt(3) != 0)
        setMusic(listOf(0, 1, 2, 2).random(r))
        pageOk = r.nextInt(5) != 0
        phone = listOf(PhoneOpener.Result.OPENED, PhoneOpener.Result.OPENED, PhoneOpener.Result.NO_PHONE, PhoneOpener.Result.FAILED).random(r)
        searchMode = listOf(0, 0, 0, 1, 2, 3).random(r)
        val state = TubeState(context)
        state.target = listOf(null, null, PlayTarget.PHONE_MUSIC, PlayTarget.WATCH_WEB, PlayTarget.WATCH_MUSIC, PlayTarget.WATCH_MUSIC).random(r)
        repeat(r.nextInt(4)) { state.addPlayed(songs[r.nextInt(songs.size)], 1_000L + it) }
        if (r.nextBoolean()) state.addSearch("아이유")
        val targetsTitle = context.getString(R.string.tube_targets_title)
        fun header(rs: List<Row>): String? = (rs.firstOrNull() as? Row.Header)?.title

        val start = when (r.nextInt(6)) {
            0 -> Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)
            1 -> Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_VOICE, true)
            2 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube")).addCategory(Intent.CATEGORY_BROWSABLE)
            3 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/phone?v=${songs[0].id}&t=42&mix=1&title=%EB%85%B8%EB%9E%98")).addCategory(Intent.CATEGORY_BROWSABLE)
            else -> Intent(context, TubeActivity::class.java)
        }
        run.log += "start ${start.action ?: ""} ${start.dataString ?: ""} ${start.extras?.keySet() ?: ""} browser=$browserInstalled music=$musicMode target=${state.target} page=$pageOk phone=$phone search=$searchMode"
        val c: ActivityController<TubeActivity> = Robolectric.buildActivity(TubeActivity::class.java, start).setup()
        // 앱이 아직 '타일에서 온 한 번의 일'을 들고 있어야 하는지 (그 일을 마치면 닫혀도 된다)
        var shortcutFlow = start.hasExtra(TubeActivity.EXTRA_MIX) || start.hasExtra(TubeActivity.EXTRA_VOICE)
        var pendingInput: Intent? = null
        var lastAction = "start"
        var allowedToPlay = start.hasExtra(TubeActivity.EXTRA_MIX)
        var rowsBefore: List<Row> = emptyList()
        fun expectWatch(): Boolean {
            // 앱이 아는 것: 인터넷 앱 + (열린다고 기억 / 안 열린다고 기억하지 않았고 지금 열림)
            val now = System.currentTimeMillis()
            return browserInstalled && (now < WatchPlayer.okUntil || (now >= WatchPlayer.failUntil && pageOk))
        }
        // 폰으로 보냄. 폰이 없으면(그리고 대신해도 되는 길이면) 워치 화면 플레이어가 열릴 때 그리로
        fun phoneExpect(canWatch: Boolean, fallback: Boolean) =
            Expect("phone", webAfterNoPhone = fallback && phone == PhoneOpener.Result.NO_PHONE && browserInstalled && canWatch)
        // 고른 곳 [saved] 에서 틀 때 일어나야 하는 일 (고른 곳이 지금 안 되면 프리미엄이 되는 곳, 안 골랐으면 묻기)
        fun where(kind: String, canWatch: Boolean, saved: PlayTarget?): Expect {
            val t = PlayTarget.choose(saved, watchMusic = musicMode == 2, watchWeb = browserInstalled)
            return when (kind) {
                "default" -> when {
                    saved == null -> Expect("ask")
                    t == PlayTarget.WATCH_MUSIC -> Expect("music")
                    t == PlayTarget.WATCH_WEB -> if (canWatch) Expect("web") else phoneExpect(canWatch, fallback = false)
                    else -> phoneExpect(canWatch, fallback = true)
                }
                "web" -> if (canWatch) Expect("web") else phoneExpect(canWatch, fallback = false)
                // 메뉴를 연 사이 워치 유튜브 뮤직이 곡 링크를 안 받게 됐으면(앱을 지움) 폰으로 대신
                "music" -> if (musicMode == 2) Expect("music") else phoneExpect(canWatch, fallback = true)
                else -> phoneExpect(canWatch, fallback = true)
            }
        }
        var expected: Expect? = if (start.hasExtra(TubeActivity.EXTRA_MIX) && state.played().isNotEmpty()) where("default", expectWatch(), state.target) else null
        // 앱이 듣는 곳을 묻는 중인 곡을 들고 있는지 (고르면 그 곡을 튼다)
        var pendingAsk = false
        var prevTop = state.played().firstOrNull()?.video?.id
        var prevCount = state.played().size

        fun check(step: Int) {
            val a = c.get()
            settle(a)
            // 시작된 화면들 (말 입력·업데이트·재생 화면)
            shadowOf(a).nextStartedActivityForResult?.let { pendingInput = it.intent }
            var watchStarts = 0
            var musicStarts = 0
            var appOpens = 0
            var storeOpens = 0
            while (true) {
                val it = shadowOf(a).nextStartedActivity ?: break
                val data = it.dataString.orEmpty()
                val pkg = it.`package` ?: it.component?.packageName
                when {
                    data.startsWith(base) -> {
                        watchStarts++
                        if (!browserInstalled) fail("씨앗 $seed 걸음 $step: 인터넷 앱이 없는데 재생 화면을 엶 (${run.tail()})")
                        if ((it.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0) fail("씨앗 $seed: 재생 화면을 새 작업 없이 엶")
                    }
                    pkg == MusicApp.PACKAGE && data.startsWith(MUSIC_WATCH) -> {
                        musicStarts++
                        if (musicMode != 2) fail("씨앗 $seed 걸음 $step: 워치 유튜브 뮤직이 곡 링크를 안 받는데 넘김 (${run.tail()})")
                        if ((it.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0) fail("씨앗 $seed: 워치 유튜브 뮤직을 새 작업 없이 엶")
                    }
                    pkg == MusicApp.PACKAGE -> appOpens++
                    data.startsWith("market://") -> storeOpens++
                    it.component?.className == UpdateActivity::class.java.name -> Unit
                    else -> Unit
                }
            }
            val opened = phoneOpened.toList()
            phoneOpened.clear()
            if ((watchStarts > 0 || musicStarts > 0 || opened.isNotEmpty()) && !allowedToPlay) {
                fail("씨앗 $seed 걸음 $step: '$lastAction' 만으로 재생함 watch=$watchStarts music=$musicStarts phone=$opened (${run.tail()})")
            }
            if (appOpens > 0 && lastAction != "action:${TubeRows.A_OPEN_MUSIC}") fail("씨앗 $seed 걸음 $step: '$lastAction' 에 워치 유튜브 뮤직을 엶")
            if (storeOpens > 0 && lastAction !in setOf("action:${TubeRows.A_INSTALL_MUSIC}", "action:${TubeRows.A_OPEN_MUSIC}")) {
                fail("씨앗 $seed 걸음 $step: '$lastAction' 에 Play 스토어를 엶")
            }
            if (watchStarts > 0 && lastProbe != true) fail("씨앗 $seed 걸음 $step: 재생 화면이 안 열린다고 확인했는데 엶")
            // 꼭 일어나야 하는 일이 그곳에서 (다른 곳에서 틀지 않음)
            expected?.let { e ->
                val key = if (e.webAfterNoPhone) "phone>web" else e.place
                checked[key] = (checked[key] ?: 0) + 1
            }
            if (expected != null && lastAction.startsWith("action:${TubeRows.A_TO}")) checked["chose"] = (checked["chose"] ?: 0) + 1
            if (appOpens > 0) checked["app"] = (checked["app"] ?: 0) + 1
            if (storeOpens > 0) checked["store"] = (checked["store"] ?: 0) + 1
            val e = expected
            val where = "watch=$watchStarts music=$musicStarts phone=$opened (${run.tail()})"
            when (e?.place) {
                "web" -> if (watchStarts == 0 || musicStarts > 0 || opened.isNotEmpty()) {
                    fail("씨앗 $seed 걸음 $step: 워치 화면 플레이어로 틀 수 있는데 '$lastAction' 이 거기서만 틀지 않음 $where")
                }
                "music" -> if (musicStarts == 0 || watchStarts > 0 || opened.isNotEmpty()) {
                    fail("씨앗 $seed 걸음 $step: '$lastAction' 이 워치 유튜브 뮤직에서 안 틂 $where")
                }
                "phone" -> {
                    if (musicStarts > 0 || opened.none { it.startsWith(MUSIC_WATCH) }) fail("씨앗 $seed 걸음 $step: '$lastAction' 이 폰 유튜브 뮤직에서 안 틂 $where")
                    if (opened.any { it.contains("watch?v=") && !it.startsWith(MUSIC_WATCH) }) fail("씨앗 $seed 걸음 $step: 곡을 폰 유튜브 뮤직이 아닌 곳으로 보냄 $where")
                    if ((watchStarts > 0) != e.webAfterNoPhone) {
                        fail("씨앗 $seed 걸음 $step: 폰이 없을 때 워치 화면 플레이어로 대신 ${if (e.webAfterNoPhone) "안 틂" else "틂"} $where")
                    }
                }
                "ask" -> if (watchStarts > 0 || musicStarts > 0 || opened.isNotEmpty() || header(rows(a)) != targetsTitle) {
                    fail("씨앗 $seed 걸음 $step: 듣는 곳을 안 골랐는데 '$lastAction' 이 묻지 않음 ${header(rows(a))} $where")
                }
            }
            // 최근 들은 곡: 실제로 틀었을 때만 바뀜
            val now = state.played()
            val top = now.firstOrNull()?.video?.id
            val changed = top != prevTop || (now.size > prevCount)
            val reallyPlayed = watchStarts > 0 || musicStarts > 0 || (opened.isNotEmpty() && phone == PhoneOpener.Result.OPENED && opened.any { it.contains("watch?v=") })
            if (changed && !reallyPlayed && !lastAction.startsWith("clear") && !lastAction.startsWith("forget")) {
                fail("씨앗 $seed 걸음 $step: 틀지 않았는데 최근 곡이 바뀜 ($prevTop → $top, '$lastAction')")
            }
            prevTop = top
            prevCount = now.size
            // 닫혔으면: 뒤로(처음 화면)나 타일 바로가기 일을 마쳤을 때만
            if (a.isFinishing) {
                if (!(lastAction == "back" || shortcutFlow)) fail("씨앗 $seed 걸음 $step: '$lastAction' 뒤에 앱이 닫힘 (${run.tail()})")
                return
            }
            // 타일에서 온 한 번의 일이 끝나는 때 (앱도 잊는다): 틀어 보고도 안 닫힘(실패), 결과·오류·묻는 화면에서 뒤로·'처음으로', 링크
            val playedSomething = watchStarts > 0 || musicStarts > 0 || opened.isNotEmpty()
            if (playedSomething) shortcutFlow = false
            if (lastAction == "back" && rowsBefore.any { it is Row.Action && it.id == TubeRows.A_HOME }) shortcutFlow = false
            if (lastAction == "back" && header(rowsBefore) == targetsTitle) shortcutFlow = false
            if (lastAction == "action:${TubeRows.A_HOME}") shortcutFlow = false
            // 갇히지 않음: 다 끝나면 목록이 보이고, 첫 줄은 제목
            val status = a.findViewById<View>(R.id.status)
            if (status.visibility == View.VISIBLE && pendingInput == null) {
                fail("씨앗 $seed 걸음 $step: '$lastAction' 뒤에 상태 글자에 갇힘: ${(status as android.widget.TextView).text} (${run.tail()})")
            }
            val rs = rows(a)
            if (rs.isEmpty() || rs.first() !is Row.Header) fail("씨앗 $seed 걸음 $step: 첫 줄이 제목이 아님: ${rs.take(2)}")
            val ids = rs.filterIsInstance<Row.VideoItem>().map { it.video.id }
            if (ids.size != ids.toSet().size) fail("씨앗 $seed 걸음 $step: 같은 곡이 두 번: $ids")
            rs.filterIsInstance<Row.Action>().forEach { if (it.text.isBlank()) fail("씨앗 $seed: 빈 버튼 ${it.id}") }
            // 묻는 곡: 묻는 화면이 열리면 생기고, 고르거나 그 화면에서 뒤로·링크·타일로 처음 화면에 가면 사라진다
            pendingAsk = when {
                e?.place == "ask" -> true
                lastAction.startsWith("action:${TubeRows.A_TO}") -> false
                lastAction == "back" && header(rowsBefore) == targetsTitle -> false
                lastAction.startsWith("link:") || lastAction == "tile-mix" -> false
                else -> pendingAsk
            }
        }

        check(0)
        for (step in 1..steps) {
            if (c.get().isFinishing) break
            val a = c.get()
            val rs = rows(a)
            rowsBefore = rs
            allowedToPlay = false
            var kind: String? = null
            var toTarget: PlayTarget? = null
            // 누르기 전에 앱이 아는 것으로 (누르면서 재생 화면을 확인해 기억이 바뀔 수 있다)
            val canWatch = expectWatch()
            val savedBefore = state.target
            val x = r.nextInt(100)
            when {
                // 말 입력 화면이 열려 있으면 먼저 답한다 (말하거나 취소)
                pendingInput != null -> {
                    val intent = pendingInput!!
                    pendingInput = null
                    if (r.nextInt(3) == 0) {
                        lastAction = "input-cancel"
                        shadowOf(a).receiveResult(intent, Activity.RESULT_CANCELED, null)
                    } else {
                        lastAction = "input-text"
                        val data = Intent()
                        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("query").build()), data, Bundle().apply { putCharSequence("query", listOf("아이유 밤편지", "  뉴진스  ", "🎵", "a".repeat(200)).random(r)) })
                        shadowOf(a).receiveResult(intent, Activity.RESULT_OK, data)
                    }
                }
                x < 22 && rs.any { it is Row.VideoItem } -> {
                    val i = rs.indices.filter { rs[it] is Row.VideoItem }.random(r)
                    lastAction = "tap-video"
                    allowedToPlay = true
                    kind = "default"
                    if (!press(a, i, long = false)) { misses++; allowedToPlay = false; kind = null; lastAction += "(miss)" }
                    presses++
                }
                x < 32 && rs.any { it is Row.VideoItem } -> {
                    val i = rs.indices.filter { rs[it] is Row.VideoItem }.random(r)
                    lastAction = "long-video"
                    if (!press(a, i, long = true)) { misses++; lastAction += "(miss)" }
                    presses++
                }
                x < 58 && rs.any { it is Row.Action } -> {
                    val i = rs.indices.filter { rs[it] is Row.Action }.random(r)
                    val id = (rs[i] as Row.Action).id
                    lastAction = "action:$id"
                    allowedToPlay = id in setOf(
                        TubeRows.A_MIX, TubeRows.A_WATCH_MUSIC, TubeRows.A_PHONE_MUSIC, TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE,
                        TubeRows.A_HANDOFF, TubeRows.A_PHONE_SEARCH,
                    )
                    // 믹스 이어 듣기는 '듣는 곳'에서, 곡 메뉴의 버튼은 그곳에서 (워치 화면 플레이어가 안 열리면 폰으로 대신)
                    kind = when (id) {
                        TubeRows.A_MIX -> "default"
                        TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE -> "web"
                        TubeRows.A_WATCH_MUSIC -> "music"
                        TubeRows.A_PHONE_MUSIC -> "phone"
                        else -> null
                    }
                    // 듣는 곳을 고름: 묻던 곡이 있으면 그곳에서 튼다
                    if (id.startsWith(TubeRows.A_TO)) {
                        toTarget = PlayTarget.of(id.removePrefix(TubeRows.A_TO))
                        allowedToPlay = pendingAsk
                        if (pendingAsk) kind = "to"
                    }
                    if (id == TubeRows.A_CLEAR) lastAction = "clear"
                    if (id == TubeRows.A_FORGET) lastAction = "forget"
                    if (!press(a, i, long = false)) { misses++; allowedToPlay = false; kind = null; lastAction += "(miss)" }
                    presses++
                }
                x < 66 -> {
                    lastAction = "back"
                    a.onBackPressedDispatcher.onBackPressed()
                }
                x < 74 -> {
                    val link = when (r.nextInt(5)) {
                        0 -> "kakaowatch://tube"
                        1 -> "kakaowatch://tube/phone?v=${songs[r.nextInt(songs.size)].id}&t=${r.nextInt(400)}&mix=1&title=%EA%B3%A1"
                        2 -> "kakaowatch://tube/phone?v=%3Cx%3E&t=1"
                        3 -> "kakaowatch://tube/phone?v=${songs[1].id}&t=-3"
                        else -> "kakaowatch://tube/phone?v=${songs[2].id}"
                    }
                    lastAction = "link:$link"
                    c.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).putExtra(TubeActivity.EXTRA_MIX, true))
                    shortcutFlow = false
                }
                x < 78 -> {
                    lastAction = "tile-mix"
                    allowedToPlay = true
                    shortcutFlow = true
                    kind = if (state.played().isNotEmpty()) "default" else null
                    c.newIntent(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
                }
                x < 84 -> {
                    lastAction = "pause-resume"
                    c.pause().stop().restart().start().resume()
                }
                x < 90 -> {
                    lastAction = "env"
                    when (r.nextInt(5)) {
                        0 -> setBrowser(!browserInstalled)
                        1 -> pageOk = !pageOk
                        2 -> phone = PhoneOpener.Result.entries.random(r)
                        3 -> setMusic(r.nextInt(3))
                        else -> searchMode = r.nextInt(4)
                    }
                }
                x < 94 -> {
                    lastAction = "time"
                    WatchPlayer.okUntil = 0L
                    WatchPlayer.failUntil = 0L
                    lastProbe = null
                }
                else -> {
                    lastAction = "recents"
                    c.newIntent(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
                }
            }
            run.log += "$step $lastAction browser=$browserInstalled music=$musicMode target=${state.target} page=$pageOk phone=$phone ask=$pendingAsk"
            expected = when (kind) {
                null -> null
                "to" -> where("default", canWatch, toTarget)
                else -> where(kind, canWatch, savedBefore)
            }
            check(step)
        }
        c.pause().stop().destroy()
    }

    @Test
    fun randomUsersNeverBreakTheYouTubeScreen() {
        val seeds = (System.getProperty("tube.seeds") ?: "1..200").split("..").map { it.trim().toInt() }
        for (seed in seeds[0]..seeds.last()) {
            setUp()
            browserInstalled = false
            musicMode = 0
            journey(seed, steps = 25)
            // 다음 사용자: 깨끗하게
            shadowOf(context.packageManager).removeActivity(browser)
            browserInstalled = false
            setMusic(0)
            TubeState(context).clear()
            TubeState(context).target = null
        }
        println("유튜브 여정: 씨앗 ${seeds[0]}..${seeds.last()} · 누름 $presses · 못 누름 $misses · 확인한 재생 $checked")
        // 누르려던 줄을 거의 다 실제로 눌렀어야 의미가 있다
        assertTrue("못 누른 걸음이 너무 많음: $misses / $presses", misses * 20 <= presses)
        // 모든 길을 실제로 거쳤어야 의미가 있다 (기본 200명이면 다 나온다)
        if (seeds.last() - seeds[0] + 1 >= 200) {
            val missing = listOf("ask", "chose", "music", "phone", "phone>web", "web", "app", "store").filter { (checked[it] ?: 0) == 0 }
            assertTrue("한 번도 확인하지 못한 길: $missing ($checked)", missing.isEmpty())
        }
    }
}
