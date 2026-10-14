package com.mywatch.kakaomap.tube

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.concurrent.futures.await
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.net.KakaoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 검색어·본 동영상 기록과 추천에 쓰는 것(믹스 추천·관심 없음·본 Shorts) — 워치 안에만 저장 */
class TubeState(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tube", Context.MODE_PRIVATE)

    val searches: List<String> get() = SearchHistory.decode(prefs.getString(K_SEARCHES, null))

    fun addSearch(query: String) = saveSearches(SearchHistory.add(searches, query))

    fun removeSearch(query: String) = saveSearches(SearchHistory.remove(searches, query))

    private fun saveSearches(list: List<String>) = prefs.edit { putString(K_SEARCHES, SearchHistory.encode(list)) }

    /**
     * 최근 들은 곡. 30일이 지난 곡은 보여 주지 않을 뿐 아니라 저장소에서도 지운다
     * (유튜브 API 로 받은 정보는 30일까지만 보관)
     */
    fun played(nowMs: Long = System.currentTimeMillis()): List<Played> = synchronized(LOCK) {
        val all = PlayedHistory.decodeAll(prefs.getString(K_PLAYED, null))
        val kept = PlayedHistory.prune(all, nowMs).take(PlayedHistory.MAX)
        if (kept.size != all.size) prefs.edit { putString(K_PLAYED, PlayedHistory.encode(kept)) }
        kept
    }

    fun lastPlayed(nowMs: Long = System.currentTimeMillis()): TubeVideo? = played(nowMs).firstOrNull()?.video

    fun addPlayed(video: TubeVideo, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        prefs.edit { putString(K_PLAYED, PlayedHistory.encode(PlayedHistory.add(played(nowMs), video, nowMs))) }
    }

    fun removePlayed(id: String, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        prefs.edit { putString(K_PLAYED, PlayedHistory.encode(played(nowMs).filterNot { it.video.id == id })) }
    }

    /** 받아 둔 믹스 추천 (새것부터 · 7일 · 12개). 지난 것은 보여 주지 않을 뿐 아니라 저장소에서도 지운다 */
    fun harvests(nowMs: Long = System.currentTimeMillis()): List<Harvest> = synchronized(LOCK) {
        val all = MixHarvests.decode(prefs.getString(K_HARVESTS, null))
        val kept = MixHarvests.prune(all, nowMs).take(MixHarvests.MAX)
        if (kept.size != all.size) prefs.edit { putString(K_HARVESTS, MixHarvests.encode(kept)) }
        kept
    }

    /** 재생 화면이 받은 믹스 하나를 받아 둔다 (같은 씨앗은 바꾼다) */
    fun addHarvest(h: Harvest, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        prefs.edit { putString(K_HARVESTS, MixHarvests.encode(MixHarvests.add(harvests(nowMs), h, nowMs))) }
    }

    /** '관심 없음' 동영상 (새것부터 · 30일) — 홈에서 뺀다 */
    fun dismissedVideos(nowMs: Long = System.currentTimeMillis()): Set<String> = ids(K_DISMISSED_VIDEOS, nowMs, TubeLinks::isId)

    /** 동영상 번호만 받는다 */
    fun dismissVideo(id: String, nowMs: Long = System.currentTimeMillis()) {
        if (TubeLinks.isId(id)) mark(K_DISMISSED_VIDEOS, id, nowMs)
    }

    /** '채널 추천 안 함' 채널 (새것부터 · 30일) — 홈에서 뺀다 */
    fun dismissedChannels(nowMs: Long = System.currentTimeMillis()): Set<String> = ids(K_DISMISSED_CHANNELS, nowMs, TubeLinks::isChannelId)

    /** 채널 번호(UC…)만 받는다 */
    fun dismissChannel(id: String, nowMs: Long = System.currentTimeMillis()) {
        if (TubeLinks.isChannelId(id)) mark(K_DISMISSED_CHANNELS, id, nowMs)
    }

    /** 본 Shorts (새것부터 · 30일) — 다음엔 뒤로 미룬다 */
    fun seenShorts(nowMs: Long = System.currentTimeMillis()): Set<String> = ids(K_SEEN_SHORTS, nowMs, TubeLinks::isId)

    /** 동영상 번호만 받는다 */
    fun markSeen(id: String, nowMs: Long = System.currentTimeMillis()) {
        if (TubeLinks.isId(id)) mark(K_SEEN_SHORTS, id, nowMs)
    }

    /**
     * 재생 화면이 보낸 것([TubeInbox])을 적용 — 믹스 추천(recs)은 받아 두고 본 Shorts(seen)는 표시한다. 그 밖은 버린다.
     * 바뀐 것이 있으면 true
     */
    fun ingest(links: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val mixes = links.mapNotNull { TubeLinks.recs(it, nowMs) }
        val seen = links.mapNotNull { TubeLinks.seen(it) }
        if (mixes.isNotEmpty()) {
            val list = mixes.fold(harvests(nowMs)) { acc, h -> MixHarvests.add(acc, h, nowMs) }
            prefs.edit { putString(K_HARVESTS, MixHarvests.encode(list)) }
        }
        if (seen.isNotEmpty()) {
            val list = seen.fold(marks(K_SEEN_SHORTS, nowMs)) { acc, id -> Marks.add(acc, id, nowMs) }
            prefs.edit { putString(K_SEEN_SHORTS, Marks.encode(list)) }
        }
        mixes.isNotEmpty() || seen.isNotEmpty()
    }

    /** [key] 의 표시 목록 — 30일이 지난 것은 저장소에서도 지운다 */
    private fun marks(key: String, nowMs: Long): List<Mark> = synchronized(LOCK) {
        val all = Marks.decode(prefs.getString(key, null))
        val kept = Marks.prune(all, nowMs).take(Marks.MAX)
        if (kept.size != all.size) prefs.edit { putString(key, Marks.encode(kept)) }
        kept
    }

    private fun ids(key: String, nowMs: Long, valid: (String) -> Boolean): Set<String> =
        marks(key, nowMs).mapNotNullTo(LinkedHashSet()) { m -> m.id.takeIf(valid) }

    private fun mark(key: String, id: String, nowMs: Long) = synchronized(LOCK) {
        prefs.edit { putString(key, Marks.encode(Marks.add(marks(key, nowMs), id, nowMs))) }
    }

    /**
     * YouTube 약관·이 앱의 개인정보 처리방침에 동의했는지 (유튜브 API 정책: 앱을 쓰기 전에 동의).
     * 처리방침이 크게 바뀌면 [TERMS_VERSION] 을 올려 다시 묻는다 — 판 2 는 워치 안 기록(본 동영상·믹스 추천·관심 없음·본 Shorts)을
     * 추천에 쓴다고 알린 것이라 판 1 에 동의한 사람에게도 다시 묻는다. '기록 지우기'로는 지워지지 않는다
     */
    val agreed: Boolean get() = prefs.getInt(K_AGREED, 0) >= TERMS_VERSION

    fun agree() = prefs.edit { putInt(K_AGREED, TERMS_VERSION) }

    /**
     * '기록 지우기' — 본 동영상·검색어·믹스 추천·관심 없음(동영상·채널)·본 Shorts, 아직 읽지 않은 재생 화면의 알림([TubeInbox])까지
     * (예전 버전의 '듣는 곳' 설정도 함께 정리)
     */
    fun clear() {
        synchronized(LOCK) {
            prefs.edit {
                remove(K_SEARCHES)
                remove(K_PLAYED)
                remove(K_TARGET)
                remove(K_HARVESTS)
                remove(K_DISMISSED_VIDEOS)
                remove(K_DISMISSED_CHANNELS)
                remove(K_SEEN_SHORTS)
            }
        }
        TubeInbox.of(app).drain()
    }

    /** 오늘(유튜브 기준 날짜) 유튜브 검색 한도를 다 썼으면 카카오 검색만 쓴다 — 실패할 요청을 계속 보내지 않게 */
    fun youtubeBlocked(day: String = quotaDay(Instant.now()).toString()): Boolean = prefs.getString(K_YT_BLOCKED, null) == day

    fun blockYoutube(day: String = quotaDay(Instant.now()).toString()) = prefs.edit { putString(K_YT_BLOCKED, day) }

    companion object {
        private const val K_SEARCHES = "searches"
        private const val K_PLAYED = "played"
        private const val K_YT_BLOCKED = "youtube_blocked_day"
        private const val K_HARVESTS = "mix_harvests"
        private const val K_DISMISSED_VIDEOS = "dismissed_videos"
        private const val K_DISMISSED_CHANNELS = "dismissed_channels"
        private const val K_SEEN_SHORTS = "seen_shorts"
        private const val K_AGREED = "terms_agreed"
        /** 동의받는 처리방침의 판 — 1: 처음 · 2: 워치 안 기록으로 추천 (2026-10) */
        private const val TERMS_VERSION = 2
        /** 예전 버전의 '듣는 곳' (지금은 쓰지 않음 — 기록 지우기 때 정리) */
        private const val K_TARGET = "target"
        private val LOCK = Any()

        /** 유튜브 API 하루 한도는 미국 태평양 시간 자정에 다시 채워진다 */
        fun quotaDay(now: Instant): LocalDate = now.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()
    }
}

/** 폰에서 유튜브 링크 열기 — 폰의 유튜브 앱(로그인된 내 계정)이 받아서 재생한다 */
class PhoneOpener(context: Context) {

    enum class Result { OPENED, NO_PHONE, FAILED }

    private val app = context.applicationContext

    /** 한 번 만들어 계속 쓴다 (만들 때마다 작업 스레드가 하나씩 생김). 만들다 실패하면 null */
    private val helper: RemoteActivityHelper? by lazy {
        try {
            RemoteActivityHelper(app)
        } catch (e: Exception) {
            ErrorLog.record("유튜브·폰에서 열기", e)
            null
        }
    }

    /**
     * 누르자마자 보낸다 — Wear OS 6+ 는 앱이 앞에 보일 때 보낸 요청만 받으므로 미리 확인하느라 기다리지 않는다.
     * 무슨 일이 있어도 예외를 밖으로 내지 않는다(같은 프로세스의 길안내까지 꺼지지 않게). 취소만 그대로 전한다
     */
    suspend fun open(url: String): Result {
        override?.let { return it(url) }
        val h = helper ?: return Result.FAILED
        try {
            withTimeout(15_000) { h.startRemoteActivity(intent(url)).await() }
            return Result.OPENED
        } catch (e: TimeoutCancellationException) {
            ErrorLog.record("유튜브·폰에서 열기", "폰이 15초 안에 응답하지 않음")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.record("유튜브·폰에서 열기", e)
        }
        // 실패했을 때만: 폰과 연결이 끊긴 건지 확인해서 알맞게 알린다 (예전 워치는 '모름'만 알려 줌)
        val status = try {
            withTimeoutOrNull(1_500) { h.availabilityStatus.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (status == RemoteActivityHelper.STATUS_UNAVAILABLE || status == RemoteActivityHelper.STATUS_TEMPORARILY_UNAVAILABLE) {
            Result.NO_PHONE
        } else {
            Result.FAILED
        }
    }

    companion object {
        /** 테스트·시뮬레이션에서 폰 대신 (폰이 연 것처럼/폰이 없는 것처럼) */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var override: (suspend (String) -> Result)? = null

        /** 원격 실행은 '열 수 있는 링크'(VIEW + BROWSABLE + 주소)만 받는다 */
        fun intent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}

/**
 * 유튜브 데이터 (네트워크). 검색의 순서·대체 판단은 [TubeSearcher], 묻는 방법은 [YouTubeData]. 백그라운드 스레드에서 부른다.
 */
object TubeApi {

    /** 테스트·시뮬레이션에서 검색 대신 (결과·오류를 마음대로) */
    @androidx.annotation.VisibleForTesting
    @Volatile internal var override: ((query: String, next: String?, source: TubeSource?) -> TubeResult)? = null

    /** [next]·[source] = '더 보기'일 때 앞 결과의 다음 쪽과 출처 (같은 곳에서 이어서) */
    fun search(context: Context, query: String, next: String? = null, source: TubeSource? = null): TubeResult {
        override?.let { return it(query, next, source) }
        val state = TubeState(context)
        val data = data(context)
        return TubeSearcher(
            youtubeKey = BuildConfig.YOUTUBE_API_KEY,
            youtubeBlocked = { state.youtubeBlocked() },
            blockYoutube = { state.blockYoutube() },
            youtube = { q, token -> data.search(q, token) },
            kakao = { q, page -> KakaoApi.videos(q, page) },
            log = { where, e -> ErrorLog.record(where, e) },
            details = { ids -> data.details(ids) },
        ).search(query, next, source)
    }

    /**
     * 유튜브 Data API — 키 + 이 앱의 서명 머리글(키를 'Android 앱'으로 제한해 둔 경우),
     * 내 데이터는 로그인 토큰 ([TubeAccount], 거절되면 새로 받게)
     */
    fun data(context: Context): YouTubeData {
        val app = context.applicationContext
        val auth = TubeAccount.auth(app)
        return YouTubeData(
            key = BuildConfig.YOUTUBE_API_KEY,
            token = { auth.accessToken() },
            get = { url, headers -> Http.get(url, appHeaders(app) + headers) },
            onUnauthorized = { auth.invalidateAccess() },
        )
    }

    /**
     * 키를 'Android 앱'으로 제한해 둔 경우 구글이 확인하는 값 (패키지 이름 + 서명 인증서 SHA-1, 구글 예제처럼 소문자 16진수).
     * 키를 'YouTube Data API v3 만'으로 제한하는 게 핵심이고, 이건 덤 (패키지·SHA-1 은 공개라 흉내 낼 수는 있음)
     */
    private fun appHeaders(context: Context): Map<String, String> {
        val sha1 = signingSha1(context) ?: return emptyMap()
        return mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to sha1)
    }

    @Volatile private var cachedSha1: String? = null

    private fun signingSha1(context: Context): String? = cachedSha1 ?: runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()?.also { cachedSha1 = it }
}
