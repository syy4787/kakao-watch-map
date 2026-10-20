package doctor

import java.io.File

/**
 * 테스트용 가짜 adb. 호출을 전부 기록하고, 인자 문자열(공백으로 이은 것)에 대한 응답표로 답한다.
 * 응답표에 없는 명령은 성공(빈 출력)으로 처리한다.
 */
class FakeAdb(override val serial: String? = null) : AdbRunner {
    val calls = ArrayList<List<String>>()
    /** 인자 문자열 접두 → 응답 차례(마지막 것은 계속 반복) */
    val responses = LinkedHashMap<String, ArrayDeque<Result>>()
    /** pull 로 만들어 줄 파일 내용(remote 경로 → 내용); 없으면 실패 */
    val pullable = HashMap<String, String>()
    val pulls = ArrayList<Pair<String, File>>()

    fun on(prefix: String, out: String, code: Int = 0, err: String = "") { responses[prefix] = ArrayDeque(listOf(Result(code, out, err))) }

    /** 같은 명령이 불릴 때마다 차례로 다른 답 */
    fun onSequence(prefix: String, vararg outs: String) { responses[prefix] = ArrayDeque(outs.map { Result(0, it, "") }) }

    override fun run(args: List<String>, timeoutSec: Int): Result {
        calls += args
        val joined = args.joinToString(" ")
        val hit = responses.entries.firstOrNull { joined == it.key } ?: responses.entries.firstOrNull { joined.startsWith(it.key) }
        val q = hit?.value ?: return Result(0, "", "")
        return if (q.size > 1) q.removeFirst() else q.first()
    }

    override fun pull(remote: String, local: File): Boolean {
        pulls += remote to local
        val body = pullable[remote] ?: return false
        local.parentFile?.mkdirs()
        local.writeText(body)
        return true
    }

    override fun withSerial(serial: String?): AdbRunner = this

    fun joined(): List<String> = calls.map { it.joinToString(" ") }
}

/** 미리 준비한 답을 차례로 돌려주는 콘솔. 출력은 전부 모은다 */
class FakeConsole(answers: List<String> = emptyList()) : Console {
    private val queue = ArrayDeque(answers)
    val out = StringBuilder()
    val prompts = ArrayList<String>()
    override fun println(s: String) { out.append(s).append('\n') }
    override fun readLine(prompt: String): String? {
        prompts += prompt
        return queue.removeFirstOrNull()
    }
    fun text() = out.toString()
}

/** src/test/resources/samples/<name> 을 읽는다 */
fun sample(name: String): String {
    val url = Thread.currentThread().contextClassLoader.getResource("samples/$name")
        ?: error("견본 없음: samples/$name")
    return url.readText()
}

fun sampleFile(name: String): File = File(Thread.currentThread().contextClassLoader.getResource("samples/$name")!!.toURI())

/** 견본 전부를 복사한 스냅샷 폴더 `root/snapshots/20261019-220105/` */
fun sampleSnapshot(root: File, except: Set<String> = emptySet()): Snapshot {
    val dir = File(root, "snapshots/20261019-220105").apply { mkdirs() }
    val src = sampleFile("battery.txt").parentFile
    src.listFiles()!!.filter { it.isFile && it.nameWithoutExtension !in except && it.name !in except }.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    File(dir, "meta.properties").writeText("time=1792414865000\nserial=192.168.0.23\\:5555\nmodel=SM-L350\n")
    return Snapshot(dir)
}
