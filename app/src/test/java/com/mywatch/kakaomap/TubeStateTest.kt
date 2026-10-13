package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.content.Intent
import com.mywatch.kakaomap.tube.PhoneOpener
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
