package com.mywatch.kakaomap.tube

import android.content.Context
import android.content.Intent
import android.provider.Browser
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 워치에서 바로 듣기: 워치의 인터넷 앱(삼성 인터넷)으로 둥근 화면용 재생 화면(web/tube, GitHub Pages)을 연다.
 * 재생은 그 화면 안의 유튜브 공식 플레이어(임베드)가 한다 — 우리 앱이 영상·소리를 직접 받지 않는다 (유튜브 약관).
 * 삼성 인터넷으로 유튜브 사이트를 그대로 열면 폰 화면용이라 둥근 화면 밖으로 삐져나가서, 우리 화면 안에 플레이어만 넣었다.
 * 워치에 링크를 열 앱이 없거나 재생 화면에 닿지 못하면 지금처럼 폰 유튜브로 보낸다.
 */
object WatchPlayer {

    /** 재생 화면 주소의 바탕 (저장소를 모르는 로컬 빌드는 null) */
    @VisibleForTesting
    internal var base: String? = TubeLinks.playerBase(BuildConfig.UPDATE_REPO)

    /** 재생 화면이 열리는지 확인 (테스트에선 바꿔 끼운다) */
    @VisibleForTesting
    internal var probe: suspend (String) -> Boolean = ::fetchOk

    /** 한 번 열리면 이때까지는 다시 묻지 않는다 */
    @VisibleForTesting
    @Volatile internal var okUntil = 0L

    /** 안 열리면 이때까지는 묻지 않고 바로 폰으로 (누를 때마다 기다리지 않게) */
    @VisibleForTesting
    @Volatile internal var failUntil = 0L

    /** 확인은 한 번에 하나 — 화면을 열 때 미리 확인하는 사이 누르면 그 결과를 기다린다 */
    private val lock = Mutex()

    /** 확인이 실패한 횟수 — 기다리는 사이 앞의 확인이 실패했는지 알려고 */
    @Volatile private var failures = 0L

    private const val OK_FOR_MS = 30 * 60_000L
    private const val FAIL_FOR_MS = 2 * 60_000L
    /** 연결·읽기 각각 (HttpURLConnection 은 코루틴 취소로 끊기지 않으므로 여기서 짧게) */
    private const val PROBE_MS = 3_000
    /** 재생 화면에 넣어 둔 표시 — 와이파이 로그인 화면 등이 200 을 줘도 속지 않게 */
    private const val MARKER = "kakaowatch-player"

    @VisibleForTesting
    internal fun reset() {
        base = TubeLinks.playerBase(BuildConfig.UPDATE_REPO)
        probe = ::fetchOk
        okUntil = 0L
        failUntil = 0L
    }

    /** 재생 화면 주소의 바탕 (앱 안 재생 화면이 제 주소인지 확인할 때) */
    fun pageBase(): String? = base

    /** 이 곡을 워치 재생 화면으로 여는 링크. 저장소를 모르면 null */
    fun intent(context: Context, video: TubeVideo, mix: Boolean, nowMs: Long = System.currentTimeMillis()): Intent? {
        val b = base ?: return null
        return link(context, TubeLinks.playerUrl(b, video, mix, nonce = nowMs))
    }

    private fun link(context: Context, url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        // 인터넷 앱은 제 작업(task)에서 — 타일에서 틀고 이 앱을 닫아도 재생 화면이 같이 닫히지 않게
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 같은 앱이 연 링크는 인터넷 앱이 같은 탭에서 연다 — 곡을 고를 때마다 탭이 쌓이지 않게
        .putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)

    /** 재생 화면 주소를 그대로 인터넷 앱으로 (앱 안 엔진이 멈췄을 때 이어서). 열 앱이 없으면 null */
    fun browserIntent(context: Context, url: String): Intent? =
        link(context, url).takeIf { runCatching { it.resolveActivity(context.packageManager) != null }.getOrDefault(false) }

    /** 워치에 https 링크를 열 앱(삼성 인터넷 등)이 있고 재생 화면 주소를 안다 */
    fun available(context: Context): Boolean {
        val b = base ?: return false
        return runCatching { link(context, b).resolveActivity(context.packageManager) != null }.getOrDefault(false)
    }

    /**
     * 재생 화면에 닿는지 (GitHub Pages 가 꺼졌거나 인터넷이 안 되면 false → 폰으로).
     * 되면 30분, 안 되면 2분 동안은 다시 묻지 않는다
     */
    suspend fun reachable(nowMs: Long = System.currentTimeMillis()): Boolean = check(nowMs, rememberFailure = true)

    /**
     * 유튜브 화면을 열 때 미리 확인해 둔다 — 곡을 누를 때 기다리지 않게. 되면 기억하고, 안 되면 기억하지 않는다
     * (워치가 막 깨어 인터넷이 다시 잡히는 중일 수 있으니 누를 때 다시 확인)
     */
    suspend fun prewarm(nowMs: Long = System.currentTimeMillis()) {
        check(nowMs, rememberFailure = false)
    }

    /** 미리 확인할 게 있는지: 인터넷 앱이 있고, 지금 확인 중이 아니고, 아직 모름 */
    fun needsCheck(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        !lock.isLocked && nowMs >= okUntil && nowMs >= failUntil && available(context)

    // 같은 확인을 두 번 하지 않게 하나씩 — 앞의 확인이 끝나면 그 결과(기억)를 본다
    private suspend fun check(nowMs: Long, rememberFailure: Boolean): Boolean {
        val seen = failures
        return lock.withLock { checkLocked(nowMs, rememberFailure, seen) }
    }

    private suspend fun checkLocked(nowMs: Long, rememberFailure: Boolean, seen: Long): Boolean {
        if (nowMs < okUntil) return true
        if (nowMs < failUntil) return false
        // 기다리는 사이 앞의 확인(화면을 열 때 미리 한 것)이 방금 실패했다 — 같은 순간의 인터넷이니 또 기다리게 하지 않는다
        if (failures != seen) return false
        val b = base ?: return false
        val ok = try {
            withTimeoutOrNull(PROBE_MS * 3L) { probe(b) } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            okUntil = nowMs + OK_FOR_MS
        } else {
            failures++
            if (rememberFailure) failUntil = nowMs + FAIL_FOR_MS
        }
        return ok
    }

    private suspend fun fetchOk(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = Http.get(url, timeoutMs = PROBE_MS)
            val ok = res.code == 200 && res.body.contains(MARKER)
            if (!ok) ErrorLog.record("유튜브·워치 재생 화면", "열리지 않음 (${res.code})")
            ok
        } catch (e: Exception) {
            ErrorLog.record("유튜브·워치 재생 화면", e)
            false
        }
    }
}
