package com.mywatch.kakaomap.tube

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

/**
 * 재생 화면 프로세스(:player) → 앱 본체로 가는 짧은 글 (재생 화면이 보낸 링크 — 믹스 추천·본 Shorts).
 * 글 하나 = 파일 하나라 두 프로세스가 같은 파일을 고쳐 쓰는 일이 없다: 임시 이름(.msg.tmp)으로 다 쓴 뒤 이름을 바꿔(원자적)
 * 반쯤 쓴 글은 읽히지 않는다. 앱 본체가 열 때 오래된 것부터 읽고 지운다 ([drain]). [MAX] 개가 넘으면 오래된 것부터 버린다.
 * 파일 이름 = <보낸 때 13자리>-<이 프로세스의 차례 8자리><임의 8자리>(16진수).msg — 같은 때 보낸 것도 차례대로, 겹치지 않게.
 * 보낸 때를 함께 준다 (받아 두는 기간을 보낸 때부터 세게). 보낸 지 [MAX_AGE_MS] 가 지난 글은 읽지 않고 지운다 — 보낼 때도 (앱을 안 열어도
 * 쌓여 남지 않게). 무슨 일이 있어도 예외를 내지 않는다
 */
class TubeInbox(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    /** 받은 글 하나: [text] 와 보낸 때 [at] (시계가 뒤로 가 앞날이면 지금) */
    data class Note(val text: String, val at: Long)

    /** [text] 를 남긴다. 비었거나 [MAX_TEXT] 자보다 길면 버린다 */
    fun post(text: String) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        runCatching {
            dir.mkdirs()
            val name = now().coerceIn(0, MAX_TIME).toString().padStart(13, '0') + "-" + hex(SEQ.incrementAndGet()) + hex(Random.nextInt())
            val tmp = File(dir, name + TMP)
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name + MSG))) {
                tmp.delete()
                return
            }
            val all = messages()
            all.filter { tooOld(it.name) }.forEach { it.delete() }
            val left = messages()
            left.take((left.size - MAX).coerceAtLeast(0)).forEach { it.delete() }
        }
    }

    /**
     * 남은 글을 모두 오래된 것부터 읽고 지운다. 못 읽는 파일·보낸 지 [MAX_AGE_MS] 가 지난 글은 지우고 건너뛰고,
     * 1분 넘게 남은 임시 파일(쓰다 만 것)도 지운다
     */
    fun drain(): List<Note> = synchronized(LOCK) {
        runCatching {
            val t = now()
            dir.listFiles()?.filter { TEMP.matches(it.name) && stale(it.name) }?.forEach { it.delete() }
            messages().mapNotNull { f ->
                val at = sentAt(f.name)
                val text = if (at == null || tooOld(f.name)) null else read(f)
                f.delete()
                if (text == null || at == null) null else Note(text, at.coerceAtMost(t))
            }
        }.getOrDefault(emptyList())
    }

    /** 이름의 보낸 때 (모양이 다르면 null) */
    private fun sentAt(name: String): Long? = name.take(13).toLongOrNull()

    /** 보낸 지 [MAX_AGE_MS] 가 지남 */
    private fun tooOld(name: String): Boolean = sentAt(name)?.let { now() - it > MAX_AGE_MS } ?: true

    /** 남은 글 파일 (오래된 것부터) — 이름이 다른 것·폴더는 모른 척 */
    private fun messages(): List<File> = dir.listFiles()?.filter { MESSAGE.matches(it.name) && it.isFile }?.sortedBy { it.name }.orEmpty()

    /** 이름의 보낸 때가 지금에서 1분 넘게 떨어짐 (시계가 바뀐 것도) */
    private fun stale(name: String): Boolean {
        val at = name.take(13).toLongOrNull() ?: return false
        return abs(now() - at) > STALE_MS
    }

    /** 글 하나 — 너무 크거나 UTF-8 이 아니거나 비었거나 [MAX_TEXT] 자보다 길면 null */
    private fun read(f: File): String? = runCatching {
        if (f.length() > MAX_TEXT * 4L) return@runCatching null
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(f.readBytes()))
            .toString()
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }

    companion object {
        /** 남겨 두는 글 수 */
        const val MAX = 50
        /** 글 하나의 최대 글자 수 */
        const val MAX_TEXT = 2048
        /** 보낸 지 이만큼 지난 글은 버린다 (믹스 추천을 받아 두는 기간 — 본 Shorts 도 그 안에 읽힌다) */
        const val MAX_AGE_MS = 7L * 24 * 3_600_000
        /** 임시 파일이 이만큼 남았으면 쓰다 만 것 (쓰는 중에 프로세스가 죽음) */
        private const val STALE_MS = 60_000L
        private const val MAX_TIME = 9_999_999_999_999L
        private const val MSG = ".msg"
        private const val TMP = ".msg.tmp"
        private val MESSAGE = Regex("[0-9]{13}-[0-9a-f]{16}\\.msg")
        private val TEMP = Regex("[0-9]{13}-[0-9a-f]{16}\\.msg\\.tmp")
        private val SEQ = AtomicInteger()
        private val LOCK = Any()

        private fun hex(n: Int) = Integer.toHexString(n).padStart(8, '0')

        /** 앱 본체·재생 화면 프로세스가 함께 보는 자리 (백업하지 않음) */
        fun of(context: Context) = TubeInbox(File(context.noBackupFilesDir, "tube_inbox"))
    }
}
