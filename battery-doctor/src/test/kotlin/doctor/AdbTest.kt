package doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var n = 0

    /** `echo "$@"` 로 인자를 그대로 돌려주는 가짜 adb 실행 파일 */
    private fun fakeAdb(body: String): File {
        assumeTrue("bash 가 있는 환경에서만", File("/bin/bash").exists())
        val f = tmp.newFile("adb${n++}")
        f.writeText("#!/bin/bash\n$body\n")
        f.setExecutable(true)
        return f
    }

    @Test
    fun passesArgumentsThroughAndDecodesUtf8() {
        val adb = RealAdb(fakeAdb("echo \"\$@\"; echo \"한글 출력\"").path, serial = null)
        val r = adb.run(listOf("shell", "getprop", "ro.product.model"))
        assertEquals(0, r.code)
        assertEquals("shell getprop ro.product.model\n한글 출력\n", r.out)
        assertEquals("", r.err)
    }

    @Test
    fun insertsSerialWhenGiven() {
        val adb = RealAdb(fakeAdb("echo \"\$@\"").path, serial = "192.168.0.23:5555")
        val r = adb.run(listOf("shell", "date"))
        assertEquals("-s 192.168.0.23:5555 shell date\n", r.out)
    }

    @Test
    fun devicesDoesNotUseSerialAndParsesStates() {
        val adb = RealAdb(fakeAdb("echo \"args=\$@\" >&2; printf 'List of devices attached\\n192.168.0.23:5555\\tdevice\\n192.168.0.9:5555\\toffline\\n\\n'").path, serial = "x")
        assertEquals(listOf("192.168.0.23:5555"), adb.devices())
        val states = Adb.parseDevices("List of devices attached\n192.168.0.23:5555\tdevice\n192.168.0.9:5555\toffline\nemulator-5554\tunauthorized\n")
        assertEquals(mapOf("192.168.0.23:5555" to "device", "192.168.0.9:5555" to "offline", "emulator-5554" to "unauthorized"), states)
    }

    @Test
    fun timeoutKillsTheProcess() {
        val adb = RealAdb(fakeAdb("sleep 5; echo done").path, serial = null)
        val t0 = System.currentTimeMillis()
        val r = adb.run(listOf("shell", "sleep"), timeoutSec = 1)
        assertNotEquals(0, r.code)
        assertTrue(r.err.contains("시간 초과"))
        assertTrue("1초 타임아웃이 5초 안에 끝나야 함", System.currentTimeMillis() - t0 < 4500)
    }

    @Test
    fun nonZeroExitAndStderrAreReported() {
        val adb = RealAdb(fakeAdb("echo 'error: device offline' >&2; exit 1").path, serial = null)
        val r = adb.run(listOf("shell", "ls"))
        assertEquals(1, r.code)
        assertTrue(r.err.contains("device offline"))
        assertFalse(r.ok)
    }

    @Test
    fun missingExecutableIsAnErrorNotAnException() {
        val adb = RealAdb(File(tmp.root, "no-such-adb").path, serial = null)
        val r = adb.run(listOf("devices"))
        assertNotEquals(0, r.code)
        assertTrue(r.err.isNotBlank())
    }

    @Test
    fun pullCopiesViaAdbAndReportsSuccessOnlyWhenFileExists() {
        // 가짜 adb: pull <remote> <local> → local 에 내용 기록
        val adb = RealAdb(fakeAdb("if [ \"\$1\" = pull ]; then echo hello > \"\$3\"; echo '1 file pulled'; else exit 1; fi").path, serial = null)
        val local = File(tmp.root, "sub/log.csv")
        assertTrue(adb.pull("/sdcard/x.csv", local))
        assertEquals("hello\n", local.readText())
        val failing = RealAdb(fakeAdb("echo 'adb: error: remote object does not exist' >&2; exit 1").path, serial = null)
        assertFalse(failing.pull("/sdcard/none.csv", File(tmp.root, "none.csv")))
    }

    @Test
    fun withSerialMakesACopyBoundToThatDevice() {
        val base = RealAdb("adb", serial = null)
        val bound = base.withSerial("10.0.0.2:5555")
        assertEquals("10.0.0.2:5555", (bound as RealAdb).serial)
        assertEquals("adb", bound.adbPath)
    }
}
