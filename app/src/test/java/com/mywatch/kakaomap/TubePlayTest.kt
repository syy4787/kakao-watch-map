package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.PlayRoute
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.tube.PlayerPresence
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePlay
import com.mywatch.kakaomap.tube.TubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 고른 동영상을 어디서 어떻게 틀지: 재생 화면 주소(목록 그대로 이어 틀기 · 재생목록 · 믹스 · 앱 안),
 * 앱 안 엔진 → 안 되면 삼성 인터넷 → 그것도 안 되면 폰, 엔진이 이 워치에서 죽은 적이 있으면 잠시 삼성 인터넷으로
 */
class TubePlayTest {

    @get:Rule val tmp = TemporaryFolder()

    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"
    private val iu = TubeVideo("BzYnNdJhZQw", "밤편지", "IU")
    private fun v(id: String, embeddable: Boolean = true) = TubeVideo(id, "제목 $id", "채널", embeddable = embeddable)

    @Test
    fun playerUrlCarriesTheQueue() {
        assertEquals(base + "#v=BzYnNdJhZQw&mix=1&t=%EB%B0%A4%ED%8E%B8%EC%A7%80&c=IU&n=5", TubeLinks.playerUrl(base, iu, Queue.Mix, nonce = 5))
        assertEquals(base + "#v=BzYnNdJhZQw&t=%EB%B0%A4%ED%8E%B8%EC%A7%80&c=IU", TubeLinks.playerUrl(base, iu, Queue.Single))
        // 앱의 목록: 이상한 번호·자기 자신·같은 번호는 빼고
        assertEquals(
            base + "#v=BzYnNdJhZQw&ids=pSUydWEqKwE,jfKfPfyJRdk&t=%EB%B0%A4%ED%8E%B8%EC%A7%80&c=IU&app=1",
            TubeLinks.playerUrl(base, iu, Queue.Ids(listOf("pSUydWEqKwE", "bad", "BzYnNdJhZQw", "jfKfPfyJRdk", "pSUydWEqKwE")), inApp = true),
        )
        // 재생목록 (이상한 번호면 이 동영상만)
        assertEquals(base + "#v=BzYnNdJhZQw&list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx", TubeLinks.playerUrl(base, iu.copy(title = "", channel = ""), Queue.Playlist("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx")))
        assertEquals(base + "#v=BzYnNdJhZQw", TubeLinks.playerUrl(base, iu.copy(title = "", channel = ""), Queue.Playlist("bad id!")))
        // 처음 동영상까지 50개 (재생 화면도 50개까지만 받는다)
        val many = (1..80).map { "vid%08d".format(it) }
        val url = TubeLinks.playerUrl(base, iu, Queue.Ids(many))
        assertEquals(49, url.substringAfter("&ids=").substringBefore('&').split(',').size)
        // 목록이 비면 이 동영상만
        assertEquals(TubeLinks.playerUrl(base, iu, Queue.Single), TubeLinks.playerUrl(base, iu, Queue.Ids(listOf("bad"))))
    }

    @Test
    fun aTapInAListPlaysThatVideoAndTheOnesAfterIt() {
        val list = listOf(v("aaaaaaaaaa1"), v("aaaaaaaaaa2"), v("aaaaaaaaaa3", embeddable = false), v("aaaaaaaaaa4"))
        val r = TubePlay.fromList(list, 1)
        assertEquals("aaaaaaaaaa2", r.video.id)
        assertEquals("워치에서 못 트는 것은 빼고 그 뒤 것들", Queue.Ids(listOf("aaaaaaaaaa4")), r.queue)
        assertEquals("마지막이면 그것만", Queue.Single, TubePlay.fromList(list, 3).queue)
        assertEquals(Queue.Single, TubePlay.fromList(list.take(1), 0).queue)
    }

    @Test
    fun whereToPlay() {
        fun route(embeddable: Boolean = true, page: Boolean = true, engine: Boolean = true, crashed: Boolean = false, browser: Boolean = true) =
            TubePlay.route(iu.copy(embeddable = embeddable), page, engine, crashed, browser)
        assertEquals("보통은 앱 안에서", PlayRoute.IN_APP, route())
        assertEquals("올린 사람이 막아 둔 동영상은 폰 유튜브로", PlayRoute.PHONE, route(embeddable = false))
        assertEquals("엔진이 최근에 죽었으면 삼성 인터넷으로", PlayRoute.BROWSER, route(crashed = true))
        assertEquals("엔진이 없는 기기(ARM 아님)", PlayRoute.BROWSER, route(engine = false))
        assertEquals(PlayRoute.PHONE, route(engine = false, browser = false))
        assertEquals(PlayRoute.PHONE, route(crashed = true, browser = false))
        assertEquals("재생 화면 주소를 모르는 빌드", PlayRoute.PHONE, route(page = false))

        assertTrue(TubePlay.engineSupported(arrayOf("armeabi-v7a", "armeabi")))
        assertTrue(TubePlay.engineSupported(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertFalse("x86 은 GeckoView 를 빼고 만든다", TubePlay.engineSupported(arrayOf("x86_64", "x86")))
    }

    @Test
    fun theGuardRemembersAnEngineThatDiedWhileStarting() {
        var now = 1_000_000L
        fun guard(version: Int = 130) = PlayerGuard(tmp.root, version) { now }
        assertFalse(guard().crashedRecently())

        // 잘 뜸: 시작 → 첫 화면
        guard().starting()
        guard().started()
        now += 10 * 60_000
        assertFalse(guard().crashedRecently())

        // 그리기 전에 사용자가 떠남 → 죽은 게 아님
        guard().starting()
        guard().stopped()
        now += 10 * 60_000
        assertFalse(guard().crashedRecently())

        // 시작하다 프로세스가 죽음 (표시가 남음): 바로는 모르고(아직 뜨는 중일 수 있음), 1분이 넘으면 죽은 것
        guard().starting()
        now += 30_000
        assertFalse(guard().crashedRecently())
        now += 60_000
        assertTrue(guard().crashedRecently())
        now += 5 * 3_600_000
        assertTrue("6시간 동안은 삼성 인터넷으로", guard().crashedRecently())
        now += 3_600_000
        assertFalse("그 뒤엔 다시 해 본다", guard().crashedRecently())

        // 엔진이 죽었다고 알림 → 6시간, 하지만 앱이 새 버전이면 바로 다시 해 본다
        guard().failed()
        assertTrue(guard().crashedRecently())
        assertFalse(guard(version = 131).crashedRecently())
        // 그 버전에서 잘 뜨면 표시를 지운다
        guard(version = 131).started()
        assertFalse(guard().crashedRecently())
    }

    @Test
    fun presenceTellsOtherProcessesThatAVideoIsOnScreen() {
        var t = 5_000_000L
        val p = PlayerPresence(tmp.root) { t }
        assertFalse(p.busy(quietMs = 600_000))
        p.shown()
        assertTrue(p.busy(600_000))
        t += 80_000
        assertTrue("30초마다 알리므로 90초까지는 보는 중", p.busy(600_000))
        t += 20_000
        assertFalse("알림이 끊김 = 재생 화면 프로세스가 죽음", p.busy(600_000))
        p.shown()
        p.hidden()
        assertTrue("닫은 직후 조용히 기다리는 동안", p.busy(600_000))
        t += 600_001
        assertFalse(p.busy(600_000))
        // 워치를 다시 켜면(부팅 후 시간이 처음부터) 예전 기록은 무시
        p.shown()
        t = 1_000
        assertFalse(p.busy(600_000))
    }

    @Test
    fun aCrashInThePlayerProcessIsRememberedAtOnce() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
        try {
            val guard = PlayerGuard(tmp.root, 130) { 1_000_000L }
            guard.starting()
            guard.watchCrashes()
            val boom = UnsatisfiedLinkError("libxul.so")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertTrue("1분을 기다리지 않고 바로", guard.crashedRecently())
            assertTrue("오류 기록·종료는 원래대로", passedOn === boom)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun handoffCanContinueAPlaylistOnThePhone() {
        val h = TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=83&list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx&title=x")!!
        assertEquals("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx", h.list)
        assertNull(TubeLinks.handoff("kakaowatch://tube/phone?v=BzYnNdJhZQw&list=bad!id")!!.list)
        assertEquals(
            "https://www.youtube.com/watch?v=BzYnNdJhZQw&list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx&t=83s",
            TubeLinks.watchUrl("BzYnNdJhZQw", mix = false, startSec = 83, list = h.list),
        )
        // 재생목록이 있으면 믹스보다 재생목록
        assertEquals(
            "https://www.youtube.com/watch?v=BzYnNdJhZQw&list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx",
            TubeLinks.watchUrl("BzYnNdJhZQw", mix = true, list = "PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"),
        )
    }
}
