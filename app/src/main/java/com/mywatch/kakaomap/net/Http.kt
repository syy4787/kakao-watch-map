package com.mywatch.kakaomap.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 아주 작은 HTTP GET 도우미 (라이브러리 없이 HttpURLConnection 사용) */
object Http {

    class Response(val code: Int, val body: String)

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, body)
        } finally {
            conn.disconnect()
        }
    }

    fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
