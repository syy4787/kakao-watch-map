package com.mywatch.kakaomap.update

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 업데이트 테스트용 GitHub 릴리스 흉내 (127.0.0.1 의 아주 작은 HTTP 서버).
 * update.json 과 APK 를 주고 받은 요청을 적어 둔다. 블루투스로 받을 때처럼 첫 APK 응답을 중간에 끊거나([cutFirstApkAt]),
 * 느린 내려받기처럼 [holdApk] 가 풀릴 때까지 붙잡아 둘 수 있다. Range 요청이면 그 뒤부터 이어서 준다.
 */
class FakeReleaseServer : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()

    val base: String get() = "http://127.0.0.1:${socket.localPort}"
    val apkUrl: String get() = "$base/apk/app.apk"

    /** 받은 요청 — "GET /경로" (+ " (Range 머리글)") */
    val requests = CopyOnWriteArrayList<String>()
    val apkRequests: List<String> get() = requests.filter { it.contains("/apk/") }

    @Volatile var updateJson: String? = null
    /** /files/<이름> 으로 주는 그 밖의 파일 (작은 업데이트 패치 등) — Range 요청이면 그 뒤부터 */
    val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    fun fileUrl(name: String) = "$base/files/$name"
    @Volatile var apk: ByteArray = ByteArray(0)
    @Volatile var apkMissing = false
    /** 첫 APK 응답은 이만큼만 보내고 연결을 닫는다 (받는 쪽은 조용히 끝난 것처럼 보임) */
    @Volatile var cutFirstApkAt: Int? = null
    /** 첫 APK 응답은 이만큼만 보내고 연결을 끊어 버린다 (RST — 워치의 OkHttp 처럼 읽다가 예외가 남) */
    @Volatile var resetFirstApkAt: Int? = null
    /** 걸어 두면 APK 응답을 이것이 풀릴 때까지(최대 10초) 미룬다 */
    @Volatile var holdApk: CountDownLatch? = null

    init {
        pool.execute {
            while (!socket.isClosed) {
                val s = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@execute
                }
                pool.execute { s.use { runCatching { serve(it) } } }
            }
        }
    }

    /** [size] = update.json 에 적힌 APK 크기 (없으면 안 적음 — 예전 릴리스처럼) */
    fun updateJsonFor(versionCode: Long, size: Long? = null) =
        """{"versionCode":$versionCode,"versionName":"2.${versionCode - 100}","apkUrl":"$apkUrl",""" +
            """"sha256":"${sha256Hex(apk)}","notes":"테스트"""" + (size?.let { ""","size":$it""" } ?: "") + "}"

    private fun serve(s: Socket) {
        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val path = requestLine.split(' ').getOrElse(1) { "/" }
        val range = headers["range"]
        requests += "GET $path" + (range?.let { " ($it)" } ?: "")
        val out = s.getOutputStream()
        fun head(status: String, length: Int, extra: String = "") = out.write(
            "HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.ISO_8859_1)
        )
        when {
            path.endsWith("/releases/latest/download/update.json") -> {
                val body = updateJson?.toByteArray()
                if (body == null) head("404 Not Found", 0) else {
                    head("200 OK", body.size)
                    out.write(body)
                }
            }
            path.startsWith("/files/") -> {
                val bytes = files[path.removePrefix("/files/")]
                if (bytes == null) head("404 Not Found", 0) else {
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (from > 0) head("206 Partial Content", bytes.size - from, "Content-Range: bytes $from-${bytes.size - 1}/${bytes.size}\r\n")
                    else head("200 OK", bytes.size)
                    out.write(bytes, from, bytes.size - from)
                }
            }
            path.startsWith("/apk/") -> {
                holdApk?.await(10, TimeUnit.SECONDS)
                if (apkMissing) {
                    head("404 Not Found", 0)
                } else {
                    val bytes = apk
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (from > 0) {
                        head("206 Partial Content", bytes.size - from, "Content-Range: bytes $from-${bytes.size - 1}/${bytes.size}\r\n")
                    } else {
                        head("200 OK", bytes.size)
                    }
                    val first = apkRequests.size == 1
                    val reset = resetFirstApkAt?.takeIf { first }
                    val cut = cutFirstApkAt?.takeIf { first }
                    val end = reset ?: cut ?: bytes.size
                    out.write(bytes, from, end - from)
                    if (reset != null) {
                        out.flush()
                        Thread.sleep(300)   // 보낸 만큼은 받는 쪽이 읽게 한 뒤
                        s.setSoLinger(true, 0)   // 닫으면 RST
                    }
                    // end < 전체 이면 여기서 연결이 닫힌다 (블루투스가 잠깐 끊긴 것처럼)
                }
            }
            else -> head("404 Not Found", 0)
        }
        out.flush()
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
