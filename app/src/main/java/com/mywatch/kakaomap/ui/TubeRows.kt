package com.mywatch.kakaomap.ui

import android.content.Context
import androidx.annotation.StringRes
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.DeviceCode
import com.mywatch.kakaomap.tube.Handoff
import com.mywatch.kakaomap.tube.HomeChip
import com.mywatch.kakaomap.tube.HomeFeed
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlay
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeText
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.tube.ChannelTab
import com.mywatch.kakaomap.ui.tube.Slot
import com.mywatch.kakaomap.ui.tube.SubsChip
import com.mywatch.kakaomap.ui.tube.TubeTab

/**
 * 유튜브 앱 화면들의 줄 구성 (유튜브 앱처럼 — 맨 위 탭, 칩 줄, 큰 16:9 동영상 카드, Shorts 선반·격자, 동그란 채널 사진, 흰 주 버튼).
 * 화면 그림 테스트도 같은 것을 그린다. 누르면 할 일은 [Row.Action.id] 로 ([TubeActivity] 가 처리).
 * 홈·검색·구독·채널·기록의 동영상을 누르면 유튜브 믹스로 이어서 (유튜브 앱의 '다음 동영상'처럼), 재생목록·좋아요는 목록 순서대로
 */
object TubeRows {
    const val A_SEARCH = "search"
    /** 검색 화면의 '말하거나 입력해서 검색' */
    const val A_INPUT = "input"
    const val A_SUBS = "subs"
    const val A_LIBRARY = "library"
    const val A_UPDATE = "update"
    /** 최근 검색어: "q:" + 검색어 */
    const val A_QUERY = "q:"
    const val A_CLEAR = "clear"
    const val A_CLEAR_SEARCHES = "clear_searches"
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
    const val A_SUBS_MORE = "subs_more"
    /** 구독 채널 모두 (채널 사진 줄 끝의 '전체') */
    const val A_SUBS_CHANNELS = "subs_channels"
    /** Shorts 탭의 '▶ Shorts 보기' */
    const val A_SHORTS_PLAY = "shorts_play"
    const val A_PLAY_ALL = "play_all"
    const val A_PLAY = "play"
    const val A_CHANNEL = "channel"
    const val A_ON_PHONE = "on_phone"
    const val A_NOT_INTERESTED = "not_interested"
    const val A_NO_CHANNEL = "no_channel"
    const val A_FORGET = "forget"
    const val A_CANCEL = "cancel"
    const val A_HANDOFF = "handoff"
    const val A_AGREE = "agree"
    const val A_YT_TERMS = "yt_terms"
    const val A_PRIVACY = "privacy"
    const val A_TERMS = "terms"

    /** 칩 줄 이름 (넘긴 자리를 줄마다 기억) */
    const val BAR_HOME = "home"
    const val BAR_SUBS = "subs"
    const val BAR_CHANNEL = "channel"

    /** '나'에서 보여 주는 기록 수 (나머지는 '기록 모두 보기') */
    private const val YOU_HISTORY = 2
    /** 검색 화면의 최근 검색어 수 */
    private const val FIND_SEARCHES = 8
    /** 받는 중에 보여 주는 회색 자리 수 */
    private const val PLACEHOLDERS = 3
    /** 구독 화면 위 채널 사진 수 */
    private const val AVATARS = 15
    /** 이보다 적으면 검색 결과의 Shorts 는 선반 없이 카드로 */
    private const val SHELF_MIN = 2

    /** 로그인: [configured] = 이 빌드에 구글 로그인 클라이언트가 있음, [channel] = 내 유튜브 채널(이름·사진) */
    data class Account(val configured: Boolean, val signedIn: Boolean, val channel: TubeChannel? = null)

    /** 여러 곳에서 받아 합친 화면의 상태: 아직 받는 중 · 모두 실패(이유, 로그인이 필요한지) */
    data class Load(val loading: Boolean = false, val error: String? = null, val signIn: Boolean = false)

    /** 로그인 화면의 상태 */
    sealed interface LoginState {
        data object Starting : LoginState
        /**
         * [intervalSec] = 지금 묻는 간격 (구글이 천천히 하라면 늘어난 것 — 화면이 꺼졌다 켜져도 그대로),
         * [phone] = '폰에서 주소 열기'를 누른 결과 (코드 아래 한 줄로)
         */
        data class Waiting(val code: DeviceCode, val intervalSec: Int = code.intervalSec, val phone: PhoneStep? = null) : LoginState
        data object Denied : LoginState
        data object Expired : LoginState
        data class Failed(val message: String) : LoginState
    }

    /** 로그인 화면에서 '폰에서 주소 열기'를 누른 뒤 */
    enum class PhoneStep { SENDING, OPENED, NO_PHONE, FAILED }

    /**
     * 홈: [chip] 고른 칩, [feed] 그 칩의 목록([com.mywatch.kakaomap.tube.TubeHome]), [load] 받는 중·실패,
     * [more] '더 보기'로 이어 받을 것(인기 차트 — 없으면 null), [update] 새 버전
     */
    data class HomeData(
        val chip: HomeChip,
        val feed: HomeFeed,
        val load: Load,
        val more: Slot?,
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

    /** 피드의 카드들 (홈·검색·구독·채널·기록): 누르면 그 동영상부터 유튜브 믹스로 이어서 */
    private fun mixCards(c: Context, list: List<TubeVideo>, now: Long): List<Row> = list.map { card(c, it, now, Queue.Mix) }

    /** 목록 순서대로 이어 트는 카드들 (좋아요): 누르면 그 동영상부터 뒤의 것들을 */
    private fun listCards(c: Context, list: List<TubeVideo>, now: Long, queue: (Int) -> Queue = { TubePlay.fromList(list, it).queue }) =
        list.mapIndexed { i, v -> card(c, v, now, queue(i)) }

    /** 카드 사이 [at] 자리에 Shorts 선반 ([shorts] 가 없거나 [at] < 0 이면 없음) */
    private fun withShelf(c: Context, cards: List<Row>, shorts: List<TubeVideo>, at: Int): List<Row> {
        if (at < 0 || shorts.isEmpty()) return cards
        val i = at.coerceIn(0, cards.size)
        return cards.subList(0, i) + Row.ShortsShelf(c.getString(R.string.tube_shorts), shorts) + cards.subList(i, cards.size)
    }

    /** Shorts 격자 (두 칸씩) */
    private fun grid(list: List<TubeVideo>): List<Row> = list.indices.step(2).map { Row.ShortsPair(list, it) }

    /**
     * 받는 데이터의 자리: 받았으면 [ready], 받는 중이면 회색 자리 [placeholders] 개 (받으면 올 카드 수만큼 — 다 받아져도 아래가 밀리지 않게),
     * 실패면 이유 + 다시 시도 (로그인이 필요하면 로그인)
     */
    private fun slotRows(c: Context, slot: Slot, placeholders: Int = 2, ready: (TubePage) -> List<Row>): List<Row> {
        val page = slot.page
        return when {
            page != null -> ready(page)
            slot.error != null -> failed(c, slot.error, slot.signIn)
            else -> (0 until placeholders).map { Row.Skeleton(it) }
        }
    }

    private fun failed(c: Context, error: String, signIn: Boolean): List<Row> = listOf(
        Row.Note(error),
        if (signIn) Row.Action(A_LOGIN, R.drawable.ic_person, c.getString(R.string.tube_login), accent = true)
        else Row.Action(A_RETRY, R.drawable.ic_refresh, c.getString(R.string.tube_retry)),
    )

    /** 합친 화면의 자리: 보여 줄 것이 있으면 그것(받는 중이면 아래에 회색 자리 하나), 없으면 회색 자리 · 실패 이유 · 비었다는 말 */
    private fun loadRows(c: Context, content: List<Row>, load: Load, @StringRes empty: Int): List<Row> = when {
        content.isNotEmpty() -> if (load.loading) content + Row.Skeleton(0) else content
        load.loading -> (0 until PLACEHOLDERS).map { Row.Skeleton(it) }
        load.error != null -> failed(c, load.error, load.signIn)
        else -> listOf(Row.Note(c.getString(empty)))
    }

    /** '더 보기' (받는 중이면 '더 불러오는 중…') */
    private fun more(c: Context, slot: Slot?, id: String = A_MORE): List<Row> = when {
        slot == null -> emptyList()
        slot.loadingMore -> listOf(Row.Note(c.getString(R.string.tube_loading_more)))
        slot.page?.next != null -> listOf(Row.Action(id, R.drawable.ic_expand_more, c.getString(R.string.tube_more)))
        else -> emptyList()
    }

    // ---------------- 홈 ----------------

    /** 홈의 칩 줄 이름 */
    private fun chipLabel(chip: HomeChip): Int = when (chip) {
        HomeChip.ALL -> R.string.tube_chip_all
        HomeChip.MIXES -> R.string.tube_chip_mixes
        HomeChip.MUSIC -> R.string.tube_chip_music
        HomeChip.GAMING -> R.string.tube_chip_gaming
        HomeChip.LIVE -> R.string.tube_chip_live
        HomeChip.NEWS -> R.string.tube_chip_news
        HomeChip.RECENT -> R.string.tube_chip_recent
        HomeChip.WATCHED -> R.string.tube_chip_watched
        HomeChip.NEW -> R.string.tube_chip_new
    }

    private fun emptyText(chip: HomeChip): Int = when (chip) {
        HomeChip.ALL -> R.string.tube_empty_all
        HomeChip.MIXES -> R.string.tube_empty_mixes
        HomeChip.MUSIC, HomeChip.GAMING, HomeChip.NEWS -> R.string.tube_empty_category
        HomeChip.LIVE -> R.string.tube_empty_live
        HomeChip.RECENT -> R.string.tube_empty_recent
        HomeChip.WATCHED -> R.string.tube_history_empty
        HomeChip.NEW -> R.string.tube_empty_new
    }

    /**
     * 홈 (유튜브 앱처럼): 제목 · (새 버전) · 탭 · 흰 '검색' · 칩 줄 · 그 칩의 목록 (전체·음악·게임은 넷째 자리에 Shorts 선반) · 더 보기.
     * '믹스' 칩은 최근 본 동영상으로 시작하는 믹스 카드 (누르면 그 믹스)
     */
    fun home(c: Context, d: HomeData): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_title)))
        if (d.update) rows += Row.Action(A_UPDATE, R.drawable.ic_download, c.getString(R.string.hub_update_new), accent = true)
        rows += Row.Tabs(TubeTab.HOME)
        rows += Row.Action(A_SEARCH, R.drawable.ic_search, c.getString(R.string.tube_search), accent = !d.update)
        rows += Row.ChipBar(BAR_HOME, HomeChip.entries.map { it.name to c.getString(chipLabel(it)) }, d.chip.name)
        val content = if (d.chip == HomeChip.MIXES) {
            d.feed.videos.map { seed ->
                Row.VideoCard(
                    seed,
                    seed.channel.ifBlank { null }?.let { c.getString(R.string.tube_mix_meta, it) } ?: c.getString(R.string.tube_mix_meta_plain),
                    Queue.Mix,
                    badge = c.getString(R.string.tube_mix),
                )
            }
        } else {
            withShelf(c, mixCards(c, d.feed.videos, d.now), d.feed.shorts, d.feed.shelfAt)
        }
        rows += loadRows(c, content, d.load, emptyText(d.chip))
        if (content.isNotEmpty()) rows += more(c, d.more)
        return rows
    }

    // ---------------- Shorts · 검색 화면 ----------------

    /** Shorts 탭: 제목 · 탭 · 흰 '▶ Shorts 보기' · 세로 카드 격자 */
    fun shorts(c: Context, videos: List<TubeVideo>, load: Load): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_shorts)), Row.Tabs(TubeTab.SHORTS))
        if (videos.isNotEmpty()) rows += Row.Action(A_SHORTS_PLAY, R.drawable.ic_play, c.getString(R.string.tube_shorts_play), accent = true)
        rows += loadRows(c, grid(videos), load, R.string.tube_shorts_empty)
        return rows
    }

    /** 검색 화면 (유튜브 앱처럼): 흰 '말하거나 입력해서 검색' · 최근 검색어 · 검색 기록 지우기 */
    fun find(c: Context, searches: List<String>): List<Row> {
        val rows = mutableListOf<Row>(
            Row.Header(c.getString(R.string.tube_search)),
            Row.Action(A_INPUT, R.drawable.ic_mic, c.getString(R.string.tube_search_voice), accent = true),
        )
        if (searches.isNotEmpty()) {
            rows += Row.Header(c.getString(R.string.tube_recent_searches))
            rows += searches.take(FIND_SEARCHES).map { Row.Action(A_QUERY + it, R.drawable.ic_history, it) }
            rows += Row.Action(A_CLEAR_SEARCHES, R.drawable.ic_delete, c.getString(R.string.tube_clear_searches))
        }
        return rows
    }

    /**
     * 검색 결과 (유튜브 앱처럼): 채널이 맨 위, 동영상 카드 사이사이에 재생목록, 결과 속 Shorts 는 셋째 동영상 뒤 선반으로 (둘 이상일 때).
     * 끝에 '다시 검색'·'폰 유튜브에서 이 검색 열기'. 카카오 동영상 검색으로 대신 찾았으면 그렇다고
     */
    fun search(c: Context, query: String, slot: Slot, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header("‘$query’", slot.source?.takeIf { it == TubeSource.KAKAO }?.label))
        slot.note?.let { rows += Row.Note(it) }
        rows += slotRows(c, slot) { page ->
            if (page.videos.isEmpty() && page.channels.isEmpty() && page.playlists.isEmpty()) return@slotRows listOf(Row.Note(c.getString(R.string.no_results)))
            val shorts = page.videos.filter { it.isShort }.takeIf { it.size >= SHELF_MIN }.orEmpty()
            val out = mutableListOf<Row>()
            // 받은 쪽 순서대로: 쪽마다 채널 → 동영상(3개마다 재생목록 하나, 유튜브 앱처럼) → 남은 재생목록
            var cardsSoFar = 0
            var shelfDone = shorts.isEmpty()
            for (part in page.split()) {
                out += part.channels.map { channelItem(it) }
                val playlists = part.playlists.map { playlistItem(it, own = false) }.toMutableList()
                part.videos.filterNot { it in shorts }.forEachIndexed { i, v ->
                    out += card(c, v, now, Queue.Mix)
                    cardsSoFar++
                    if (!shelfDone && cardsSoFar == 3) {
                        out += Row.ShortsShelf(c.getString(R.string.tube_shorts), shorts)
                        shelfDone = true
                    }
                    if (i % 4 == 2 && playlists.isNotEmpty()) out += playlists.removeAt(0)
                }
                out += playlists
            }
            if (!shelfDone) out += Row.ShortsShelf(c.getString(R.string.tube_shorts), shorts)
            out
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

    // ---------------- 채널 ----------------

    private fun channelLabel(t: ChannelTab): Int = when (t) {
        ChannelTab.VIDEOS -> R.string.tube_chip_videos
        ChannelTab.SHORTS -> R.string.tube_shorts
        ChannelTab.LIVE -> R.string.tube_chip_live
        ChannelTab.PLAYLISTS -> R.string.tube_chip_playlists
    }

    /**
     * 채널 (유튜브 앱처럼): 동그란 사진 · 이름 · 구독자 → 칩 [동영상 · Shorts · 실시간 · 재생목록] → 고른 것.
     * [uploads] = 고른 칩의 업로드 (동영상·Shorts·실시간), [playlists] = 재생목록
     */
    fun channel(c: Context, title: String, section: ChannelTab, info: Slot, uploads: Slot, playlists: Slot, now: Long): List<Row> {
        val ch = info.page?.channels?.firstOrNull()
        val rows = mutableListOf<Row>(Row.Header(ch?.title ?: title, ch?.let(::channelSub), avatar = ch?.thumb))
        rows += Row.ChipBar(BAR_CHANNEL, ChannelTab.entries.map { it.name to c.getString(channelLabel(it)) }, section.name)
        when (section) {
            ChannelTab.VIDEOS -> {
                rows += slotRows(c, uploads) { page -> if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_videos))) else mixCards(c, page.videos, now) }
                rows += more(c, uploads)
            }
            ChannelTab.SHORTS -> {
                rows += slotRows(c, uploads) { page -> if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_channel_shorts_empty))) else grid(page.videos) }
                rows += more(c, uploads)
            }
            ChannelTab.LIVE -> {
                rows += slotRows(c, uploads) { page -> if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_channel_live_empty))) else mixCards(c, page.videos, now) }
                rows += more(c, uploads)
            }
            ChannelTab.PLAYLISTS -> {
                rows += slotRows(c, playlists) { page ->
                    if (page.playlists.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_playlists))) else page.playlists.map { playlistItem(it, own = false) }
                }
                rows += more(c, playlists, A_MORE_PLAYLISTS)
            }
        }
        return rows
    }

    /**
     * 재생목록: 제목 · '모두 재생'(흰) · 동영상. 공개 재생목록은 누른 동영상의 자리부터 유튜브가 목록 끝까지 이어 주고,
     * 내 재생목록(비공개일 수 있음)은 받은 목록으로 이어 튼다
     */
    fun playlist(c: Context, title: String, id: String, own: Boolean, slot: Slot, now: Long, private: Boolean = own): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(title))
        rows += slotRows(c, slot) { page ->
            if (page.videos.isEmpty()) return@slotRows listOf(Row.Note(c.getString(R.string.tube_no_videos)))
            // 비공개 내 재생목록은 받은 동영상 번호로 (폰에선 그 목록으로), 그 밖엔 유튜브가 목록 끝까지
            fun queue(i: Int): Queue =
                if (own && private) Queue.Ids((TubePlay.fromList(page.videos, i).queue as? Queue.Ids)?.next.orEmpty(), list = id)
                else Queue.Playlist(id)
            listOf(Row.Action(A_PLAY_ALL, R.drawable.ic_play, c.getString(R.string.tube_play_all), accent = true)) +
                listCards(c, page.videos, now, queue = ::queue)
        }
        rows += more(c, slot)
        return rows
    }

    /** 시청 기록 (이 앱에서 본 것): 누르면 그 동영상의 믹스로 이어서 */
    fun history(c: Context, played: List<Played>, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_history)))
        if (played.isEmpty()) {
            rows += Row.Note(c.getString(R.string.tube_history_empty))
        } else {
            rows += mixCards(c, played.map { it.video }, now)
            rows += Row.Action(A_CLEAR, R.drawable.ic_delete, c.getString(R.string.tube_clear), sub = c.getString(R.string.tube_clear_sub))
        }
        rows += Row.Note(c.getString(R.string.tube_history_note))
        return rows
    }

    /** 좋아요 표시한 동영상 (재생목록처럼 목록 순서대로 이어서) */
    fun liked(c: Context, slot: Slot, now: Long): List<Row> =
        listOf<Row>(Row.Header(c.getString(R.string.tube_liked))) +
            slotRows(c, slot) { page -> if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_videos))) else listCards(c, page.videos, now) } +
            more(c, slot)

    fun myPlaylists(c: Context, slot: Slot): List<Row> =
        listOf<Row>(Row.Header(c.getString(R.string.tube_my_playlists))) +
            slotRows(c, slot) { page ->
                if (page.playlists.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_playlists))) else page.playlists.map { playlistItem(it, own = true) }
            } + more(c, slot)

    // ---------------- 구독 · 나 ----------------

    private fun subsLabel(chip: SubsChip): Int = when (chip) {
        SubsChip.ALL -> R.string.tube_chip_all
        SubsChip.VIDEOS -> R.string.tube_chip_videos
        SubsChip.SHORTS -> R.string.tube_shorts
        SubsChip.LIVE -> R.string.tube_chip_live
    }

    /**
     * 구독 (유튜브 앱처럼): 제목 · 탭 · 채널 사진 줄(끝에 '전체') · 칩 [전체 · 동영상 · Shorts · 실시간] · 고른 것.
     * [feed] 구독 피드(새것부터), [shorts] 구독 채널 Shorts, [live] 구독 채널 생방송 (고른 칩일 때만 받음).
     * 로그인 안 했으면 로그인 안내
     */
    fun subscriptions(c: Context, account: Account, chip: SubsChip, channels: Slot, feed: Slot, shorts: Slot?, live: Slot?, now: Long): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_subscriptions)), Row.Tabs(TubeTab.SUBS))
        if (!account.signedIn) {
            rows += Row.Note(c.getString(R.string.tube_subs_need_login))
            rows += accountRow(c, account)
            return rows
        }
        channels.page?.channels?.takeIf { it.isNotEmpty() }?.let { rows += Row.AvatarStrip(it.take(AVATARS), more = true) }
        rows += Row.ChipBar(BAR_SUBS, SubsChip.entries.map { it.name to c.getString(subsLabel(it)) }, chip.name)
        when (chip) {
            SubsChip.ALL, SubsChip.VIDEOS -> rows += slotRows(c, feed, PLACEHOLDERS) { page ->
                val long = page.videos.filterNot { it.isShort }
                val cards = mixCards(c, long, now)
                if (long.isEmpty() && page.videos.none { it.isShort }) listOf(Row.Note(c.getString(R.string.tube_feed_empty)))
                else if (chip == SubsChip.ALL) {
                    val shelf = (page.videos.filter { it.isShort } + shorts?.page?.videos.orEmpty()).distinctBy { it.id }
                    withShelf(c, cards, shelf.takeIf { it.size >= SHELF_MIN }.orEmpty(), 3)
                } else cards.ifEmpty { listOf(Row.Note(c.getString(R.string.tube_feed_empty))) }
            }
            SubsChip.SHORTS -> rows += slotRows(c, shorts ?: Slot(loading = true), PLACEHOLDERS) { page ->
                val list = (page.videos + feed.page?.videos.orEmpty().filter { it.isShort }).distinctBy { it.id }
                if (list.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_subs_shorts_empty))) else grid(list)
            }
            SubsChip.LIVE -> rows += slotRows(c, live ?: Slot(loading = true), PLACEHOLDERS) { page ->
                if (page.videos.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_subs_live_empty))) else mixCards(c, page.videos, now)
            }
        }
        return rows
    }

    /** 구독한 채널 모두 */
    fun subsChannels(c: Context, channels: Slot): List<Row> =
        listOf<Row>(Row.Header(c.getString(R.string.tube_subs_channels))) +
            slotRows(c, channels) { page ->
                if (page.channels.isEmpty()) listOf(Row.Note(c.getString(R.string.tube_no_subscriptions))) else page.channels.map(::channelItem)
            } + more(c, channels)

    /** 계정 줄: 로그인했으면 내 채널(사진·이름), 아니면 '로그인', 로그인 키가 없는 빌드면 안내 */
    private fun accountRow(c: Context, a: Account): List<Row> = when {
        a.signedIn -> listOf(
            Row.Action(A_ACCOUNT, R.drawable.ic_person, a.channel?.title ?: c.getString(R.string.tube_account), sub = c.getString(R.string.tube_signed_in), avatar = a.channel?.thumb),
        )
        a.configured -> listOf(Row.Action(A_LOGIN, R.drawable.ic_person, c.getString(R.string.tube_login), sub = c.getString(R.string.tube_login_sub)))
        else -> listOf(Row.Note(c.getString(R.string.tube_login_unavailable)))
    }

    /**
     * 나 (유튜브 앱의 '나'처럼): 내 채널(또는 '나') · (새 버전) · 탭 · 기록(최근 둘 + 모두 보기) · 내 재생목록·좋아요(로그인) ·
     * 계정/로그인 · 추천에 쓰는 것(무엇을 얼마나 두는지 — 믹스 추천 [recs] · 관심 없음 [dismissed] · 본 Shorts [seen]) ·
     * 기록 지우기(지울 것이 있으면 — 검색어 [searches] 까지) · 약관
     */
    fun library(
        c: Context, account: Account, history: List<Played>, recs: Int, dismissed: Int, seen: Int = 0, searches: Int = 0,
        update: Boolean, now: Long,
    ): List<Row> {
        val me = account.channel.takeIf { account.signedIn }
        val rows = mutableListOf<Row>(
            if (me != null) Row.Header(me.title, c.getString(R.string.tube_signed_in), avatar = me.thumb) else Row.Header(c.getString(R.string.tube_you)),
        )
        if (update) rows += Row.Action(A_UPDATE, R.drawable.ic_download, c.getString(R.string.hub_update_new), accent = true)
        rows += Row.Tabs(TubeTab.YOU)
        rows += Row.Header(c.getString(R.string.tube_history))
        if (history.isEmpty()) {
            rows += Row.Note(c.getString(R.string.tube_history_empty))
        } else {
            rows += mixCards(c, history.take(YOU_HISTORY).map { it.video }, now)
            rows += Row.Action(A_HISTORY, R.drawable.ic_history, c.getString(R.string.tube_history_all), sub = c.getString(R.string.tube_history_count, history.size))
        }
        if (account.signedIn) {
            rows += Row.Action(A_MY_PLAYLISTS, R.drawable.ic_queue_music, c.getString(R.string.tube_my_playlists))
            rows += Row.Action(A_LIKED, R.drawable.ic_thumb_up, c.getString(R.string.tube_liked))
        }
        rows += accountRow(c, account)
        rows += Row.Note(c.getString(R.string.tube_recs_info, history.size, recs, dismissed, seen))
        if (history.isNotEmpty() || recs > 0 || dismissed > 0 || seen > 0 || searches > 0) {
            rows += Row.Action(A_CLEAR, R.drawable.ic_delete, c.getString(R.string.tube_clear), sub = c.getString(R.string.tube_clear_sub))
        }
        rows += Row.Action(A_TERMS, R.drawable.ic_info, c.getString(R.string.tube_terms))
        return rows
    }

    /**
     * YouTube 약관·개인정보 (유튜브 API 정책 III.A): 처음엔 '시작하기 전에' + 동의(주 버튼), 그 뒤엔 볼 것만.
     * 약관·처리방침은 폰에서 연다. [privacy] = 처리방침 주소를 앎 (로컬 빌드는 모름)
     */
    fun terms(c: Context, agreed: Boolean, privacy: Boolean): List<Row> = listOfNotNull(
        Row.Header(c.getString(if (agreed) R.string.tube_terms else R.string.tube_terms_first)),
        Row.Note(c.getString(R.string.tube_terms_text)),
        if (agreed) null else Row.Action(A_AGREE, R.drawable.ic_check, c.getString(R.string.tube_terms_agree), accent = true),
        Row.Action(A_YT_TERMS, R.drawable.ic_smartphone, c.getString(R.string.tube_terms_youtube), sub = c.getString(R.string.tube_terms_on_phone)),
        if (privacy) Row.Action(A_PRIVACY, R.drawable.ic_smartphone, c.getString(R.string.tube_terms_privacy), sub = c.getString(R.string.tube_terms_on_phone)) else null,
    )

    // ---------------- 메뉴 · 로그인 · 계정 · 이어 보기 ----------------

    /**
     * 동영상을 길게 누름: 큰 그림 · 재생 · 채널로 가기 · 폰 유튜브에서 보기 · 관심 없음 · 채널 추천 안 함 · (기록이면) 지우기 · 취소
     */
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
            Row.Action(A_NOT_INTERESTED, R.drawable.ic_close, c.getString(R.string.tube_not_interested)),
            if (v.channelId != null) Row.Action(A_NO_CHANNEL, R.drawable.ic_close, c.getString(R.string.tube_no_channel), sub = v.channel.ifBlank { null }) else null,
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
                rows += Row.Code(s.code.userCode, shortUrl(s.code))
                rows += Row.Note(c.getString(R.string.tube_login_how))
                // 폰에서 주소를 열어도 코드는 그대로 (폰에 넣으려면 봐야 한다) — 결과는 아래 한 줄로
                val phone = Row.Action(A_LOGIN_PHONE, R.drawable.ic_smartphone, c.getString(R.string.tube_login_phone), accent = true)
                rows += when (s.phone) {
                    null -> listOf(phone)
                    PhoneStep.SENDING -> listOf(Row.Note(c.getString(R.string.tube_login_phone_sending)))
                    PhoneStep.OPENED -> listOf(
                        Row.Note(c.getString(R.string.tube_login_phone_opened)),
                        phone.copy(text = c.getString(R.string.tube_login_phone_again), accent = false),
                    )
                    PhoneStep.NO_PHONE -> listOf(Row.Note(c.getString(R.string.tube_no_phone)), phone)
                    PhoneStep.FAILED -> listOf(Row.Note(c.getString(R.string.tube_phone_failed)), phone)
                }
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

    private fun shortUrl(code: DeviceCode) = code.verificationUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    /**
     * 손목을 내렸을 때(저전력 화면)의 글: 앱 이름 + 마지막에 본 것. 로그인 코드를 기다리는 중이면 코드와 주소를 크게 —
     * 폰에 코드를 넣는 동안 손목을 내려도 코드가 보이게 (점검 C6)
     */
    fun ambient(c: Context, lastTitle: String?, login: LoginState?): CharSequence {
        val app = c.getString(R.string.tube_app_name)
        if (login is LoginState.Waiting) {
            val code = login.code.userCode
            return android.text.SpannableStringBuilder().apply {
                append(code)
                setSpan(android.text.style.RelativeSizeSpan(1.7f), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n")
                append(shortUrl(login.code))
            }
        }
        return listOfNotNull(app, lastTitle).joinToString("\n")
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
