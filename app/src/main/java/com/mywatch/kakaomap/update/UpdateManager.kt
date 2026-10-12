package com.mywatch.kakaomap.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import com.mywatch.kakaomap.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHub Releases 에 올라간 update.json 내용 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
) {
    fun toJson(): String = JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName)
        .put("apkUrl", apkUrl).put("sha256", sha256).put("notes", notes).toString()

    companion object {
        fun parse(text: String): UpdateInfo {
            val o = JSONObject(text)
            return UpdateInfo(
                versionCode = o.getLong("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = o.getString("apkUrl"),
                sha256 = o.getString("sha256").lowercase(),
                notes = o.optString("notes"),
            )
        }
    }
}

/**
 * 자체 업데이트: GitHub 최신 릴리스 확인 → APK 다운로드 → SHA-256·서명 확인 → PackageInstaller 로 설치.
 * 설치되는 APK 가 지금 앱과 같은 키로 서명돼 있어야만 진행한다(안드로이드도 다르면 거부함).
 */
object UpdateManager {
    /** GitHub 저장소 이름 (아이디/kakao-watch-map) */
    const val REPO_NAME = "kakao-watch-map"
    private const val PREFS = "updates"
    private const val KEY_OWNER = "owner"
    private const val KEY_LATEST = "latest"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_ERROR = "error"
    private const val KEY_PENDING = "pending_user_action"

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
        val url = "https://github.com/$repo/releases/latest/download/update.json"
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
            return info.takeIf { it.versionCode > currentVersionCode(c) }
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────── 다운로드 ─────────────────

    private val downloadLock = Any()

    /** 자동 업데이트와 화면의 '지금 업데이트'가 동시에 같은 파일을 받지 않게 한 번에 하나씩 */
    @Throws(IOException::class)
    fun download(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit = {}): File = synchronized(downloadLock) {
        downloadLocked(c, info, onProgress)
    }

    private fun downloadLocked(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "update").apply { mkdirs() }
        // 다른 버전의 옛 파일은 지운다
        dir.listFiles()?.filter { it.name != "app-${info.versionCode}.apk" }?.forEach { it.delete() }
        val file = File(dir, "app-${info.versionCode}.apk")
        if (file.exists() && sha256(file) == info.sha256) return file
        try {
            fetch(info, file, resume = file.exists() && file.length() > 0, onProgress)
        } catch (e: RangeNotUsable) {
            // 이어받기가 안 되는 조각 파일(손상·크기 초과 등)이면 지우고 처음부터
            file.delete()
            fetch(info, file, resume = false, onProgress)
        }
        if (sha256(file) != info.sha256) {
            file.delete()
            throw IOException("파일이 손상됐어요 (SHA-256 불일치) — 다시 시도해 주세요")
        }
        return file
    }

    private class RangeNotUsable : IOException("이어받기 불가")

    /** 받다 만 파일이 있으면 Range 요청으로 이어받는다 */
    private fun fetch(info: UpdateInfo, file: File, resume: Boolean, onProgress: (Int) -> Unit) {
        val already = if (resume) file.length() else 0L
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            if (already > 0 && code == 416) throw RangeNotUsable()
            if (code != 200 && code != 206) throw IOException("다운로드 실패 ($code)")
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
        } finally {
            conn.disconnect()
        }
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
    }

    fun recordError(c: Context, message: String) = prefs(c).edit { putString(KEY_ERROR, message) }

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
