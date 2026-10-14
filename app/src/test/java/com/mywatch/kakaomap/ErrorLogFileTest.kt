package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import com.mywatch.kakaomap.data.ErrorLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 오류 기록 파일은 본체와 재생 화면 프로세스(:player)가 함께 쓴다 — 동시에 써도 기록이 잘리거나 깨지지 않아야 한다
 * (전에는 각 프로세스 안에서만 잠그고 파일을 바로 덮어써서, 동시에 쓰면 기록이 사라지거나 반쯤 잘릴 수 있었다)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ErrorLogFileTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun manyWritersAtOnceLeaveAWholeLog() {
        ErrorLog.init(context)
        ErrorLog.clear()
        val start = CountDownLatch(1)
        val writers = (0 until 8).map { w ->
            thread {
                start.await()
                repeat(25) { i -> ErrorLog.record("곳$w", "오류 $w-$i", atMs = 1_000L + w * 100 + i) }
            }
        }
        start.countDown()
        writers.forEach { it.join(10_000) }
        val entries = ErrorLog.entries()
        assertEquals("최근 30개만", 30, entries.size)
        assertTrue(entries.all { it.message.startsWith("오류 ") })
        val file = File(context.filesDir, "error_log.jsonl")
        assertTrue("반쯤 쓴 줄이 없어야", file.readLines().all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun writingLeavesNoTemporaryFilesInBackedUpStorage() {
        ErrorLog.init(context)
        ErrorLog.record("곳", "오류")
        val stray = context.filesDir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("error_log") && it != "error_log.jsonl" }
        assertEquals("백업되는 곳에 잠금·임시 파일을 남기지 않음", emptyList<String>(), stray)
    }
}
