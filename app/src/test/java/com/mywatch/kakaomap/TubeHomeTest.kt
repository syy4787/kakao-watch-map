package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.HomeChip
import com.mywatch.kakaomap.tube.HomeContext
import com.mywatch.kakaomap.tube.HomeSources
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.TubeHome
import com.mywatch.kakaomap.tube.TubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈의 칩마다 목록 만들기 — 점수 없이 출처를 번갈아 섞고(믹스 추천 R · 구독 S · 본 채널 W · 인기 T), 본 것·관심 없음·Shorts 는 빼고,
 * 한 채널이 몰리지 않게, Shorts 는 선반으로
 */
class TubeHomeTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    /** 11자 번호: 앞 글자 + 숫자. 채널은 따로 정하지 않으면 동영상마다 다르게 (앞 글자·숫자로) */
    private fun id(p: String, n: Int) = p + n.toString().padStart(11 - p.length, '0')
    private fun ch(n: Int) = "UC" + n.toString().padStart(22, '0')

    private fun v(
        p: String, n: Int, channel: Int = p[0].code * 1000 + n, cat: String? = null, short: Boolean = false, live: Boolean = false,
        ago: Long = day, embeddable: Boolean = true,
    ) = TubeVideo(
        id(p, n), "제목 $p$n", "채널 $channel", durationSec = if (live) null else if (short) 30 else 300, live = live,
        channelId = ch(channel), publishedAt = now - ago, embeddable = embeddable, categoryId = cat, portrait = if (live) null else short,
    )

    private fun ctx(
        history: List<TubeVideo> = emptyList(), dismissed: Set<String> = emptySet(), dismissedChannels: Set<String> = emptySet(),
        subscribed: Set<String> = emptySet(), seen: Set<String> = emptySet(),
    ) = HomeContext(
        history = history.mapIndexed { i, x -> Played(x, now - i * 60_000L) },
        dismissedVideos = dismissed, dismissedChannels = dismissedChannels, subscribed = subscribed, seenShorts = seen, now = now,
    )

    private fun ids(list: List<TubeVideo>) = list.map { it.id }

    // ---------------- 전체 ----------------

    @Test
    fun allTakesSourcesInTurnsAndSkipsEmptyOnes() {
        val s = HomeSources(
            recs = (1..4).map { v("r", it) }, subs = (1..3).map { v("s", it) },
            watched = (1..2).map { v("w", it) }, trending = (1..3).map { v("t", it) },
        )
        val f = TubeHome.feed(HomeChip.ALL, s, ctx())
        // R S R T W R S T 를 되풀이, 다 쓴 출처는 건너뜀
        assertEquals(
            listOf(id("r", 1), id("s", 1), id("r", 2), id("t", 1), id("w", 1), id("r", 3), id("s", 2), id("t", 2),
                id("r", 4), id("s", 3), id("t", 3), id("w", 2)),
            ids(f.videos),
        )
    }

    @Test
    fun aFirstTimeUserGetsTrending() {
        val f = TubeHome.feed(HomeChip.ALL, HomeSources(trending = (1..5).map { v("t", it) }), ctx())
        assertEquals((1..5).map { id("t", it) }, ids(f.videos))
        assertEquals(-1, f.shelfAt)
    }

    @Test
    fun allLeavesOutWatchedDismissedShortsAndRepeats() {
        val seen = v("r", 1)
        val gone = v("r", 2)
        val blockedChannel = v("r", 3, channel = 7)
        val short = v("r", 4, short = true)
        val twice = v("r", 5)
        val s = HomeSources(recs = listOf(seen, gone, blockedChannel, short, twice, v("r", 6)), trending = listOf(twice, v("t", 1)))
        val f = TubeHome.feed(HomeChip.ALL, s, ctx(history = listOf(seen), dismissed = setOf(gone.id), dismissedChannels = setOf(ch(7))))
        assertEquals(listOf(twice.id, id("r", 6), id("t", 1)), ids(f.videos))
        assertTrue("Shorts 는 선반으로", f.shorts.map { it.id }.contains(short.id))
    }

    @Test
    fun oneChannelDoesNotCrowdTheFeed() {
        val sameChannel = (1..6).map { v("r", it, channel = 1) }
        val others = (1..12).map { v("t", it) }
        val f = TubeHome.feed(HomeChip.ALL, HomeSources(recs = sameChannel, trending = others), ctx())
        assertEquals(18, f.videos.size)
        val chans = f.videos.map { it.channelId }
        // 처음 열두 칸(다른 채널이 남아 있는 동안)은 10칸마다 같은 채널 2개까지
        for (start in 0..(12 - TubeHome.SPREAD_WINDOW)) {
            val window = chans.subList(start, start + TubeHome.SPREAD_WINDOW)
            assertTrue("$start: $window", window.count { it == ch(1) } <= TubeHome.SPREAD_PER)
        }
        assertEquals("미룬 것도 빠지지 않는다", sameChannel.map { it.id }.toSet(), f.videos.filter { it.channelId == ch(1) }.map { it.id }.toSet())
    }

    @Test
    fun theShortsShelfSitsAfterTheThirdCardWithUnseenShortsFirst() {
        val shorts = (1..4).map { v("h", it, short = true) }
        val f = TubeHome.feed(
            HomeChip.ALL,
            HomeSources(trending = (1..6).map { v("t", it) }, shorts = shorts),
            ctx(seen = setOf(shorts[0].id)),
        )
        assertEquals(TubeHome.SHELF_AT, f.shelfAt)
        assertEquals(listOf(id("h", 2), id("h", 3), id("h", 4), id("h", 1)), ids(f.shorts))
        // 한 개뿐이면 선반 없음
        val one = TubeHome.feed(HomeChip.ALL, HomeSources(trending = (1..6).map { v("t", it) }, shorts = shorts.take(1)), ctx())
        assertEquals(-1, one.shelfAt)
        // 카드가 셋보다 적으면 끝에
        val few = TubeHome.feed(HomeChip.ALL, HomeSources(trending = listOf(v("t", 1)), shorts = shorts), ctx())
        assertEquals(1, few.shelfAt)
    }

    // ---------------- 칩 ----------------

    @Test
    fun musicMixesPoolMusicWithTheChartAndKeepsWatchedOnes() {
        val heard = v("m", 9, cat = "10")
        val s = HomeSources(
            recs = listOf(v("r", 1, cat = "10"), v("r", 2, cat = "20")),
            subs = listOf(v("s", 1, cat = "10")),
            music = listOf(v("m", 1, cat = "10"), heard, v("m", 2, cat = "10")),
        )
        val f = TubeHome.feed(HomeChip.MUSIC, s, ctx(history = listOf(heard)))
        assertEquals(listOf(id("r", 1), id("m", 1), id("s", 1), heard.id, id("m", 2)), ids(f.videos))
    }

    @Test
    fun gamingNewsAndLiveUseTheirOwnSources() {
        val s = HomeSources(
            recs = listOf(v("r", 1, cat = "20"), v("r", 2, cat = "25"), v("r", 3, live = true)),
            gaming = listOf(v("g", 1, cat = "20")),
            news = listOf(v("n", 1, cat = "25"), v("n", 2, cat = "22"), v("n", 3, cat = "25")),
            live = listOf(v("l", 1, live = true)),
        )
        assertEquals(listOf(id("r", 1), id("g", 1)), ids(TubeHome.feed(HomeChip.GAMING, s, ctx()).videos))
        // 뉴스 차트에 섞여 오는 다른 분류(22)는 뺀다
        assertEquals(listOf(id("r", 2), id("n", 1), id("n", 3)), ids(TubeHome.feed(HomeChip.NEWS, s, ctx()).videos))
        assertEquals(listOf(id("r", 3), id("l", 1)), ids(TubeHome.feed(HomeChip.LIVE, s, ctx()).videos))
    }

    @Test
    fun recentlyUploadedIsTheLastWeekNewestFirst() {
        val s = HomeSources(
            recs = listOf(v("r", 1, ago = 3 * day), v("r", 2, ago = 9 * day)),
            subs = listOf(v("s", 1, ago = 1 * day)),
            trending = listOf(v("t", 1, ago = 2 * day), v("t", 2, ago = 1 * day / 2)),
        )
        val f = TubeHome.feed(HomeChip.RECENT, s, ctx(history = listOf(v("t", 1, ago = 2 * day))))
        assertEquals(listOf(id("t", 2), id("s", 1), id("r", 1)), ids(f.videos))
    }

    @Test
    fun watchedIsTheHistoryAndNewToYouIsChannelsYouHaveNotSeen() {
        val a = v("x", 1, channel = 1)
        val b = v("x", 2, channel = 2)
        assertEquals(listOf(a.id, b.id), ids(TubeHome.feed(HomeChip.WATCHED, HomeSources(), ctx(history = listOf(a, b))).videos))
        val s = HomeSources(
            recs = listOf(v("r", 1, channel = 1), v("r", 2, channel = 3), v("r", 3, channel = 4)),
            trending = listOf(v("t", 1, channel = 5)),
        )
        val f = TubeHome.feed(HomeChip.NEW, s, ctx(history = listOf(a), subscribed = setOf(ch(3))))
        assertEquals(listOf(id("r", 3), id("t", 1)), ids(f.videos))
    }

    @Test
    fun mixSeedsPreferMusicFromHistoryAndTopUpFromTheChart() {
        val history = listOf(v("a", 1), v("a", 2, cat = "10"), v("a", 3, cat = "10", embeddable = false), v("a", 4))
        val chart = (1..9).map { v("m", it, cat = "10") }
        val seeds = TubeHome.mixSeeds(ctx(history = history), chart)
        // 음악 먼저(틀 수 있는 것만), 그다음 다른 기록, 기록이 4개보다 적으면 인기 음악으로 8개까지
        assertEquals(listOf(id("a", 2), id("a", 1), id("a", 4)) + (1..5).map { id("m", it) }, ids(seeds))
        val many = (1..10).map { v("b", it, cat = "10") }
        assertEquals((1..8).map { id("b", it) }, ids(TubeHome.mixSeeds(ctx(history = many), chart)))
    }

    // ---------------- 채널·Shorts 고르기 ----------------

    @Test
    fun watchedChannelsAreRecentNotSubscribedNotDismissed() {
        val history = listOf(v("a", 1, channel = 1), v("a", 2, channel = 2), v("a", 3, channel = 1), v("a", 4, channel = 3),
            v("a", 5, channel = 4), v("a", 6, channel = 5), v("a", 7, channel = 6))
        val got = TubeHome.watchedChannels(ctx(history = history, subscribed = setOf(ch(2)), dismissedChannels = setOf(ch(4))))
        assertEquals(listOf(ch(1), ch(3), ch(5), ch(6)), got)
    }

    @Test
    fun shortsChannelsAreEightSubscriptionsThenWatchedChannels() {
        val subs = (1..10).map { ch(it) }
        val history = listOf(v("a", 1, channel = 3), v("a", 2, channel = 50), v("a", 3, channel = 51))
        assertEquals((1..8).map { ch(it) } + listOf(ch(3), ch(50), ch(51)).filterNot { it in (1..8).map(::ch) },
            TubeHome.shortsChannels(subs, ctx(history = history)))
    }

    @Test
    fun shortsKeepOnlyShortsAlternateChannelsAndPutSeenLast() {
        val list = listOf(
            v("h", 1, channel = 1, short = true), v("h", 2, channel = 1, short = true), v("h", 3, channel = 2, short = true),
            v("h", 4, channel = 3), v("h", 5, channel = 4, short = true), v("h", 6, channel = 9, short = true),
        )
        val got = TubeHome.shorts(list, ctx(seen = setOf(id("h", 3)), dismissedChannels = setOf(ch(9))))
        assertEquals(listOf(id("h", 1), id("h", 5), id("h", 2), id("h", 3)), ids(got))
    }
}
