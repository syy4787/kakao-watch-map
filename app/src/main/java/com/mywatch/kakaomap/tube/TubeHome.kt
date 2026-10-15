package com.mywatch.kakaomap.tube

/** 홈의 칩 (유튜브 앱의 홈 위 칩처럼) */
enum class HomeChip { ALL, MIXES, MUSIC, GAMING, LIVE, NEWS, RECENT, WATCHED, NEW }

/**
 * 홈에 섞을 것들 — 저마다 받은 순서 그대로: [recs] 유튜브 믹스 추천([MixHarvests.order] 차례) · [subs] 구독 새 동영상 ·
 * [watched] 이 앱에서 본 채널(구독 안 한)의 새 동영상 · [trending] 인기(한국) · [music]·[gaming]·[news] 그 분류의 인기 차트 ·
 * [live] 실시간 검색 · [shorts] Shorts 후보(이미 고른 차례)
 */
data class HomeSources(
    val recs: List<TubeVideo> = emptyList(),
    val subs: List<TubeVideo> = emptyList(),
    val watched: List<TubeVideo> = emptyList(),
    val trending: List<TubeVideo> = emptyList(),
    val music: List<TubeVideo> = emptyList(),
    val gaming: List<TubeVideo> = emptyList(),
    val news: List<TubeVideo> = emptyList(),
    val live: List<TubeVideo> = emptyList(),
    val shorts: List<TubeVideo> = emptyList(),
)

/**
 * 워치 안의 것: 이 앱에서 본 기록(새것부터) · '관심 없음' 동영상 · '채널 추천 안 함' 채널 · 구독 채널(알면) · 본 Shorts · 지금
 */
data class HomeContext(
    val history: List<Played> = emptyList(),
    val dismissedVideos: Set<String> = emptySet(),
    val dismissedChannels: Set<String> = emptySet(),
    val subscribed: Set<String> = emptySet(),
    val seenShorts: Set<String> = emptySet(),
    val now: Long,
)

/** 칩 하나의 목록: [videos] 카드들 · [shorts] Shorts 선반 · [shelfAt] 선반을 넣을 카드 자리 (-1 = 선반 없음) */
data class HomeFeed(val videos: List<TubeVideo>, val shorts: List<TubeVideo> = emptyList(), val shelfAt: Int = -1)

/**
 * 홈의 칩마다 목록 만들기 (순수 계산 — 단위 테스트로 검증).
 * 점수를 매기지 않는다 (유튜브 API 정책 III.E.4.h: 받은 데이터로 새 지표를 만들지 않음): 출처를 정한 차례대로 번갈아 쓰고,
 * 이 앱에서 본 것·'관심 없음'·Shorts(→ 선반)를 빼고, 한 채널이 몰리면 뒤로 미룰 뿐이다
 */
object TubeHome {
    /** Shorts 선반을 넣는 카드 자리 (넷째 카드 앞) */
    const val SHELF_AT = 3
    /** 선반의 최대 Shorts 수 */
    const val SHELF_MAX = 10
    /** 이보다 적으면 선반을 만들지 않는다 */
    const val SHELF_MIN = 2
    /** 연속한 이만큼의 카드 안에서 한 채널은 [SPREAD_PER] 개까지 */
    const val SPREAD_WINDOW = 10
    const val SPREAD_PER = 2
    /** '최근 업로드' = 이만큼 안에 올라온 것 */
    const val RECENT_MS = 7L * 24 * 3_600_000
    private const val RECENT_MAX = 40
    /** '믹스' 칩의 카드 수, 기록에서 이만큼도 안 나오면 인기 음악으로 채운다 */
    const val MIX_SEEDS = 8
    private const val MIX_MIN_FROM_HISTORY = 4
    /** '전체'에 새 동영상을 섞는 '내가 본 채널' 수 */
    const val WATCHED_CHANNELS = 4
    /** Shorts 를 받는 구독 채널 수 (관련도순 앞에서) */
    const val SHORTS_SUBSCRIBED = 8

    const val MUSIC = "10"
    const val GAMING = "20"
    const val NEWS = "25"

    private enum class Src { R, S, W, T }

    /** '전체'의 차례: 믹스 추천 · 구독 · 믹스 추천 · 인기 · 본 채널 · 믹스 추천 · 구독 · 인기 를 되풀이 */
    private val PATTERN = listOf(Src.R, Src.S, Src.R, Src.T, Src.W, Src.R, Src.S, Src.T)

    fun feed(chip: HomeChip, s: HomeSources, ctx: HomeContext): HomeFeed = when (chip) {
        HomeChip.ALL -> all(s, ctx)
        HomeChip.MIXES -> HomeFeed(mixSeeds(ctx, s.music))
        HomeChip.MUSIC -> category(s, ctx, MUSIC, s.music)
        HomeChip.GAMING -> category(s, ctx, GAMING, s.gaming)
        HomeChip.NEWS -> category(s, ctx, NEWS, s.news)
        HomeChip.LIVE -> HomeFeed(spread(distinct(pool(s, ctx).filter { it.live } + s.live.filter { ok(it, ctx) && it.live })))
        HomeChip.RECENT -> HomeFeed(recent(s, ctx))
        HomeChip.WATCHED -> HomeFeed(ctx.history.map { it.video }.filter { notDismissed(it, ctx) }.distinctBy { it.id })
        HomeChip.NEW -> HomeFeed(spread(newToYou(s, ctx)))
    }

    /** '전체': 출처를 차례로 번갈아 + 한 채널이 몰리지 않게 + 넷째 자리에 Shorts 선반 */
    private fun all(s: HomeSources, ctx: HomeContext): HomeFeed {
        val watched = watchedIds(ctx)
        val videos = spread(interleave(s) { ok(it, ctx) && it.id !in watched })
        val shorts = shorts(s.shorts + listOf(s.recs, s.subs, s.watched, s.trending).flatten().filter { it.isShort }, ctx).take(SHELF_MAX)
        return HomeFeed(videos, shorts, shelf(videos, shorts))
    }

    /** 음악·게임·뉴스: 받아 둔 것 중 그 분류(전체 차례대로) 와 그 분류 인기 차트를 하나씩 번갈아 (본 것도 둔다) + 그 분류 Shorts 선반 */
    private fun category(s: HomeSources, ctx: HomeContext, cat: String, chart: List<TubeVideo>): HomeFeed {
        val mine = pool(s, ctx).filter { it.categoryId == cat }
        val top = chart.filter { ok(it, ctx) && (it.categoryId == null || it.categoryId == cat) }
        val out = LinkedHashMap<String, TubeVideo>()
        for (i in 0 until maxOf(mine.size, top.size)) {
            mine.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
            top.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
        }
        val videos = spread(out.values.toList())
        val shorts = shorts((s.shorts + listOf(s.recs, s.subs, s.watched, chart).flatten()).filter { it.categoryId == cat }, ctx).take(SHELF_MAX)
        return HomeFeed(videos, shorts, shelf(videos, shorts))
    }

    /** 받아 둔 모든 동영상(Shorts·관심 없음 빼고, 본 것은 둔다) — '전체'의 차례대로 */
    private fun pool(s: HomeSources, ctx: HomeContext): List<TubeVideo> = interleave(s) { ok(it, ctx) }

    /** '최근 업로드': 받아 둔 것(차트·실시간까지) 중 [RECENT_MS] 안에 올라온 것, 새것부터. 본 것은 뺀다 */
    private fun recent(s: HomeSources, ctx: HomeContext): List<TubeVideo> {
        val watched = watchedIds(ctx)
        val since = ctx.now - RECENT_MS
        return distinct(listOf(s.recs, s.subs, s.watched, s.trending, s.music, s.gaming, s.news, s.live).flatten())
            .filter { ok(it, ctx) && it.id !in watched && (it.publishedAt ?: Long.MIN_VALUE) >= since }
            .sortedByDescending { it.publishedAt }
            .take(RECENT_MAX)
    }

    /** '새로운 맞춤 동영상': 믹스 추천 → 인기 중 본 적 없고 구독도 안 한 채널의 것 */
    private fun newToYou(s: HomeSources, ctx: HomeContext): List<TubeVideo> {
        val watched = watchedIds(ctx)
        val known = ctx.history.mapNotNull { it.video.channelId }.toSet() + ctx.subscribed
        return distinct(s.recs + s.trending).filter { v ->
            ok(v, ctx) && v.id !in watched && v.channelId != null && v.channelId !in known
        }
    }

    /**
     * '믹스' 칩의 씨앗: 이 앱에서 본 것(새것부터, 틀 수 있는 것 — 생방송·Shorts 빼고) 중 음악 먼저 [MIX_SEEDS] 개.
     * 기록에서 [MIX_MIN_FROM_HISTORY] 개도 안 나오면 [music](인기 음악)으로 채운다
     */
    fun mixSeeds(ctx: HomeContext, music: List<TubeVideo>): List<TubeVideo> {
        val heard = ctx.history.map { it.video }.filter { it.embeddable && !it.live && !it.isShort && notDismissed(it, ctx) }.distinctBy { it.id }
        val (songs, rest) = heard.partition { it.categoryId == MUSIC }
        val seeds = (songs + rest).take(MIX_SEEDS).toMutableList()
        if (seeds.size < MIX_MIN_FROM_HISTORY) {
            music.filter { it.embeddable && !it.live && !it.isShort && notDismissed(it, ctx) && seeds.none { s -> s.id == it.id } }
                .take(MIX_SEEDS - seeds.size).forEach { seeds += it }
        }
        return seeds
    }

    /** '믹스' 칩: 기록에서 나오는 씨앗([mixSeeds] 와 같은 규칙)이 모자라 인기 음악으로 채워야 하는지 */
    fun needsMusicTopUp(ctx: HomeContext): Boolean = mixSeeds(ctx, emptyList()).size < MIX_MIN_FROM_HISTORY

    /** '전체'에 새 동영상을 섞을 채널: 이 앱에서 본 채널(최근 것부터) 중 구독·'채널 추천 안 함' 아닌 것 [max] 개 */
    fun watchedChannels(ctx: HomeContext, max: Int = WATCHED_CHANNELS): List<String> =
        ctx.history.mapNotNull { it.video.channelId }.distinct()
            .filter { it !in ctx.subscribed && it !in ctx.dismissedChannels }
            .take(max)

    /** Shorts 를 받을 채널: 구독 채널 앞 [SHORTS_SUBSCRIBED] 개 + 최근 본 채널 [WATCHED_CHANNELS] 개 (같은 채널은 한 번) */
    fun shortsChannels(subscribedInOrder: List<String>, ctx: HomeContext): List<String> {
        val subs = subscribedInOrder.filter { it !in ctx.dismissedChannels }.distinct().take(SHORTS_SUBSCRIBED)
        val seen = ctx.history.mapNotNull { it.video.channelId }.distinct()
            .filter { it !in subs && it !in ctx.dismissedChannels }.take(WATCHED_CHANNELS)
        return subs + seen
    }

    /**
     * Shorts 고르기: Shorts 모양인 것만 · 관심 없음 빼고 · 같은 것은 한 번 · 같은 채널이 이어지지 않게 · 본 Shorts 는 뒤로
     */
    fun shorts(candidates: List<TubeVideo>, ctx: HomeContext): List<TubeVideo> {
        val all = distinct(candidates.filter { it.isShort && notDismissed(it, ctx) })
        val (seen, fresh) = all.partition { it.id in ctx.seenShorts }
        return alternate(fresh) + alternate(seen)
    }

    // ---------------- 도우미 ----------------

    /** 출처를 [PATTERN] 차례로 번갈아 — 다 쓴 출처는 건너뛰고, [keep] 인 것만, 같은 것은 한 번 */
    private fun interleave(s: HomeSources, keep: (TubeVideo) -> Boolean): List<TubeVideo> {
        val lanes = mapOf(
            Src.R to s.recs.filter(keep), Src.S to s.subs.filter(keep),
            Src.W to s.watched.filter(keep), Src.T to s.trending.filter(keep),
        )
        val at = HashMap<Src, Int>()
        val out = LinkedHashMap<String, TubeVideo>()
        while (lanes.any { (src, lane) -> (at[src] ?: 0) < lane.size }) {
            for (src in PATTERN) {
                val lane = lanes.getValue(src)
                var i = at[src] ?: 0
                // 이미 나온 것은 건너뛰고 그 출처의 다음 것
                while (i < lane.size && out.containsKey(lane[i].id)) i++
                if (i < lane.size) {
                    out[lane[i].id] = lane[i]
                    i++
                }
                at[src] = i
            }
        }
        return out.values.toList()
    }

    /**
     * 한 채널이 몰리지 않게: 앞의 ([SPREAD_WINDOW]-1)개 안에 같은 채널이 [SPREAD_PER] 개 있으면 뒤로 미룬다
     * (놓을 수 있게 되면 바로 놓고, 끝까지 못 놓으면 맨 뒤에 차례대로). 채널을 모르면 미루지 않는다
     */
    fun spread(list: List<TubeVideo>): List<TubeVideo> {
        val out = ArrayList<TubeVideo>(list.size)
        val waiting = ArrayList<TubeVideo>()
        fun fits(v: TubeVideo): Boolean {
            val c = v.channelId ?: return true
            val from = (out.size - (SPREAD_WINDOW - 1)).coerceAtLeast(0)
            return (from until out.size).count { out[it].channelId == c } < SPREAD_PER
        }
        fun flush() {
            var placed = true
            while (placed) {
                placed = false
                val i = waiting.indexOfFirst(::fits)
                if (i >= 0) {
                    out += waiting.removeAt(i)
                    placed = true
                }
            }
        }
        for (v in list) {
            if (fits(v)) {
                out += v
                flush()
            } else {
                waiting += v
            }
        }
        flush()
        out += waiting
        return out
    }

    /** 같은 채널이 이어지지 않게 (되도록): 다음 것이 앞과 같은 채널이면 뒤에서 다른 채널을 먼저 */
    private fun alternate(list: List<TubeVideo>): List<TubeVideo> {
        val rest = list.toMutableList()
        val out = ArrayList<TubeVideo>(list.size)
        while (rest.isNotEmpty()) {
            val last = out.lastOrNull()?.channelId
            val i = rest.indexOfFirst { last == null || it.channelId == null || it.channelId != last }.takeIf { it >= 0 } ?: 0
            out += rest.removeAt(i)
        }
        return out
    }

    private fun shelf(videos: List<TubeVideo>, shorts: List<TubeVideo>): Int =
        if (shorts.size >= SHELF_MIN) minOf(SHELF_AT, videos.size) else -1

    /** 카드로 보여 줄 수 있는 것: 관심 없음·채널 추천 안 함이 아니고, Shorts 가 아닌 것 */
    private fun ok(v: TubeVideo, ctx: HomeContext) = notDismissed(v, ctx) && !v.isShort

    private fun notDismissed(v: TubeVideo, ctx: HomeContext) = v.id !in ctx.dismissedVideos && v.channelId !in ctx.dismissedChannels

    private fun watchedIds(ctx: HomeContext): Set<String> = ctx.history.mapTo(HashSet()) { it.video.id }

    private fun distinct(list: List<TubeVideo>) = list.distinctBy { it.id }
}
