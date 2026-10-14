package com.mywatch.kakaomap.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.MapApp
import java.io.File
import java.io.RandomAccessFile

/**
 * 이 워치에서 난 오류 기록 (최근 30줄) — '앱 정보·업데이트' 화면 아래 '최근 오류 기록'에 보여 준다.
 * "잘 안 돼요"일 때 무엇이 안 됐는지(검색·길찾기·실시간 버스·위치·지도·앱 멈춤) 알 수 있게.
 * 워치 밖으로 보내지 않고, 인증키처럼 보이는 값은 지우고 저장한다. 아무 스레드에서나 불러도 된다.
 * 본체와 재생 화면 프로세스(:player)가 함께 쓰므로 파일 잠금으로 한 번에 하나씩, 임시 파일에 다 쓴 뒤 바꿔 끼운다
 * (반쯤 쓴 파일이 남지 않게). 잠금·임시 파일은 백업되지 않는 곳에
 */
object ErrorLog {
    private const val FILE = "error_log.jsonl"
    private const val MAX = 30
    private const val MAX_MESSAGE = 600
    private val lock = Any()

    @Volatile private var file: File? = null
    @Volatile private var spare: File? = null

    /** Application.onCreate 에서 한 번 */
    fun init(context: Context) {
        val app = context.applicationContext
        file = File(app.filesDir, FILE)
        spare = app.noBackupFilesDir
    }

    /** 다른 프로세스와도 한 번에 하나씩 (같은 프로세스 안은 [lock]) */
    private fun <T> exclusive(block: () -> T): T = synchronized(lock) {
        val dir = spare
        if (dir == null) block() else RandomAccessFile(File(dir, "$FILE.lock"), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }

    /** 임시 파일에 다 쓴 뒤 바꿔 끼운다 — 쓰다 멈춰도 예전 기록은 그대로 */
    private fun replace(f: File, text: String) {
        val tmp = File(spare ?: f.parentFile, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun record(where: String, t: Throwable) = record(where, t.message?.ifBlank { null } ?: t.javaClass.simpleName)

    fun record(
        where: String,
        message: String?,
        atMs: Long = System.currentTimeMillis(),
        version: String = BuildConfig.VERSION_NAME,
    ) {
        val f = file ?: return
        val text = ErrorLogFormat.sanitize(message?.trim()?.ifBlank { null } ?: "알 수 없는 오류").take(MAX_MESSAGE)
        val entry = ErrorEntry(atMs, where, text, version)
        try {
            exclusive {
                val list = ErrorLogFormat.add(read(f), entry, MAX)
                replace(f, list.joinToString("\n") { ErrorLogFormat.encode(it) })
            }
        } catch (e: Exception) {
            Log.w(MapApp.TAG, "오류 기록 실패", e)
        }
    }

    /** 최근 것부터 (시스템 종료 기록은 나중에 옮겨 오므로 시각으로 정렬) */
    fun entries(): List<ErrorEntry> {
        val f = file ?: return emptyList()
        return synchronized(lock) { runCatching { read(f) }.getOrDefault(emptyList()) }.sortedByDescending { it.atMs }
    }

    fun clear() {
        runCatching { exclusive { file?.delete() } }
    }

    /**
     * 앱이 멈추면(잡지 못한 예외) 원인을 기록한 뒤 원래 처리(시스템의 '앱이 멈춤')로 넘긴다.
     * 다음에 앱을 열면 앱 정보 화면에서 볼 수 있다.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    private class CrashRecorder(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                record("앱 멈춤", ErrorLogFormat.crashText(e, "com.mywatch.kakaomap"))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * 자바 예외가 아닌 이유로 앱이 끝났던 기록(시스템이 남김)을 옮겨 온다 — 지도 엔진(네이티브) 멈춤, 응답 없음(ANR),
     * 메모리 부족·자원 과다로 강제 종료. 앱을 시작할 때 한 번, 백그라운드 스레드에서.
     */
    fun recordExitReasons(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
        val infos = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        } catch (e: Exception) {
            return
        }
        var newest = seen
        for (info in infos.sortedBy { it.timestamp }) {
            if (info.timestamp <= seen) continue
            newest = maxOf(newest, info.timestamp)
            // 화면을 보던 중이거나 안내 중(포그라운드 서비스)이었는지
            val inUse = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            val what = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "앱 멈춤(지도 엔진 등)"
                ApplicationExitInfo.REASON_ANR -> "응답 없음(ANR)"
                // 앱을 닫아 둔 뒤 메모리 정리로 끝난 것은 정상 — 쓰는 중에 끊긴 것만
                ApplicationExitInfo.REASON_LOW_MEMORY -> if (inUse) "메모리 부족으로 종료" else null
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "배터리·자원 과다로 종료"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "시작 실패"
                else -> null   // 자바 예외(직접 기록함)·사용자가 닫음·업데이트 등은 오류가 아님
            } ?: continue
            // 어느 버전에서 났는지는 시스템 기록에 없으므로 비워 둔다 (업데이트 뒤에 옮겨 오면 새 버전으로 보이지 않게)
            record(
                what,
                listOfNotNull(if (inUse) "사용 중" else "뒤에서", info.description?.ifBlank { null }).joinToString(" · "),
                atMs = info.timestamp,
                version = "",
            )
        }
        if (newest > seen) prefs.edit { putLong(KEY_EXIT_SEEN, newest) }
    }

    private const val PREFS = "error_log"
    private const val KEY_EXIT_SEEN = "exit_seen"

    private fun read(f: File): List<ErrorEntry> =
        if (!f.exists()) emptyList() else f.readLines().mapNotNull(ErrorLogFormat::decode)
}
