package com.mywatch.kakaomap.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 아주 작은 HTTP 도우미 (라이브러리 없이 HttpURLConnection 사용) */
object Http {

    class Response(val code: Int, val body: String)

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): Response =
        send(url, "GET", null, headers, timeoutMs)

    /** 폼(application/x-www-form-urlencoded)을 보낸다 — 구글 로그인(OAuth) 요청 */
    @Throws(IOException::class)
    fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): Response =
        send(url, "POST", query(form).toByteArray(Charsets.UTF_8), headers, timeoutMs)

    /**
     * 응답을 끝까지 읽으면 연결을 끊지 않는다 — 시스템이 그 연결을 몇 분 동안 다시 써서, 45초마다 묻는 실시간 버스 도착 등이
     * 매번 새 TLS 연결(블루투스 너머 폰까지 왕복 여러 번)을 맺지 않게. 실패했을 때만 끊는다
     */
    private fun send(url: String, method: String, body: ByteArray?, headers: Map<String, String>, timeoutMs: Int): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        var done = false
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            done = true
            return Response(code, text)
        } finally {
            if (!done) conn.disconnect()
        }
    }

    fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
