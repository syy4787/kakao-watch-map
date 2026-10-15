package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.TubeInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 재생 화면 프로세스(:player) → 앱 본체로 가는 받은 편지. 같은 폴더를 보는 두 [TubeInbox] 가 두 프로세스 대신이다:
 * 글 하나 = 파일 하나(임시 이름으로 다 쓴 뒤 이름 바꾸기), 오래된 것부터 한 번만 읽고 지우기, 50개까지, 이상한 파일은 모른 척
 */
class TubeInboxTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_760_000_000_000L
    private val dir: File get() = File(tmp.root, "tube_inbox")

    private fun inbox() = TubeInbox(dir) { clock }

    private fun names() = dir.list().orEmpty().sorted()

    @Test
    fun messagesFromTheOtherProcessArriveOnceOldestFirst() {
        val player = inbox()
        val app = inbox()
        assertEquals("아직 아무것도 없음 (폴더도 없음)", emptyList<String>(), app.drain().map { it.text })
        player.post("kakaowatch://tube/seen?v=BzYnNdJhZQw")
        clock += 5
        player.post("kakaowatch://tube/recs?seed=BzYnNdJhZQw&ids=pSUydWEqKwE")
        inbox().post("같은 때 보낸 것도 보낸 차례대로")
        assertEquals(3, names().count { it.endsWith(".msg") })
        assertTrue("다 쓴 뒤엔 임시 파일이 남지 않는다", names().none { it.endsWith(".tmp") })
        assertTrue(names().all { Regex("[0-9]{13}-[0-9a-f]{16}\\.msg").matches(it) })
        assertEquals(
            listOf("kakaowatch://tube/seen?v=BzYnNdJhZQw", "kakaowatch://tube/recs?seed=BzYnNdJhZQw&ids=pSUydWEqKwE", "같은 때 보낸 것도 보낸 차례대로"),
            app.drain().map { it.text },
        )
        assertEquals("읽은 것은 지운다", emptyList<String>(), names())
        assertEquals(emptyList<String>(), app.drain().map { it.text })
        assertEquals(emptyList<String>(), player.drain().map { it.text })
    }

    /**
     * 보낸 때를 함께 준다 (받아 두는 기간을 보낸 때부터 세게 — 앱을 한참 안 열어도 늘어나지 않게). 보낸 지 7일이 지난 글은
     * 읽지 않고 지우고, 보낼 때도 그런 글을 지운다 (앱을 안 열어도 쌓여 남지 않게). 시계가 뒤로 가 앞날이 된 것은 지금으로
     */
    @Test
    fun notesCarryWhenTheyWereSentAndOldOnesAreDropped() {
        val player = inbox()
        player.post("오래된 글")
        clock += TubeInbox.MAX_AGE_MS + 1
        val sentAt = clock
        player.post("새 글")
        assertEquals("보낼 때 오래된 글은 지운다", 1, names().size)
        clock += 1_000
        assertEquals(listOf(TubeInbox.Note("새 글", sentAt)), inbox().drain())
        player.post("다른 글")
        clock += TubeInbox.MAX_AGE_MS + 1
        assertEquals("읽을 때도 오래된 글은 버린다", emptyList<TubeInbox.Note>(), inbox().drain())
        assertEquals(emptyList<String>(), names())
        // 시계가 뒤로 감: 보낸 때가 앞날이면 지금으로
        player.post("앞날")
        clock -= 60_000
        assertEquals(listOf(TubeInbox.Note("앞날", clock)), inbox().drain())
    }

    @Test
    fun onlyTheNewestFiftyAreKept() {
        val player = inbox()
        repeat(55) {
            clock++
            player.post("글 $it")
        }
        assertEquals(TubeInbox.MAX, names().size)
        assertEquals(50, TubeInbox.MAX)
        assertEquals((5 until 55).map { "글 $it" }, inbox().drain().map { it.text })
    }

    @Test
    fun emptyOrTooLongTextIsNotPosted() {
        val player = inbox()
        player.post("")
        player.post("   \n")
        player.post("x".repeat(TubeInbox.MAX_TEXT + 1))
        assertEquals(emptyList<String>(), names())
        player.post("x".repeat(TubeInbox.MAX_TEXT))
        assertEquals(listOf("x".repeat(2048)), inbox().drain().map { it.text })
        assertEquals(2048, TubeInbox.MAX_TEXT)
    }

    /** 쓰다 만 글(프로세스가 쓰는 중에 죽음)은 읽지 않고, 1분 넘게 남았으면 지운다 */
    @Test
    fun aHalfWrittenMessageIsNeverReadAndIsCleanedUpLater() {
        dir.mkdirs()
        val fresh = File(dir, "${clock}-00000001deadbeef.msg.tmp").apply { writeText("kakaowatch://tube/seen?v=BzYn") }
        val stale = File(dir, "${clock - 61_000}-00000002deadbeef.msg.tmp").apply { writeText("kakaowatch://tube/se") }
        inbox().post("다 쓴 글")
        assertEquals(listOf("다 쓴 글"), inbox().drain().map { it.text })
        assertTrue("방금 쓰기 시작한 것은 두고", fresh.exists())
        assertFalse("1분 넘은 것은 지운다", stale.exists())
        clock += 61_000
        assertEquals(emptyList<String>(), inbox().drain().map { it.text })
        assertFalse(fresh.exists())
    }

    @Test
    fun filesThatAreNotMessagesAreIgnored() {
        dir.mkdirs()
        val strangers = listOf("hello.msg", "123-abc.msg", "notes.txt", "${clock}-00000001deadbeef.msg.bak", "${clock}-0000000Xdeadbeef.msg")
            .map { File(dir, it).apply { writeText("kakaowatch://tube/seen?v=BzYnNdJhZQw") } }
        val folder = File(dir, "${clock}-00000003deadbeef.msg").apply { mkdirs() }
        inbox().post("진짜 글")
        assertEquals(listOf("진짜 글"), inbox().drain().map { it.text })
        assertTrue("남의 파일은 건드리지 않는다", strangers.all { it.exists() })
        assertTrue(folder.isDirectory)
        // 50개를 셀 때도 넣지 않는다
        repeat(50) { inbox().post("글 $it") }
        assertEquals(50, inbox().drain().map { it.text }.size)
    }

    /** 못 읽는 글(UTF-8 이 아님 · 너무 큼 · 빈 것)은 지우고 건너뛴다 — 다음에 또 걸리지 않게 */
    @Test
    fun unreadableMessagesAreDeletedAndSkipped() {
        dir.mkdirs()
        clock -= 10
        val broken = File(dir, "${clock}-00000001deadbeef.msg").apply { writeBytes(byteArrayOf(0x6b, 0xff.toByte(), 0xfe.toByte(), 0x00)) }
        val huge = File(dir, "${clock}-00000002deadbeef.msg").apply { writeText("x".repeat(TubeInbox.MAX_TEXT * 5)) }
        val tooLong = File(dir, "${clock}-00000003deadbeef.msg").apply { writeText("가".repeat(TubeInbox.MAX_TEXT + 1)) }
        val blank = File(dir, "${clock}-00000004deadbeef.msg").apply { writeText("") }
        clock += 10
        inbox().post("읽을 수 있는 글")
        assertEquals(listOf("읽을 수 있는 글"), inbox().drain().map { it.text })
        assertTrue(listOf(broken, huge, tooLong, blank).none { it.exists() })
    }

    @Test
    fun neverThrowsEvenWhenTheFolderCannotBeMade() {
        tmp.root.mkdirs()
        dir.writeText("폴더 자리에 파일")
        val inbox = inbox()
        inbox.post("kakaowatch://tube/seen?v=BzYnNdJhZQw")
        assertEquals(emptyList<String>(), inbox.drain().map { it.text })
        assertTrue(dir.isFile)
    }
}
