package com.mywatch.kakaomap.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random

/**
 * 작은 업데이트(.kwdelta) 적용 — 워치에 깔린 APK 로 새 APK 를 바이트까지 똑같이 다시 만든다.
 * 형식은 CI 의 .github/scripts/make-deltas.py 와 같다 (그 스크립트가 만든 파일로도 확인).
 */
class DeltaPatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private sealed interface Rec {
        data class Copy(val offset: Long, val length: Int) : Rec
        class Lit(val bytes: ByteArray) : Rec
        class End(val total: Long, val sha: ByteArray) : Rec
    }

    /** 시험용 패치 쓰기 (make-deltas.py 와 같은 형식) */
    private fun patch(vararg recs: Rec, magic: String = "KWDELTA1"): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(body)).use { d ->
            for (r in recs) when (r) {
                is Rec.Copy -> { d.writeByte('C'.code); d.writeLong(r.offset); d.writeInt(r.length) }
                is Rec.Lit -> { d.writeByte('L'.code); d.writeInt(r.bytes.size); d.write(r.bytes) }
                is Rec.End -> { d.writeByte('E'.code); d.writeLong(r.total); d.write(r.sha) }
            }
        }
        return magic.toByteArray(Charsets.US_ASCII) + body.toByteArray()
    }

    private val old = Random(7).nextBytes(300_000)

    private fun files(patchBytes: ByteArray): Triple<File, File, File> {
        val o = tmp.newFile().apply { writeBytes(old) }
        val p = tmp.newFile().apply { writeBytes(patchBytes) }
        val out = File(tmp.root, "out-${System.nanoTime()}.apk")
        return Triple(o, p, out)
    }

    @Test
    fun copiesAndLiteralsRebuildTheExactBytes() {
        val lit = "새로 바뀐 코드".toByteArray()
        val expected = old.copyOfRange(1_000, 201_000) + lit + old.copyOfRange(0, 1_000)
        val (o, p, out) = files(patch(Rec.Copy(1_000, 200_000), Rec.Lit(lit), Rec.Copy(0, 1_000), Rec.End(expected.size.toLong(), sha(expected))))
        val got = DeltaPatch.apply(o, p, out)
        assertArrayEquals(expected, out.readBytes())
        assertEquals(hex(sha(expected)), got)
    }

    @Test
    fun aPatchMadeByTheReleaseScriptRebuildsTheNewApk() {
        val dir = File(javaClass.getResource("/delta/old.apk")!!.toURI()).parentFile
        val out = File(tmp.root, "rebuilt.apk")
        val got = DeltaPatch.apply(File(dir, "old.apk"), File(dir, "new-from-old.kwdelta"), out)
        val expected = File(dir, "new.apk").readBytes()
        assertArrayEquals(expected, out.readBytes())
        assertEquals(hex(sha(expected)), got)
    }

    private fun assertRefused(patchBytes: ByteArray) {
        val (o, p, out) = files(patchBytes)
        assertThrows(DeltaPatch.BadPatch::class.java) { DeltaPatch.apply(o, p, out) }
        assertFalse("잘못 만든 파일은 남기지 않음", out.exists())
    }

    @Test
    fun aNonPatchFileIsRefused() {
        val ok = old.copyOfRange(0, 10)
        assertRefused(patch(Rec.Copy(0, 10), Rec.End(10, sha(ok)), magic = "PK\u0003\u0004XXXX"))
    }

    @Test
    fun copyingPastTheEndOfTheInstalledApkIsRefused() {
        val fake = ByteArray(10)
        assertRefused(patch(Rec.Copy(old.size - 5L, 10), Rec.End(10, sha(fake))))
    }

    @Test
    fun aTruncatedPatchIsRefused() {
        val expected = old.copyOfRange(0, 50_000)
        val full = patch(Rec.Lit(Random(3).nextBytes(40_000)), Rec.Copy(0, 50_000), Rec.End(90_000, sha(expected)))
        assertRefused(full.copyOfRange(0, full.size / 2))
    }

    @Test
    fun aPatchWithoutItsEndMarkIsRefused() {
        assertRefused(patch(Rec.Copy(0, 100)))
    }

    @Test
    fun theWrongLengthOrChecksumIsRefused() {
        val expected = old.copyOfRange(0, 100)
        assertRefused(patch(Rec.Copy(0, 100), Rec.End(101, sha(expected))))
        assertRefused(patch(Rec.Copy(0, 100), Rec.End(100, sha(old.copyOfRange(1, 101)))))
    }

    @Test
    fun anUnknownRecordIsRefused() {
        val bad = patch(Rec.Copy(0, 100)).let { it.copyOfRange(0, 8) }
        val body = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(body)).use { it.writeByte('Z'.code) }
        assertRefused(bad + body.toByteArray())
    }

    @Test
    fun stoppingMidwayStopsAndLeavesNothingBehind() {
        val expected = old + old
        val (o, p, out) = files(patch(Rec.Copy(0, old.size), Rec.Copy(0, old.size), Rec.End(expected.size.toLong(), sha(expected))))
        var calls = 0
        assertThrows(UpdateManager.Cancelled::class.java) { DeltaPatch.apply(o, p, out) { ++calls > 3 } }
        assertFalse(out.exists())
    }

    /**
     * 실제 릴리스로 확인 (로컬에서만): DELTA_OLD=이전 APK, DELTA_PATCH=make-deltas.py 가 만든 패치, DELTA_NEW_SHA=새 APK SHA-256
     * 예) v123 → v124: 104.5MB 대신 3.7MB
     */
    @Test
    fun aRealReleasePatchRebuildsTheRealApk() {
        val oldPath = System.getenv("DELTA_OLD")
        val patchPath = System.getenv("DELTA_PATCH")
        val newSha = System.getenv("DELTA_NEW_SHA")
        assumeTrue(oldPath != null && patchPath != null && newSha != null)
        val out = File(tmp.root, "real.apk")
        assertEquals(newSha, DeltaPatch.apply(File(oldPath!!), File(patchPath!!), out))
        assertEquals(newSha, UpdateManager.sha256(out))
    }
}
