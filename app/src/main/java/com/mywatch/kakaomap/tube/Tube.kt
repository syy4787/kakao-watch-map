package com.mywatch.kakaomap.tube

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 워치용 유튜브 리모컨의 순수 계산 (단위 테스트로 검증).
 * 워치는 검색·고르기·기록만 하고, 재생과 '믹스'(다음 곡 추천 = 내 계정의 알고리즘)는 폰의 공식 유튜브 앱이 한다 —
 * 유튜브 약관상 다른 앱이 영상·소리를 직접 받아 재생하면 안 되고, 프리미엄 백그라운드 재생도 공식 앱에서만 된다.
 */
data class TubeVideo(
    val id: String,
    val title: String,
    val channel: String,
    /** 길이(초). 모르거나 생방송이면 null */
    val durationSec: Int? = null,
    val live: Boolean = false,
)

/** 한 번 검색한 결과 한 쪽. [next] = 다음 쪽을 받을 값(유튜브 토큰 또는 카카오 쪽 번호), 없으면 끝 */
data class TubePage(val videos: List<TubeVideo>, val next: String?)

object TubeLinks {
    private val ID = Regex("[A-Za-z0-9_-]{11}")

    fun isId(s: String?): Boolean = s != null && ID.matches(s)

    /** 유튜브 주소에서 영상 번호(11자). 유튜브 영상이 아니면 null */
    fun videoId(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val parts = path.split('/').filter { it.isNotEmpty() }
                when {
                    parts == listOf("watch") -> param(uri.rawQuery, "v")
                    parts.size >= 2 && parts[0] in setOf("shorts", "embed", "live", "v") -> parts[1]
                    else -> null
                }
            }
            else -> null
        }
        return id?.takeIf(::isId)
    }

    private fun param(rawQuery: String?, name: String): String? = rawQuery.orEmpty().split('&').firstNotNullOfOrNull { kv ->
        val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
        if (k == name) runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull() else null
    }

    /**
     * 폰에서 열 주소. [mix] = 이 곡으로 시작하는 유튜브 '믹스' — 로그인된 폰 유튜브 앱이
     * 내 시청 기록으로 비슷한 곡을 끝없이 이어 준다 (평소 폰에서 듣던 그 알고리즘)
     */
    fun watchUrl(id: String, mix: Boolean): String =
        if (mix) "https://www.youtube.com/watch?v=$id&list=RD$id&start_radio=1" else "https://www.youtube.com/watch?v=$id"

    /** 폰 유튜브 앱의 검색 결과 (워치 검색이 안 될 때) */
    fun searchUrl(query: String): String = "https://www.youtube.com/results?search_query=" + encode(query.trim())

    /** 320×180 미리보기 그림 */
    fun thumbnail(id: String): String = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    /** URLEncoder 는 띄어쓰기를 + 로 바꾸는데, 검색어 속 진짜 + 와 헷갈리지 않게 %20 으로 */
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object TubeText {
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]{1,8}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val SPACES = Regex("\\s+")
    private val ISO = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")

    /** 검색 결과 제목의 강조 태그(<b>)를 떼고 &amp;·&#39; 같은 글자를 되돌린다 */
    fun plain(s: String): String {
        val decoded = ENTITY.replace(TAG.replace(s, "")) { m -> decode(m.groupValues[1]) ?: m.value }
        return SPACES.replace(decoded, " ").trim()
    }

    private fun decode(e: String): String? {
        val code = when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
            e.startsWith("#") -> e.substring(1).toIntOrNull()
            else -> return NAMED[e]
        } ?: return null
        return if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else null
    }

    /** 유튜브 길이 표기(PT4M54S) → 초. 0(생방송·예정)이나 읽을 수 없으면 null */
    fun isoSeconds(s: String?): Int? {
        val m = ISO.matchEntire(s?.trim() ?: return null) ?: return null
        val (d, h, min, sec) = m.destructured
        val total = (d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 +
            (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
        return total.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }

    /** 4:54 / 1:02:03 */
    fun clock(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

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

    /** 유튜브 Data API 검색 결과. 아직 시작 안 한 생방송·채널·재생목록은 틀 수 없으니 뺀다 */
    fun youtubeSearch(json: JSONObject): TubePage {
        val items = json.optJSONArray("items") ?: JSONArray()
        val videos = (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optJSONObject("id")?.optString("videoId")?.takeIf(TubeLinks::isId) ?: return@mapNotNull null
            val sn = item.optJSONObject("snippet") ?: return@mapNotNull null
            val state = sn.optString("liveBroadcastContent")
            if (state == "upcoming") return@mapNotNull null
            TubeVideo(
                id = id,
                title = TubeText.plain(sn.optString("title")).ifBlank { "제목 없음" },
                channel = TubeText.plain(sn.optString("channelTitle")),
                live = state == "live",
            )
        }
        return TubePage(videos.distinctBy { it.id }, json.optString("nextPageToken").ifBlank { null })
    }

    /** videos.list(contentDetails) → 영상별 길이(초, 생방송은 null) */
    fun youtubeDurations(json: JSONObject): Map<String, Int?> {
        val items = json.optJSONArray("items") ?: return emptyMap()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(TubeLinks::isId) ?: return@mapNotNull null
            id to TubeText.isoSeconds(item.optJSONObject("contentDetails")?.optString("duration"))
        }.toMap()
    }

    fun withDurations(videos: List<TubeVideo>, durations: Map<String, Int?>): List<TubeVideo> =
        videos.map { v -> if (durations.containsKey(v.id)) v.copy(durationSec = durations[v.id]) else v }

    /** 유튜브 API 오류의 이유 (quotaExceeded·keyInvalid 등). 읽을 수 없으면 null */
    fun youtubeErrorReason(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.ifBlank { null }
    } catch (e: JSONException) {
        null
    }
}

/** 최근 검색어 (최근 것 먼저, 띄어쓰기·대소문자만 다른 검색은 한 번만) */
object SearchHistory {
    const val MAX = 12
    const val MAX_LENGTH = 80
    private val SPACES = Regex("\\s+")

    fun clean(query: String): String = SPACES.replace(query, " ").trim().take(MAX_LENGTH).trim()

    private fun key(query: String) = query.filterNot { it.isWhitespace() }.lowercase()

    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val q = clean(query)
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { key(it) == key(q) }).take(max)
    }

    fun remove(list: List<String>, query: String): List<String> = list.filterNot { key(it) == key(query) }

    /** 검색어에는 줄바꿈이 없으므로(clean) 한 줄에 하나씩 */
    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(text: String?): List<String> = text.orEmpty().split('\n').map(::clean).filter { it.isNotEmpty() }
}

/** 워치에서 튼 곡 */
data class Played(val video: TubeVideo, val atMs: Long)

/** 최근 들은 곡 — 유튜브 API 로 받은 정보는 30일까지만 보관할 수 있어서 그보다 오래된 건 지운다 */
object PlayedHistory {
    const val MAX = 30
    const val KEEP_MS = 30L * 24 * 3_600_000

    fun add(list: List<Played>, video: TubeVideo, nowMs: Long): List<Played> =
        prune(listOf(Played(video, nowMs)) + list.filterNot { it.video.id == video.id }, nowMs).take(MAX)

    fun prune(list: List<Played>, nowMs: Long): List<Played> = list.filter { nowMs - it.atMs <= KEEP_MS }

    fun encode(list: List<Played>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("id", p.video.id)
                put("title", p.video.title)
                put("channel", p.video.channel)
                p.video.durationSec?.let { put("sec", it) }
                if (p.video.live) put("live", true)
                put("at", p.atMs)
            })
        }
    }.toString()

    /** 30일이 지난 것은 빼고 */
    fun decode(text: String?, nowMs: Long): List<Played> = prune(decodeAll(text), nowMs).take(MAX)

    /** 저장된 그대로 (오래된 것도) — 지울 게 있는지 알려고 */
    fun decodeAll(text: String?): List<Played> {
        val arr = try {
            JSONArray(text ?: return emptyList())
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf(TubeLinks::isId) ?: return@mapNotNull null
            Played(
                TubeVideo(
                    id = id,
                    title = o.optString("title"),
                    channel = o.optString("channel"),
                    durationSec = if (o.has("sec")) o.optInt("sec") else null,
                    live = o.optBoolean("live", false),
                ),
                o.optLong("at"),
            )
        }
    }
}
