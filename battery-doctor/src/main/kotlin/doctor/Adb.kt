package doctor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** adb 한 번 실행한 결과 */
data class Result(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

/** adb 실행기. 실제 구현은 [RealAdb], 테스트와 e2e 는 가짜를 끼운다 */
interface AdbRunner {
    /** 묶인 기기(IP:포트). 없으면 adb 가 유일한 기기를 고른다 */
    val serial: String? get() = null

    /** `adb <args>` 실행. 기기 serial 이 정해져 있으면 `-s serial` 을 앞에 끼운다 */
    fun run(args: List<String>, timeoutSec: Int = 120): Result

    /** `adb pull remote local`. 파일이 실제로 생겼을 때만 true */
    fun pull(remote: String, local: File): Boolean

    /** 같은 adb 를 다른 기기에 묶은 실행기 */
    fun withSerial(serial: String?): AdbRunner = this
}

object Adb {
    /** `adb devices` 출력 → serial → 상태(device/offline/unauthorized…) */
    fun parseDevices(out: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (raw in out.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("List of devices") || line.startsWith("*")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) m[parts[0]] = parts[1]
        }
        return m
    }
}

/**
 * ProcessBuilder 로 adb.exe/adb 를 실행한다. 출력은 UTF-8 로 읽고 CRLF 를 LF 로 맞춘다.
 * [timeoutSec] 이 지나면 프로세스를 강제로 끝내고 code=-1 을 돌려준다.
 */
class RealAdb(val adbPath: String, override val serial: String?) : AdbRunner {

    override fun withSerial(serial: String?): AdbRunner = RealAdb(adbPath, serial)

    override fun run(args: List<String>, timeoutSec: Int): Result {
        val full = ArrayList<String>()
        if (serial != null) { full += "-s"; full += serial }
        full += args
        return exec(full, timeoutSec)
    }

    /** 연결된 기기 중 상태가 device 인 serial 목록 (`-s` 없이 실행) */
    fun devices(): List<String> =
        Adb.parseDevices(exec(listOf("devices"), 30).out).filterValues { it == "device" }.keys.toList()

    override fun pull(remote: String, local: File): Boolean {
        local.parentFile?.mkdirs()
        val r = run(listOf("pull", remote, local.path), timeoutSec = 300)
        return r.ok && local.isFile && local.length() > 0
    }

    private fun exec(args: List<String>, timeoutSec: Int): Result {
        val cmd = listOf(adbPath) + args
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return Result(-2, "", "adb 를 실행할 수 없습니다 ($adbPath): ${e.message}")
        }
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val tOut = drain(process.inputStream, outBuf)
        val tErr = drain(process.errorStream, errBuf)
        process.outputStream.close()
        val finished = try {
            process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!finished) {
            // 자식(셸이 띄운 adb 등)까지 끝내야 출력 파이프가 닫힌다
            try { process.toHandle().descendants().forEach { it.destroyForcibly() } } catch (_: Exception) {}
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            tOut.join(500); tErr.join(500)
            return Result(-1, normalize(outBuf), normalize(errBuf) + "\n시간 초과(${timeoutSec}초): adb ${args.joinToString(" ")}")
        }
        tOut.join(); tErr.join()
        return Result(process.exitValue(), normalize(outBuf), normalize(errBuf))
    }

    private fun drain(input: InputStream, into: ByteArrayOutputStream): Thread {
        val t = Thread {
            try { input.use { it.copyTo(into) } } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun normalize(buf: ByteArrayOutputStream): String =
        String(buf.toByteArray(), Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
}
