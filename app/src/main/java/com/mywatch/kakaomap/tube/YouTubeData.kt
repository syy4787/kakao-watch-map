package com.mywatch.kakaomap.tube

import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** 로그인이 필요함 — 안 했거나, 풀렸거나(계정에서 앱 접근을 끊음), '유튜브 계정 보기' 권한을 안 줌 */
class SignInNeededException(message: String = "로그인이 필요해요") : ApiException(message)

/** 유튜브가 거절한 요청. [code]·[reason] 은 판단용(404 = 없음 등), 글은 보여 줄 말 (주소·키는 넣지 않는다) */
class YouTubeHttpException(val code: Int, val reason: String?, message: String) : ApiException(message)

/**
 * 유튜브 Data API v3 (공식 API 만). 네트워크([get])와 로그인 토큰([token])은 밖에서 넣어 준다 — 단위 테스트로 검증.
 *  · 누구나 보는 것(인기·검색·채널·재생목록)은 API 키로, 내 것(구독·좋아요·내 재생목록·내 채널)은 로그인 토큰으로.
 *    키가 없으면 로그인 토큰으로 대신 묻는다
 *  · 토큰이 거절되면(401) [onUnauthorized] 로 새로 받게 하고 한 번만 다시 묻는다. 또 거절되면 [SignInNeededException]
 *  · 받는 칸은 필요한 것만 (fields) — 워치는 폰 인터넷을 블루투스로 빌려 쓴다
 *  · 한도: 검색 하루 100번, 나머지 하루 1만 단위(대부분 1) — 2026-06 부터 따로 센다
 * 오류 글에는 주소·키·토큰을 넣지 않고, 원인 예외도 달지 않는다 (오류 기록에 키가 남지 않게).
 * 네트워크를 쓰므로 백그라운드 스레드에서 부른다
 */
class YouTubeData(
    private val key: String,
    private val token: () -> String?,
    private val get: (url: String, headers: Map<String, String>) -> Http.Response,
    private val onUnauthorized: () -> Unit = {},
) {

    /** 인기 차트 (한국). [categoryId] "10" = 음악. 2025-07 부터 유튜브 음악·영화·게임 인기 차트에서 고른다 */
    fun popular(categoryId: String?, pageToken: String?): TubePage {
        val p = linkedMapOf(
            "part" to VIDEO_PARTS, "chart" to "mostPopular", "regionCode" to REGION,
            "maxResults" to "$PAGE", "maxHeight" to "$MAX_HEIGHT", "fields" to VIDEO_FIELDS,
        )
        categoryId?.let { p["videoCategoryId"] = it }
        pageToken?.let { p["pageToken"] = it }
        return TubeParsers.videoPage(call("/videos", p))
    }

    /**
     * 검색: 동영상·채널·재생목록. 동영상은 정보(길이·조회수·틀 수 있는지)를 한 번 더 받아 채운다 —
     * 정보에 없는 영상(지워짐·비공개)은 뺀다. 정보를 못 받으면 검색 결과만이라도 보여 준다
     */
    fun search(query: String, pageToken: String?): TubePage {
        val p = linkedMapOf(
            "part" to "snippet", "type" to "video,channel,playlist", "maxResults" to "$PAGE", "q" to query,
            "regionCode" to REGION, "relevanceLanguage" to "ko", "fields" to SEARCH_FIELDS,
        )
        pageToken?.let { p["pageToken"] = it }
        val page = TubeParsers.youtubeSearchAll(call("/search", p, search = true))
        if (page.videos.isEmpty()) return page
        val details = try {
            details(page.videos.map { it.id })
        } catch (e: ApiException) {
            return page
        }
        return page.copy(videos = page.videos.mapNotNull { details[it.id] })
    }

    /** 동영상 정보 (50개씩 나눠 묻는다). 이상한 번호·같은 번호는 한 번만 */
    fun details(ids: List<String>): Map<String, TubeVideo> {
        val out = LinkedHashMap<String, TubeVideo>()
        ids.filter(TubeLinks::isId).distinct().chunked(MAX_IDS).forEach { chunk ->
            out.putAll(TubeParsers.videoDetails(call("/videos", linkedMapOf(
                "part" to VIDEO_PARTS, "id" to chunk.joinToString(","), "maxHeight" to "$MAX_HEIGHT", "fields" to VIDEO_FIELDS,
            ))))
        }
        return out
    }

    /**
     * 조건 검색 — 동영상만 (검색 한도): [extra] = 더할 조건 (실시간 eventType=live · Shorts videoDuration=short&q=#shorts 등).
     * 기본 조건(동영상·한국·한국어·받는 칸)은 [extra] 로 바꾸지 않는다. 정보를 한 번 더 받아 길이·조회수·모양을 채우고
     * 검색 순서 그대로 (정보에 없는 것은 뺀다). 정보를 못 받으면 검색 결과만이라도
     */
    fun searchVideos(extra: Map<String, String>, pageToken: String?): TubePage {
        val p = linkedMapOf(
            "part" to "snippet", "type" to "video", "maxResults" to "$PAGE",
            "regionCode" to REGION, "relevanceLanguage" to "ko", "fields" to SEARCH_FIELDS,
        )
        extra.forEach { (k, v) -> p.putIfAbsent(k, v) }
        pageToken?.let { p["pageToken"] = it }
        val found = TubeParsers.youtubeSearchAll(call("/search", p, search = true)).let { TubePage(it.videos, it.next) }
        if (found.videos.isEmpty()) return found
        val details = try {
            details(found.videos.map { it.id })
        } catch (e: ApiException) {
            return found
        }
        return found.copy(videos = found.videos.mapNotNull { details[it.id] })
    }

    /** 동영상을 올린 채널 (번호, 이름) — 재생 화면에서 채널 이름을 누르면 (1단위). 모르거나 이상한 번호면 null */
    fun channelOfVideo(videoId: String): Pair<String, String>? {
        if (!TubeLinks.isId(videoId)) return null
        val v = details(listOf(videoId))[videoId] ?: return null
        return v.channelId?.let { it to v.channel.ifBlank { "채널" } }
    }

    /** 채널 정보 (없거나 이상한 번호면 null) */
    fun channel(id: String): TubeChannel? {
        if (!TubeLinks.isChannelId(id)) return null
        return TubeParsers.channels(call("/channels", linkedMapOf("part" to CHANNEL_PARTS, "id" to id, "fields" to CHANNEL_FIELDS)))
            .firstOrNull()
    }

    /** 로그인한 계정의 유튜브 채널 (채널을 안 만든 계정이면 null) */
    fun myChannel(): TubeChannel? = TubeParsers.channels(
        call("/channels", linkedMapOf("part" to CHANNEL_PARTS, "mine" to "true", "fields" to CHANNEL_FIELDS), mine = true),
    ).firstOrNull()

    /**
     * 재생목록 속 동영상 (담긴 순서대로 · 비공개·삭제·지워진 영상 빼고). 채널 업로드(UU…)도 이것으로.
     * [mine] = 내 재생목록(비공개일 수 있음)이라 로그인으로 묻는다. 없는 목록(404 — 영상이 하나도 없는 채널 등)은 빈 목록
     */
    fun videosOf(playlistId: String, pageToken: String?, mine: Boolean = false): TubePage =
        playlistVideos(playlistId, pageToken, mine) ?: TubePage(emptyList(), null)

    /**
     * [videosOf] 와 같되 없는 목록(404)이면 null — 대신할 목록으로 바꾸려고. [keep] = 이것만 남긴다.
     * 한 쪽에 남는 것이 없으면(모두 비공개·삭제됐거나 걸러짐) 볼 것이 나올 때까지 세 쪽까지 (예전엔 첫 쪽만 보고 '동영상이 없어요')
     */
    private fun playlistVideos(
        playlistId: String,
        pageToken: String?,
        mine: Boolean = false,
        keep: (TubeVideo) -> Boolean = { true },
    ): TubePage? {
        if (!TubeLinks.isPlaylistId(playlistId)) return TubePage(emptyList(), null)
        var token = pageToken
        repeat(MAX_EMPTY_PAGES) {
            val p = linkedMapOf(
                "part" to "snippet,contentDetails,status", "playlistId" to playlistId,
                "maxResults" to "$PAGE", "fields" to ITEM_FIELDS,
            )
            token?.let { p["pageToken"] = it }
            val (ids, next) = try {
                TubeParsers.playlistItems(call("/playlistItems", p, mine = mine))
            } catch (e: YouTubeHttpException) {
                if (e.code == 404) return null else throw e
            }
            val videos = if (ids.isEmpty()) emptyList() else details(ids).let { d -> ids.mapNotNull { d[it] } }.filter(keep)
            if (videos.isNotEmpty() || next == null) return TubePage(videos, next)
            token = next
        }
        return TubePage(emptyList(), token)
    }

    /**
     * 채널의 [kind] 업로드 (새것부터). UULF·UUSH·UULV 는 문서에 없는 자동 재생목록이라 없으면(404) 대신한다:
     * 긴 동영상·Shorts 는 전체(UU)에서 모양으로 골라서, 생방송·전체는 빈 목록.
     * 대신한 쪽의 [TubePage.next] 엔 'UU:' 를 붙인다 — 그 값으로 다시 부르면 전체 목록에서 같은 거르기로 이어서
     */
    fun uploads(channelId: String, kind: Uploads, pageToken: String?): TubePage {
        val all = TubeLinks.uploads(channelId, Uploads.ALL) ?: return TubePage(emptyList(), null)
        if (pageToken != null && pageToken.startsWith(FROM_ALL)) {
            return fromAll(all, kind, pageToken.removePrefix(FROM_ALL).ifEmpty { null })
        }
        val list = TubeLinks.uploads(channelId, kind) ?: return TubePage(emptyList(), null)
        playlistVideos(list, pageToken)?.let { return it }
        return when (kind) {
            // 다음 쪽을 받다 목록이 없어졌으면 처음부터 대신하지 않는다 — 이미 받은 쪽과 겹친다
            Uploads.LONG, Uploads.SHORTS -> if (pageToken == null) fromAll(all, kind, null) else TubePage(emptyList(), null)
            Uploads.ALL, Uploads.LIVE -> TubePage(emptyList(), null)
        }
    }

    /** 전체 업로드([all] = UU…)에서 [kind] 의 모양만 — 다음 쪽 값엔 'UU:' */
    private fun fromAll(all: String, kind: Uploads, pageToken: String?): TubePage {
        val page = playlistVideos(all, pageToken, keep = shapeOf(kind)) ?: return TubePage(emptyList(), null)
        return page.copy(next = page.next?.let { FROM_ALL + it })
    }

    /** [kind] 의 모양: 긴 동영상 = Shorts 아닌 것 · Shorts = 세로·3분 이하 · 그 밖은 모두 */
    private fun shapeOf(kind: Uploads): (TubeVideo) -> Boolean = when (kind) {
        Uploads.LONG -> { v -> !v.isShort }
        Uploads.SHORTS -> { v -> v.isShort }
        Uploads.ALL, Uploads.LIVE -> { _ -> true }
    }

    /** 채널이 만든 재생목록 ([TubePage.playlists]) */
    fun channelPlaylists(channelId: String, pageToken: String?): TubePage =
        playlistPage(linkedMapOf("channelId" to channelId), pageToken, mine = false)

    /** 내 재생목록 (로그인) */
    fun myPlaylists(pageToken: String?): TubePage = playlistPage(linkedMapOf("mine" to "true"), pageToken, mine = true)

    private fun playlistPage(filter: Map<String, String>, pageToken: String?, mine: Boolean): TubePage {
        // 내 재생목록은 공개 범위도 (비공개면 재생 화면이 목록으로 못 연다 — 값은 같음: 1단위)
        val p = if (mine) linkedMapOf("part" to "snippet,contentDetails,status", "maxResults" to "$PAGE", "fields" to MY_PLAYLIST_FIELDS)
        else linkedMapOf("part" to "snippet,contentDetails", "maxResults" to "$PAGE", "fields" to PLAYLIST_FIELDS)
        p.putAll(filter)
        pageToken?.let { p["pageToken"] = it }
        val (lists, next) = TubeParsers.playlists(call("/playlists", p, mine = mine))
        return TubePage(emptyList(), next, playlists = lists)
    }

    /** 좋아요 표시한 동영상 (로그인) — 영상 정보가 바로 와서 한 번만 묻는다 */
    fun liked(pageToken: String?): TubePage {
        val p = linkedMapOf(
            "part" to VIDEO_PARTS, "myRating" to "like", "maxResults" to "$PAGE", "maxHeight" to "$MAX_HEIGHT", "fields" to VIDEO_FIELDS,
        )
        pageToken?.let { p["pageToken"] = it }
        return TubeParsers.videoPage(call("/videos", p, mine = true))
    }

    /** 구독한 채널 ([TubePage.channels], 유튜브 관련도순 — 자주 보는 채널이 앞에). 채널이 없는 계정(404)은 빈 목록 */
    fun subscriptions(pageToken: String?, max: Int = MAX_IDS): TubePage {
        val p = linkedMapOf(
            "part" to "snippet", "mine" to "true", "maxResults" to "$max", "order" to "relevance", "fields" to SUB_FIELDS,
        )
        pageToken?.let { p["pageToken"] = it }
        val (channels, next) = try {
            TubeParsers.subscriptions(call("/subscriptions", p, mine = true))
        } catch (e: YouTubeHttpException) {
            if (e.code == 404) return TubePage(emptyList(), null) else throw e
        }
        return TubePage(emptyList(), next, channels = channels)
    }

    /**
     * 구독 피드 (유튜브 앱의 '구독' 탭처럼 새 영상 먼저): 구독 채널(관련도순) 앞 [maxChannels]개의 업로드 목록에서 최신 [perChannel]개씩 →
     * 정보를 한 번에 받아 올린 때 순으로 [max]개. 약 17단위. 한 채널이 안 되면 그 채널만 빼고, 하나도 못 읽으면 그 오류를 낸다
     */
    suspend fun subscriptionFeed(maxChannels: Int = 15, perChannel: Int = 3, max: Int = 30): List<TubeVideo> =
        withContext(Dispatchers.IO) {
            val channels = subscriptions(null).channels.take(maxChannels)
            if (channels.isEmpty()) return@withContext emptyList()
            val gate = Semaphore(PARALLEL)
            val results: List<Result<List<String>>> = coroutineScope {
                channels.map { ch ->
                    async {
                        gate.withPermit {
                            try {
                                Result.success(latestOf(ch, perChannel))
                            } catch (e: ApiException) {
                                Result.failure(e)
                            }
                        }
                    }
                }.awaitAll()
            }
            val ids = results.flatMap { it.getOrNull().orEmpty() }.distinct()
            if (ids.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            val details = details(ids)
            ids.mapNotNull { details[it] }.sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }.take(max)
        }

    /** 채널 업로드 목록의 최신 [n]개 번호 (영상이 없는 채널 = 404 → 없음) */
    private fun latestOf(ch: TubeChannel, n: Int): List<String> {
        val uploads = ch.uploads ?: TubeLinks.uploadsOf(ch.id) ?: return emptyList()
        return latestOrNull(uploads, n).orEmpty()
    }

    /**
     * 여러 채널(구독 안 한 채널도)의 [kind] 업로드를 새것부터 [max]개 — 구독 피드처럼 채널마다 최신 [perChannel]개, 정보는 한 번에.
     * 그 종류의 모양만 남긴다 (긴 동영상 = Shorts 아닌 것, Shorts = 세로·3분 이하). 한 채널이 안 되면 그 채널만 빼고,
     * 하나도 못 읽으면 그 오류를 낸다
     */
    suspend fun uploadsFeed(channelIds: List<String>, kind: Uploads, perChannel: Int, max: Int = 30): List<TubeVideo> =
        latestByChannel(channelIds, kind, perChannel).flatten().distinctBy { it.id }
            .sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }.take(max)

    /**
     * Shorts 고르기: 채널마다 최신 Shorts [perChannel]개 → 준 채널 순서대로 번갈아 (첫 채널의 가장 새것, 둘째 채널의 가장 새것, …
     * 그다음 저마다 둘째 것). 세로·3분 이하인 것만. 한 채널이 안 되면 그 채널만 빼고, 하나도 못 읽으면 그 오류를 낸다
     */
    suspend fun shortsOf(channelIds: List<String>, perChannel: Int): List<TubeVideo> {
        val lanes = latestByChannel(channelIds, Uploads.SHORTS, perChannel)
        val out = LinkedHashMap<String, TubeVideo>()
        for (i in 0 until (lanes.maxOfOrNull { it.size } ?: 0)) {
            lanes.forEach { lane -> lane.getOrNull(i)?.let { out.putIfAbsent(it.id, it) } }
        }
        return out.values.toList()
    }

    /**
     * 채널마다(준 순서, 같은 채널은 한 번) [kind] 의 최신 [perChannel]개 — 그 모양만, 새것부터. 번호는 채널마다 따로
     * (동시에 [PARALLEL]개까지), 정보는 한 번에. 받은 번호가 없는데 안 된 채널이 있으면 그 첫 오류를 낸다
     */
    private suspend fun latestByChannel(channelIds: List<String>, kind: Uploads, perChannel: Int): List<List<TubeVideo>> =
        withContext(Dispatchers.IO) {
            val channels = channelIds.filter(TubeLinks::isChannelId).distinct()
            if (channels.isEmpty() || perChannel <= 0) return@withContext emptyList()
            val gate = Semaphore(PARALLEL)
            val results: List<Result<List<String>>> = coroutineScope {
                channels.map { id ->
                    async {
                        gate.withPermit {
                            try {
                                Result.success(latestIds(id, kind, perChannel))
                            } catch (e: ApiException) {
                                Result.failure(e)
                            }
                        }
                    }
                }.awaitAll()
            }
            val lanes = results.map { it.getOrNull().orEmpty() }
            val ids = lanes.flatten().distinct()
            if (ids.isEmpty()) {
                results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
                return@withContext emptyList()
            }
            val details = details(ids)
            val keep = shapeOf(kind)
            lanes.map { lane ->
                lane.mapNotNull { details[it] }.filter(keep).sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }.take(perChannel)
            }
        }

    /**
     * 채널의 [kind] 최신 [n]개 번호. 그 목록이 없으면(404) 긴 동영상·Shorts 는 전체(UU)의 최신 2n개 (정보를 받은 뒤 모양으로 고른다),
     * 생방송·전체는 없음
     */
    private fun latestIds(channelId: String, kind: Uploads, n: Int): List<String> {
        val list = TubeLinks.uploads(channelId, kind) ?: return emptyList()
        latestOrNull(list, n)?.let { return it }
        if (kind != Uploads.LONG && kind != Uploads.SHORTS) return emptyList()
        return TubeLinks.uploads(channelId, Uploads.ALL)?.let { latestOrNull(it, n * 2) }.orEmpty()
    }

    /** 재생목록의 앞 [n]개 번호 (1단위). 없는 목록(404)이면 null */
    private fun latestOrNull(playlistId: String, n: Int): List<String>? {
        val p = linkedMapOf(
            "part" to "contentDetails", "playlistId" to playlistId, "maxResults" to "${n.coerceIn(1, MAX_IDS)}",
            "fields" to "items/contentDetails/videoId",
        )
        return try {
            TubeParsers.playlistItems(call("/playlistItems", p)).first.take(n)
        } catch (e: YouTubeHttpException) {
            if (e.code == 404) null else throw e
        }
    }

    /** 묻고 → 오류를 알맞은 예외로 → JSON. [search] = 검색 한도(따로 셈)의 요청 */
    private fun call(path: String, params: Map<String, String>, mine: Boolean = false, search: Boolean = false): JSONObject {
        val res = request(path, params, mine)
        if (res.code !in 200..299) throw failure(res, search)
        return try {
            JSONObject(res.body)
        } catch (e: JSONException) {
            throw ApiException("유튜브 응답을 읽지 못했어요")
        }
    }

    private fun request(path: String, params: Map<String, String>, mine: Boolean): Http.Response {
        if (!mine && key.isNotBlank()) return send(path, params + ("key" to key.trim()), emptyMap())
        val first = token() ?: throw if (mine) SignInNeededException() else ApiException("유튜브 API 키가 없어요")
        val res = send(path, params, bearer(first))
        if (res.code != 401) return res
        // 토큰이 막 끝났거나 시계가 어긋남 → 새로 받아 한 번만 다시
        onUnauthorized()
        val second = token() ?: throw SignInNeededException("로그인이 풀렸어요 — 다시 로그인해 주세요")
        val again = send(path, params, bearer(second))
        if (again.code == 401) throw SignInNeededException("로그인이 풀렸어요 — 다시 로그인해 주세요")
        return again
    }

    private fun bearer(t: String) = mapOf("Authorization" to "Bearer $t")

    private fun send(path: String, params: Map<String, String>, headers: Map<String, String>): Http.Response = try {
        get("$BASE$path?${Http.query(params)}", headers)
    } catch (e: IOException) {
        // 원인(주소에 키가 들어 있음)은 달지 않는다
        throw NetworkException()
    }

    private fun failure(res: Http.Response, search: Boolean): ApiException {
        val reason = TubeParsers.youtubeErrorReason(res.body)
        return when (reason) {
            "quotaExceeded", "dailyLimitExceeded" -> if (search) YouTubeQuotaException() else YouTubeQuotaException(BROWSE_LIMIT)
            "rateLimitExceeded", "userRateLimitExceeded" -> YouTubeHttpException(res.code, reason, "유튜브 요청이 잠깐 몰렸어요 — 잠시 뒤 다시 해 주세요")
            "insufficientPermissions" -> SignInNeededException("유튜브 계정 보기 권한이 없어요 — 다시 로그인해서 허용해 주세요")
            else -> YouTubeHttpException(res.code, reason, errorMessage(res.code, reason, res.body))
        }
    }

    companion object {
        const val BASE = "https://www.googleapis.com/youtube/v3"
        const val REGION = "KR"
        /** 한 번에 받는 개수 (워치 화면엔 20개면 충분, 더 보기로 이어서) */
        const val PAGE = 20
        /** id 로 묻기·구독 목록 한 번의 최대 */
        const val MAX_IDS = 50
        /** 구독 피드에서 동시에 묻는 수 */
        private const val PARALLEL = 4

        const val BROWSE_LIMIT = "오늘 유튜브 사용 한도를 다 썼어요 — 한국 시간 오후 4~5시에 다시 채워져요"

        private const val THUMBS = "thumbnails(default/url,medium/url,high/url)"
        const val VIDEO_PARTS = "snippet,contentDetails,statistics,status,player"
        const val VIDEO_FIELDS = "nextPageToken,items(id,snippet(publishedAt,channelId,title,channelTitle,liveBroadcastContent,categoryId)," +
            "contentDetails(duration,contentRating/ytRating),statistics/viewCount,status/embeddable,player(embedWidth,embedHeight))"
        /**
         * 동영상 정보를 물을 때 함께 보내는 플레이어 높이 — 유튜브는 maxHeight(또는 maxWidth)를 보낸 요청에만
         * player.embedWidth/embedHeight 를 준다(문서). 그 비율로 세로(Shorts)를 가린다. 한도는 그대로 1단위
         */
        const val MAX_HEIGHT = 360
        /** 대신한 목록(전체 업로드 UU…)에서 이어 받는 다음 쪽 값의 앞머리 */
        private const val FROM_ALL = "UU:"
        const val SEARCH_FIELDS = "nextPageToken,items(id(videoId,channelId,playlistId)," +
            "snippet(publishedAt,channelId,title,channelTitle,liveBroadcastContent,$THUMBS))"
        const val CHANNEL_PARTS = "snippet,statistics,contentDetails"
        const val CHANNEL_FIELDS = "items(id,snippet(title,$THUMBS),statistics(subscriberCount,hiddenSubscriberCount,videoCount)," +
            "contentDetails/relatedPlaylists/uploads)"
        const val PLAYLIST_FIELDS = "nextPageToken,items(id,snippet(title,channelTitle,$THUMBS),contentDetails/itemCount)"
        const val MY_PLAYLIST_FIELDS = "nextPageToken,items(id,snippet(title,channelTitle,$THUMBS),contentDetails/itemCount,status/privacyStatus)"
        /** 볼 영상이 없는 쪽을 넘겨 가며 보는 수 */
        private const val MAX_EMPTY_PAGES = 3
        const val ITEM_FIELDS = "nextPageToken,items(snippet(title,resourceId/videoId),contentDetails/videoId,status/privacyStatus)"
        const val SUB_FIELDS = "nextPageToken,items/snippet(title,resourceId/channelId,$THUMBS)"

        /** 사용자에게 보여 줄 오류 글 (키·주소 없이) */
        fun errorMessage(code: Int, reason: String?, body: String): String = when {
            body.contains("API_KEY_INVALID") || reason == "keyInvalid" -> "유튜브 API 키가 올바르지 않아요"
            reason == "accessNotConfigured" || body.contains("SERVICE_DISABLED") -> "YouTube Data API 사용 설정이 꺼져 있어요"
            body.contains("API_KEY_ANDROID_APP_BLOCKED") || body.contains("API_KEY_HTTP_REFERRER_BLOCKED") -> "유튜브 API 키의 앱 제한 설정을 확인하세요"
            code == 404 -> "유튜브에서 찾을 수 없어요"
            code >= 500 -> "유튜브 서버 오류 ($code)"
            else -> "유튜브 오류 ($code)"
        }
    }
}
