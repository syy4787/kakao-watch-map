package com.mywatch.kakaomap.data

import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 오류 기록 한 줄. [count] = 10분 안에 같은 오류가 되풀이된 횟수 */
data class ErrorEntry(
    val atMs: Long,
    val where: String,
    val message: String,
    val version: String,
    val count: Int = 1,
)

/** 오류 기록의 글자 다루기 (순수 함수 — 단위 테스트로 검증) */
object ErrorLogFormat {

    /** 같은 오류가 이 시간 안에 다시 나면 새 줄 대신 횟수만 올린다 */
    private const val REPEAT_WINDOW_MS = 10 * 60_000L

    private val SERVICE_KEY = Regex("""(?i)(serviceKey=)[^&\s"]+""")
    private val KAKAO_AK = Regex("""(KakaoAK)\s+\S+""")
    /** 카카오 앱 키(32자리)·공공데이터 키(64자리)처럼 긴 16진수 */
    private val HEX_TOKEN = Regex("""\b[0-9a-fA-F]{24,}\b""")

    /** 인증키처럼 보이는 값을 지운다 — 오류 문구에 요청 주소가 섞여 와도 키가 기록에 남지 않게 */
    fun sanitize(text: String): String = text
        .replace(SERVICE_KEY, "$1…")
        .replace(KAKAO_AK, "$1 …")
        .replace(HEX_TOKEN, "…")

    /** 앱이 멈춘 원인: 예외 이름·문구 + 우리 코드에서 가장 가까운 세 곳 + 근본 원인 */
    fun crashText(t: Throwable, ourPackage: String): String {
        val ours = t.stackTrace.filter { it.className.startsWith(ourPackage) }.take(3)
        val frames = ours.ifEmpty { t.stackTrace.take(1) }.map {
            "← ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val root = generateSequence(t.cause) { c -> c.cause?.takeIf { it !== c } }.take(10).lastOrNull()
        return (listOf(title(t)) + frames + listOfNotNull(root?.let { "원인: ${title(it)}" })).joinToString("\n")
    }

    private fun title(t: Throwable) = t.javaClass.simpleName + (t.message?.ifBlank { null }?.let { ": $it" } ?: "")

    /**
     * 기록에 한 줄 더하기. 같은 곳·같은 문구가 10분 안에 또 나면 그 줄의 횟수를 올려 맨 뒤로,
     * [max] 를 넘으면 오래된 줄부터 버린다.
     */
    fun add(list: List<ErrorEntry>, entry: ErrorEntry, max: Int): List<ErrorEntry> {
        val i = list.indexOfLast {
            it.where == entry.where && it.message == entry.message && entry.atMs - it.atMs in 0..REPEAT_WINDOW_MS
        }
        if (i >= 0) {
            val merged = list[i].copy(atMs = entry.atMs, version = entry.version, count = list[i].count + entry.count)
            return list.filterIndexed { j, _ -> j != i } + merged
        }
        return (list + entry).takeLast(max)
    }

    /** 파일 한 줄 (JSON — 줄바꿈은 \n 으로 바뀌어 한 줄에 들어간다) */
    fun encode(e: ErrorEntry): String = JSONObject()
        .put("t", e.atMs).put("w", e.where).put("m", e.message).put("v", e.version).put("n", e.count)
        .toString()

    fun decode(line: String): ErrorEntry? {
        if (line.isBlank()) return null
        return try {
            val o = JSONObject(line)
            if (!o.has("t") || !o.has("w")) return null
            ErrorEntry(o.optLong("t"), o.optString("w"), o.optString("m"), o.optString("v"), o.optInt("n", 1).coerceAtLeast(1))
        } catch (e: JSONException) {
            null
        }
    }

    /** 앱 정보 화면에 보여 줄 한 칸: "10/13 09:21 · 실시간 버스 ×3 / 문구 / (v버전)" */
    fun line(e: ErrorEntry): String {
        val time = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(e.atMs))
        val times = if (e.count > 1) " ×${e.count}" else ""
        val version = if (e.version.isNotBlank()) "\n(v${e.version})" else ""
        // 화살표와 코드 위치가 줄바꿈으로 갈라지지 않게 (붙는 빈칸)
        return "$time · ${e.where}$times\n${e.message.replace("← ", "←\u00A0")}$version"
    }
}
