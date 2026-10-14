package com.mywatch.kakaomap.ui

import android.content.Context
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.DeviceCode
import com.mywatch.kakaomap.tube.Handoff
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlay
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeText
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.tube.Slot

/**
 * 유튜브 앱 화면들의 줄 구성 (유튜브 앱처럼 — 큰 16:9 동영상 카드, 동그란 채널 사진, 흰 주 버튼).
 * 화면 그림 테스트도 같은 것을 그린다. 누르면 할 일은 [Row.Action.id] 로 ([TubeActivity] 가 처리)
 */
object TubeRows {
    const val A_SEARCH = "search"
    const val A_SUBS = "subs"
    const val A_LIBRARY = "library"
    const val A_UPDATE = "update"
    /** 최근 검색어: "q:" + 검색어 */
    const val A_QUERY = "q:"
    const val A_CLEAR = "clear"
    const val A_MORE = "more"
    const val A_MORE_PLAYLISTS = "more_playlists"
    const val A_PHONE_SEARCH = "phone_search"
    const val A_RETRY = "retry"
    const val A_HISTORY = "history"
    const val A_LIKED = "liked"
    const val A_MY_PLAYLISTS = "my_playlists"
    const val A_ACCOUNT = "account"
    const val A_LOGIN = "login"
    const val A_LOGIN_PHONE = "login_phone"
    const val A_LOGOUT = "logout"
    const val A_PERMISSIONS = "permissions"
    const val A_CHART_MUSIC = "chart_music"
    const val A_CHART_POPULAR = "chart_popular"
    const val A_SUBS_MORE = "subs_more"
    const val A_PLAY_ALL = "play_all"
    const val A_PLAY = "play"
    const val A_CHANNEL = "channel"
    const val A_ON_PHONE = "on_phone"
    const val A_FORGET = "forget"
    const val A_CANCEL = "cancel"
    const val A_HANDOFF = "handoff"

    /** 홈 구역마다 보여 주는 수 (나머지는 '더 보기') */
    private const val HOME_HISTORY = 2
    private const val HOME_FEED = 3
    private const val HOME_CHART = 4
    private const val HOME_SEARCHES = 4

    /** 로그인: [configured] = 이 빌드에 구글 로그인 클라이언트가 있음, [channel] = 내 유튜브 채널(이름·사진) */
    data class Account(val configured: Boolean, val signedIn: Boolean, val channel: TubeChannel? = null)

    /** 로그인 화면의 상태 */
    sealed interface LoginState {
        data object Starting : LoginState
        data class Waiting(val code: DeviceCode) : LoginState
        data object Denied : LoginState
        data object Expired : LoginState
        data class Failed(val message: String) : LoginState
    }

    /** 홈에 필요한 것: 이 앱의 기록·검색어, 구독 피드(로그인했으면), 인기 음악·인기 동영상, 계정, 새 버전 */
    data class HomeData(
        val history: List<Played>,
        val searches: List<String>,
        val feed: Slot?,
        val music: Slot,
        val popular: Slot,
        val account: Account,
        val update: Boolean,
        val now: Long,
    )

    // ---------------- 카드 ----------------

    /**
     * 동영상 카드의 둘째 줄: 채널 · 조회수 · 언제 (생방송은 채널만).
     * 워치에서 못 트는 것은 맨 앞에 '폰에서 재생' (줄이 길면 끝이 잘리므로)
     */
    fun meta(c: Context, v: TubeVideo, now: Long): String {
        val parts = mutableListOf<String>()
        if (!v.embeddable) parts += c.getString(R.string.tube_phone_only)
        v.channel.ifBlank { null }?.let { parts += it }
        if (!v.live) {
            v.views?.let { parts += TubeText.views(it) }
            v.publishedAt?.let { parts += TubeText.ago(it, now) }
        }
        return parts.joinToString(" · ")
    }

    fun card(c: Context, v: TubeVideo, now: Long, queue: Queue): Row.VideoCard = Row.VideoCard(v, meta(c, v, now), queue)

    /** 목록의 카드들: 누르면 그 동영상부터 뒤의 것들을 이어서 ([queue] 로 바꿀 수 있음) */
    private fun cards(c: Context, list: List<TubeVideo>, now: Long, shown: Int = list.size, queue: (Int) -> Queue = { TubePlay.fromList(list, it).queue }) =
        list.take(shown).mapIndexed { i, v -> card(c, v, now, queue(i)) }

    /**
     * 받는 데이터의 자리: 받았으면 [ready], 받는 중이면 회색 자리 둘, 실패면 이유 + 다시 시도 (로그인이 필요하면 로그인)
     */
    private fun slotRows(c: Context, slot: Slot, ready: (TubePage) -> List<Row>): List<Row> {
        val page = slot.page
        return when {
            page != null -> ready(page)
            slot.error != null -> listOf(
                Row.Note(slot.error),
                if (slot.signIn) Row.Action(A_LOGIN, R.drawable.ic_person, c.getString(R.string.tube_login), accent = true)
                else Row.Action(A_RETRY, R.drawable.ic_refresh, c.getString(R.string.tube_retry)),
            )
            else -> listOf(Row.Skeleton(0), Row.Skeleton(1))
        }
    }

    /** '더 보기' (받는 중이면 '더 불러오는 중…') */
    private fun more(c: Context, slot: Slot, id: String = A_MORE): List<Row> = when {
        slot.loadingMore -> listOf(Row.Note(c.getString(R.string.tube_loading_more)))
        slot.page?.next != null -> listOf(Row.Action(id, R.drawable.ic_expand_more, c.getString(R.string.tube_more)))
        else -> emptyList()
    }

    // ---------------- 홈 ----------------

    /**
     * 홈 (유튜브 앱처럼): 제목 · 흰 '검색' · 구독/보관함 · 이어서 보기 · 구독 새 동영상(로그인) · 인기 음악 · 인기 동영상 ·
     * 최근 검색 · 계정. 새 버전이 있으면 맨 위에 '새 버전 업데이트'
     */
    fun home(c: Context, d: HomeData): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_title)))
        if (d.update) rows += Row.Action(A_UPDATE, R.drawable.ic_download, c.getString(R.string.hub_update_new), accent = true)
        rows += Row.Action(A_SEARCH, R.drawable.ic_search, c.getString(R.string.tube_search), accent = !d.update)
        rows += Row.Chips(listOf(
            Row.Chip(A_SUBS, R.drawable.ic_subscriptions, c.getString(R.string.tube_subscriptions)),
            Row.Chip(A_LIBRARY, R.drawable.ic_video_library, c.getString(R.string.tube_library)),
        ))
        if (d.history.isNotEmpty()) {
            rows += Row.Header(c.getString(R.string.tube_continue))
            rows += cards(c, d.history.map { it.video }, d.now, HOME_HISTORY)
            if (d.history.size > HOME_HISTORY) rows += Row.Action(A_HISTORY, R.drawable.ic_history, c.getString(R.string.tube_history_all))
        }
        if (d.account.signedIn && d.feed != null) {
            rows += Row.Header(c.getString(R.string.tube_feed))
            rows += slotRows(c, d.feed) { page ->
                if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_feed_empty)))
                else cards(c, page.videos, d.now, HOME_FEED) +
                    Row.Action(A_SUBS_MORE, R.drawable.ic_expand_more, c.getString(R.string.tube_more))
            }
        }
        rows += chartSection(c, c.getString(R.string.tube_chart_music), d.music, d.now, A_CHART_MUSIC)
        rows += chartSection(c, c.getString(R.string.tube_chart_popular), d.popular, d.now, A_CHART_POPULAR)
        if (d.searches.isNotEmpty()) {
            rows += Row.Header(c.getString(R.string.tube_recent_searches))
            rows += d.searches.take(HOME_SEARCHES).map { Row.Action(A_QUERY + it, R.drawable.ic_history, it) }
        }
        rows += accountRow(c, d.account)
        return rows
    }

    private fun chartSection(c: Context, title: String, slot: Slot, now: Long, moreId: String): List<Row> =
        listOf(Row.Header(title)) + slotRows(c, slot) { page ->
            cards(c, page.videos, now, HOME_CHART) +
                if (page.videos.size > HOME_CHART || page.next != null) listOf(Row.Action(moreId, R.drawable.ic_expand_more, c.getString(R.string.tube_more))) else emptyList()
        }

    /** 계정 줄: 로그인했으면 내 채널(사진·이름), 아니면 '로그인', 로그인 키가 없는 빌드면 안내 */
    private fun accountRow(c: Context, a: Account): List<Row> = when {
        a.signedIn -> listOf(
            Row.Action(A_ACCOUNT, R.drawable.ic_person, a.channel?.title ?: c.getString(R.string.tube_account), sub = c.getString(R.string.tube_signed_in), avatar = a.channel?.thumb),
        )
        a.configured -> listOf(Row.Action(A_LOGIN, R.drawable.ic_person, c.getString(R.string.tube_login), sub = c.getString(R.string.tube_login_sub)))
        else -> listOf(Row.Note(c.getString(R.string.tube_login_unavailable)))
    }

    // ---------------- 목록 화면 ----------------

    /** 인기 차트 전체 */
    fun chart(c: Context, title: String, slot: Slot, now: Long): List<Row> =
        listOf<Row>(Row.Header(title)) + slotRows(c, slot) { page -> cards(c, page.videos, now) } + more(c, slot)

    /**
     * 검색 결과 (유튜브 앱처럼): 채널이 맨 위, 동영상 카드 사이사이에 재생목록. 끝에 '다시 검색'·'폰 유튜브에서 이 검색 열기'.
     * 카카오 동영상 검색으로 대신 찾았으면 그렇다고
     */
    fun search(c: Context, query: String, slot: Slot, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header("‘$query’", slot.source?.takeIf { it == TubeSource.KAKAO }?.label))
        slot.note?.let { rows += Row.Note(it) }
        rows += slotRows(c, slot) { page ->
            if (page.videos.isEmpty() && page.channels.isEmpty() && page.playlists.isEmpty()) return@slotRows listOf(Row.Note(c.getString(R.string.no_results)))
            val out = mutableListOf<Row>()
            out += page.channels.map { channelItem(it) }
            val playlists = page.playlists.map { playlistItem(it, own = false) }.toMutableList()
            cards(c, page.videos, now).forEachIndexed { i, card ->
                out += card
                // 동영상 3개마다 재생목록 하나 (유튜브 앱처럼 섞어서)
                if (i % 4 == 2 && playlists.isNotEmpty()) out += playlists.removeAt(0)
            }
            out + playlists
        }
        rows += more(c, slot)
        rows += Row.Action(A_SEARCH, R.drawable.ic_search, c.getString(R.string.tube_search_again), accent = slot.page?.videos.isNullOrEmpty() && slot.page != null)
        rows += Row.Action(A_PHONE_SEARCH, R.drawable.ic_smartphone, c.getString(R.string.tube_search_on_phone))
        return rows
    }

    fun channelItem(ch: TubeChannel): Row.ChannelItem = Row.ChannelItem(ch, channelSub(ch))

    /** 구독자 2530만명 · 동영상 1.3만개 (숨겼거나 모르면 빼고) */
    private fun channelSub(ch: TubeChannel): String? =
        listOfNotNull(ch.subscribers?.let(TubeText::subscribers), ch.videoCount?.let(TubeText::videoCount)).joinToString(" · ").ifBlank { null }

    private fun playlistItem(p: TubePlaylist, own: Boolean): Row.PlaylistItem =
        Row.PlaylistItem(p, listOfNotNull(p.channel.ifBlank { null }, p.count?.let { "${it}개" }).joinToString(" · ").ifBlank { null }, own)

    /** 채널: 동그란 사진 · 이름 · 구독자 → 동영상(최신순, 더 보기) → 재생목록 */
    fun channel(c: Context, title: String, info: Slot, videos: Slot, playlists: Slot, now: Long): List<Row> {
        val ch = info.page?.channels?.firstOrNull()
        val rows = mutableListOf<Row>(Row.Header(ch?.title ?: title, ch?.let(::channelSub), avatar = ch?.thumb))
        rows += Row.Header(c.getString(R.string.tube_videos))
        rows += slotRows(c, videos) { page ->
            if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_videos))) else cards(c, page.videos, now)
        }
        rows += more(c, videos)
        val lists = playlists.page?.playlists.orEmpty()
        if (lists.isNotEmpty()) {
            rows += Row.Header(c.getString(R.string.tube_playlists))
            rows += lists.map { playlistItem(it, own = false) }
            rows += more(c, playlists, A_MORE_PLAYLISTS)
        }
        return rows
    }

    /**
     * 재생목록: 제목 · '모두 재생'(흰) · 동영상. 공개 재생목록은 누른 동영상의 자리부터 유튜브가 목록 끝까지 이어 주고,
     * 내 재생목록(비공개일 수 있음)은 받은 목록으로 이어 튼다
     */
    fun playlist(c: Context, title: String, id: String, own: Boolean, slot: Slot, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(title))
        rows += slotRows(c, slot) { page ->
            if (page.videos.isEmpty()) return@slotRows listOf(Row.Note(c.getString(R.string.tube_no_videos)))
            listOf(Row.Action(A_PLAY_ALL, R.drawable.ic_play, c.getString(R.string.tube_play_all), accent = true)) +
                cards(c, page.videos, now) { i -> if (own) TubePlay.fromList(page.videos, i).queue else Queue.Playlist(id) }
        }
        rows += more(c, slot)
        return rows
    }

    fun history(c: Context, played: List<Played>, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_history)))
        if (played.isEmpty()) {
            rows += Row.Note(c.getString(R.string.tube_history_empty))
        } else {
            rows += cards(c, played.map { it.video }, now)
            rows += Row.Action(A_CLEAR, R.drawable.ic_delete, c.getString(R.string.tube_clear))
        }
        rows += Row.Note(c.getString(R.string.tube_history_note))
        return rows
    }

    fun liked(c: Context, slot: Slot, now: Long): List<Row> =
        listOf<Row>(Row.Header(c.getString(R.string.tube_liked))) +
            slotRows(c, slot) { page -> if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_videos))) else cards(c, page.videos, now) } +
            more(c, slot)

    fun myPlaylists(c: Context, slot: Slot): List<Row> =
        listOf<Row>(Row.Header(c.getString(R.string.tube_my_playlists))) +
            slotRows(c, slot) { page ->
                if (page.playlists.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_playlists))) else page.playlists.map { playlistItem(it, own = true) }
            } + more(c, slot)

    /** 구독: 새 동영상(구독 채널 최신순) → 채널 목록 (로그인 안 했으면 로그인 안내) */
    fun subscriptions(c: Context, account: Account, feed: Slot, channels: Slot, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_subscriptions)))
        if (!account.signedIn) {
            rows += Row.Note(c.getString(R.string.tube_subs_need_login))
            rows += accountRow(c, account)
            return rows
        }
        rows += Row.Header(c.getString(R.string.tube_feed))
        rows += slotRows(c, feed) { page ->
            if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_feed_empty))) else cards(c, page.videos, now)
        }
        rows += Row.Header(c.getString(R.string.tube_channels))
        rows += slotRows(c, channels) { page ->
            if (page.channels.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_subscriptions))) else page.channels.map(::channelItem)
        }
        rows += more(c, channels)
        return rows
    }

    /** 보관함: 시청 기록 · (로그인) 좋아요·내 재생목록·계정 / 로그인 · 기록 지우기 */
    fun library(c: Context, history: Int, account: Account): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_library)))
        rows += Row.Action(
            A_HISTORY, R.drawable.ic_history, c.getString(R.string.tube_history),
            sub = if (history > 0) c.getString(R.string.tube_history_count, history) else c.getString(R.string.tube_history_none),
        )
        if (account.signedIn) {
            rows += Row.Action(A_LIKED, R.drawable.ic_thumb_up, c.getString(R.string.tube_liked))
            rows += Row.Action(A_MY_PLAYLISTS, R.drawable.ic_queue_music, c.getString(R.string.tube_my_playlists))
        }
        rows += accountRow(c, account)
        if (history > 0) rows += Row.Action(A_CLEAR, R.drawable.ic_delete, c.getString(R.string.tube_clear))
        return rows
    }

    // ---------------- 메뉴 · 로그인 · 계정 · 이어 보기 ----------------

    /** 동영상을 길게 누름: 큰 그림 · 재생 · 채널로 가기 · 폰 유튜브에서 보기 · (기록이면) 지우기 */
    fun menu(c: Context, v: TubeVideo, inHistory: Boolean): List<Row> {
        val meta = listOfNotNull(v.channel.ifBlank { null }, v.durationSec?.let(TubeText::clock)).joinToString(" · ")
        return listOfNotNull(
            Row.Header(v.title, meta.ifBlank { null }, art = v.id),
            if (v.embeddable) Row.Action(A_PLAY, R.drawable.ic_play, c.getString(R.string.tube_play), accent = true) else null,
            if (v.channelId != null) Row.Action(A_CHANNEL, R.drawable.ic_person, c.getString(R.string.tube_go_channel), sub = v.channel.ifBlank { null }) else null,
            Row.Action(
                A_ON_PHONE, R.drawable.ic_smartphone, c.getString(R.string.tube_watch_on_phone), accent = !v.embeddable,
                sub = if (v.embeddable) null else c.getString(R.string.tube_phone_only_why),
            ),
            if (inHistory) Row.Action(A_FORGET, R.drawable.ic_delete, c.getString(R.string.tube_forget)) else null,
            Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.cancel)),
        )
    }

    /**
     * 로그인: 큰 코드와 할 일 — 폰(또는 PC)에서 주소를 열고 코드를 넣어 승인. 비밀번호는 워치에 넣지 않는다.
     * 기다리는 동안 저절로 끝난다
     */
    fun login(c: Context, s: LoginState): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_login)))
        when (s) {
            LoginState.Starting -> rows += Row.Note(c.getString(R.string.tube_login_starting))
            is LoginState.Waiting -> {
                rows += Row.Code(s.code.userCode, s.code.verificationUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'))
                rows += Row.Note(c.getString(R.string.tube_login_how))
                rows += Row.Action(A_LOGIN_PHONE, R.drawable.ic_smartphone, c.getString(R.string.tube_login_phone), accent = true)
                rows += Row.Note(c.getString(R.string.tube_login_waiting))
            }
            LoginState.Denied -> {
                rows += Row.Note(c.getString(R.string.tube_login_denied))
                rows += Row.Action(A_LOGIN, R.drawable.ic_person, c.getString(R.string.tube_login_again), accent = true)
            }
            LoginState.Expired -> {
                rows += Row.Note(c.getString(R.string.tube_login_expired))
                rows += Row.Action(A_LOGIN, R.drawable.ic_refresh, c.getString(R.string.tube_login_new_code), accent = true)
            }
            is LoginState.Failed -> {
                rows += Row.Note(s.message)
                rows += Row.Action(A_LOGIN, R.drawable.ic_refresh, c.getString(R.string.tube_retry), accent = true)
            }
        }
        rows += Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.cancel))
        return rows
    }

    /** 내 계정: 사진·이름 · 무엇을 하는지 · 로그아웃 · 폰에서 권한 관리 */
    fun account(c: Context, a: Account): List<Row> = listOf(
        Row.Header(a.channel?.title ?: c.getString(R.string.tube_account), c.getString(R.string.tube_signed_in), avatar = a.channel?.thumb),
        Row.Note(c.getString(R.string.tube_account_what)),
        Row.Action(A_LOGOUT, R.drawable.ic_logout, c.getString(R.string.tube_logout)),
        Row.Action(A_PERMISSIONS, R.drawable.ic_smartphone, c.getString(R.string.tube_permissions), sub = c.getString(R.string.tube_permissions_sub)),
    )

    /**
     * 재생 화면의 '폰에서 이어 보기': 무엇을 어디서부터 틀지 보여 주고 한 번 묻는다 (웹 페이지가 보낸 일이라 바로 틀지 않는다)
     */
    fun handoff(c: Context, h: Handoff): List<Row> = listOf(
        Row.Header(
            h.video.title.ifBlank { c.getString(R.string.tube_handoff_untitled) },
            if (h.startSec > 0) c.getString(R.string.tube_handoff_sub, TubeText.clock(h.startSec)) else c.getString(R.string.tube_handoff_sub_start),
        ),
        Row.Action(A_HANDOFF, R.drawable.ic_smartphone, c.getString(R.string.tube_handoff_go), accent = true),
        Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.cancel)),
    )
}
