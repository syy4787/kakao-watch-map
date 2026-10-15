package com.mywatch.kakaomap.ui.tube

import com.mywatch.kakaomap.tube.HomeChip
import com.mywatch.kakaomap.tube.HomeContext
import com.mywatch.kakaomap.tube.HomeFeed
import com.mywatch.kakaomap.tube.HomeSources
import com.mywatch.kakaomap.tube.MixHarvests
import com.mywatch.kakaomap.tube.TubeHome
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.tube.Uploads
import com.mywatch.kakaomap.ui.TubeRows

/**
 * 화면마다 받을 거리와, 받은 것 + 워치 안의 기록·추천([TubeState])으로 만든 홈·Shorts 목록.
 * 믹스 추천·내가 본 채널·Shorts 를 받을 채널은 워치 안의 것에 따라 정해지고(열쇠가 바뀜), 구독 채널을 받아야 정해지는 것도 있다 —
 * 정할 수 있게 되는 대로 받는다 ([feedsFor] 를 받을 때마다 다시 부름). 열쇠가 바뀌면 새것을 받는 동안 바로 앞 것을 보여 주고
 * (목록이 비었다 다시 차며 들썩이지 않게), 새것이 오면 앞 것은 잊는다
 */
class TubeFeeds(private val loader: TubeLoader, private val state: TubeState, private val signedIn: () -> Boolean) {

    /** 열쇠가 바뀌는 받을 거리마다 마지막으로 받은 것 (새것을 받는 동안 보여 줌) */
    private val shown = HashMap<String, Feed>()

    /** 워치 안의 것: 본 기록 · 관심 없음 · 구독 채널(받았으면) · 본 Shorts */
    fun context(now: Long): HomeContext = HomeContext(
        history = state.played(now),
        dismissedVideos = state.dismissedVideos(now),
        dismissedChannels = state.dismissedChannels(now),
        subscribed = subscribedOrder().orEmpty().toSet(),
        seenShorts = state.seenShorts(now),
        now = now,
    )

    /** 구독 채널(유튜브 관련도순 첫 쪽). 로그인했는데 아직 받는 중이면 null (실패하면 없는 것으로) */
    fun subscribedOrder(): List<String>? {
        if (!signedIn()) return emptyList()
        val s = loader.slot(Feed.SubsChannels)
        return s.page?.channels?.map { it.id } ?: if (s.error != null) emptyList() else null
    }

    /** 받아 둔 유튜브 믹스 추천 (없으면 null) */
    fun recs(now: Long): Feed.Recs? = MixHarvests.order(state.harvests(now)).takeIf { it.isNotEmpty() }?.let { Feed.Recs(it) }

    /** 이 앱에서 본 채널(구독 안 한)의 새 동영상 — 로그인했으면 구독 채널을 받은 뒤에 (구독 채널을 빼야 해서) */
    private fun watched(ctx: HomeContext): Feed.Watched? {
        if (subscribedOrder() == null) return null
        return TubeHome.watchedChannels(ctx).takeIf { it.isNotEmpty() }?.let { Feed.Watched(it) }
    }

    /** Shorts 를 받을 채널 (구독 채널을 받는 중이면 기다린다) */
    private fun shortsPool(ctx: HomeContext): Feed.ShortsPool? {
        val subs = subscribedOrder() ?: return null
        return TubeHome.shortsChannels(subs, ctx).takeIf { it.isNotEmpty() }?.let { Feed.ShortsPool(it) }
    }

    private fun subsShorts(): Feed.SubsShorts? = subscribedOrder()?.take(SUBS_CHANNELS)?.takeIf { it.isNotEmpty() }?.let { Feed.SubsShorts(it) }

    private fun subsLive(): Feed.SubsLive? = subscribedOrder()?.take(SUBS_CHANNELS)?.takeIf { it.isNotEmpty() }?.let { Feed.SubsLive(it) }

    /** 홈 칩의 인기 차트 (음악 10 · 게임 20 · 뉴스 25) */
    private fun chart(chip: HomeChip): Feed.Popular? = when (chip) {
        HomeChip.MUSIC -> Feed.Popular(TubeHome.MUSIC)
        HomeChip.GAMING -> Feed.Popular(TubeHome.GAMING)
        HomeChip.NEWS -> Feed.Popular(TubeHome.NEWS)
        else -> null
    }

    /** 칩이 받아 둔 것을 섞어 쓰는지 (믹스 추천·구독·본 채널·인기) */
    private fun usesPool(chip: HomeChip) = chip != HomeChip.MIXES && chip != HomeChip.WATCHED

    /**
     * [page] 가 받을 것 — 지금 정할 수 있는 것만. 열쇠가 바뀐 예전 믹스 추천·본 채널·Shorts 는 새것이 올 때까지 두고 그다음 잊는다.
     * Shorts 를 채우는 검색은 Shorts 탭에서만 (하루 100번 한도라 홈을 열 때마다 하지 않게)
     */
    fun feedsFor(page: Page, now: Long): List<Feed> {
        val ctx = context(now)
        val recs = recs(now)
        val watched = watched(ctx)
        val pool = shortsPool(ctx)
        forgetStale(recs, watched, pool)
        val mine = signedIn()
        return when (page) {
            is Page.Home -> {
                val chip = page.chip
                val out = mutableListOf<Feed?>()
                if (mine) out += Feed.SubsChannels
                if (usesPool(chip)) {
                    out += listOf(recs, Feed.Popular(null), watched)
                    if (mine) out += Feed.SubsFeed
                }
                out += chart(chip)
                when (chip) {
                    HomeChip.ALL, HomeChip.MUSIC, HomeChip.GAMING -> out += pool
                    HomeChip.LIVE -> out += Feed.Live
                    HomeChip.MIXES -> if (TubeHome.needsMusicTopUp(ctx)) out += Feed.Popular(TubeHome.MUSIC)
                    else -> Unit
                }
                out.filterNotNull().distinct()
            }
            Page.Shorts -> listOfNotNull(
                if (mine) Feed.SubsChannels else null, recs, pool,
                if (needsShortsSearch(pool, recs, now)) Feed.ShortsSearch else null,
            )
            is Page.Subscriptions -> if (!mine) emptyList() else listOfNotNull(
                Feed.SubsChannels, Feed.SubsFeed,
                if (page.chip == SubsChip.ALL || page.chip == SubsChip.SHORTS) subsShorts() else null,
                if (page.chip == SubsChip.LIVE) subsLive() else null,
            )
            Page.SubsChannels -> if (mine) listOf(Feed.SubsChannels) else emptyList()
            Page.Library, Page.Account -> if (mine) listOf(Feed.Account) else emptyList()
            is Page.Search -> listOf(Feed.Search(page.query))
            Page.Liked -> listOf(Feed.Liked)
            Page.MyPlaylists -> listOf(Feed.MyPlaylists)
            is Page.Channel -> listOf(Feed.ChannelInfo(page.id), channelList(page))
            is Page.Playlist -> listOf(Feed.PlaylistVideos(page.id, page.own))
            Page.Find, Page.History, is Page.Menu, Page.Login, is Page.Handoff, Page.Terms -> emptyList()
        }
    }

    /** '더 보기'로 이어 받을 것 (홈은 그 칩의 인기 차트) */
    fun moreFor(page: Page): Feed? = when (page) {
        is Page.Home -> when (page.chip) {
            HomeChip.ALL -> Feed.Popular(null)
            else -> chart(page.chip)
        }
        is Page.Search -> Feed.Search(page.query)
        Page.SubsChannels -> Feed.SubsChannels
        Page.Liked -> Feed.Liked
        Page.MyPlaylists -> Feed.MyPlaylists
        is Page.Channel -> channelList(page)
        is Page.Playlist -> Feed.PlaylistVideos(page.id, page.own)
        else -> null
    }

    /** 채널 화면의 고른 칩이 받을 목록 (업로드 종류 또는 재생목록) */
    fun channelList(page: Page.Channel): Feed = when (page.section) {
        ChannelTab.VIDEOS -> Feed.ChannelUploads(page.id, Uploads.LONG)
        ChannelTab.SHORTS -> Feed.ChannelUploads(page.id, Uploads.SHORTS)
        ChannelTab.LIVE -> Feed.ChannelUploads(page.id, Uploads.LIVE)
        ChannelTab.PLAYLISTS -> Feed.ChannelPlaylists(page.id)
    }

    /**
     * 구독 화면의 Shorts·실시간 칩이 보여 줄 것: 받는 중이면 그 받기, 구독 채널이 없으면 빈 목록, 구독 채널을 못 받았으면 그 실패
     * (다시 시도) — 끝없이 회색 자리로 남지 않게. 그 칩이 아니면 null
     */
    fun subsChipSlot(page: Page.Subscriptions, chip: SubsChip): Slot? {
        val feed = when {
            chip == SubsChip.SHORTS && (page.chip == SubsChip.ALL || page.chip == SubsChip.SHORTS) -> subsShorts()
            chip == SubsChip.LIVE && page.chip == SubsChip.LIVE -> subsLive()
            else -> return null
        }
        if (feed != null) return loader.slot(feed)
        val channels = loader.slot(Feed.SubsChannels)
        return when {
            channels.error != null -> channels
            subscribedOrder() != null -> Slot(page = TubePage(emptyList(), null))
            else -> Slot(loading = true)
        }
    }

    /** 홈 [chip] 의 목록 · 받는 상태 · '더 보기' 자리 */
    fun home(chip: HomeChip, now: Long): Triple<HomeFeed, TubeRows.Load, Slot?> {
        val ctx = context(now)
        fun videos(f: Feed?) = f?.let { loader.slot(it).page?.videos }.orEmpty()
        val s = HomeSources(
            recs = shownVideos(RECS, recs(now)),
            subs = if (signedIn()) videos(Feed.SubsFeed) else emptyList(),
            watched = shownVideos(WATCHED, watched(ctx)),
            trending = videos(Feed.Popular(null)),
            music = videos(Feed.Popular(TubeHome.MUSIC)),
            gaming = videos(Feed.Popular(TubeHome.GAMING)),
            news = videos(Feed.Popular(TubeHome.NEWS)),
            live = videos(Feed.Live),
            shorts = shownVideos(POOL, shortsPool(ctx)) + videos(Feed.ShortsSearch),
        )
        val feed = TubeHome.feed(chip, s, ctx)
        val more = moreFor(Page.Home(chip))?.let(loader::slot)?.takeIf { chip == HomeChip.ALL || chart(chip) != null }
        return Triple(feed, load(feedsFor(Page.Home(chip), now)), more)
    }

    /** Shorts 탭의 목록 (세로 카드 차례) · 받는 상태 */
    fun shorts(now: Long): Pair<List<TubeVideo>, TubeRows.Load> {
        val ctx = context(now)
        val list = TubeHome.shorts(shortsCandidates(ctx, now), ctx)
        return list to load(feedsFor(Page.Shorts, now))
    }

    /** Shorts 후보: 채널의 Shorts → 믹스 추천 속 Shorts → 채우는 검색 (Shorts 탭에서 받았으면) */
    private fun shortsCandidates(ctx: HomeContext, now: Long): List<TubeVideo> =
        shownVideos(POOL, shortsPool(ctx)) + shownVideos(RECS, recs(now)).filter { it.isShort } +
            loader.slot(Feed.ShortsSearch).page?.videos.orEmpty()

    /**
     * Shorts 를 검색으로 채워야 하는지: 채널 Shorts·믹스 추천을 다 받았는데(또는 받을 것이 없는데) Shorts 가 [SHORTS_ENOUGH] 개보다 적고,
     * 오늘 저절로 한 검색이 [SHORTS_SEARCH_DAILY] 번보다 적을 때 (이미 받아 둔 검색은 그대로). 검색은 하루 100번 한도라
     * 3시간 기억한다 ([Feed.ShortsSearch])
     */
    private fun needsShortsSearch(pool: Feed.ShortsPool?, recs: Feed.Recs?, now: Long): Boolean {
        if (signedIn() && subscribedOrder() == null) return false   // 구독 채널을 받는 중 — 기다린다
        val parts = listOfNotNull(pool, recs).map(loader::slot)
        if (parts.any { it.page == null && it.error == null }) return false   // 아직 받는 중
        val have = parts.flatMap { it.page?.videos.orEmpty() }.filter { it.isShort }.distinctBy { it.id }.size
        if (have >= SHORTS_ENOUGH) return false
        val cached = loader.slot(Feed.ShortsSearch).let { it.page != null || it.loading }
        return cached || state.shortsSearchesToday(now) < SHORTS_SEARCH_DAILY
    }

    /** 여러 받을 거리를 합친 상태: 하나라도 받는 중이면 받는 중, 모두 실패면 첫 실패 */
    private fun load(feeds: List<Feed>): TubeRows.Load {
        val slots = feeds.map(loader::slot)
        if (slots.isEmpty()) return TubeRows.Load()
        val loading = slots.any { it.loading || (it.page == null && it.error == null) }
        val failed = slots.filter { it.error != null }
        val error = if (!loading && failed.size == slots.size) failed.first() else null
        return TubeRows.Load(loading = loading, error = error?.error, signIn = error?.signIn == true)
    }

    /**
     * 열쇠가 바뀌는 받을 거리([prefix])의 동영상: [feed] 를 받았으면 그것 (그리고 기억), 받는 중이면 바로 앞에 받은 것.
     * 받을 것이 없어졌으면(null — 추천이 지남·지움) 앞 것도 보여 주지 않는다
     */
    private fun shownVideos(prefix: String, feed: Feed?): List<TubeVideo> {
        if (feed == null) {
            shown.remove(prefix)
            return emptyList()
        }
        val page = loader.slot(feed).page
        if (page != null) {
            shown[prefix] = feed
            return page.videos
        }
        return shown[prefix]?.let { loader.slot(it).page?.videos }.orEmpty()
    }

    /** 열쇠가 바뀐 예전 것을 잊는다 — 새것을 받는 동안엔 바로 앞 것([shown])은 둔다 */
    private fun forgetStale(recs: Feed.Recs?, watched: Feed.Watched?, pool: Feed.ShortsPool?) {
        keepOnly(RECS, recs)
        keepOnly(WATCHED, watched)
        keepOnly(POOL, pool)
        loader.forget(SUBS_SHORTS, subsShorts())
        loader.forget(SUBS_LIVE, subsLive())
    }

    /** [prefix] 의 것 중 [current] 와 (그것을 받는 동안) 바로 앞 것만 남긴다 */
    private fun keepOnly(prefix: String, current: Feed?) {
        if (current == null) shown.remove(prefix)
        val previous = shown[prefix]?.takeIf { it != current && loader.slot(current ?: it).page == null }
        loader.forget(prefix, setOfNotNull(current, previous))
    }

    /** 로그아웃·기록 지우기: 바로 앞 것도 잊는다 (구독 채널로 고른 Shorts·지운 추천이 남지 않게) */
    fun reset() {
        shown.clear()
    }

    companion object {
        /** Shorts 가 이만큼은 있어야 검색으로 채우지 않는다 */
        const val SHORTS_ENOUGH = 10
        /** 저절로 하는 Shorts 검색 — 하루(유튜브 한도 날) 이만큼까지 */
        const val SHORTS_SEARCH_DAILY = 4
        /** 구독 화면의 Shorts·실시간을 받는 구독 채널 수 */
        const val SUBS_CHANNELS = 10
        private const val RECS = "recs/"
        private const val WATCHED = "watched/"
        private const val POOL = "shorts/pool/"
        private const val SUBS_SHORTS = "subs/shorts/"
        private const val SUBS_LIVE = "subs/live/"
    }
}
