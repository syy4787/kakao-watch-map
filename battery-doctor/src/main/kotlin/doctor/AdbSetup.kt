package doctor

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * adb 준비 — Android Studio 가 없는 PC 를 위해. .bat 이 찾은 adb → 설정 파일의 adbPath → 이 툴킷 폴더의 platform-tools →
 * PATH 순으로 쓰고, 어디에도 없으면 물어본 뒤(구글 라이선스 안내) 구글 공식 주소에서 Android SDK Platform-Tools 를 받아
 * 이 폴더의 platform-tools 에 푼다. 받는 것은 그때 한 번뿐이고 툴킷의 다른 일은 네트워크를 쓰지 않는다.
 * platform-tools 를 툴킷에 넣어 배포하지 않는 건 구글 SDK 라이선스가 재배포를 막아서다 — 사용자가 직접 받는다
 */
object AdbSetup {
    const val WINDOWS_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    const val LINUX_URL = "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
    const val LICENSE_URL = "https://developer.android.com/studio/releases/platform-tools"
    private const val DIR = "platform-tools"
    /** 받을 zip 의 상한 — 지금 platform-tools(Windows)는 8MB 안팎 */
    private const val MAX_BYTES = 200L * 1024 * 1024

    fun exeName(windows: Boolean) = if (windows) "adb.exe" else "adb"

    /** 이 툴킷(jar)이 있는 폴더 — 모르면 현재 폴더(.bat 이 그 폴더로 옮겨 실행한다) */
    fun appDir(): File = runCatching {
        File(AdbSetup::class.java.protectionDomain.codeSource.location.toURI()).parentFile
    }.getOrNull()?.takeIf { it.isDirectory } ?: File(".").absoluteFile

    /**
     * 쓸 adb — .bat 이 찾아 넘긴 것([explicit]) → 설정 파일에 적어 둔 것(파일이 있을 때만 — 폴더를 옮겼으면 건너뜀) →
     * 이 폴더의 platform-tools → PATH 의 adb
     */
    fun resolve(explicit: String?, configured: String?, appDir: File, windows: Boolean): String {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        configured?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile }?.let { return it }
        File(File(appDir, DIR), exeName(windows)).takeIf { it.isFile }?.let { return it.path }
        return "adb"
    }

    /** [path] 의 adb 가 실행되는지 (`adb version` 이 0 으로 끝나는지 — 서버를 띄우지 않는다) */
    fun works(path: String): Boolean = runCatching {
        val p = ProcessBuilder(path, "version").redirectErrorStream(true).start()
        p.outputStream.close()
        val drain = Thread { runCatching { p.inputStream.use { it.readBytes() } } }.apply { isDaemon = true; start() }
        val done = p.waitFor(20, TimeUnit.SECONDS)
        if (!done) { p.destroyForcibly(); return@runCatching false }
        drain.join(1000)
        p.exitValue() == 0
    }.getOrDefault(false)

    /**
     * adb 를 못 찾았을 때: 무엇을 어디서 받는지·라이선스를 알리고 물은 뒤 받는다. 받아 풀고 실행까지 되면 그 adb 경로, 아니면 null
     * (아니라고 하면 직접 까는 길을 알려 준다). [download] 는 (주소, 저장할 파일) — 실패하면 예외, [check] 는 받은 adb 가 실행되는지
     */
    fun offer(io: Console, appDir: File, windows: Boolean, download: (String, File) -> Unit, check: (String) -> Boolean): String? {
        val url = if (windows) WINDOWS_URL else LINUX_URL
        val target = File(appDir, DIR)
        io.println()
        io.println("adb(워치와 무선 디버깅으로 이야기하는 구글 공식 도구)를 이 PC 에서 찾지 못했습니다.")
        io.println("구글 공식 주소에서 Android SDK Platform-Tools(약 8MB)를 받아 이 폴더에 둘 수 있습니다:")
        io.println("  주소: $url")
        io.println("  둘 곳: ${target.absolutePath}")
        io.println("  받으면 구글의 Android SDK Platform-Tools 라이선스에 동의하는 것입니다: $LICENSE_URL")
        val answer = io.readLine("지금 받을까요? (y = 예, 엔터 = 아니오): ")?.trim()?.lowercase()
        if (answer == null || answer !in YES) {
            io.println("받지 않았습니다. 직접 깔려면: 위 주소의 zip 을 받아 압축을 풀고, 그 안의 platform-tools 폴더를 통째로")
            io.println("  ${target.absolutePath} 가 되게 두세요 (그 안에 ${exeName(windows)} 이 있으면 다음부터 알아서 찾습니다).")
            io.println("  Android Studio 가 있으면 SDK Manager › SDK Tools › 'Android SDK Platform-Tools' 를 켜도 됩니다.")
            return null
        }
        val zip = File(appDir, "platform-tools-download.zip")
        try {
            io.println("받는 중… ($url)")
            download(url, zip)
            val adb = install(zip, appDir, windows)
            if (adb == null) {
                io.println("받은 파일에 ${exeName(windows)} 이 없습니다 — 위 주소에서 직접 받아 주세요.")
                return null
            }
            if (!check(adb.path)) {
                io.println("받은 adb 가 실행되지 않습니다(${adb.path}) — 백신이 막았는지 보거나 위 주소에서 직접 받아 주세요.")
                return null
            }
            io.println("adb 준비 완료: ${adb.path}")
            return adb.path
        } catch (e: Exception) {
            io.println("내려받지 못했습니다: ${e.message ?: e} — 인터넷 연결을 확인하거나 위 주소에서 직접 받아 이 폴더에 풀어 주세요.")
            return null
        } finally {
            zip.delete()
        }
    }

    private val YES = setOf("y", "yes", "예", "네", "ㅇ", "응")

    /**
     * 받은 zip 에서 platform-tools/ 아래만 [appDir]/platform-tools 에 푼다 — 그 밖의 항목, 폴더 밖으로 나가는 경로(.. · 절대 경로)는 버린다.
     * 그 안의 adb 파일, 없거나 zip 이 아니면 null
     */
    fun install(zip: File, appDir: File, windows: Boolean): File? {
        val root = File(appDir, DIR).canonicalFile
        try {
            ZipFile(zip).use { z ->
                var total = 0L
                for (e in z.entries()) {
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory || !name.startsWith("$DIR/")) continue
                    val rel = name.removePrefix("$DIR/")
                    if (rel.isEmpty() || rel.startsWith("/") || rel.split('/').any { it == ".." || it.contains(':') }) continue
                    val out = File(root, rel).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) continue
                    out.parentFile.mkdirs()
                    z.getInputStream(e).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw IOException("압축을 푼 크기가 너무 큽니다")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    if (!windows && out.name in setOf("adb", "fastboot")) out.setExecutable(true)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return File(root, exeName(windows)).takeIf { it.isFile }
    }

    /** HTTPS 로 [url] 을 [to] 에 받는다 (java.base 의 HttpURLConnection — 리디렉션 따라감, 200 이 아니면 예외) */
    fun download(url: String, to: File) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "battery-doctor")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            to.parentFile?.mkdirs()
            c.inputStream.use { input ->
                to.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("파일이 너무 큽니다")
                        o.write(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
