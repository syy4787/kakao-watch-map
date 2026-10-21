package com.mywatch.kakaomap.battery

import android.content.Context
import com.mywatch.battery.core.Span
import org.json.JSONObject
import java.io.File

/**
 * 지킴이가 레버를 당기고 놓은 때를 남기는 일지 (`guardian_journal.jsonl`) — 조치 효과(밤 모드 켠 밤 vs 안 켠 밤)를 재는 근거.
 * 30일보다 오래된 줄은 지운다. 워치 밖으로 보내지 않는다.
 */
class GuardianJournal(context: Context) {
    private val dir = BatteryLog(context).dir
    private val file = File(dir, "guardian_journal.jsonl")

    @Synchronized
    fun add(event: String, t: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        file.appendText(JSONObject().put("t", t).put("e", event).toString() + "\n")
        if (file.length() > 64_000L) prune(t)
    }

    /** 밤 모드가 켜져 있던 구간들 (끝 표시 없이 끊긴 것은 켠 뒤 최대 [MAX_NIGHT_MS] 까지로) */
    @Synchronized
    fun nightSessions(now: Long): List<Span> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Span>()
        var start: Long? = null
        file.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = o.optLong("t")
            when (o.optString("e")) {
                NIGHT_ON -> {
                    start?.let { s -> out += Span(s, minOf(t, s + MAX_NIGHT_MS)) }
                    start = t
                }
                NIGHT_OFF -> {
                    start?.let { s -> if (t > s) out += Span(s, t) }
                    start = null
                }
            }
        }
        start?.let { s -> if (now > s) out += Span(s, minOf(now, s + MAX_NIGHT_MS)) }
        return out
    }

    private fun prune(now: Long) {
        val keepFrom = now - 30 * 86_400_000L
        val kept = file.readLines().filter { l -> runCatching { JSONObject(l).optLong("t") >= keepFrom }.getOrDefault(false) }
        file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    companion object {
        const val NIGHT_ON = "night_on"
        const val NIGHT_OFF = "night_off"
        const val MAX_NIGHT_MS = 10L * 3_600_000L
    }
}
