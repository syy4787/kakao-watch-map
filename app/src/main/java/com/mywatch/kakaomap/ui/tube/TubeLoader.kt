package com.mywatch.kakaomap.ui.tube

import com.mywatch.kakaomap.tube.SignInNeededException
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.Uploads
import com.mywatch.kakaomap.tube.YouTubeQuotaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 받을 거리 — [key] 로 기억한다. [mine] = 내 계정 데이터 (로그아웃하면 지움), [ttlMs] = 이만큼은 다시 받지 않는다 */
sealed class Feed(val key: String, val mine: Boolean, val ttlMs: Long) {
    /** 인기 차트 (한국). [category] "10" 음악 · "20" 게임 · "25" 뉴스 · null 전체 */
    class Popular(val category: String?) : Feed("popular/${category ?: "all"}", false, LONG)
    /** 검색은 하루 100번 한도(1번 = 100 단위)라 3시간 기억한다 (같은 검색어를 다시 해도) */
    class Search(val query: String) : Feed("search/$query", false, EXTENDED)
    data object SubsFeed : Feed("subs/feed", true, SHORT)
    data object SubsChannels : Feed("subs/channels", true, SHORT)
    /** 구독 채널([channels])의 Shorts */
    class SubsShorts(val channels: List<String>) : Feed("subs/shorts/" + channels.joinToString(","), true, LONG)
    data object Liked : Feed("liked", true, SHORT)
    data object MyPlaylists : Feed("myplaylists", true, SHORT)
    data object Account : Feed("account", true, LONG)
    class ChannelInfo(val id: String) : Feed("channel/$id", false, LONG)
    /** 채널의 업로드 — 종류별 ([kind] 긴 동영상 · Shorts · 생방송) */
    class ChannelUploads(val id: String, val kind: Uploads) : Feed("channel/$id/" + kind.name.lowercase(), false, SHORT)
    class ChannelPlaylists(val id: String) : Feed("channel/$id/playlists", false, LONG)
    class PlaylistVideos(val id: String, val own: Boolean) : Feed("playlist/$id", own, SHORT)
    /** 받아 둔 유튜브 믹스 추천 [ids] 의 정보 — 추천이 새로 오면 열쇠가 바뀐다 (예전 것은 [TubeLoader.forget]) */
    class Recs(val ids: List<String>) : Feed("recs/" + ids.joinToString(",").hashCode().toUInt().toString(16), false, LONG)
    /** 이 앱에서 본 채널(구독 안 한) [channels] 의 새 긴 동영상 */
    class Watched(val channels: List<String>) : Feed("watched/" + channels.joinToString(","), false, LONG)
    /** Shorts 후보: 채널 [channels](구독 앞 8 + 최근 본 4)의 최신 Shorts */
    class ShortsPool(val channels: List<String>) : Feed("shorts/pool/" + channels.joinToString(","), false, LONG)
    /** Shorts 가 모자랄 때 채우는 검색 (검색 한도라 3시간) */
    data object ShortsSearch : Feed("shorts/search", false, EXTENDED)
    /** 지금 생방송 검색 (검색 한도라 3시간 — '실시간' 칩을 열 때만) */
    data object Live : Feed("live", false, EXTENDED)
    /** 동영상 [video] 를 올린 채널 (재생 화면의 채널 이름을 눌렀을 때) */
    class VideoChannel(val video: String) : Feed("vchannel/$video", false, LONG)

    override fun equals(other: Any?) = other is Feed && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = key

    companion object {
        /** 받은 데이터는 메모리에만 잠깐 (유튜브 정책: 받은 것을 오래 두지 않기) */
        const val SHORT = 15 * 60_000L
        const val LONG = 30 * 60_000L
        /** 검색 결과와 더 보기로 늘린 목록 — 뒤로 올 때마다 첫 쪽으로 줄이지 않게 (새로 고침은 바로) */
        const val EXTENDED = 3 * 60 * 60_000L
    }
}

/** 받은 한 쪽 (+ 검색이면 어디서 찾았는지·바꿔 찾은 이유) */
data class Fetched(val page: TubePage, val source: TubeSource? = null, val note: String? = null)

/** 데이터를 받는 곳 (워치에선 유튜브 Data API + 카카오 검색, 시험에선 가짜) */
interface TubeBackend {
    /** [feed] 의 한 쪽. [token] = 더 보기면 다음 쪽 값, [source] = 검색 '더 보기'는 처음 찾은 곳에서 */
    suspend fun fetch(feed: Feed, token: String?, source: TubeSource?): Fetched
}

/**
 * 화면 하나가 보여 줄 데이터의 상태. [page] 가 있으면 보여 준다 (새로 고치는 동안에도 그대로),
 * 없으면 [loading] 이면 회색 자리, [error] 면 이유 + 다시 시도 ([signIn] 이면 로그인 안내)
 */
data class Slot(
    val page: TubePage? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val signIn: Boolean = false,
    val source: TubeSource? = null,
    val note: String? = null,
    val loadedAt: Long = 0,
    /** 더 보기로 늘린 목록 (저절로 새로 받으면 첫 쪽으로 줄어드므로 [Feed.EXTENDED] 동안 그대로) */
    val extended: Boolean = false,
)

/**
 * 화면들의 데이터를 받아 메모리에 잠깐 기억한다 (메인 스레드에서 쓴다). 받으면 [changed] 로 알린다.
 *  · 없거나 오래됐을 때만 받는다 (15~30분, 검색·더 보기로 늘린 목록은 3시간), 받는 중엔 또 묻지 않는다
 *  · 더 보기는 아래에 붙인다 (같은 것은 한 번). 새로 고치는 중에 누르면 끝나고 바로. 실패하면 보던 것은 그대로, 알림은 [takeMoreError] 로 한 번
 *  · 로그아웃하면 내 계정 데이터는 지운다 ([clearMine]) — 받는 중이던 것도 버린다
 */
class TubeLoader(
    private val scope: CoroutineScope,
    private val backend: TubeBackend,
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: (Feed) -> Unit,
) {
    private val slots = HashMap<String, Slot>()
    private val jobs = HashMap<String, Job>()
    private val moreErrors = HashMap<String, String>()
    /** 새로 고치는 중에 누른 '더 보기' — 끝나면 바로 */
    private val pendingMore = HashSet<String>()

    fun slot(feed: Feed): Slot = slots[feed.key] ?: Slot()

    /** 화면을 열 때: 없거나 오래됐거나 실패했으면 받는다. [force] = 새로 고침·다시 시도 */
    fun load(feed: Feed, force: Boolean = false) {
        if (feed.mine) mineKeys += feed.key
        val s = slot(feed)
        val running = jobs[feed.key]?.isActive == true
        if (running && !force) return
        val ttl = if (s.extended) maxOf(feed.ttlMs, Feed.EXTENDED) else feed.ttlMs
        if (!force && s.page != null && s.error == null && now() - s.loadedAt < ttl) return
        jobs[feed.key]?.cancel()
        slots[feed.key] = s.copy(loading = true, loadingMore = feed.key in pendingMore, error = null, signIn = false)
        changed(feed)
        jobs[feed.key] = scope.launch {
            val next = try {
                val r = backend.fetch(feed, null, null)
                Slot(page = r.page, source = r.source, note = r.note, loadedAt = now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInNeededException) {
                pendingMore.remove(feed.key)
                slot(feed).copy(loading = false, loadingMore = false, error = e.message, signIn = true, page = null)
            } catch (e: Exception) {
                pendingMore.remove(feed.key)
                slot(feed).copy(loading = false, loadingMore = false, error = e.message ?: "불러오지 못했어요")
            }
            slots[feed.key] = next
            val queued = pendingMore.remove(feed.key)
            changed(feed)
            if (queued && next.page != null) {
                // 이 받기는 끝났다 — 기다리던 '더 보기'를 이어서
                if (jobs[feed.key] === coroutineContext[Job]) jobs.remove(feed.key)
                more(feed)
            }
        }
    }

    /** 더 보기: 다음 쪽을 아래에 붙인다 (새로 고치는 중이면 끝나고 바로) */
    fun more(feed: Feed) {
        val s = slot(feed)
        val token = s.page?.next ?: return
        if (s.loadingMore && feed.key !in pendingMore) return
        if (jobs[feed.key]?.isActive == true) {
            if (s.loading && pendingMore.add(feed.key)) {
                slots[feed.key] = s.copy(loadingMore = true)
                changed(feed)
            }
            return
        }
        slots[feed.key] = s.copy(loadingMore = true)
        changed(feed)
        jobs[feed.key] = scope.launch {
            val next = try {
                val r = backend.fetch(feed, token, s.source)
                val cur = slot(feed)
                cur.copy(page = cur.page?.let { TubePage.append(it, r.page) } ?: r.page, loadingMore = false, extended = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeQuotaException) {
                moreErrors[feed.key] = e.message.orEmpty()
                slot(feed).let { it.copy(loadingMore = false, page = it.page?.copy(next = null)) }
            } catch (e: Exception) {
                moreErrors[feed.key] = e.message ?: "더 불러오지 못했어요"
                slot(feed).copy(loadingMore = false)
            }
            slots[feed.key] = next
            changed(feed)
        }
    }

    /** 받는 중인 것(새로 고침·더 보기, 이어지는 더 보기까지)이 끝날 때까지 기다린다 ('모두 재생'이 같은 쪽을 또 받지 않게) */
    suspend fun settled(feed: Feed) {
        while (true) {
            val job = jobs[feed.key]?.takeIf { it.isActive } ?: return
            job.join()
        }
    }

    /** 다른 곳에서 더 받은 목록으로 바꾼다 ('모두 재생'이 비공개 재생목록을 더 받았을 때 — 목록에도 보이게) */
    fun offer(feed: Feed, page: TubePage) {
        val s = slot(feed)
        if (s.page == null || s.loading || s.loadingMore) return
        slots[feed.key] = s.copy(page = page, extended = s.extended || page.parts.size > 1)
        changed(feed)
    }

    /** 더 보기 실패 알림 (한 번만) */
    fun takeMoreError(feed: Feed): String? = moreErrors.remove(feed.key)

    /**
     * 열쇠가 [prefix] 로 시작하는 받을 거리를 [keep] 말고 모두 잊는다 (받는 중이던 것도 버림) —
     * 믹스 추천·본 채널처럼 내용에 따라 열쇠가 바뀌는 것의 예전 것 (메모리에 쌓이지 않게)
     */
    fun forget(prefix: String, keep: Feed?) {
        (slots.keys + jobs.keys).filter { it.startsWith(prefix) && it != keep?.key }.toSet().forEach(::drop)
    }

    private fun drop(key: String) {
        jobs.remove(key)?.cancel()
        slots.remove(key)
        moreErrors.remove(key)
        pendingMore.remove(key)
    }

    /** 로그아웃: 내 계정 데이터를 지운다 (받는 중이던 것도 버림) */
    fun clearMine() {
        (slots.keys + jobs.keys).filter(::isMine).toSet().forEach(::drop)
    }

    /** 내 계정 데이터의 키 (받을 때 기억해 둔다 — 로그아웃하면 이것들을 지움) */
    private val mineKeys = HashSet<String>()

    private fun isMine(key: String) = key in mineKeys

}
