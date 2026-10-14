package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import com.mywatch.kakaomap.tube.DeviceCode
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.TubeRows.Account
import com.mywatch.kakaomap.ui.TubeRows.LoginState
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.ui.tube.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 유튜브 앱 화면들의 줄 구성 (유튜브 앱처럼): 홈 구역 순서, 큰 동영상 카드의 둘째 줄(채널 · 조회수 · 언제),
 * 누르면 그 뒤 동영상들을 이어 트는 목록, 불러오는 동안 회색 자리, 실패하면 이유 + 다시 시도, 로그인 안내
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeRowsTest {

    private val c: Context = RuntimeEnvironment.getApplication()
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun v(id: String, embeddable: Boolean = true, views: Long? = 12_345, ago: Long = 3 * day, channelId: String? = "UCweOkPb1wVVH0Q0Tlj4a5Pw") =
        TubeVideo(id, "제목 $id", "1theK (원더케이)", 294, channelId = channelId, views = views, publishedAt = now - ago, embeddable = embeddable)

    private val videos = listOf(v("aaaaaaaaaa1"), v("aaaaaaaaaa2"), v("aaaaaaaaaa3", embeddable = false), v("aaaaaaaaaa4"), v("aaaaaaaaaa5"), v("aaaaaaaaaa6"))
    private fun ready(vararg list: TubeVideo, next: String? = null) = Slot(page = TubePage(list.toList(), next), loadedAt = now)
    private val loading = Slot(loading = true)
    private val channel = TubeChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK (원더케이)", "https://yt3.ggpht.com/k=s240", 25_300_000, 13_000, "UUweOkPb1wVVH0Q0Tlj4a5Pw")

    private fun cards(rows: List<Row>) = rows.filterIsInstance<Row.VideoCard>()
    private fun actions(rows: List<Row>) = rows.filterIsInstance<Row.Action>().map { it.id }
    private fun headers(rows: List<Row>) = rows.filterIsInstance<Row.Header>().map { it.title }

    @Test
    fun channelPicturesAreDrawnRoundAndVideoPicturesStaySquare() {
        val url = "https://yt3.ggpht.com/round-test=s240"
        TubeThumbs.preload(url, Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565))
        val avatar = ImageView(c).also { TubeThumbs.into(it, url, circle = true) }
        assertTrue("채널 사진은 그림 자체가 동그랗게", (avatar.drawable as RoundedBitmapDrawable).isCircular)
        val thumb = ImageView(c).also { TubeThumbs.into(it, url) }
        assertFalse("동영상 미리보기는 그대로", thumb.drawable is RoundedBitmapDrawable)
    }

    @Test
    fun picturesAreRememberedInProportionToTheAppsMemory() {
        val mb = 1L shl 20
        assertEquals("보통 워치(앱 메모리 256MB 이상)는 24MB 까지 — 큰 미리보기 70개쯤", 24 * mb, TubeThumbs.cacheBytes(512 * mb).toLong())
        assertEquals("1/8", 16 * mb, TubeThumbs.cacheBytes(128 * mb).toLong())
        assertEquals("아무리 작아도 4MB", 4 * mb, TubeThumbs.cacheBytes(16 * mb).toLong())
    }

    @Test
    fun aCardReadsLikeTheYouTubeApp() {
        val rows = TubeRows.chart(c, "인기 음악", ready(*videos.toTypedArray()), now)
        val first = cards(rows).first()
        assertEquals("1theK (원더케이) · 조회수 1.2만회 · 3일 전", first.meta)
        // 누르면 그 동영상부터 (워치에서 못 트는 것은 빼고) 목록 그대로 이어서
        assertEquals(Queue.Ids(listOf("aaaaaaaaaa2", "aaaaaaaaaa4", "aaaaaaaaaa5", "aaaaaaaaaa6")), first.queue)
        // 둘째 줄이 길면 끝이 잘리므로 '폰에서 재생'은 맨 앞에 (가장 중요한 정보)
        assertEquals("올린 사람이 막아 둔 동영상은 폰에서", "폰에서 재생 · 1theK (원더케이) · 조회수 1.2만회 · 3일 전", cards(rows)[2].meta)
        val live = TubeRows.card(c, TubeVideo("jfKfPfyJRdk", "lofi", "Lofi Girl", live = true), now, Queue.Single)
        assertEquals("Lofi Girl", live.meta)
        val bare = TubeRows.card(c, TubeVideo("bbbbbbbbbbb", "제목", ""), now, Queue.Single)
        assertEquals("", bare.meta)
    }

    @Test
    fun homeHasTheSectionsInOrderWhenSignedOut() {
        val rows = TubeRows.home(
            c, TubeRows.HomeData(
                history = listOf(Played(videos[0], now - 1000), Played(videos[1], now - 2000)),
                searches = listOf("아이유 밤편지", "뉴진스"),
                feed = null,
                music = ready(*videos.toTypedArray(), next = "M2"),
                popular = loading,
                account = Account(configured = true, signedIn = false),
                update = false,
                now = now,
            ),
        )
        assertEquals(listOf("유튜브", "이어서 보기", "인기 음악", "인기 동영상", "최근 검색"), headers(rows))
        assertTrue("맨 위 흰 '검색'", (rows[1] as Row.Action).let { it.id == TubeRows.A_SEARCH && it.accent })
        assertTrue("구독·보관함 둘이 한 줄", rows.any { it is Row.Chips && it.items.map { i -> i.id } == listOf(TubeRows.A_SUBS, TubeRows.A_LIBRARY) })
        assertTrue("인기 음악은 몇 개만 + 더 보기", actions(rows).contains(TubeRows.A_CHART_MUSIC))
        assertTrue("불러오는 중엔 회색 자리", rows.any { it is Row.Skeleton })
        assertTrue(actions(rows).contains(TubeRows.A_QUERY + "아이유 밤편지"))
        assertTrue("로그인 버튼", actions(rows).contains(TubeRows.A_LOGIN))
        assertFalse(actions(rows).contains(TubeRows.A_UPDATE))
    }

    @Test
    fun homeWhenSignedInShowsTheSubscriptionFeedAndMyAccount() {
        val rows = TubeRows.home(
            c, TubeRows.HomeData(
                history = emptyList(), searches = emptyList(),
                feed = ready(*videos.toTypedArray()),
                music = ready(videos[0]), popular = ready(videos[1]),
                account = Account(configured = true, signedIn = true, channel = channel),
                update = true, now = now,
            ),
        )
        assertEquals(listOf("유튜브", "구독 새 동영상", "인기 음악", "인기 동영상"), headers(rows))
        assertEquals("새 버전이 있으면 맨 위", TubeRows.A_UPDATE, (rows[1] as Row.Action).id)
        assertTrue(actions(rows).contains(TubeRows.A_SUBS_MORE))
        val account = rows.filterIsInstance<Row.Action>().first { it.id == TubeRows.A_ACCOUNT }
        assertEquals("1theK (원더케이)", account.text)
        assertFalse(actions(rows).contains(TubeRows.A_LOGIN))

        val off = TubeRows.home(c, TubeRows.HomeData(emptyList(), emptyList(), null, loading, loading, Account(configured = false, signedIn = false), false, now))
        assertFalse("로그인 키가 없는 빌드엔 로그인 버튼 대신 안내", actions(off).contains(TubeRows.A_LOGIN))
        assertTrue(off.any { it is Row.Note && it.text == c.getString(R.string.tube_login_unavailable) })
    }

    @Test
    fun aFailedSectionSaysWhyAndCanBeRetried() {
        val rows = TubeRows.chart(c, "인기 동영상", Slot(error = "네트워크 연결을 확인하세요"), now)
        assertTrue(rows.any { it is Row.Note && it.text == "네트워크 연결을 확인하세요" })
        assertTrue(actions(rows).contains(TubeRows.A_RETRY))
        val signIn = TubeRows.liked(c, Slot(error = "로그인이 필요해요", signIn = true), now)
        assertTrue("로그인이 풀렸으면 로그인 버튼", actions(signIn).contains(TubeRows.A_LOGIN))
    }

    @Test
    fun searchShowsChannelsFirstThenVideosWithPlaylistsInBetween() {
        val page = TubePage(
            videos.take(5), "S2",
            channels = listOf(channel),
            playlists = listOf(TubePlaylist("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx", "아이유 모음", "멜론", null)),
        )
        val rows = TubeRows.search(c, "아이유", Slot(page = page, source = TubeSource.YOUTUBE, note = null), now)
        val kinds = rows.map { it::class.simpleName }
        assertEquals("Header", kinds[0])
        assertEquals("ChannelItem", kinds[1])
        assertTrue("재생목록은 동영상 사이에", kinds.indexOf("PlaylistItem") in (kinds.indexOf("VideoCard") + 1) until kinds.lastIndexOf("VideoCard"))
        assertEquals("구독자 2530만명 · 동영상 1.3만개", rows.filterIsInstance<Row.ChannelItem>().single().sub)
        assertTrue(actions(rows).containsAll(listOf(TubeRows.A_MORE, TubeRows.A_SEARCH, TubeRows.A_PHONE_SEARCH)))
        val kakao = TubeRows.search(c, "아이유", Slot(page = TubePage(videos.take(1), null), source = TubeSource.KAKAO, note = "오늘 유튜브 검색 한도를 다 써서 카카오 동영상 검색으로 찾았어요"), now)
        assertTrue(kakao.any { it is Row.Note && it.text.startsWith("오늘 유튜브 검색 한도") })
        assertFalse(actions(kakao).contains(TubeRows.A_MORE))
        val nothing = TubeRows.search(c, "zzz", Slot(page = TubePage(emptyList(), null), source = TubeSource.YOUTUBE), now)
        assertTrue(nothing.any { it is Row.Note && it.text == c.getString(R.string.no_results) })
    }

    @Test
    fun aChannelPageShowsItsPictureVideosAndPlaylists() {
        val rows = TubeRows.channel(
            c, "1theK",
            info = Slot(page = TubePage(emptyList(), null, channels = listOf(channel))),
            videos = ready(*videos.take(2).toTypedArray(), next = "C2"),
            playlists = Slot(page = TubePage(emptyList(), null, playlists = listOf(TubePlaylist("PL1aaaaaaaaaa", "라이브", "1theK", null, 12)))),
            now = now,
        )
        val header = rows.first() as Row.Header
        assertEquals("1theK (원더케이)", header.title)
        assertEquals("https://yt3.ggpht.com/k=s240", header.avatar)
        assertEquals("구독자 2530만명 · 동영상 1.3만개", header.subtitle)
        assertEquals(2, cards(rows).size)
        assertTrue(actions(rows).contains(TubeRows.A_MORE))
        assertEquals("1theK · 12개", rows.filterIsInstance<Row.PlaylistItem>().single().sub)
    }

    @Test
    fun aPlaylistPlaysThroughTheWholeListOnYouTube() {
        val rows = TubeRows.playlist(c, "드라이브", "PL1aaaaaaaaaa", own = false, ready(*videos.take(3).toTypedArray()), now)
        val all = rows.filterIsInstance<Row.Action>().first { it.id == TubeRows.A_PLAY_ALL }
        assertTrue(all.accent)
        assertEquals("공개 재생목록은 유튜브가 끝까지 이어 준다", Queue.Playlist("PL1aaaaaaaaaa"), cards(rows)[1].queue)
        val mine = TubeRows.playlist(c, "내 목록", "PLmine0000001", own = true, ready(*videos.take(3).toTypedArray()), now)
        assertEquals("내 재생목록(비공개일 수 있음)은 받은 목록으로", Queue.Ids(listOf("aaaaaaaaaa2")), cards(mine)[0].queue)
    }

    @Test
    fun theLoginPageShowsABigCodeAndWhatToDo() {
        val code = DeviceCode("dc", "GQVQ-JKEC", "https://www.google.com/device", now + 1_800_000, 5)
        val waiting = TubeRows.login(c, LoginState.Waiting(code))
        val big = waiting.filterIsInstance<Row.Code>().single()
        assertEquals("GQVQ-JKEC", big.code)
        assertEquals("주소는 앞의 https:// 를 떼고", "www.google.com/device", big.url)
        assertTrue(actions(waiting).contains(TubeRows.A_LOGIN_PHONE))
        assertTrue(TubeRows.login(c, LoginState.Denied).let { actions(it).contains(TubeRows.A_LOGIN) })
        assertTrue(TubeRows.login(c, LoginState.Expired).let { actions(it).contains(TubeRows.A_LOGIN) })
        assertTrue(TubeRows.login(c, LoginState.Starting).any { it is Row.Skeleton || it is Row.Note })
        assertTrue(TubeRows.login(c, LoginState.Failed("구글 로그인 오류 (500)")).any { it is Row.Note && it.text == "구글 로그인 오류 (500)" })
    }

    @Test
    fun theVideoMenuOffersWhatMakesSense() {
        val rows = TubeRows.menu(c, videos[0], inHistory = true)
        assertEquals("aaaaaaaaaa1", (rows.first() as Row.Header).art)
        assertEquals(listOf(TubeRows.A_PLAY, TubeRows.A_CHANNEL, TubeRows.A_ON_PHONE, TubeRows.A_FORGET, TubeRows.A_CANCEL), actions(rows))
        val noChannel = TubeRows.menu(c, videos[0].copy(channelId = null), inHistory = false)
        assertEquals(listOf(TubeRows.A_PLAY, TubeRows.A_ON_PHONE, TubeRows.A_CANCEL), actions(noChannel))
        val phoneOnly = TubeRows.menu(c, videos[2], inHistory = false)
        val phone = phoneOnly.filterIsInstance<Row.Action>().first { it.accent }
        assertEquals("워치에서 못 트는 동영상은 폰이 주 버튼", TubeRows.A_ON_PHONE, phone.id)
        assertEquals("왜 폰인지 짧게 (두 줄 안에)", "워치에서는 재생할 수 없어요", phone.sub)
    }

    @Test
    fun theTermsPageAsksOnceAndStaysReachableFromTheLibrary() {
        val first = TubeRows.terms(c, agreed = false, privacy = true)
        assertEquals("시작하기 전에", (first.first() as Row.Header).title)
        assertTrue("유튜브 API 서비스를 쓴다고 알린다", first.filterIsInstance<Row.Note>().any { it.text.contains("YouTube API 서비스") && it.text.contains("YouTube 서비스 약관") })
        assertEquals("동의가 주 버튼", TubeRows.A_AGREE, first.filterIsInstance<Row.Action>().first { it.accent }.id)
        assertEquals(listOf(TubeRows.A_AGREE, TubeRows.A_YT_TERMS, TubeRows.A_PRIVACY), actions(first))
        val later = TubeRows.terms(c, agreed = true, privacy = true)
        assertEquals("약관·개인정보", (later.first() as Row.Header).title)
        assertEquals("이미 동의했으면 볼 것만", listOf(TubeRows.A_YT_TERMS, TubeRows.A_PRIVACY), actions(later))
        assertEquals("처리방침 주소를 모르면(로컬 빌드) 약관만", listOf(TubeRows.A_YT_TERMS), actions(TubeRows.terms(c, agreed = true, privacy = false)))
        assertTrue("보관함에서 언제든", actions(TubeRows.library(c, 0, Account(configured = true, signedIn = false))).contains(TubeRows.A_TERMS))
    }

    @Test
    fun theLibraryShowsMyListsOnlyWhenSignedIn() {
        val out = TubeRows.library(c, history = 3, Account(configured = true, signedIn = false))
        assertEquals(listOf(TubeRows.A_HISTORY, TubeRows.A_LOGIN, TubeRows.A_CLEAR, TubeRows.A_TERMS), actions(out))
        val inn = TubeRows.library(c, history = 0, Account(configured = true, signedIn = true, channel = channel))
        assertEquals(listOf(TubeRows.A_HISTORY, TubeRows.A_LIKED, TubeRows.A_MY_PLAYLISTS, TubeRows.A_ACCOUNT, TubeRows.A_TERMS), actions(inn))
    }
}
