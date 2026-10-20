package doctor

import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream

/** 메뉴·안내 출력과 입력. 테스트는 가짜를 끼운다 */
interface Console {
    fun println(s: String = "")
    /** 입력 끝(EOF)이면 null */
    fun readLine(prompt: String): String?
}

/** 표준 입출력 콘솔 — 코드 페이지와 무관하게 UTF-8 로 쓴다 */
class StdConsole : Console {
    private val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    private val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))

    override fun println(s: String) { out.println(s) }

    override fun readLine(prompt: String): String? {
        out.print(prompt)
        out.flush()
        return try { reader.readLine()?.trim() } catch (_: Exception) { null }
    }
}

/** 입력 없이 출력만 모으는 콘솔(배치 실행용) */
class SilentConsole(private val sink: (String) -> Unit = { kotlin.io.println(it) }) : Console {
    override fun println(s: String) = sink(s)
    override fun readLine(prompt: String): String? = null
}
