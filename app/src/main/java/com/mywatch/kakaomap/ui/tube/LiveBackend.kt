package com.mywatch.kakaomap.ui.tube

import android.content.Context
import com.mywatch.kakaomap.tube.TubeApi
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 워치에서 실제로 받는 곳: 유튜브 Data API([com.mywatch.kakaomap.tube.YouTubeData]) — 검색은 [TubeApi.search]
 * (유튜브 → 한도·오류면 카카오 동영상 검색). 내 계정 데이터는 로그인 토큰으로 (없으면 '로그인 필요')
 */
class LiveBackend(private val context: Context) : TubeBackend {

    private val data by lazy { TubeApi.data(context) }

    override suspend fun fetch(feed: Feed, token: String?, source: TubeSource?): Fetched = withContext(Dispatchers.IO) {
        when (feed) {
            is Feed.Popular -> Fetched(data.popular(feed.category, token))
            is Feed.Search -> TubeApi.search(context, feed.query, token, source).let { Fetched(it.page, it.source, it.note) }
            Feed.SubsFeed -> Fetched(TubePage(data.subscriptionFeed(), null))
            Feed.SubsChannels -> Fetched(data.subscriptions(token))
            is Feed.SubsShorts -> Fetched(TubePage(data.shortsOf(feed.channels, SUBS_SHORTS_PER_CHANNEL), null))
            Feed.Liked -> Fetched(data.liked(token))
            Feed.MyPlaylists -> Fetched(data.myPlaylists(token))
            Feed.Account -> Fetched(TubePage(emptyList(), null, channels = listOfNotNull(data.myChannel())))
            is Feed.ChannelInfo -> Fetched(TubePage(emptyList(), null, channels = listOfNotNull(data.channel(feed.id))))
            is Feed.ChannelUploads -> Fetched(data.uploads(feed.id, feed.kind, token))
            is Feed.ChannelPlaylists -> Fetched(data.channelPlaylists(feed.id, token))
            is Feed.PlaylistVideos -> Fetched(data.videosOf(feed.id, token, mine = feed.own))
            // 받아 둔 순서 그대로 (정보에 없는 것 — 지워짐·비공개 — 은 뺀다)
            is Feed.Recs -> Fetched(TubePage(data.details(feed.ids).let { d -> feed.ids.mapNotNull { d[it] } }, null))
            is Feed.Watched -> Fetched(TubePage(data.uploadsFeed(feed.channels, Uploads.LONG, WATCHED_PER_CHANNEL, WATCHED_MAX), null))
            is Feed.ShortsPool -> Fetched(TubePage(data.shortsOf(feed.channels, SHORTS_PER_CHANNEL), null))
            Feed.ShortsSearch -> Fetched(data.searchVideos(mapOf("videoDuration" to "short", "q" to "#shorts"), token))
            Feed.Live -> Fetched(data.searchVideos(mapOf("eventType" to "live"), token))
            is Feed.VideoChannel -> Fetched(
                TubePage(emptyList(), null, channels = listOfNotNull(data.channelOfVideo(feed.video)?.let { (id, title) -> TubeChannel(id, title, null) })),
            )
        }
    }

    companion object {
        /** '내가 본 채널'마다 받는 새 긴 동영상 수와 모두의 최대 */
        const val WATCHED_PER_CHANNEL = 3
        const val WATCHED_MAX = 12
        /** Shorts 후보: 채널마다 최신 Shorts 수 */
        const val SHORTS_PER_CHANNEL = 4
        /** 구독 화면의 Shorts: 채널마다 */
        const val SUBS_SHORTS_PER_CHANNEL = 3
    }
}
