package com.mywatch.kakaomap.tube

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 워치용 유튜브 앱의 순수 계산 (단위 테스트로 검증): 동영상·채널·재생목록 모양, 링크, 보여 줄 글, 기록.
 * 재생은 유튜브 공식 플레이어(임베드)만 — 영상·소리를 직접 받거나 저장하지 않는다 (유튜브 API 정책).
 */
data class TubeVideo(
    val id: String,
    val title: String,
    val channel: String,
    /** 길이(초). 모르거나 생방송이면 null */
    val durationSec: Int? = null,
    val live: Boolean = false,
    /** 올린 채널 번호(UC…) — 채널 화면으로 갈 때. 모르면 null */
    val channelId: String? = null,
    /** 조회수 (숨겼거나 모르면 null) */
    val views: Long? = null,
    /** 올린 때(ms). 모르면 null */
    val publishedAt: Long? = null,
    /** 워치 플레이어(다른 곳에 넣은 유튜브 플레이어)로 틀 수 있음 — 올린 사람이 막았거나 나이 제한이면 false (폰 유튜브로) */
    val embeddable: Boolean = true,
)

/** 채널: 번호(UC…)·이름·동그란 사진 · 구독자(숨기면 null) · 동영상 수 · 업로드 재생목록(UU…, 응답에 없으면 null) */
data class TubeChannel(
    val id: String,
    val title: String,
    val thumb: String?,
    val subscribers: Long? = null,
    val videoCount: Long? = null,
    val uploads: String? = null,
)

/** 재생목록: 번호·제목·만든 채널·미리보기 · 동영상 수(모르면 null) */
data class TubePlaylist(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String?,
    val count: Int? = null,
)

/** 워치 재생 화면의 '폰에서 이어 보기'가 맡긴 일: 이 동영상을 [startSec] 초부터, [mix] 면 믹스로, [list] 면 그 재생목록으로 */
data class Handoff(val video: TubeVideo, val startSec: Int, val mix: Boolean, val list: String? = null)

/**
 * 받은 목록 한 쪽. [next] = 다음 쪽을 받을 값(유튜브 토큰 또는 카카오 쪽 번호), 없으면 끝.
 * 검색 결과엔 채널·재생목록도 섞여 온다
 */
data class TubePage(
    val videos: List<TubeVideo>,
    val next: String?,
    val channels: List<TubeChannel> = emptyList(),
    val playlists: List<TubePlaylist> = emptyList(),
    /**
     * '더 보기'로 이어 붙인 쪽마다 새로 들어온 수 (없으면 한 쪽) — 검색 결과를 받은 쪽 순서대로 그리려고
     * (둘째 쪽의 채널이 맨 위 첫 쪽 채널 옆에 끼어들어 목록이 밀리지 않게)
     */
    val parts: List<Part> = emptyList(),
) {
    data class Part(val videos: Int, val channels: Int, val playlists: Int)

    /** 받은 쪽들 — 쪽마다 (동영상, 채널, 재생목록) */
    fun split(): List<TubePage> {
        val ps = parts.ifEmpty { listOf(Part(videos.size, channels.size, playlists.size)) }
        var v = 0
        var c = 0
        var p = 0
        return ps.map { part ->
            TubePage(
                videos.subList(v, (v + part.videos).coerceAtMost(videos.size)),
                null,
                channels.subList(c, (c + part.channels).coerceAtMost(channels.size)),
                playlists.subList(p, (p + part.playlists).coerceAtMost(playlists.size)),
            ).also {
                v = (v + part.videos).coerceAtMost(videos.size)
                c = (c + part.channels).coerceAtMost(channels.size)
                p = (p + part.playlists).coerceAtMost(playlists.size)
            }
        }
    }

    companion object {
        /** [old] 아래에 [new] 를 붙인다 (이미 있는 것은 빼고) */
        fun append(old: TubePage, new: TubePage): TubePage {
            val v = new.videos.filter { n -> old.videos.none { it.id == n.id } }.distinctBy { it.id }
            val c = new.channels.filter { n -> old.channels.none { it.id == n.id } }.distinctBy { it.id }
            val p = new.playlists.filter { n -> old.playlists.none { it.id == n.id } }.distinctBy { it.id }
            val before = old.parts.ifEmpty { listOf(Part(old.videos.size, old.channels.size, old.playlists.size)) }
            return TubePage(
                videos = old.videos + v,
                next = new.next,
                channels = old.channels + c,
                playlists = old.playlists + p,
                parts = before + Part(v.size, c.size, p.size),
            )
        }
    }
}

object TubeLinks {
    private val ID = Regex("[A-Za-z0-9_-]{11}")
    private val CHANNEL = Regex("UC[A-Za-z0-9_-]{22}")
    /** 재생목록 번호: PL…(34자)·UU…(업로드)·OLAK5uy_…(앨범)·LL(좋아요) 등 */
    private val PLAYLIST = Regex("[A-Za-z0-9_-]{2,64}")

    fun isId(s: String?): Boolean = s != null && ID.matches(s)

    fun isChannelId(s: String?): Boolean = s != null && CHANNEL.matches(s)

    fun isPlaylistId(s: String?): Boolean = s != null && PLAYLIST.matches(s)

    /** 채널의 '업로드' 재생목록 = 채널 번호의 UC 를 UU 로 (채널 정보를 따로 받지 않고 최신 영상을 받을 때) */
    fun uploadsOf(channelId: String): String? = if (isChannelId(channelId)) "UU" + channelId.substring(2) else null

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
     * 폰에서 열 주소. [list] = 그 재생목록 속에서 (믹스보다 먼저), [mix] = 이 곡으로 시작하는 유튜브 '믹스' — 로그인된 폰 유튜브 앱이
     * 내 시청 기록으로 비슷한 곡을 끝없이 이어 준다 (평소 폰에서 듣던 그 알고리즘)
     */
    fun watchUrl(id: String, mix: Boolean, startSec: Int = 0, list: String? = null): String =
        when {
            isPlaylistId(list) -> "https://www.youtube.com/watch?v=$id&list=$list"
            mix -> "https://www.youtube.com/watch?v=$id&list=RD$id&start_radio=1"
            else -> "https://www.youtube.com/watch?v=$id"
        } + (if (startSec > 0) "&t=${startSec}s" else "")

    /**
     * YouTube Music 앱(휴대폰·워치)에서 열 주소 — 로그인된 내 계정(프리미엄)으로 튼다.
     * [mix] = 이 곡으로 시작하는 '노래 라디오'(RDAMVM): 내 계정의 추천으로 비슷한 곡이 끝없이 이어진다
     */
    fun musicUrl(id: String, mix: Boolean): String =
        if (mix) "https://music.youtube.com/watch?v=$id&list=RDAMVM$id" else "https://music.youtube.com/watch?v=$id"

    /** 이어 듣기에서 받아들이는 시각 (하루) — 그 밖이면 처음부터 */
    private const val MAX_START_SEC = 86_400

    /**
     * 재생 화면(웹 페이지)이 보낸 '폰에서 이어 듣기' 링크 kakaowatch://tube/phone?v=…&t=…&mix=1&title=…&channel=… 읽기.
     * 아무 웹 페이지나 보낼 수 있는 값이라 꼼꼼히: 모양이 다르거나 영상 번호가 이상하면 null, 시각이 이상하면 0, 글은 다듬어 자른다
     */
    fun handoff(link: String): Handoff? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("kakaowatch", ignoreCase = true) || uri.host != "tube" || uri.rawPath != "/phone") return null
        val id = param(uri.rawQuery, "v")?.takeIf(::isId) ?: return null
        val start = param(uri.rawQuery, "t")?.toIntOrNull()?.takeIf { it in 0..MAX_START_SEC } ?: 0
        val title = TubeText.clean(param(uri.rawQuery, "title"), 100)
        val channel = TubeText.clean(param(uri.rawQuery, "channel"), 40)
        val list = param(uri.rawQuery, "list")?.takeIf(::isPlaylistId)
        return Handoff(TubeVideo(id, title, channel), start, param(uri.rawQuery, "mix") == "1", list)
    }

    /** 폰 유튜브 앱의 검색 결과 (워치 검색이 안 될 때) */
    fun searchUrl(query: String): String = "https://www.youtube.com/results?search_query=" + encode(query.trim())

    /**
     * 미리보기 그림: 작은 것 320×180(mqdefault), [big] = 480×360(hqdefault — 위아래 검은 띠를 16:9 로 잘라 내면 480×270,
     * 화면 폭 카드(약 420px)에 흐리지 않게)
     */
    fun thumbnail(id: String, big: Boolean = false): String =
        if (big) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    private val REPO_PART = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

    /**
     * 워치 재생 화면(web/tube)이 올라가는 곳 = 이 저장소의 GitHub Pages (사용자 이름은 주소에서 소문자).
     * 저장소를 모르면(로컬 빌드) null — 워치 재생 없이 폰으로
     */
    fun playerBase(repo: String?): String? {
        val parts = repo?.trim()?.split('/') ?: return null
        if (parts.size != 2 || parts.any { !REPO_PART.matches(it) || it.contains("..") }) return null
        return "https://${parts[0].lowercase()}.github.io/${parts[1]}/tube/"
    }

    /** 이 앱의 개인정보 처리방침 (재생 화면과 같은 GitHub Pages — web/privacy.html). 저장소를 모르면 null */
    fun privacyUrl(repo: String?): String? = playerBase(repo)?.removeSuffix("tube/")?.plus("privacy.html")

    /** YouTube 서비스 약관 (유튜브 API 정책: 앱이 링크를 보여 줘야 함) */
    const val YOUTUBE_TERMS = "https://www.youtube.com/t/terms"

    /**
     * 워치 인터넷 앱으로 열 재생 화면 주소. 곡은 # 뒤에 — 브라우저가 서버로 보내지 않고 화면 안에서만 읽는다.
     * [mix] = 이 곡으로 시작하는 유튜브 믹스(유튜브가 비슷한 곡을 이어 줌). 제목·채널은 열리자마자 보여 줄 이름.
     * [nonce] = 고른 시각(ms). 같은 곡을 다시 골라도 주소가 달라지게 (같은 탭에서 주소가 똑같으면 화면이 그대로라 다시 틀지 않음),
     * 재생 화면은 이 시각이 최근인 것만 바로 튼다 (뒤로 가기로 온 예전 주소·며칠 뒤 되살아난 탭은 ▶ 를 기다림)
     */
    /**
     * 재생 화면 주소 — [queue] 로 무엇을 이어 틀지: 믹스(mix=1) · 앱의 목록 그대로(ids=다음 동영상들, 처음 것까지 50개) ·
     * 재생목록(list=). [inApp] = 워치 앱 안의 엔진으로 연다(app=1 — 아래 링크가 앱의 목록으로 돌아감)
     */
    fun playerUrl(base: String, video: TubeVideo, queue: Queue, nonce: Long? = null, inApp: Boolean = false): String = buildString {
        append(base).append("#v=").append(video.id)
        when (queue) {
            Queue.Mix -> append("&mix=1")
            Queue.Single -> Unit
            is Queue.Ids -> queue.next.filter { isId(it) && it != video.id }.distinct().take(MAX_QUEUE - 1)
                .takeIf { it.isNotEmpty() }?.let { append("&ids=").append(it.joinToString(",")) }
            is Queue.Playlist -> if (isPlaylistId(queue.id)) append("&list=").append(queue.id)
        }
        takeChars(video.title.trim(), MAX_TITLE).takeIf { it.isNotEmpty() }?.let { append("&t=").append(encode(it)) }
        takeChars(video.channel.trim(), MAX_CHANNEL).takeIf { it.isNotEmpty() }?.let { append("&c=").append(encode(it)) }
        if (nonce != null) append("&n=").append(nonce)
        if (inApp) append("&app=1")
    }

    /** 재생 화면이 한 번에 받는 목록 (처음 동영상 포함) */
    const val MAX_QUEUE = 50

    /** 글자 수로 자르기 — 이모지(두 칸짜리 글자)를 반으로 잘라 '?'가 되지 않게 */
    private fun takeChars(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

    private const val MAX_TITLE = 100
    private const val MAX_CHANNEL = 40

    /** URLEncoder 는 띄어쓰기를 + 로 바꾸는데, 검색어 속 진짜 + 와 헷갈리지 않게 %20 으로 */
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object TubeText {
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]{1,8}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val SPACES = Regex("\\s+")
    private val ISO = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")

    /**
     * 밖에서 받은 글: 줄바꿈·제어 문자를 띄어쓰기로, 글자 방향을 뒤집거나(U+202E 등) 안 보이게 끼우는 글자(U+200B·U+FEFF 등)는 빼고,
     * 띄어쓰기를 하나로, 글자 수(이모지도 한 글자)로 자르기. 이모지를 잇는 U+200C·U+200D 와 깃발 이모지의 태그 글자(U+E0020~E007F)는 둔다
     */
    fun clean(s: String?, max: Int): String {
        val sb = StringBuilder()
        s.orEmpty().codePoints().forEach { cp ->
            when {
                Character.getType(cp) == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D && cp !in 0xE0020..0xE007F -> Unit
                Character.isISOControl(cp) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        val one = SPACES.replace(sb, " ").trim()
        return if (one.codePointCount(0, one.length) <= max) one else one.substring(0, one.offsetByCodePoints(0, max)).trim()
    }

    /** 카카오 검색 결과 제목의 강조 태그(<b>)를 떼고 &amp;·&#39; 같은 글자를 되돌린다 */
    fun plain(s: String): String {
        val decoded = ENTITY.replace(TAG.replace(s, "")) { m -> decode(m.groupValues[1]) ?: m.value }
        return SPACES.replace(decoded, " ").trim()
    }

    /** 유튜브에서 받은 제목·이름의 최대 글자 수 (유튜브 재생목록 제목 한도) */
    const val MAX_TEXT = 150

    /**
     * 유튜브 Data API 의 글: search.list 는 &#39;·&amp;·&lt; 로 바꿔 보내고 다른 목록은 쓴 그대로 보낸다 → 글자 표시만 되돌린다.
     * 꺾쇠 속 글은 지우지 않는다 ('드라마 <도깨비> OST' — [plain] 은 태그로 보고 지움). 그다음 [clean] 으로 다듬는다
     */
    fun unescape(s: String?, max: Int = MAX_TEXT): String =
        clean(ENTITY.replace(s.orEmpty()) { m -> decode(m.groupValues[1]) ?: m.value }, max)

    /** 유튜브 앱처럼 줄인 수 (반올림 없이 버림): 999 · 1.2천 · 1.2만 · 12만 · 1234만 · 5.1억 · 12억 */
    fun compact(n: Long): String {
        if (n < 1_000) return n.coerceAtLeast(0).toString()
        val (unit, name) = when {
            n >= 100_000_000 -> 100_000_000L to "억"
            n >= 10_000 -> 10_000L to "만"
            else -> 1_000L to "천"
        }
        val whole = n / unit
        val tenth = n % unit * 10 / unit
        return if (whole >= 10 || tenth == 0L) "$whole$name" else "$whole.$tenth$name"
    }

    /** 조회수 1.2만회 (0 이면 '조회수 없음' — 유튜브 앱처럼) */
    fun views(n: Long): String = if (n <= 0) "조회수 없음" else "조회수 ${compact(n)}회"

    /** 구독자 120만명 */
    fun subscribers(n: Long): String = "구독자 ${compact(n)}명"

    /** 동영상 1.3만개 */
    fun videoCount(n: Long): String = "동영상 ${compact(n)}개"

    private const val DAY_MS = 86_400_000L

    /** 올린 때 → '방금 전 · 5분 전 · 3시간 전 · 6일 전 · 2주 전 · 11개월 전 · 8년 전'. 시계가 어긋나 미래면 '방금 전' */
    fun ago(ms: Long, now: Long): String {
        val diff = now - ms
        return when {
            diff < 60_000 -> "방금 전"
            diff < 3_600_000 -> "${diff / 60_000}분 전"
            diff < DAY_MS -> "${diff / 3_600_000}시간 전"
            diff < 7 * DAY_MS -> "${diff / DAY_MS}일 전"
            diff < 30 * DAY_MS -> "${diff / (7 * DAY_MS)}주 전"
            diff < 365 * DAY_MS -> "${(diff / (30 * DAY_MS)).coerceIn(1, 11)}개월 전"
            else -> "${diff / (365 * DAY_MS)}년 전"
        }
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

/**
 * 최근 본 영상 — 유튜브 API 로 받은 정보는 30일까지만 보관할 수 있어서 그보다 오래된 건 지운다.
 * 조회수·올린 때 같은 통계는 남기지 않는다 (볼 때마다 새로 받음). 채널 번호는 '채널로 가기'에 쓰려고 남긴다
 */
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
                p.video.channelId?.let { put("cid", it) }
                p.video.durationSec?.let { put("sec", it) }
                if (p.video.live) put("live", true)
                if (!p.video.embeddable) put("emb", false)
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
                    channelId = o.optString("cid").takeIf(TubeLinks::isChannelId),
                    embeddable = o.optBoolean("emb", true),
                ),
                o.optLong("at"),
            )
        }
    }
}
