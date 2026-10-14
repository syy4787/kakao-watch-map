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

/** 검색어·본 동영상 기록 (워치 안에만 저장) */
class TubeState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tube", Context.MODE_PRIVATE)

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

    /** '기록 지우기' — 본 동영상·검색어 (예전 버전의 '듣는 곳' 설정도 함께 정리) */
    fun clear() = synchronized(LOCK) {
        prefs.edit {
            remove(K_SEARCHES)
            remove(K_PLAYED)
            remove(K_TARGET)
        }
    }

    /** 오늘(유튜브 기준 날짜) 유튜브 검색 한도를 다 썼으면 카카오 검색만 쓴다 — 실패할 요청을 계속 보내지 않게 */
    fun youtubeBlocked(day: String = quotaDay(Instant.now()).toString()): Boolean = prefs.getString(K_YT_BLOCKED, null) == day

    fun blockYoutube(day: String = quotaDay(Instant.now()).toString()) = prefs.edit { putString(K_YT_BLOCKED, day) }

    companion object {
        private const val K_SEARCHES = "searches"
        private const val K_PLAYED = "played"
        private const val K_YT_BLOCKED = "youtube_blocked_day"
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
