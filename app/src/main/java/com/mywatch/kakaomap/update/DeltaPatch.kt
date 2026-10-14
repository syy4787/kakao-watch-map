package com.mywatch.kakaomap.update

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * 작은 업데이트(.kwdelta) 적용 — 워치에 깔린 APK(이전 버전)와 패치로 새 APK 를 바이트까지 똑같이 다시 만든다.
 * 재생 엔진(약 100MB)은 버전마다 그대로라 패치에는 바뀐 코드만 들어 있다 (v123→v124: 104.5MB 대신 3.7MB).
 *
 * 형식 (빅엔디언, CI 의 .github/scripts/make-deltas.py 가 만듦): "KWDELTA1" 다음 zlib 스트림 하나 —
 *  'C' u64 오프셋 u32 길이 → 깔린 APK 에서 복사 · 'L' u32 길이 + 바이트 → 그대로 · 'E' u64 전체 길이 + SHA-256 → 끝.
 * 메모리는 버퍼 몇 개만 쓴다(파일에서 파일로 흘려 씀). 틀린 패치면 [BadPatch] 이고 만들던 파일은 지운다 —
 * 그때 업데이트는 전체 APK 를 받는다.
 */
object DeltaPatch {

    private val MAGIC = "KWDELTA1".toByteArray(Charsets.US_ASCII)
    private const val CHUNK = 64 * 1024

    /** 패치가 이 기기의 APK 와 맞지 않거나 깨졌음 */
    class BadPatch(message: String) : IOException(message)

    /**
     * [installed] + [patch] → [out]. 다시 만든 파일의 SHA-256(소문자 16진수)을 돌려준다 —
     * 패치 끝에 적힌 값과 같을 때만 (호출하는 쪽은 update.json 의 값과도 비교). [cancelled] 면 [UpdateManager.Cancelled]
     */
    @Throws(IOException::class)
    fun apply(installed: File, patch: File, out: File, cancelled: () -> Boolean = { false }): String {
        try {
            return rebuild(installed, patch, out, cancelled)
        } catch (e: Exception) {
            out.delete()
            throw when (e) {
                is BadPatch, is UpdateManager.Cancelled -> e
                is EOFException, is ZipException -> BadPatch("작은 업데이트 파일이 깨졌거나 잘렸어요")
                else -> e
            }
        }
    }

    private fun rebuild(installed: File, patch: File, out: File, cancelled: () -> Boolean): String {
        RandomAccessFile(installed, "r").use { src ->
            val srcLength = src.length()
            FileInputStream(patch).use { raw ->
                val magic = ByteArray(MAGIC.size)
                if (raw.readNBytesCompat(magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
                    throw BadPatch("작은 업데이트 파일 형식이 아니에요")
                }
                val inflater = Inflater()
                try {
                    val input = DataInputStream(BufferedInputStream(InflaterInputStream(raw, inflater, CHUNK), CHUNK))
                    val md = MessageDigest.getInstance("SHA-256")
                    BufferedOutputStream(FileOutputStream(out), 4 * CHUNK).use { dst ->
                        val buf = ByteArray(CHUNK)
                        var written = 0L
                        while (true) {
                            if (cancelled()) throw UpdateManager.Cancelled()
                            when (input.read()) {
                                'C'.code -> {
                                    val offset = input.readLong()
                                    val length = input.readInt().toLong() and 0xFFFF_FFFFL
                                    if (offset < 0 || offset + length > srcLength) throw BadPatch("작은 업데이트가 이 워치의 앱과 맞지 않아요")
                                    src.seek(offset)
                                    var left = length
                                    while (left > 0) {
                                        if (cancelled()) throw UpdateManager.Cancelled()
                                        val n = src.read(buf, 0, minOf(left, CHUNK.toLong()).toInt())
                                        if (n < 0) throw BadPatch("깔린 앱 파일이 짧아요")
                                        dst.write(buf, 0, n)
                                        md.update(buf, 0, n)
                                        left -= n
                                        written += n
                                    }
                                }
                                'L'.code -> {
                                    var left = input.readInt().toLong() and 0xFFFF_FFFFL
                                    while (left > 0) {
                                        if (cancelled()) throw UpdateManager.Cancelled()
                                        val n = minOf(left, CHUNK.toLong()).toInt()
                                        input.readFully(buf, 0, n)
                                        dst.write(buf, 0, n)
                                        md.update(buf, 0, n)
                                        left -= n
                                        written += n
                                    }
                                }
                                'E'.code -> {
                                    val total = input.readLong()
                                    val expected = ByteArray(32).also { input.readFully(it) }
                                    if (total != written) throw BadPatch("다시 만든 파일 크기가 달라요")
                                    val actual = md.digest()
                                    if (!actual.contentEquals(expected)) throw BadPatch("다시 만든 파일이 달라요")
                                    dst.flush()
                                    return actual.joinToString("") { "%02x".format(it) }
                                }
                                -1 -> throw BadPatch("작은 업데이트 파일이 잘렸어요")
                                else -> throw BadPatch("작은 업데이트 파일 형식이 아니에요")
                            }
                        }
                    }
                } finally {
                    inflater.end()
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw BadPatch("작은 업데이트 파일이 잘렸어요")
    }

    /** 다 읽을 때까지 (짧으면 읽은 만큼) */
    private fun FileInputStream.readNBytesCompat(b: ByteArray): Int {
        var got = 0
        while (got < b.size) {
            val n = read(b, got, b.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }
}
