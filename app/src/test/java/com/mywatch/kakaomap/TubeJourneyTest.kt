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
import com.mywatch.kakaomap.tube.PhoneOpener
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
 * 열림/안 열림, 폰 연결됨/없음/실패, 검색 성공/빈 결과/오류/한도), 동작(곡 누르기·길게·메뉴·뒤로·검색·더 보기·기록 지우기·
 * 새 링크·멈췄다 다시·시간 흐름)을 섞고, 매 동작 뒤 지켜야 할 것을 확인한다:
 *  · 죽지 않는다 · '여는 중…'에 갇히지 않는다 · 첫 줄은 늘 제목
 *  · 워치 재생 화면은 인터넷 앱이 있고 재생 화면이 열릴 때만 연다 — 그리고 그럴 땐 (폰 버튼이 아니면) 꼭 워치에서 연다
 *  · 웹 링크·뒤로·멈췄다 다시는 저절로 아무것도 틀지 않는다 (이어 듣기는 확인을 눌러야)
 *  · '최근 들은 곡'은 실제로 틀었을 때만 바뀐다 · 앱은 뒤로(처음 화면)나 타일 바로가기 일을 마쳤을 때만 닫힌다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeJourneyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val browser = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")

    // 환경 (걸음마다 바뀔 수 있음)
    private var browserInstalled = false
    private var pageOk = true
    private var phone = PhoneOpener.Result.OPENED
    private var searchMode = 0   // 0 결과 · 1 빈 결과 · 2 오류 · 3 한도

    // 이번 걸음에 일어난 일
    private val phoneOpened = mutableListOf<String>()
    private var probes = 0
    /** 마지막으로 재생 화면을 확인했을 때 열렸는지 (앱은 열리면 30분 동안 다시 확인하지 않는다 — 그 사이엔 그 결과대로) */
    private var lastProbe: Boolean? = null

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

    private class Run(val seed: Int, val log: MutableList<String> = mutableListOf()) {
        fun tail() = (listOf(log.first()) + log.drop(1).takeLast(5)).joinToString(" | ")
    }

    private fun journey(seed: Int, steps: Int) {
        val r = Random(seed)
        val run = Run(seed)
        setBrowser(r.nextInt(3) != 0)
        pageOk = r.nextInt(5) != 0
        phone = listOf(PhoneOpener.Result.OPENED, PhoneOpener.Result.OPENED, PhoneOpener.Result.NO_PHONE, PhoneOpener.Result.FAILED).random(r)
        searchMode = listOf(0, 0, 0, 1, 2, 3).random(r)
        val state = TubeState(context)
        repeat(r.nextInt(4)) { state.addPlayed(songs[r.nextInt(songs.size)], 1_000L + it) }
        if (r.nextBoolean()) state.addSearch("아이유")

        val start = when (r.nextInt(6)) {
            0 -> Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true)
            1 -> Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_VOICE, true)
            2 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube")).addCategory(Intent.CATEGORY_BROWSABLE)
            3 -> Intent(Intent.ACTION_VIEW, Uri.parse("kakaowatch://tube/phone?v=${songs[0].id}&t=42&mix=1&title=%EB%85%B8%EB%9E%98")).addCategory(Intent.CATEGORY_BROWSABLE)
            else -> Intent(context, TubeActivity::class.java)
        }
        run.log += "start ${start.action ?: ""} ${start.dataString ?: ""} ${start.extras?.keySet() ?: ""} browser=$browserInstalled page=$pageOk phone=$phone search=$searchMode"
        val c: ActivityController<TubeActivity> = Robolectric.buildActivity(TubeActivity::class.java, start).setup()
        // 앱이 아직 '타일에서 온 한 번의 일'을 들고 있어야 하는지 (그 일을 마치면 닫혀도 된다)
        var shortcutFlow = start.hasExtra(TubeActivity.EXTRA_MIX) || start.hasExtra(TubeActivity.EXTRA_VOICE)
        var pendingInput: Intent? = null
        var lastAction = "start"
        var allowedToPlay = start.hasExtra(TubeActivity.EXTRA_MIX)
        // 이번 걸음이 '워치에서 틀어야 하는' 재생인지 (폰 버튼·이어 듣기가 아닌 곡 누르기·믹스 이어 듣기·워치 버튼)
        var watchPlay = start.hasExtra(TubeActivity.EXTRA_MIX) && state.played().isNotEmpty()
        var watchExpected = false
        var rowsBefore: List<Row> = emptyList()
        fun expectWatch(): Boolean {
            // 앱이 아는 것: 인터넷 앱 + (열린다고 기억 / 안 열린다고 기억하지 않았고 지금 열림)
            val now = System.currentTimeMillis()
            return browserInstalled && (now < WatchPlayer.okUntil || (now >= WatchPlayer.failUntil && pageOk))
        }
        watchExpected = watchPlay && expectWatch()
        var prevTop = state.played().firstOrNull()?.video?.id
        var prevCount = state.played().size

        fun check(step: Int) {
            val a = c.get()
            settle(a)
            // 시작된 화면들 (말 입력·업데이트·재생 화면)
            shadowOf(a).nextStartedActivityForResult?.let { pendingInput = it.intent }
            var watchStarts = 0
            while (true) {
                val it = shadowOf(a).nextStartedActivity ?: break
                val data = it.dataString.orEmpty()
                when {
                    data.startsWith(base) -> {
                        watchStarts++
                        if (!browserInstalled) fail("씨앗 $seed 걸음 $step: 인터넷 앱이 없는데 재생 화면을 엶 (${run.tail()})")
                        if ((it.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0) fail("씨앗 $seed: 재생 화면을 새 작업 없이 엶")
                    }
                    it.component?.className == UpdateActivity::class.java.name -> Unit
                    else -> Unit
                }
            }
            val opened = phoneOpened.toList()
            phoneOpened.clear()
            if ((watchStarts > 0 || opened.isNotEmpty()) && !allowedToPlay) {
                fail("씨앗 $seed 걸음 $step: '$lastAction' 만으로 재생함 watch=$watchStarts phone=$opened (${run.tail()})")
            }
            if (watchStarts > 0 && lastProbe != true) fail("씨앗 $seed 걸음 $step: 재생 화면이 안 열린다고 확인했는데 엶")
            // 반대쪽: 인터넷 앱이 있고 재생 화면이 열리면 곡 누르기·믹스 이어 듣기·워치 버튼은 꼭 워치에서
            if (watchExpected && watchStarts == 0) fail("씨앗 $seed 걸음 $step: 워치에서 틀 수 있는데 '$lastAction' 이 워치에서 안 틂 phone=$opened (${run.tail()})")
            // 최근 들은 곡: 실제로 틀었을 때만 바뀜
            val now = state.played()
            val top = now.firstOrNull()?.video?.id
            val changed = top != prevTop || (now.size > prevCount)
            val reallyPlayed = watchStarts > 0 || (opened.isNotEmpty() && phone == PhoneOpener.Result.OPENED && opened.any { it.contains("watch?v=") })
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
            // 타일에서 온 한 번의 일이 끝나는 때 (앱도 잊는다): 틀어 보고도 안 닫힘(실패), 결과·오류 화면에서 뒤로·'처음으로', 링크
            val playedSomething = watchStarts > 0 || opened.isNotEmpty()
            if (playedSomething) shortcutFlow = false
            if (lastAction == "back" && rowsBefore.any { it is Row.Action && it.id == TubeRows.A_HOME }) shortcutFlow = false
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
        }

        check(0)
        for (step in 1..steps) {
            if (c.get().isFinishing) break
            val a = c.get()
            val rs = rows(a)
            rowsBefore = rs
            allowedToPlay = false
            watchPlay = false
            val canWatch = expectWatch()
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
                    watchPlay = true
                    if (!press(a, i, long = false)) { misses++; allowedToPlay = false; watchPlay = false; lastAction += "(miss)" }
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
                    allowedToPlay = id in setOf(TubeRows.A_MIX, TubeRows.A_PLAY_MIX, TubeRows.A_PLAY_ONE, TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE, TubeRows.A_HANDOFF, TubeRows.A_PHONE_SEARCH)
                    // 믹스 이어 듣기는 인터넷 앱이 있으면 워치에서, 워치 버튼은 늘 워치에서 (안 열리면 폰으로 대신)
                    watchPlay = id in setOf(TubeRows.A_MIX, TubeRows.A_WATCH_MIX, TubeRows.A_WATCH_ONE)
                    if (id == TubeRows.A_CLEAR) lastAction = "clear"
                    if (id == TubeRows.A_FORGET) lastAction = "forget"
                    if (!press(a, i, long = false)) { misses++; allowedToPlay = false; watchPlay = false; lastAction += "(miss)" }
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
                    watchPlay = state.played().isNotEmpty()
                    c.newIntent(Intent(context, TubeActivity::class.java).putExtra(TubeActivity.EXTRA_MIX, true))
                }
                x < 84 -> {
                    lastAction = "pause-resume"
                    c.pause().stop().restart().start().resume()
                }
                x < 90 -> {
                    lastAction = "env"
                    when (r.nextInt(4)) {
                        0 -> setBrowser(!browserInstalled)
                        1 -> pageOk = !pageOk
                        2 -> phone = PhoneOpener.Result.entries.random(r)
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
            run.log += "$step $lastAction browser=$browserInstalled page=$pageOk phone=$phone"
            watchExpected = watchPlay && canWatch
            check(step)
        }
        c.pause().stop().destroy()
    }

    @Test
    fun randomUsersNeverBreakTheYouTubeScreen() {
        val seeds = (System.getProperty("tube.seeds") ?: "1..60").split("..").map { it.trim().toInt() }
        for (seed in seeds[0]..seeds.last()) {
            setUp()
            browserInstalled = false
            journey(seed, steps = 25)
            // 다음 사용자: 깨끗하게
            shadowOf(context.packageManager).removeActivity(browser)
            browserInstalled = false
            TubeState(context).clear()
        }
        println("유튜브 여정: 씨앗 ${seeds[0]}..${seeds.last()} · 누름 $presses · 못 누름 $misses")
        // 누르려던 줄을 거의 다 실제로 눌렀어야 의미가 있다
        assertTrue("못 누른 걸음이 너무 많음: $misses / $presses", misses * 20 <= presses)
    }
}
