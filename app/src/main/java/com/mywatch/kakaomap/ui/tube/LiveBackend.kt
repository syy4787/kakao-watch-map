package com.mywatch.kakaomap.ui.tube

import android.content.Context
import com.mywatch.kakaomap.tube.TubeApi
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubeSource
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
            Feed.Liked -> Fetched(data.liked(token))
            Feed.MyPlaylists -> Fetched(data.myPlaylists(token))
            Feed.Account -> Fetched(TubePage(emptyList(), null, channels = listOfNotNull(data.myChannel())))
            is Feed.ChannelInfo -> Fetched(TubePage(emptyList(), null, channels = listOfNotNull(data.channel(feed.id))))
            is Feed.ChannelVideos -> {
                val uploads = TubeLinks.uploadsOf(feed.id)
                Fetched(if (uploads == null) TubePage(emptyList(), null) else data.videosOf(uploads, token))
            }
            is Feed.ChannelPlaylists -> Fetched(data.channelPlaylists(feed.id, token))
            is Feed.PlaylistVideos -> Fetched(data.videosOf(feed.id, token, mine = feed.own))
        }
    }
}
