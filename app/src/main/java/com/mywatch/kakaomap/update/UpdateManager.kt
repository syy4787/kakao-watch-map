package com.mywatch.kakaomap.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest

/** GitHub Releases 에 올라간 update.json 내용 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    /** 받을 APK 크기 (바이트). 예전 update.json 엔 없다 */
    val sizeBytes: Long? = null,
    /** 이전 버전 APK 에서 이 APK 를 만드는 작은 업데이트들 (워치에 깔린 APK 와 [Patch.from] 이 같은 것만 쓸 수 있다) */
    val patches: List<Patch> = emptyList(),
) {
    /** 작은 업데이트(.kwdelta) — [from] = 이 패치를 적용할 수 있는 APK 의 SHA-256 */
    data class Patch(val from: String, val url: String, val sha256: String, val size: Long)

    fun toJson(): String = JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName)
        .put("apkUrl", apkUrl).put("sha256", sha256).put("notes", notes)
        .apply { sizeBytes?.let { put("size", it) } }
        .apply { if (patches.isNotEmpty()) put("patches", patchesJson(patches)) }
        .toString()

    companion object {
        /**
         * [abis] = 이 기기가 돌릴 수 있는 CPU 종류(선호 순). update.json 의 "apks" 에 그 CPU 전용 APK 가 있으면
         * 그것을(작다), 없으면 모든 CPU 용 apkUrl 을 고른다. 예전 update.json(apks 없음)도 그대로 읽힌다.
         */
        fun parse(text: String, abis: List<String> = deviceAbis()): UpdateInfo {
            val o = JSONObject(text)
            val apks = o.optJSONObject("apks")
            val own = abis.firstNotNullOfOrNull { abi ->
                apks?.optJSONObject(abi)?.takeIf { it.optString("url").isNotBlank() && it.optString("sha256").isNotBlank() }
            }
            return UpdateInfo(
                versionCode = o.getLong("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = own?.getString("url") ?: o.getString("apkUrl"),
                sha256 = (own?.getString("sha256") ?: o.getString("sha256")).lowercase(),
                notes = o.optString("notes"),
                sizeBytes = (own ?: o).optLong("size", -1).takeIf { it > 0 },
                // 이 CPU 용 APK 의 작은 업데이트들 (기억해 둔 것을 다시 읽을 땐 맨 위에)
                patches = parsePatches((own ?: o).optJSONArray("patches")),
            )
        }

        private fun parsePatches(a: org.json.JSONArray?): List<Patch> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                val from = p.optString("from").lowercase()
                val url = p.optString("url")
                val sha = p.optString("sha256").lowercase()
                val size = p.optLong("size", -1)
                if (from.length != 64 || sha.length != 64 || url.isBlank() || size <= 0) null else Patch(from, url, sha, size)
            }
        }

        private fun patchesJson(list: List<Patch>) = org.json.JSONArray().apply {
            list.forEach { put(JSONObject().put("from", it.from).put("url", it.url).put("sha256", it.sha256).put("size", it.size)) }
        }

        private fun deviceAbis(): List<String> =
            runCatching { Build.SUPPORTED_ABIS?.toList() }.getOrNull().orEmpty()
    }
}

/**
 * 자체 업데이트: GitHub 최신 릴리스 확인 → APK 다운로드 → SHA-256·서명 확인 → PackageInstaller 로 설치.
 * 설치되는 APK 가 지금 앱과 같은 키로 서명돼 있어야만 진행한다(안드로이드도 다르면 거부함).
 */
object UpdateManager {
    /** GitHub 저장소 이름 (아이디/kakao-watch-map) */
    const val REPO_NAME = "kakao-watch-map"
    const val GITHUB = "https://github.com"

    /** 릴리스를 받는 곳 — 테스트에서만 가짜 서버로 바꾼다 */
    @VisibleForTesting
    internal var baseUrl = GITHUB
    private const val PREFS = "updates"
    private const val KEY_OWNER = "owner"
    private const val KEY_LATEST = "latest"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_ERROR = "error"
    private const val KEY_PENDING = "pending_user_action"
    private const val KEY_BAD_SHA = "bad_sha"
    private const val KEY_BAD_SHA_COUNT = "bad_sha_count"
    private const val KEY_WAITING = "waiting_since"
    private const val KEY_INSTALLED_SHA = "installed_sha"
    private const val KEY_PATCH_BAD = "patch_bad"

    /** 받은 파일이 update.json 의 SHA-256 과 계속 다르면 같은 릴리스는 자동으로 이만큼만 받아 본다 (밖에선 폰 데이터라) */
    private const val MAX_AUTO_CORRUPT = 2

    /** 지금 APK 를 받는 중인지 (자동 업데이트든 '지금 업데이트'든) — 화면에 '자동 업데이트가 받는 중'을 보이려고 */
    @Volatile
    var downloading = false
        private set

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 워치에서 GitHub 아이디를 입력했으면 그것을, 아니면 빌드에 넣어 둔 저장소를 쓴다 */
    fun repo(c: Context): String? =
        prefs(c).getString(KEY_OWNER, null)?.takeIf { it.isNotBlank() }?.let { "$it/$REPO_NAME" }
            ?: BuildConfig.UPDATE_REPO.ifBlank { null }

    fun setOwner(c: Context, owner: String) {
        val clean = owner.trim().removePrefix("@").substringBefore('/').filter { it.isLetterOrDigit() || it == '-' }
        prefs(c).edit { putString(KEY_OWNER, clean) }
    }

    fun currentVersionCode(c: Context): Long = packageInfo(c, 0).longVersionCode

    fun currentVersionName(c: Context): String = packageInfo(c, 0).versionName ?: "?"

    fun latest(c: Context): UpdateInfo? =
        prefs(c).getString(KEY_LATEST, null)?.let { runCatching { UpdateInfo.parse(it) }.getOrNull() }

    fun lastCheckAt(c: Context): Long = prefs(c).getLong(KEY_LAST_CHECK, 0)

    fun lastError(c: Context): String? = prefs(c).getString(KEY_ERROR, null)

    fun hasUpdate(c: Context): Boolean = latest(c)?.let { it.versionCode > currentVersionCode(c) } == true

    /** 이 앱이 스스로 설치할 수 있는지 (워치에선 처음 한 번 ADB 로 허용해 줘야 함) */
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    // ───────────────── 확인 ─────────────────

    /** 네트워크 사용 — 백그라운드에서 호출. 새 버전이 있으면 반환 */
    @Throws(IOException::class)
    fun check(c: Context): UpdateInfo? {
        val repo = repo(c) ?: return null
        val url = "$baseUrl/$repo/releases/latest/download/update.json"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            prefs(c).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            if (code == 404) throw IOException("아직 릴리스가 없어요 ($repo)")
            if (code !in 200..299) throw IOException("업데이트 정보를 받지 못했어요 ($code)")
            val info = UpdateInfo.parse(conn.inputStream.bufferedReader().use { it.readText() })
            prefs(c).edit { putString(KEY_LATEST, info.toJson()).remove(KEY_ERROR) }
            if (info.versionCode > currentVersionCode(c)) {
                // 작은 업데이트를 쓸 수 있는지 미리 알아 둔다 (깔린 APK 의 SHA-256 — 화면이 크기를 바로 보여 주게)
                if (info.patches.isNotEmpty()) installedApkSha(c)
                return info
            }
            // 이미 최신 → 설치하고 남은 APK(십수 MB)는 워치 저장공간만 차지하므로 지우고, 큰 업데이트 기다림도 끝
            prefs(c).edit { remove(KEY_WAITING) }
            synchronized(downloadLock) { cacheDir(c).listFiles()?.forEach { it.delete() } }
            return null
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────── 다운로드 ─────────────────

    private val downloadLock = Any()

    /**
     * 자동 업데이트와 화면의 '지금 업데이트'가 동시에 같은 파일을 받지 않게 한 번에 하나씩.
     * 워치 Wi-Fi 를 [wifiWaitMs] 까지 기다려 보고([UpdateNet]) 오면 그쪽으로, 아니면 늘 쓰던 망(폰 블루투스 등)으로.
     * [wifiOnly] 면 Wi-Fi 로만 — 없거나 받다 끊기면 [NeedsWifi] (받은 데까지는 남겨 두고 다음에 이어받기).
     * [cancelled] 가 true 가 되면(작업이 멈춰짐·화면이 닫힘) 64KB 안에 멈추고 [Cancelled] — 받은 데까지는 남기고 Wi-Fi 는 놓아준다
     */
    @Throws(IOException::class)
    fun download(
        c: Context,
        info: UpdateInfo,
        wifiWaitMs: Long = WIFI_WAIT_MS,
        wifiOnly: Boolean = false,
        cancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): File =
        synchronized(downloadLock) {
            downloading = true
            try {
                if (cancelled()) throw Cancelled()
                downloadLocked(c, info, wifiWaitMs, wifiOnly, cancelled, onProgress)
            } finally {
                downloading = false
            }
        }

    /** 큰 업데이트를 Wi-Fi 로만 받으려는데 Wi-Fi 가 없음 — 실패가 아니라 기다림 */
    class NeedsWifi : IOException("워치 Wi-Fi 를 기다려요")

    /** 받기를 맡긴 쪽이 멈춤 — 실패가 아니라 다음에 이어받기 */
    class Cancelled : IOException("업데이트 받기를 멈췄어요")

    /** 이보다 큰 APK 는 자동 업데이트가 워치 Wi-Fi 에서만 받는다 (재생 엔진이 들어 있어 약 105MB — 폰 데이터로 매번 받지 않게) */
    const val BIG_BYTES = 30L * 1024 * 1024

    /** 큰 업데이트가 이만큼 Wi-Fi 를 못 만나면 그때는 폰으로라도 받는다 (업데이트가 한없이 밀리지 않게) */
    const val WIFI_PATIENCE_MS = 2 * 24 * 3_600_000L

    /** 크기를 모르면(예전 update.json) 크다고 본다 */
    fun isBig(info: UpdateInfo): Boolean = (info.sizeBytes ?: Long.MAX_VALUE) > BIG_BYTES

    /**
     * 자동 업데이트가 큰 업데이트를 처음 기다리기 시작한 때 — 버전과 상관없이 (새 버전이 하루에도 여러 번 나오므로
     * 버전마다 새로 세면 Wi-Fi 없는 워치는 영영 못 받는다). 최신이 되면 [check] 가 지운다.
     * 예전 앱이 남긴 "버전:시각" 도 시각만 쓰고, 미래 시각(시계 변경 등)은 믿지 않는다
     */
    private fun waitingSince(c: Context, now: Long): Long? {
        val p = prefs(c)
        val raw = p.getString(KEY_WAITING, null) ?: return null
        val saved = raw.substringAfterLast(':').toLongOrNull()
        if (saved != null && saved <= now) return saved
        // 미래 시각(시계 변경 등)·읽을 수 없는 값은 믿지 않고 지금부터 다시 센다
        p.edit { putString(KEY_WAITING, now.toString()) }
        return now
    }

    /**
     * 큰 업데이트를 정말로 Wi-Fi 를 기다리기 시작함 (Wi-Fi 가 없어 받지 못하고 미룰 때) — 이미 세고 있으면 그대로.
     * 작은 업데이트(패치)로 받은 릴리스는 기다린 것이 아니므로 세지 않는다
     */
    private fun startWaitingForWifi(c: Context) {
        val now = clock()
        if (waitingSince(c, now) == null) prefs(c).edit { putString(KEY_WAITING, now.toString()) }
    }

    /**
     * 자동 업데이트가 Wi-Fi 에서만 받아야 하는지: 큰 업데이트이고 아직 이틀이 안 됨 ('지금 업데이트'는 언제나 어디서든).
     * 아직 기다리기 시작하지 않았으면 Wi-Fi 로만 (작은 업데이트가 있으면 그것은 어디서든 받는다)
     */
    fun autoNeedsWifi(c: Context, info: UpdateInfo, now: Long = clock()): Boolean {
        if (!isBig(info)) return false
        val since = waitingSince(c, now) ?: return true
        return now - since < WIFI_PATIENCE_MS
    }

    /** 지금 시각 (시험에선 바꿔 끼운다) */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /** 워치가 저장된 Wi-Fi 를 켜고 붙는 데 걸리는 시간만큼은 기다려 준다 (찾고·붙고·주소 받기 몇 초) */
    const val WIFI_WAIT_MS = 10_000L

    /** '지금 업데이트'(사람이 기다림)는 조금만 */
    const val MANUAL_WIFI_WAIT_MS = 6_000L

    /**
     * 자동 업데이트가 이 릴리스를 (다시) 받아도 되는지 — 받을 때마다 손상된 파일이면(update.json 과 다름)
     * 같은 릴리스는 [MAX_AUTO_CORRUPT] 번까지만. '지금 업데이트'는 언제든 받고, 새 릴리스가 나오면 다시 자동으로
     */
    fun autoDownloadAllowed(c: Context, info: UpdateInfo): Boolean {
        val p = prefs(c)
        return p.getString(KEY_BAD_SHA, null) != info.sha256 || p.getInt(KEY_BAD_SHA_COUNT, 0) < MAX_AUTO_CORRUPT
    }

    private fun noteCorrupt(c: Context, sha: String) {
        val p = prefs(c)
        val n = if (p.getString(KEY_BAD_SHA, null) == sha) p.getInt(KEY_BAD_SHA_COUNT, 0) + 1 else 1
        p.edit { putString(KEY_BAD_SHA, sha).putInt(KEY_BAD_SHA_COUNT, n) }
    }

    /** 받을 APK 의 저장 이름 — 같은 버전이라도 내용(SHA-256)이 다르면 다른 파일이라 이어받기가 섞이지 않는다 */
    fun cacheFileName(info: UpdateInfo): String =
        "app-${info.versionCode}-${info.sha256.filter { it in '0'..'9' || it in 'a'..'f' }.take(16)}.apk"

    private fun cacheDir(c: Context) = File(c.cacheDir, "update")

    private fun downloadLocked(
        c: Context,
        info: UpdateInfo,
        wifiWaitMs: Long,
        wifiOnly: Boolean,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): File {
        val dir = cacheDir(c).apply { mkdirs() }
        val name = cacheFileName(info)
        val patch = usablePatch(c, info)?.takeIf { it.size <= BIG_BYTES || !wifiOnly }
        val patchName = patch?.let { patchFileName(it) }
        // 다른 버전·다른 APK 의 옛 파일은 지운다 (이 버전의 받다 만 작은 업데이트는 남겨 이어받기)
        dir.listFiles()?.filter { it.name != name && it.name != patchName }?.forEach { it.delete() }
        val file = File(dir, name)
        if (file.exists() && sha256(file) == info.sha256) return file
        if (patch != null) {
            // 작은 업데이트: 깔린 APK + 바뀐 부분만 받아 새 APK 를 똑같이 다시 만든다 (105MB 대신 몇 MB — Wi-Fi 없이도)
            try {
                return viaPatch(c, info, patch, File(dir, patchName!!), file, cancelled, onProgress)
            } catch (e: DeltaPatch.BadPatch) {
                // 깨졌거나 이 워치의 APK 와 안 맞음 → 전체 APK 로 — 같은 패치는 다시 쓰지 않는다
                notePatchFailed(c, patch, e.message ?: "작은 업데이트 실패")
                File(dir, patchName!!).delete()
            } catch (e: HttpStatusException) {
                // 서버에 없는 패치(4xx)도 전체 APK 로. 서버가 잠깐 안 되면(5xx) 이번만 실패 — 다음에 같은 패치로
                if (e.code !in 400..499) throw e
                notePatchFailed(c, patch, e.message ?: "작은 업데이트 실패")
                File(dir, patchName!!).delete()
            }
            // 그 밖의 오류(연결이 끊김·저장 공간 부족·멈춤)는 그대로 알린다 — 받은 데까지는 남겨 다음에 이어받고,
            // 105MB 전체 APK 를 폰으로 대신 받지 않는다 (끊긴 것은 패치 탓이 아니다)
        }
        val wifi = UpdateNet.wifi(c, wifiWaitMs)
        try {
            if (wifi == null) {
                if (wifiOnly && !UpdateNet.onWifiNow(c)) {
                    startWaitingForWifi(c)
                    throw NeedsWifi()
                }
                fetchWithRetries(info.apkUrl, file, onProgress, null, retryDelaysMs, cancelled)
            } else {
                try {
                    fetchWithRetries(info.apkUrl, file, onProgress, wifi::open, WIFI_RETRY_DELAYS_MS, cancelled)
                } catch (e: IOException) {
                    // Wi-Fi 가 끊겼으면(집을 나섬 등) 받은 데까지는 두고 늘 쓰던 망으로 이어받는다 (Wi-Fi 로만이면 다음에 Wi-Fi 에서).
                    // 없는 파일(4xx)은 어디서나 같다. 멈추라고 했으면 그대로 멈춘다
                    if (e is Cancelled) throw e
                    if (e is HttpStatusException && e.code in 400..499) throw e
                    if (wifiOnly) {
                        startWaitingForWifi(c)
                        throw NeedsWifi()
                    }
                    fetchWithRetries(info.apkUrl, file, onProgress, null, retryDelaysMs, cancelled)
                }
            }
        } finally {
            wifi?.close()
        }
        if (sha256(file) != info.sha256) {
            file.delete()
            noteCorrupt(c, info.sha256)
            throw IOException("파일이 손상됐어요 (SHA-256 불일치) — 다시 시도해 주세요")
        }
        prefs(c).edit { remove(KEY_BAD_SHA).remove(KEY_BAD_SHA_COUNT) }
        return file
    }

    /**
     * 끊기면 [delays] 만큼 쉬며 받은 데부터 이어받는다 (폰 블루투스로 받을 땐 잠깐씩 끊긴다).
     * 서버가 없다고 한 것(4xx)은 다시 물어도 같으니 바로 알린다. [open] = 이 망으로 연결 (null 이면 늘 쓰던 망)
     */
    private fun fetchWithRetries(
        url: String,
        file: File,
        onProgress: (Int) -> Unit,
        open: ((URL) -> URLConnection)?,
        delays: List<Long>,
        cancelled: () -> Boolean,
    ) {
        var attempt = 0
        while (true) {
            try {
                fetchOrRestart(url, file, onProgress, open, cancelled)
                return
            } catch (e: IOException) {
                if (e is Cancelled) throw e
                if (e is HttpStatusException && e.code in 400..499) throw e
                val wait = delays.getOrNull(attempt++) ?: throw e
                sleepUnlessCancelled(wait, cancelled)
            }
        }
    }

    /** 다시 잇기 전 쉬는 동안에도 멈추라고 하면 바로 */
    private fun sleepUnlessCancelled(ms: Long, cancelled: () -> Boolean) {
        var left = ms
        while (left > 0) {
            if (cancelled()) throw Cancelled()
            val step = minOf(left, 250L)
            Thread.sleep(step)
            left -= step
        }
        if (cancelled()) throw Cancelled()
    }

    private fun fetchOrRestart(url: String, file: File, onProgress: (Int) -> Unit, open: ((URL) -> URLConnection)?, cancelled: () -> Boolean) {
        try {
            fetch(url, file, resume = file.exists() && file.length() > 0, onProgress, open, cancelled)
        } catch (e: RangeNotUsable) {
            // 이어받기가 안 되는 조각 파일(손상·크기 초과 등)이면 지우고 처음부터
            file.delete()
            fetch(url, file, resume = false, onProgress, open, cancelled)
        }
    }

    /** 끊긴 내려받기를 다시 잇기 전 쉬는 시간 — 첫 번은 바로 (블루투스가 잠깐 끊긴 경우가 대부분) */
    @VisibleForTesting
    internal val DEFAULT_RETRY_DELAYS_MS = listOf(0L, 3_000L, 10_000L)

    @VisibleForTesting
    internal var retryDelaysMs = DEFAULT_RETRY_DELAYS_MS

    /** Wi-Fi 로 받다 끊기면 한 번만 바로 다시 — 그래도 안 되면 Wi-Fi 가 사라진 것으로 보고 늘 쓰던 망으로 */
    private val WIFI_RETRY_DELAYS_MS = listOf(0L)

    private class RangeNotUsable : IOException("이어받기 불가")

    private class HttpStatusException(val code: Int) : IOException("다운로드 실패 ($code)")

    /** 받다 만 파일이 있으면 Range 요청으로 이어받는다 */
    private fun fetch(
        address: String,
        file: File,
        resume: Boolean,
        onProgress: (Int) -> Unit,
        open: ((URL) -> URLConnection)?,
        cancelled: () -> Boolean,
    ) {
        val already = if (resume) file.length() else 0L
        val url = URL(address)
        val conn = ((open?.invoke(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            if (already > 0 && code == 416) throw RangeNotUsable()
            if (code != 200 && code != 206) throw HttpStatusException(code)
            val append = code == 206 && already > 0
            if (append) {
                // "bytes 시작-끝/전체" 의 시작이 가진 크기와 같아야 이어 붙일 수 있다
                val start = conn.getHeaderField("Content-Range")
                    ?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()
                if (start != already) throw RangeNotUsable()
            }
            val total = if (append) already + conn.contentLengthLong else conn.contentLengthLong
            var done = if (append) already else 0L
            conn.inputStream.use { input ->
                FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastPct = -1
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            // 다 받기 전에 연결이 끝남 — 받은 조각은 두고 이어받는다 (지우고 처음부터 받지 않게)
            if (total > 0 && done < total) throw IOException("받는 중에 연결이 끊겼어요 (${done * 100 / total}%)")
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────── 작은 업데이트 (.kwdelta) ─────────────────

    /** 지금 깔린 이 앱의 APK (시험에선 바꿔 끼운다) */
    @VisibleForTesting
    internal var installedApk: (Context) -> File? = { c -> c.applicationInfo?.sourceDir?.let(::File)?.takeIf { it.canRead() } }

    /**
     * 깔린 APK 의 SHA-256 — 한 번 재면 기억한다 (설치할 때마다 경로가 바뀌므로 경로·크기·시각이 같을 때만 그대로).
     * 105MB 를 읽으므로 작업 스레드에서만
     */
    fun installedApkSha(c: Context): String? {
        val apk = installedApk(c) ?: return null
        cachedInstalledApkSha(c)?.let { return it }
        val sha = runCatching { sha256(apk) }.getOrNull() ?: return null
        prefs(c).edit { putString(KEY_INSTALLED_SHA, "${apkKey(apk)}|$sha") }
        return sha
    }

    /** 이미 재 둔 깔린 APK 의 SHA-256 (없으면 재지 않고 null — 화면에서 씀) */
    fun cachedInstalledApkSha(c: Context): String? {
        val apk = installedApk(c) ?: return null
        val saved = prefs(c).getString(KEY_INSTALLED_SHA, null) ?: return null
        return saved.substringAfterLast('|').takeIf { saved.substringBeforeLast('|') == apkKey(apk) && it.length == 64 }
    }

    private fun apkKey(apk: File) = "${apk.path}|${apk.length()}|${apk.lastModified()}"

    /** 이 워치에 깔린 APK 로 쓸 수 있는 작은 업데이트 (전에 실패한 것은 빼고). 작업 스레드에서만 */
    fun usablePatch(c: Context, info: UpdateInfo): UpdateInfo.Patch? {
        if (info.patches.isEmpty()) return null
        val mine = installedApkSha(c) ?: return null
        return info.patches.firstOrNull { it.from == mine && !patchFailed(c, it) }
    }

    /** 화면용: 이미 아는 것만으로 받을 크기 (작은 업데이트면 그 크기) — 재지 않는다 */
    fun knownDownloadSize(c: Context, info: UpdateInfo): Long? {
        val mine = cachedInstalledApkSha(c)
        val patch = info.patches.firstOrNull { it.from == mine && !patchFailed(c, it) }
        return patch?.size ?: info.sizeBytes
    }

    /** 화면용: 작은 업데이트로 받게 되는지 (이미 아는 것만으로) */
    fun knownSmallUpdate(c: Context, info: UpdateInfo): Boolean {
        val mine = cachedInstalledApkSha(c) ?: return false
        return info.patches.any { it.from == mine && !patchFailed(c, it) }
    }

    private fun patchFailed(c: Context, p: UpdateInfo.Patch) = prefs(c).getString(KEY_PATCH_BAD, null) == p.sha256

    private fun notePatchFailed(c: Context, p: UpdateInfo.Patch, why: String) {
        prefs(c).edit { putString(KEY_PATCH_BAD, p.sha256) }
        ErrorLog.record("작은 업데이트", "$why — 전체 파일로 받아요")
    }

    private fun patchFileName(p: UpdateInfo.Patch) = "patch-${p.sha256.take(16)}.kwdelta"

    private fun viaPatch(
        c: Context,
        info: UpdateInfo,
        patch: UpdateInfo.Patch,
        patchFile: File,
        target: File,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): File {
        val installed = installedApk(c) ?: throw DeltaPatch.BadPatch("깔린 앱 파일을 찾지 못했어요")
        if (!(patchFile.exists() && sha256(patchFile) == patch.sha256)) {
            fetchWithRetries(patch.url, patchFile, onProgress, null, retryDelaysMs, cancelled)
            if (sha256(patchFile) != patch.sha256) {
                patchFile.delete()
                throw DeltaPatch.BadPatch("작은 업데이트 파일이 손상됐어요")
            }
        }
        val part = File(target.parentFile, target.name + ".part")
        val made = DeltaPatch.apply(installed, patchFile, part, cancelled)
        if (made != info.sha256) {
            part.delete()
            throw DeltaPatch.BadPatch("작은 업데이트로 만든 파일이 새 버전과 달라요")
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("받은 파일을 옮기지 못했어요")
        }
        patchFile.delete()
        onProgress(100)
        return target
    }

    // ───────────────── 확인(서명) ─────────────────

    /** 문제가 있으면 오류 문구, 괜찮으면 null */
    fun verify(c: Context, file: File, info: UpdateInfo): String? {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val archive: PackageInfo = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "APK 를 읽지 못했어요"
        if (archive.packageName != c.packageName) return "다른 앱의 APK 예요"
        if (archive.longVersionCode <= currentVersionCode(c)) return "이미 같은 버전이거나 더 최신이에요"
        if (archive.longVersionCode != info.versionCode) return "버전 정보가 맞지 않아요"
        val mine = signerDigests(packageInfo(c, PackageManager.GET_SIGNING_CERTIFICATES))
        val theirs = signerDigests(archive)
        if (mine.isEmpty() || theirs.isEmpty() || mine != theirs) {
            return "서명 키가 달라서 업데이트할 수 없어요.\n앱을 지우고 다시 설치해야 해요"
        }
        return null
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } ?: return emptySet()
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    // ───────────────── 설치 ─────────────────

    /** 설치 세션을 만들고 커밋한다. 결과는 InstallResultReceiver 로 온다 */
    @Throws(IOException::class)
    fun install(c: Context, file: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 자기 자신을 업데이트할 때는 확인 창 없이 설치될 수 있다 (조건이 안 맞으면 확인 창)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(c, InstallResultReceiver::class.java).setPackage(c.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(c, sessionId, intent, flags)
                prefs(c).edit { putBoolean(KEY_PENDING, false).remove(KEY_ERROR) }
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            // 쓰다 실패(저장공간 부족 등)한 설치 세션은 버린다 — 남겨 두면 다시 시도할 때마다 십수 MB 씩 쌓인다
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    fun recordError(c: Context, message: String) = prefs(c).edit { putString(KEY_ERROR, message) }

    /**
     * 내려받기·설치가 실패한 까닭 — 화면 위쪽 ⚠ 문구(다음 확인 때 지워짐)와 함께 '최근 오류 기록'에도 남긴다.
     * 업데이트 화면을 열면 바로 다시 확인하므로, ⚠ 문구만으로는 밖에서 자동 업데이트가 왜 안 됐는지 금방 사라진다
     */
    fun recordFailure(c: Context, where: String, message: String) {
        recordError(c, message)
        ErrorLog.record(where, message)
    }

    /** 설치 결과(오류·확인 대기)가 바뀌면 알려 준다 — 업데이트 화면의 '설치 중…' 풀기용 */
    fun prefsForListening(c: Context): android.content.SharedPreferences = prefs(c)

    fun markPendingUserAction(c: Context, pending: Boolean) = prefs(c).edit { putBoolean(KEY_PENDING, pending) }

    fun isPendingUserAction(c: Context) = prefs(c).getBoolean(KEY_PENDING, false)

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun packageInfo(c: Context, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            c.packageManager.getPackageInfo(c.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(c.packageName, flags)
        }
}
