package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.content.Intent
import com.mywatch.kakaomap.tube.Harvest
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.TubeInbox
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 워치에 남는 기록(검색어·들은 곡)과, 폰에 보내는 '유튜브 앱으로 열기' 요청 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeStateTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val day = 24 * 3_600_000L
    private val prefs get() = context.getSharedPreferences("tube", Context.MODE_PRIVATE)

    @Test
    fun remembersSearchesAndPlayedSongs() {
        val state = TubeState(context)
        assertEquals(emptyList<String>(), state.searches)
        assertNull(state.lastPlayed())

        state.addSearch("아이유 밤편지")
        state.addSearch("뉴진스 Ditto")
        val iu = TubeVideo("BzYnNdJhZQw", "밤편지", "1theK", 294)
        val ditto = TubeVideo("pSUydWEqKwE", "Ditto", "HYBE LABELS", 186)
        state.addPlayed(iu, nowMs = 10 * day)
        state.addPlayed(ditto, nowMs = 11 * day)

        val again = TubeState(context)
        assertEquals(listOf("뉴진스 Ditto", "아이유 밤편지"), again.searches)
        assertEquals(listOf(ditto, iu), again.played(nowMs = 11 * day).map { it.video })
        assertEquals(ditto, again.lastPlayed(nowMs = 11 * day))

        again.removePlayed(ditto.id, nowMs = 11 * day)
        assertEquals(iu, again.lastPlayed(nowMs = 11 * day))
        again.removeSearch("뉴진스  ditto")
        assertEquals(listOf("아이유 밤편지"), again.searches)

        again.clear()
        assertEquals(emptyList<String>(), again.searches)
        assertNull(again.lastPlayed(nowMs = 11 * day))
    }

    @Test
    fun theAgreementToTheTermsIsAskedOnceAndSurvivesClearingHistory() {
        val state = TubeState(context)
        assertFalse(state.agreed)
        state.agree()
        assertTrue(state.agreed)
        state.clear()
        assertTrue("기록 지우기는 동의를 지우지 않는다", TubeState(context).agreed)
    }

    /** 처리방침이 바뀜(워치 안 기록으로 추천 — 판 2) → 예전 판(1)에 동의한 사람에게 다시 묻는다 (유튜브 API 정책 III.E.3) */
    @Test
    fun theNewPrivacyPolicyIsAgreedToAgain() {
        prefs.edit().putInt("terms_agreed", 1).commit()
        val state = TubeState(context)
        assertFalse("예전 판 동의로는 안 된다", state.agreed)
        state.agree()
        assertTrue(TubeState(context).agreed)
        assertEquals(2, prefs.getInt("terms_agreed", 0))
    }

    @Test
    fun mixRecommendationsAreKeptSevenDays() {
        val state = TubeState(context)
        assertEquals(emptyList<Harvest>(), state.harvests(nowMs = 10 * day))
        state.addHarvest(Harvest("BzYnNdJhZQw", listOf("pSUydWEqKwE", "jfKfPfyJRdk"), 10 * day), nowMs = 10 * day)
        state.addHarvest(Harvest("pSUydWEqKwE", listOf("aaaaaaaaaaa", "BzYnNdJhZQw"), 12 * day), nowMs = 12 * day)
        state.addHarvest(Harvest("bad", listOf("aaaaaaaaaaa"), 12 * day), nowMs = 12 * day)

        val again = TubeState(context)
        assertEquals(
            listOf(Harvest("pSUydWEqKwE", listOf("aaaaaaaaaaa", "BzYnNdJhZQw"), 12 * day), Harvest("BzYnNdJhZQw", listOf("pSUydWEqKwE", "jfKfPfyJRdk"), 10 * day)),
            again.harvests(nowMs = 12 * day),
        )
        // 7일이 지난 것은 보여 주지 않을 뿐 아니라 저장소에서도 지운다
        assertEquals(listOf("pSUydWEqKwE"), again.harvests(nowMs = 18 * day).map { it.seed })
        assertFalse(prefs.all.toString().contains("jfKfPfyJRdk"))
        assertEquals(emptyList<Harvest>(), again.harvests(nowMs = 20 * day))
        assertFalse(prefs.all.toString().contains("aaaaaaaaaaa"))
    }

    @Test
    fun notInterestedAndSeenShortsAreKeptThirtyDays() {
        val state = TubeState(context)
        state.dismissVideo("BzYnNdJhZQw", nowMs = 10 * day)
        state.dismissVideo("not a video", nowMs = 10 * day)
        state.dismissVideo("UCweOkPb1wVVH0Q0Tlj4a5Pw", nowMs = 10 * day)
        state.dismissChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", nowMs = 20 * day)
        state.dismissChannel("BzYnNdJhZQw", nowMs = 20 * day)
        state.markSeen("pSUydWEqKwE", nowMs = 30 * day)
        state.markSeen("UCweOkPb1wVVH0Q0Tlj4a5Pw", nowMs = 30 * day)
        state.markSeen("jfKfPfyJRdk", nowMs = 31 * day)

        val again = TubeState(context)
        assertEquals("동영상 번호만", setOf("BzYnNdJhZQw"), again.dismissedVideos(nowMs = 31 * day))
        assertEquals("채널 번호만", setOf("UCweOkPb1wVVH0Q0Tlj4a5Pw"), again.dismissedChannels(nowMs = 31 * day))
        assertEquals("새것부터", listOf("jfKfPfyJRdk", "pSUydWEqKwE"), again.seenShorts(nowMs = 31 * day).toList())
        // 30일이 지나면 저장소에서도 지운다
        assertEquals(emptySet<String>(), again.dismissedVideos(nowMs = 41 * day))
        assertFalse(prefs.all.toString().contains("BzYnNdJhZQw"))
        assertEquals(setOf("UCweOkPb1wVVH0Q0Tlj4a5Pw"), again.dismissedChannels(nowMs = 41 * day))
        assertEquals(emptySet<String>(), again.dismissedChannels(nowMs = 51 * day))
        assertEquals(setOf("jfKfPfyJRdk"), again.seenShorts(nowMs = 60 * day + 1))
        assertFalse(prefs.all.toString().contains("pSUydWEqKwE"))
    }

    /** 재생 화면이 받은 편지로 보낸 것: 믹스 추천은 받아 두고, 본 Shorts 는 표시하고, 그 밖은 버린다 */
    @Test
    fun notesFromThePlayerPageAreApplied() {
        val player = TubeInbox.of(context)
        player.post("kakaowatch://tube/recs?seed=BzYnNdJhZQw&ids=pSUydWEqKwE,jfKfPfyJRdk")
        player.post("kakaowatch://tube/seen?v=aaaaaaaaaaa")
        player.post("kakaowatch://tube/channel?v=BzYnNdJhZQw")
        player.post("javascript:alert(1)")
        player.post("kakaowatch://tube/recs?seed=pSUydWEqKwE&ids=bbbbbbbbbbb,BzYnNdJhZQw")
        player.post("kakaowatch://tube/recs?seed=ccccccccccc&ids=ccccccccccc")

        val state = TubeState(context)
        assertTrue(state.ingest(TubeInbox.of(context).drain(), nowMs = 10 * day))
        assertEquals(
            listOf(Harvest("pSUydWEqKwE", listOf("bbbbbbbbbbb", "BzYnNdJhZQw"), 10 * day), Harvest("BzYnNdJhZQw", listOf("pSUydWEqKwE", "jfKfPfyJRdk"), 10 * day)),
            state.harvests(nowMs = 10 * day),
        )
        assertEquals(setOf("aaaaaaaaaaa"), state.seenShorts(nowMs = 10 * day))
        assertEquals(emptySet<String>(), state.dismissedVideos(nowMs = 10 * day))

        assertFalse("바뀐 것이 없으면 false", state.ingest(listOf("garbage", "", "kakaowatch://tube/phone?v=BzYnNdJhZQw"), nowMs = 10 * day))
        assertFalse(state.ingest(emptyList(), nowMs = 10 * day))
        assertTrue(state.ingest(listOf("kakaowatch://tube/seen?v=ddddddddddd"), nowMs = 11 * day))
        assertEquals(listOf("ddddddddddd", "aaaaaaaaaaa"), state.seenShorts(nowMs = 11 * day).toList())
    }

    @Test
    fun clearingHistoryWipesEverythingUsedForRecommendations() {
        val state = TubeState(context)
        state.agree()
        state.addSearch("아이유 밤편지")
        state.addPlayed(TubeVideo("BzYnNdJhZQw", "밤편지", "1theK", 294, categoryId = "10", portrait = false), nowMs = 10 * day)
        state.addHarvest(Harvest("BzYnNdJhZQw", listOf("pSUydWEqKwE"), 10 * day), nowMs = 10 * day)
        state.dismissVideo("jfKfPfyJRdk", nowMs = 10 * day)
        state.dismissChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", nowMs = 10 * day)
        state.markSeen("aaaaaaaaaaa", nowMs = 10 * day)
        // 아직 앱에 닿지 않은 재생 화면의 알림도
        TubeInbox.of(context).post("kakaowatch://tube/recs?seed=bbbbbbbbbbb&ids=ccccccccccc")

        state.clear()
        val again = TubeState(context)
        assertEquals(emptyList<String>(), again.searches)
        assertEquals(emptyList<Any>(), again.played(nowMs = 10 * day))
        assertEquals(emptyList<Harvest>(), again.harvests(nowMs = 10 * day))
        assertEquals(emptySet<String>(), again.dismissedVideos(nowMs = 10 * day))
        assertEquals(emptySet<String>(), again.dismissedChannels(nowMs = 10 * day))
        assertEquals(emptySet<String>(), again.seenShorts(nowMs = 10 * day))
        assertEquals(emptyList<String>(), TubeInbox.of(context).drain())
        assertTrue("동의는 그대로", again.agreed)
    }

    @Test
    fun songsOlderThanThirtyDaysAreDeletedFromStorageNotJustHidden() {
        val state = TubeState(context)
        val old = TubeVideo("BzYnNdJhZQw", "밤편지", "1theK", 294)
        val fresh = TubeVideo("pSUydWEqKwE", "Ditto", "HYBE LABELS", 186)
        state.addPlayed(old, nowMs = 10 * day)
        state.addPlayed(fresh, nowMs = 35 * day)
        val prefs = context.getSharedPreferences("tube", Context.MODE_PRIVATE)
        assertTrue("아직은 둘 다 저장돼 있음", prefs.getString("played", "")!!.contains(old.id))

        // 41일째에 읽으면 31일 지난 곡은 저장소에서도 지운다 (유튜브 API 정보 30일 보관)
        assertEquals(listOf(fresh), state.played(nowMs = 41 * day).map { it.video })
        assertFalse(prefs.getString("played", "")!!.contains(old.id))
        assertTrue(prefs.getString("played", "")!!.contains(fresh.id))
    }

    @Test
    fun youTubeSearchRestsForTheDayAfterUsingUpTheQuota() {
        val state = TubeState(context)
        assertFalse(state.youtubeBlocked(day = "2026-10-13"))
        state.blockYoutube(day = "2026-10-13")
        assertTrue(state.youtubeBlocked(day = "2026-10-13"))
        // 유튜브 하루 한도는 미국 서부 자정에 다시 채워진다
        assertFalse(state.youtubeBlocked(day = "2026-10-14"))
        // 10월 미국 서부(UTC-7): 한국 시각 13일 오후 3시 59분까지는 아직 12일
        assertEquals("2026-10-12", TubeState.quotaDay(java.time.Instant.parse("2026-10-13T06:59:00Z")).toString())
        assertEquals("2026-10-13", TubeState.quotaDay(java.time.Instant.parse("2026-10-13T07:00:00Z")).toString())
    }

    @Test
    fun phoneGetsAPlainYouTubeLinkItCanOpen() {
        val i = PhoneOpener.intent(TubeLinks.watchUrl("BzYnNdJhZQw", mix = true))
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertTrue("원격 실행은 '열 수 있는 링크'만 받는다", i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("https://www.youtube.com/watch?v=BzYnNdJhZQw&list=RDBzYnNdJhZQw&start_radio=1", i.dataString)
        assertNull("특정 앱을 정하지 않는다 — 폰에서 유튜브 앱이 링크를 받는다", i.`package`)
    }
}
