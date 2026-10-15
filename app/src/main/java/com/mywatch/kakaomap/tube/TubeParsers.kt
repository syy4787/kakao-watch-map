package com.mywatch.kakaomap.tube

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * 받은 JSON → 동영상·채널·재생목록. 밖에서 온 값이라 번호는 모양을 확인하고(아니면 뺀다) 글은 다듬는다.
 * 유튜브 Data API v3 (search·videos·channels·playlists·playlistItems·subscriptions) 와 카카오 동영상 검색
 */
object TubeParsers {

    /** 카카오(다음) 동영상 검색 결과에서 유튜브 영상만 (같은 영상은 한 번) */
    fun kakaoVclip(json: JSONObject): KakaoPage {
        val docs = json.optJSONArray("documents") ?: JSONArray()
        val seen = HashSet<String>()
        val videos = (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            val id = TubeLinks.videoId(d.optString("url")) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            TubeVideo(
                id = id,
                title = TubeText.plain(d.optString("title")).ifBlank { "제목 없음" },
                channel = TubeText.plain(d.optString("author")),
                durationSec = d.optInt("play_time", 0).takeIf { it > 0 },
            )
        }
        return KakaoPage(videos, isEnd = json.optJSONObject("meta")?.optBoolean("is_end", true) ?: true)
    }

    class KakaoPage(val videos: List<TubeVideo>, val isEnd: Boolean)

    /**
     * 유튜브 검색 결과(search.list type=video,channel,playlist): 동영상·채널·재생목록을 저마다 온 순서대로.
     * 아직 시작 안 한 생방송은 틀 수 없으니 뺀다. 같은 것은 한 번
     */
    fun youtubeSearchAll(json: JSONObject): TubePage {
        val videos = LinkedHashMap<String, TubeVideo>()
        val channels = LinkedHashMap<String, TubeChannel>()
        val playlists = LinkedHashMap<String, TubePlaylist>()
        items(json).forEach { item ->
            val id = item.optJSONObject("id") ?: return@forEach
            val sn = item.optJSONObject("snippet") ?: return@forEach
            val videoId = id.optString("videoId")
            val channelId = id.optString("channelId")
            val playlistId = id.optString("playlistId")
            when {
                videoId.isNotEmpty() -> {
                    if (!TubeLinks.isId(videoId) || videos.containsKey(videoId)) return@forEach
                    val state = sn.optString("liveBroadcastContent")
                    if (state == "upcoming") return@forEach
                    videos[videoId] = TubeVideo(
                        id = videoId,
                        title = TubeText.unescape(sn.optString("title")).ifBlank { "제목 없음" },
                        channel = TubeText.unescape(sn.optString("channelTitle")),
                        live = state == "live",
                        channelId = sn.optString("channelId").takeIf(TubeLinks::isChannelId),
                        publishedAt = iso8601Ms(sn.optString("publishedAt")),
                    )
                }
                channelId.isNotEmpty() -> {
                    if (!TubeLinks.isChannelId(channelId) || channels.containsKey(channelId)) return@forEach
                    val title = TubeText.unescape(sn.optString("title")).ifBlank { TubeText.unescape(sn.optString("channelTitle")) }
                    channels[channelId] = TubeChannel(channelId, title.ifBlank { "채널" }, thumb(sn))
                }
                playlistId.isNotEmpty() -> {
                    if (!TubeLinks.isPlaylistId(playlistId) || playlists.containsKey(playlistId)) return@forEach
                    playlists[playlistId] = TubePlaylist(
                        playlistId,
                        TubeText.unescape(sn.optString("title")).ifBlank { "재생목록" },
                        TubeText.unescape(sn.optString("channelTitle")),
                        thumb(sn),
                    )
                }
            }
        }
        return TubePage(videos.values.toList(), next(json), channels.values.toList(), playlists.values.toList())
    }

    /**
     * videos.list(part=snippet,contentDetails,statistics,status,player) → 번호별 동영상: 제목·채널·길이·조회수·올린 때·틀 수 있는지·
     * 분류·세로인지·어린이용인지. 아직 시작 안 한 생방송·예정 영상은 뺀다
     */
    fun videoDetails(json: JSONObject): Map<String, TubeVideo> {
        val out = LinkedHashMap<String, TubeVideo>()
        items(json).forEach { item ->
            val id = item.optString("id").takeIf(TubeLinks::isId) ?: return@forEach
            val sn = item.optJSONObject("snippet") ?: return@forEach
            val state = sn.optString("liveBroadcastContent")
            if (state == "upcoming") return@forEach
            val details = item.optJSONObject("contentDetails")
            val live = state == "live"
            val ageLimited = details?.optJSONObject("contentRating")?.optString("ytRating") == "ytAgeRestricted"
            out[id] = TubeVideo(
                id = id,
                title = TubeText.unescape(sn.optString("title")).ifBlank { "제목 없음" },
                channel = TubeText.unescape(sn.optString("channelTitle")),
                durationSec = if (live) null else TubeText.isoSeconds(details?.optString("duration")),
                live = live,
                channelId = sn.optString("channelId").takeIf(TubeLinks::isChannelId),
                views = item.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull()?.takeIf { it >= 0 },
                publishedAt = iso8601Ms(sn.optString("publishedAt")),
                embeddable = item.optJSONObject("status")?.optBoolean("embeddable", true) != false && !ageLimited,
                categoryId = sn.optString("categoryId").takeIf(TubeLinks::isCategoryId),
                portrait = portrait(item.optJSONObject("player")),
                // 참·거짓이 아니면 모름 (어린이용처럼 다룬다)
                madeForKids = item.optJSONObject("status")?.opt("madeForKids") as? Boolean,
            )
        }
        return out
    }

    /**
     * player.embedWidth/embedHeight(maxHeight 를 보낸 요청에만 옴, 글 또는 숫자) → 세로(높이 ≥ 너비)인지.
     * 둘 다 0 보다 클 때만 — 생방송은 0×0 이라 모름(null)
     */
    private fun portrait(player: JSONObject?): Boolean? {
        val w = player?.optLong("embedWidth", 0) ?: return null
        val h = player.optLong("embedHeight", 0)
        return if (w > 0 && h > 0) h >= w else null
    }

    /** videos.list 한 쪽 (인기 차트·좋아요) → 받은 순서대로 */
    fun videoPage(json: JSONObject): TubePage = TubePage(videoDetails(json).values.toList(), next(json))

    /** channels.list(part=snippet,statistics,contentDetails): 사진·구독자(숨기면 null)·동영상 수·업로드 재생목록 */
    fun channels(json: JSONObject): List<TubeChannel> {
        val out = LinkedHashMap<String, TubeChannel>()
        items(json).forEach { item ->
            val id = item.optString("id").takeIf(TubeLinks::isChannelId) ?: return@forEach
            val sn = item.optJSONObject("snippet") ?: return@forEach
            val stats = item.optJSONObject("statistics")
            val hidden = stats?.optBoolean("hiddenSubscriberCount", false) == true
            out.putIfAbsent(id, TubeChannel(
                id = id,
                title = TubeText.unescape(sn.optString("title")).ifBlank { "채널" },
                thumb = thumb(sn),
                subscribers = if (hidden) null else stats?.optString("subscriberCount")?.toLongOrNull()?.takeIf { it >= 0 },
                videoCount = stats?.optString("videoCount")?.toLongOrNull()?.takeIf { it >= 0 },
                uploads = item.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads")
                    ?.takeIf(TubeLinks::isPlaylistId),
            ))
        }
        return out.values.toList()
    }

    /** playlists.list(part=snippet,contentDetails) → 재생목록들과 다음 쪽 */
    fun playlists(json: JSONObject): Pair<List<TubePlaylist>, String?> {
        val out = LinkedHashMap<String, TubePlaylist>()
        items(json).forEach { item ->
            val id = item.optString("id").takeIf(TubeLinks::isPlaylistId) ?: return@forEach
            val sn = item.optJSONObject("snippet") ?: return@forEach
            val details = item.optJSONObject("contentDetails")
            out.putIfAbsent(id, TubePlaylist(
                id = id,
                title = TubeText.unescape(sn.optString("title")).ifBlank { "재생목록" },
                channel = TubeText.unescape(sn.optString("channelTitle")),
                thumb = thumb(sn),
                count = if (details?.has("itemCount") == true) details.optInt("itemCount").coerceAtLeast(0) else null,
                privacy = item.optJSONObject("status")?.optString("privacyStatus")?.ifBlank { null },
            ))
        }
        return out.values.toList() to next(json)
    }

    /** 비공개·삭제된 영상이 재생목록에 남으면 이 제목으로 온다 (워치 플레이어로 못 틂) */
    private val GONE = setOf("Private video", "Deleted video")

    /** playlistItems.list(part=snippet,contentDetails[,status]) → 담긴 순서대로 동영상 번호 (비공개·삭제 빼고, 같은 영상은 한 번) */
    fun playlistItems(json: JSONObject): Pair<List<String>, String?> {
        val ids = LinkedHashSet<String>()
        items(json).forEach { item ->
            val sn = item.optJSONObject("snippet")
            if (sn?.optString("title") in GONE) return@forEach
            if (item.optJSONObject("status")?.optString("privacyStatus") == "private") return@forEach
            val id = item.optJSONObject("contentDetails")?.optString("videoId")?.takeIf(TubeLinks::isId)
                ?: sn?.optJSONObject("resourceId")?.optString("videoId")?.takeIf(TubeLinks::isId)
                ?: return@forEach
            ids.add(id)
        }
        return ids.toList() to next(json)
    }

    /** subscriptions.list(mine=true, part=snippet) → 구독한 채널들 (이름·사진) */
    fun subscriptions(json: JSONObject): Pair<List<TubeChannel>, String?> {
        val out = LinkedHashMap<String, TubeChannel>()
        items(json).forEach { item ->
            val sn = item.optJSONObject("snippet") ?: return@forEach
            val id = sn.optJSONObject("resourceId")?.optString("channelId")?.takeIf(TubeLinks::isChannelId) ?: return@forEach
            out.putIfAbsent(id, TubeChannel(id, TubeText.unescape(sn.optString("title")).ifBlank { "채널" }, thumb(sn)))
        }
        return out.values.toList() to next(json)
    }

    /** 2017-03-24T09:00:01Z · 2022-07-12T16:11:24.123Z · …+09:00 → ms. 읽을 수 없으면 null */
    fun iso8601Ms(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
    }

    /** 유튜브 API 오류의 이유 (quotaExceeded·keyInvalid 등). 읽을 수 없으면 null */
    fun youtubeErrorReason(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.ifBlank { null }
    } catch (e: JSONException) {
        null
    }

    private fun items(json: JSONObject): List<JSONObject> {
        val arr = json.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun next(json: JSONObject): String? = json.optString("nextPageToken").ifBlank { null }

    /** 미리보기 그림: 320 크기(medium) → 480(high) → 작은 것(default). https 주소만 */
    private fun thumb(sn: JSONObject): String? {
        val all = sn.optJSONObject("thumbnails") ?: return null
        return listOf("medium", "high", "default").firstNotNullOfOrNull { size ->
            all.optJSONObject(size)?.optString("url")?.takeIf { it.startsWith("https://") }
        }
    }
}
