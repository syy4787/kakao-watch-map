package doctor

import doctor.fix.Fixer
import doctor.fix.Journal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GrantTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = Grant.PKG
    private fun journal() = Journal(File(tmp.root, "journal.jsonl"))

    /** 견본은 BATTERY_STATS·WRITE_SECURE_SETTINGS 만 granted=true — 전부 거부 / 전부 허용 본을 만든다 */
    private val noneGranted = sample("app_package.txt").replace("granted=true", "granted=false")
    private val allGranted = sample("app_package.txt").replace("granted=false", "granted=true")

    private val grantCommands = listOf(
        "shell pm grant $pkg android.permission.BATTERY_STATS",
        "shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
        "shell pm grant $pkg android.permission.DUMP",
        "shell pm grant $pkg android.permission.READ_LOGS",
        "shell appops set $pkg GET_USAGE_STATS allow",
        "shell appops set $pkg WRITE_SETTINGS allow",
    )

    /** 처음 조회는 아무것도 없음, 명령 뒤 조회는 전부 허용 */
    private fun adbHappy() = FakeAdb().apply {
        onSequence("shell dumpsys package $pkg", noneGranted, allGranted)
        onSequence("shell appops get $pkg GET_USAGE_STATS", "GET_USAGE_STATS: default\n", "GET_USAGE_STATS: allow; time=+1s ago\n")
        onSequence("shell appops get $pkg WRITE_SETTINGS", "No operations.\n", "WRITE_SETTINGS: allow; time=+1s ago\n")
    }

    @Test
    fun stepsAreTheSixCommandsInOrder() {
        assertEquals(grantCommands, Grant.STEPS.map { ("shell " + it.grant.joinToString(" ")) })
        assertEquals(listOf("grant:android.permission.BATTERY_STATS", "grant:android.permission.WRITE_SECURE_SETTINGS", "grant:android.permission.DUMP",
            "grant:android.permission.READ_LOGS", "grant:GET_USAGE_STATS", "grant:WRITE_SETTINGS"), Grant.STEPS.map { it.id })
        assertEquals(listOf("pm", "revoke", pkg, "android.permission.DUMP"), Grant.STEPS[2].revoke)
        assertEquals(listOf("appops", "set", pkg, "GET_USAGE_STATS", "default"), Grant.STEPS[4].revoke)
    }

    @Test
    fun successPathGrantsEverythingAndJournalsRevokes() {
        val adb = adbHappy()
        val io = FakeConsole()
        val j = journal()
        assertEquals(io.text(), 0, Grant.run(adb, io, j, now = 7000L))
        val calls = adb.joined()
        assertEquals(grantCommands, calls.filter { it in grantCommands })
        assertTrue(calls.indexOf("shell dumpsys package $pkg") < calls.indexOf(grantCommands[0]))   // 이전 상태 조회가 먼저
        assertEquals(2, calls.count { it == "shell dumpsys package $pkg" })                            // 앞뒤로 확인

        val entries = j.entries()
        assertEquals(Grant.STEPS.map { it.id }, entries.map { it.id })
        assertTrue(entries.all { it.ok && !it.undone && it.note == null && it.time == 7000L })
        assertEquals(listOf(listOf("shell", "pm", "revoke", pkg, "android.permission.BATTERY_STATS")), entries[0].undo)
        assertEquals("denied", entries[0].before)
        assertEquals(listOf(listOf("shell", "appops", "set", pkg, "GET_USAGE_STATS", "default")), entries[4].undo)
        assertEquals(listOf(listOf("shell", "appops", "set", pkg, "WRITE_SETTINGS", "allow")), entries[5].applied)

        val out = io.text()
        // 표: 권한마다 한 줄, 전부 성공
        assertEquals(6, out.lines().count { line -> Grant.STEPS.any { line.trimStart().startsWith(it.short) } && line.contains("성공") })
        assertFalse(out.contains("실패"))
        assertTrue(out.contains("BATTERY_STATS granted=true"))
        assertTrue(out.contains("WRITE_SECURE_SETTINGS granted=true"))
        assertTrue(out.contains("GET_USAGE_STATS allow"))
        assertTrue(out.contains("되돌리기"))

        // undo --all 이 전부 회수한다 (역순)
        adb.calls.clear()
        assertEquals(6, Fixer.undo(adb, j, null))
        assertEquals(listOf(
            "shell appops set $pkg WRITE_SETTINGS default",
            "shell appops set $pkg GET_USAGE_STATS default",
            "shell pm revoke $pkg android.permission.READ_LOGS",
            "shell pm revoke $pkg android.permission.DUMP",
            // 지킴이 권한을 거두기 전에 바꿔 둔 설정(밤 모드 등)을 앱이 되돌리게 한다 — 거둔 뒤엔 되돌릴 수 없다 (점검에서 찾음)
            "shell am broadcast --include-stopped-packages -n $pkg/.battery.ShellRestoreReceiver -a $pkg.battery.SHELL_RESTORE",
            "shell pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS",
            "shell pm revoke $pkg android.permission.BATTERY_STATS",
        ), adb.joined())
        assertTrue(j.entries().filter { it.id != Fixer.UNDO_ALL }.all { it.undone })
        assertNull(Fixer.lastError)
    }

    @Test
    fun partialFailureContinuesExplainsInKoreanAndReturnsOne() {
        val adb = adbHappy().apply {
            on("shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS", "", code = 255,
                err = "Exception occurred while executing 'grant':\njava.lang.SecurityException: Permission android.permission.WRITE_SECURE_SETTINGS is not requested by package $pkg\n\tat com.android.server.pm.permission.PermissionManagerService.grantRuntimePermissionInternal(PermissionManagerService.java:1234)\n")
            on("shell pm grant $pkg android.permission.DUMP", "", code = 255,
                err = "Exception occurred while executing 'grant':\njava.lang.SecurityException: Operation not allowed: grant of android.permission.DUMP to $pkg\n")
            // 확인 단계: 실패한 둘만 false
            onSequence("shell dumpsys package $pkg", noneGranted, allGranted.replace("android.permission.WRITE_SECURE_SETTINGS: granted=true", "android.permission.WRITE_SECURE_SETTINGS: granted=false").replace("android.permission.DUMP: granted=true", "android.permission.DUMP: granted=false"))
        }
        val io = FakeConsole()
        val j = journal()
        assertEquals(1, Grant.run(adb, io, j))
        // 실패해도 다음 단계로 계속
        assertEquals(grantCommands, adb.joined().filter { it in grantCommands })
        val out = io.text()
        assertTrue(out, out.contains("앱 버전이 낮아"))
        assertTrue(out, out.contains("펌웨어가 막았습니다"))
        assertTrue(out, out.contains("'권한 없음'"))
        assertTrue(out, out.contains("6개 중 4개 성공"))
        assertTrue(out, out.contains("WRITE_SECURE_SETTINGS granted=false"))
        val entries = j.entries()
        assertEquals(listOf(true, false, false, true, true, true), entries.map { it.ok })
        assertTrue(entries[1].note!!.contains("not requested by package"))
        assertEquals(emptyList<List<String>>(), entries[1].undo)     // 실패한 단계는 되돌릴 것 없음
        assertEquals(listOf(listOf("shell", "pm", "revoke", pkg, "android.permission.READ_LOGS")), entries[3].undo)
        assertEquals(listOf("shell pm revoke $pkg android.permission.BATTERY_STATS", "shell pm revoke $pkg android.permission.READ_LOGS").sorted(),
            entries.flatMap { it.undo ?: emptyList() }.map { it.joinToString(" ") }.filter { it.contains("revoke") }.sorted())
    }

    @Test
    fun unknownPackageTellsToUpdateTheWatchApp() {
        val adb = FakeAdb().apply {
            on("shell dumpsys package $pkg", "Unable to find package: $pkg\n")
            on("shell appops get $pkg", "", code = 255, err = "Error: No UID for $pkg in user 0\n")
            on("shell pm grant $pkg", "", code = 255, err = "Exception occurred while executing 'grant':\njava.lang.IllegalArgumentException: Unknown package: $pkg\n")
            on("shell appops set $pkg", "", code = 255, err = "Error: No UID for $pkg in user 0\n")
        }
        val io = FakeConsole()
        val j = journal()
        assertEquals(1, Grant.run(adb, io, j))
        val out = io.text()
        assertTrue(out, out.contains("설치되지 않았습니다"))
        assertTrue(out, out.contains(Grant.MIN_APP_VERSION))
        assertTrue(out, out.contains("6개 중 0개 성공"))
        assertTrue(out, out.contains("확인 못 함"))     // appops 조회 자체가 실패
        assertTrue(j.entries().all { !it.ok && it.undo!!.isEmpty() })
        adb.calls.clear()
        Fixer.undo(adb, j, null)
        assertTrue(adb.joined().none { it.contains("revoke") || it.contains("appops set") })
    }

    @Test
    fun alreadyGrantedIsReportedAndLeavesNothingToUndo() {
        val adb = FakeAdb().apply {
            on("shell dumpsys package $pkg", allGranted)
            on("shell appops get $pkg GET_USAGE_STATS", "GET_USAGE_STATS: allow; time=+2d3h ago\n")
            on("shell appops get $pkg WRITE_SETTINGS", "WRITE_SETTINGS: allow\n")
        }
        val io = FakeConsole()
        val j = journal()
        assertEquals(0, Grant.run(adb, io, j))
        assertTrue(io.text().contains("성공(이미 있음)"))
        assertTrue(j.entries().all { it.ok && it.before == "granted" && it.undo == emptyList<List<String>>() })
        adb.calls.clear()
        Fixer.undo(adb, j, null)
        assertTrue(adb.calls.isEmpty())     // 우리가 준 것이 아니므로 회수하지 않음
    }

    @Test
    fun verifyParsesDumpsysPackageAndAppops() {
        val m = Grant.verify(sample("app_package.txt"), "GET_USAGE_STATS: allow; time=+1h2m3s ago\nWRITE_SETTINGS: default\n")
        assertEquals(mapOf(
            "android.permission.BATTERY_STATS" to true,
            "android.permission.WRITE_SECURE_SETTINGS" to true,
            "android.permission.DUMP" to false,
            "android.permission.READ_LOGS" to false,
            "GET_USAGE_STATS" to true,
            "WRITE_SETTINGS" to false,
        ), m)
        assertEquals(Grant.STEPS.map { it.key }, m.keys.toList())
        // 아무것도 없으면 전부 false
        assertTrue(Grant.verify("", "No operations.\n").values.none { it })
        assertTrue(Grant.verify("Unable to find package: $pkg\n", "").values.none { it })
        // runtime permissions(사용자별) 가 install permissions 를 덮는다
        val both = "    install permissions:\n      android.permission.DUMP: granted=false\n    User 0: installed=true\n      runtime permissions:\n        android.permission.DUMP: granted=true, flags=[ USER_SET]\n"
        assertEquals(true, Grant.verify(both, "")["android.permission.DUMP"])
        // 다른 패키지 이름이 섞인 줄이나 요청 목록(granted 없음)은 무시
        val requestedOnly = "    requested permissions:\n      android.permission.BATTERY_STATS\n      android.permission.DUMP: restricted=false\n"
        assertTrue(Grant.verify(requestedOnly, "").values.none { it })
        // appops 의 foreground 도 허용으로, ignore/deny 는 불허
        assertEquals(true, Grant.verify("", "GET_USAGE_STATS: foreground")["GET_USAGE_STATS"])
        assertEquals(false, Grant.verify("", "GET_USAGE_STATS: ignore")["GET_USAGE_STATS"])
    }

    @Test
    fun explainMapsTypicalFailuresToKorean() {
        assertTrue(Grant.explain("java.lang.IllegalArgumentException: Unknown package: $pkg").contains("설치"))
        assertTrue(Grant.explain("Permission android.permission.DUMP is not requested by package $pkg").contains("앱 버전이 낮아"))
        assertTrue(Grant.explain("java.lang.SecurityException: Operation not allowed: grant of android.permission.DUMP").contains("펌웨어가 막았습니다"))
        assertTrue(Grant.explain("java.lang.SecurityException: Permission Denial").contains("펌웨어가 막았습니다"))
        assertTrue(Grant.explain("Unknown permission: android.permission.FOO").contains("없는 권한"))
        assertTrue(Grant.explain("error: device offline").contains("통신이 끊겼습니다"))
        assertTrue(Grant.explain("something strange").startsWith("adb 오류"))
    }
}
