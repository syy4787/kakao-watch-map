package doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AdbSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(file: File, vararg entries: Pair<String, String>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    private fun platformTools(file: File) = zipOf(file,
        "platform-tools/adb.exe" to "MZ-adb", "platform-tools/AdbWinApi.dll" to "dll", "platform-tools/AdbWinUsbApi.dll" to "dll",
        "platform-tools/NOTICE.txt" to "notice")

    @Test
    fun theAdbFoundByTheLauncherComesFirstThenTheSavedOneThenThisFolder() {
        val app = tmp.newFolder("app")
        val saved = File(tmp.root, "sdk/adb.exe").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals("C:\\sdk\\adb.exe", AdbSetup.resolve("C:\\sdk\\adb.exe", saved.path, app, windows = true))
        assertEquals(saved.path, AdbSetup.resolve("  ", saved.path, app, windows = true))
        // 저장된 경로의 파일이 없어졌으면(폴더를 옮김) 건너뛴다
        assertEquals("adb", AdbSetup.resolve(null, File(tmp.root, "gone/adb.exe").path, app, windows = true))
        val local = File(app, "platform-tools/adb.exe").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(local.path, AdbSetup.resolve(null, File(tmp.root, "gone/adb.exe").path, app, windows = true))
        assertEquals(local.path, AdbSetup.resolve("", null, app, windows = true))
        // 리눅스·맥은 adb (확장자 없음)
        assertEquals("adb", AdbSetup.resolve(null, null, app, windows = false))
    }

    @Test
    fun onlyPlatformToolsIsUnpackedAndNothingEscapesTheFolder() {
        val app = tmp.newFolder("app")
        val zip = zipOf(File(tmp.root, "pt.zip"),
            "platform-tools/adb.exe" to "MZ-adb", "platform-tools/AdbWinApi.dll" to "dll", "platform-tools/lib64/libc++.so" to "so",
            "other/readme.txt" to "no", "../evil.txt" to "evil", "platform-tools/../../evil2.txt" to "evil",
            "platform-tools\\..\\..\\evil3.txt" to "evil", "/abs/evil4.txt" to "evil")
        val adb = AdbSetup.install(zip, app, windows = true)
        assertEquals(File(app, "platform-tools/adb.exe").canonicalFile, adb?.canonicalFile)
        assertEquals("MZ-adb", adb!!.readText())
        assertTrue(File(app, "platform-tools/AdbWinApi.dll").isFile)
        assertTrue(File(app, "platform-tools/lib64/libc++.so").isFile)
        assertFalse(File(app, "other/readme.txt").exists())
        for (evil in listOf("evil.txt", "evil2.txt", "evil3.txt")) {
            assertFalse(evil, File(tmp.root, evil).exists())
            assertFalse(evil, File(app, evil).exists())
        }
        assertFalse(File("/abs/evil4.txt").exists())
    }

    @Test
    fun aZipWithoutAdbInstallsNothingUsable() {
        val app = tmp.newFolder("app")
        val zip = zipOf(File(tmp.root, "pt.zip"), "platform-tools/fastboot.exe" to "x", "readme.txt" to "x")
        assertNull(AdbSetup.install(zip, app, windows = true))
        val notZip = File(tmp.root, "page.zip").apply { writeText("<html>not a zip</html>") }
        assertNull(AdbSetup.install(notZip, app, windows = true))
    }

    @Test
    fun itAsksBeforeDownloadingAndSayingNoDownloadsNothing() {
        val app = tmp.newFolder("app")
        var downloads = 0
        val io = FakeConsole(listOf(""))
        val got = AdbSetup.offer(io, app, windows = true, download = { _, _ -> downloads++ }, check = { true })
        assertNull(got)
        assertEquals(0, downloads)
        val text = io.text()
        assertTrue(text, text.contains(AdbSetup.WINDOWS_URL))
        assertTrue(text, text.contains(AdbSetup.LICENSE_URL))
        // 직접 까는 길도 알려 준다 — 이 폴더에 풀면 다음부터 알아서 찾는다
        assertTrue(text, text.contains(File(app, "platform-tools").absolutePath))
        assertEquals(1, io.prompts.size)
    }

    @Test
    fun yesDownloadsFromGoogleUnpacksChecksAndCleansUp() {
        val app = tmp.newFolder("app")
        val asked = ArrayList<String>()
        val checked = ArrayList<String>()
        val io = FakeConsole(listOf("y"))
        val got = AdbSetup.offer(io, app, windows = true,
            download = { url, to -> asked += url; platformTools(to) },
            check = { checked += it; true })
        val adb = File(app, "platform-tools/adb.exe")
        assertEquals(adb.path, got)
        assertEquals(listOf(AdbSetup.WINDOWS_URL), asked)
        assertEquals(listOf(adb.path), checked)
        assertTrue(adb.isFile)
        // 받은 zip 은 지운다
        assertEquals(listOf("platform-tools"), app.list()!!.toList())
        assertTrue(io.text(), io.text().contains("준비"))
    }

    @Test
    fun aFailedDownloadOrAnAdbThatDoesNotRunIsReportedAndNotUsed() {
        val app = tmp.newFolder("app")
        val io = FakeConsole(listOf("y"))
        assertNull(AdbSetup.offer(io, app, windows = true, download = { _, _ -> throw IOException("HTTP 404") }, check = { true }))
        assertTrue(io.text(), io.text().contains("HTTP 404"))
        assertFalse(File(app, "platform-tools/adb.exe").exists())
        assertEquals(0, app.list()!!.size)

        val io2 = FakeConsole(listOf("예"))
        assertNull(AdbSetup.offer(io2, app, windows = true, download = { _, to -> platformTools(to) }, check = { false }))
        assertTrue(io2.text(), io2.text().contains("실행되지 않"))
    }

    @Test
    fun noWatchCommandRunsAdbWhenThereIsNoAdb() {
        val data = tmp.newFolder("data")
        val zone = ZoneId.of("Asia/Seoul")
        for (cmd in listOf("connect", "collect", "reset", "grant", "treat")) {
            val adb = FakeAdb()
            val io = FakeConsole()
            val code = Main.run(arrayOf(cmd), io, data, { adb }, zone, LocalDate.of(2026, 10, 20), clock = { 1792414865000L }, open = { false },
                ensureAdb = { false })
            assertEquals(cmd, 1, code)
            assertTrue("$cmd ran adb: ${adb.joined()}", adb.calls.isEmpty())
        }
        // 워치 없이 되는 일(지난 수집 다시 분석·증거 패키지)은 adb 를 묻지 않는다
        var asked = 0
        val io = FakeConsole()
        Main.run(arrayOf("analyze"), io, data, { FakeAdb() }, zone, LocalDate.of(2026, 10, 20), clock = { 1792414865000L }, open = { false },
            ensureAdb = { asked++; false })
        assertEquals(0, asked)
        assertNotNull(io.text())
    }
}
