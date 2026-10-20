package doctor

import doctor.fix.Catalog
import doctor.fix.Journal
import doctor.fix.Lever
import doctor.fix.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TreatTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val drain = "com.example.drain"

    private fun sampleLevers(): Triple<Facts, List<Finding>, List<Lever>> {
        val f = Analyzer.facts(sampleSnapshot(tmp.root), zone)
        val findings = Analyzer.findings(f)
        return Triple(f, findings, Catalog.levers(f, findings))
    }

    private fun List<Lever>.id(id: String) = first { it.id == id }
    private fun List<Lever>.ids() = map { it.id }

    private fun lever(id: String, tier: Tier, packages: List<String> = emptyList(), recommended: Boolean = true, apply: List<List<String>> = listOf(listOf("shell", "echo", id))) =
        Lever(id, id, "", tier, packages, readState = { null }, apply = apply, undo = { emptyList() }, recommended = recommended)

    // ───────── plan ─────────

    @Test
    fun planTakesRecommendedSafeAndCulpritCautionNothingElse() {
        val (_, _, levers) = sampleLevers()
        val ids = Treat.plan(levers, strong = false).ids()
        assertEquals(listOf("wifi-scan-off", "ble-scan-off", "adaptive-battery-on", "bg-restrict:$drain", "bucket-restricted:$drain", "doze-unwhitelist:$drain"), ids)
        // 규칙 확인: 강함 없음, 주의는 추천된 자동 종류만, 안전은 추천 또는 무손실 설정만
        for (l in Treat.plan(levers, strong = false)) {
            assertTrue(l.id, l.tier != Tier.STRONG)
            if (l.tier == Tier.CAUTION) assertTrue(l.id, l.recommended && l.id.substringBefore(':') in Treat.AUTO_CAUTION)
            if (l.tier == Tier.SAFE) assertTrue(l.id, l.recommended || l.id in Treat.NO_LOSS)
        }
        assertFalse(ids.contains("app-standby-on"))          // 추천 아님(이미 1) + 현재 값 모름
        assertFalse(ids.contains("aod-off"))
        assertFalse(ids.contains("batterystats-reset"))      // 초기화는 파이프라인이 따로 한다
        assertFalse(ids.any { it.startsWith("disable:") || it.startsWith("force-stop:") || it.startsWith("clear:") || it.startsWith("rollback:") })
    }

    @Test
    fun nonRecommendedCautionStaysOut() {
        val (f, _, levers) = sampleLevers()
        val weather = Catalog.byId(f, emptyList(), "bg-restrict:com.example.weather")!!
        assertFalse(weather.recommended)
        assertTrue(Treat.plan(listOf(weather), strong = false).isEmpty())
        assertEquals(listOf("bg-restrict:com.example.weather"), Treat.plan(listOf(weather.copy(recommended = true)), strong = false).ids())
        // 추천되었어도 자동 종류가 아니면 제외 (강제 종료)
        val stop = levers.id("force-stop:$drain").copy(recommended = true)
        assertTrue(Treat.plan(listOf(stop), strong = false).isEmpty())
        assertTrue(Treat.decide(listOf(stop), strong = false).skipped.single().second.contains("메뉴 4"))
    }

    @Test
    fun aodOffIsNeverAutomaticEvenWhenRecommended() {
        val (_, _, levers) = sampleLevers()
        val aod = levers.id("aod-off").copy(recommended = true)
        val plan = Treat.decide(levers.map { if (it.id == "aod-off") aod else it }, strong = false)
        assertFalse(plan.apply.ids().contains("aod-off"))
        assertTrue(plan.skipped.single { it.first.id == "aod-off" }.second.contains("AOD"))
    }

    @Test
    fun ourAppAndSamsungHealthAreProtected() {
        val ours = lever("bg-restrict:${Grant.PKG}", Tier.CAUTION, listOf(Grant.PKG))
        val health = lever("bucket-restricted:com.samsung.android.wear.shealth", Tier.CAUTION, listOf("com.samsung.android.wear.shealth"))
        val oursSafe = lever("disable:${Grant.PKG}", Tier.SAFE, listOf(Grant.PKG))
        val oursStrong = lever("clear:${Grant.PKG}", Tier.STRONG, listOf(Grant.PKG))
        val plan = Treat.decide(listOf(ours, health, oursSafe, oursStrong), strong = true, culprits = listOf(Grant.PKG))
        assertTrue(plan.apply.isEmpty())
        assertEquals(4, plan.skipped.size)
        assertTrue(plan.skipped.all { it.second.contains("전용앱") })
    }

    @Test
    fun strongOnlyWithTheFlagAndOnlyForCulprits() {
        val (_, _, levers) = sampleLevers()
        val clear = levers.id("clear:$drain")
        assertTrue(Treat.plan(listOf(clear), strong = false, culprits = listOf(drain)).isEmpty())
        assertTrue(Treat.decide(listOf(clear), strong = false, culprits = listOf(drain)).skipped.single().second.contains("--strong"))
        assertEquals(listOf("clear:$drain"), Treat.plan(listOf(clear), strong = true, culprits = listOf(drain)).ids())
        assertTrue(Treat.plan(listOf(clear), strong = true, culprits = emptyList()).isEmpty())          // 범인 아님
        assertTrue(Treat.decide(listOf(clear), strong = false, culprits = emptyList()).skipped.isEmpty())  // 무관한 강함 조치는 알리지도 않음
        val gms = levers.id("gms-rollback")
        assertEquals(listOf("gms-rollback"), Treat.plan(listOf(gms), strong = true, culprits = listOf(Analyzer.GMS)).ids())
        assertTrue(Treat.plan(listOf(gms), strong = true).isEmpty())
        // 추천 표시가 붙은 강함 조치도 --strong 이 있어야
        val recStrong = lever("rollback:com.example.sys", Tier.STRONG, listOf("com.example.sys"), recommended = true)
        assertTrue(Treat.plan(listOf(recStrong), strong = false).isEmpty())
        assertEquals(listOf("rollback:com.example.sys"), Treat.plan(listOf(recStrong), strong = true).ids())
        // 재시작은 절대
        val reboot = levers.id("reboot").copy(recommended = true)
        assertTrue(Treat.plan(listOf(reboot), strong = true).isEmpty())
        // 전체 목록에서 --strong: 안전·주의 그대로 + 범인 clear 만 추가
        val all = Treat.plan(levers, strong = true, culprits = listOf(drain)).ids()
        assertEquals(Treat.plan(levers, strong = false).ids() + "clear:$drain", all)
    }

    @Test
    fun noLossSettingsOnlyWhenCurrentStateDiffers() {
        val (_, _, levers) = sampleLevers()
        val standby = levers.id("app-standby-on")
        assertFalse(standby.recommended)
        assertTrue(Treat.plan(listOf(standby), false, states = mapOf("app-standby-on" to "1")).isEmpty())
        assertEquals(listOf("app-standby-on"), Treat.plan(listOf(standby), false, states = mapOf("app-standby-on" to "0")).ids())
        assertEquals(listOf("app-standby-on"), Treat.plan(listOf(standby), false, states = mapOf("app-standby-on" to "null")).ids())   // 키 없음 → 만든다
        assertTrue(Treat.plan(listOf(standby), false).isEmpty())                                      // 모름 + 추천 아님
        val wifi = levers.id("wifi-scan-off")
        assertTrue(wifi.recommended)
        assertEquals(listOf("wifi-scan-off"), Treat.plan(listOf(wifi), false).ids())                  // 모름 + 추천
        assertEquals(listOf("wifi-scan-off"), Treat.plan(listOf(wifi), false, states = mapOf("wifi-scan-off" to "1")).ids())
        val done = Treat.decide(listOf(wifi), false, states = mapOf("wifi-scan-off" to "0"))
        assertTrue(done.apply.isEmpty())
        assertTrue(done.skipped.single().second.contains("이미 적용"))
    }

    @Test
    fun cautionLeversAlreadyAtTargetAreSkipped() {
        val (_, _, levers) = sampleLevers()
        val states = mapOf("bg-restrict:$drain" to "ignore", "bucket-restricted:$drain" to "45", "doze-unwhitelist:$drain" to "not whitelisted")
        val plan = Treat.decide(levers, strong = false, states = states)
        assertFalse(plan.apply.ids().any { it.endsWith(":$drain") })
        assertEquals(3, plan.skipped.count { it.first.id.endsWith(":$drain") && it.second.contains("이미 적용") })
        assertEquals(listOf("bg-restrict:$drain", "bucket-restricted:$drain", "doze-unwhitelist:$drain"),
            Treat.plan(levers, strong = false, states = mapOf("bg-restrict:$drain" to "allow", "bucket-restricted:$drain" to "10", "doze-unwhitelist:$drain" to "whitelisted")).ids().filter { it.endsWith(":$drain") })
    }

    @Test
    fun targetAndAlreadyDone() {
        val (_, _, levers) = sampleLevers()
        assertEquals("0", Treat.target(levers.id("wifi-scan-off")))
        assertEquals("ignore", Treat.target(levers.id("bg-restrict:$drain")))
        assertEquals("restricted", Treat.target(levers.id("bucket-restricted:$drain")))
        assertEquals("not whitelisted", Treat.target(levers.id("doze-unwhitelist:$drain")))
        assertEquals("disabled", Treat.target(levers.id("disable:com.samsung.android.bixby.agent")))
        assertEquals("15000", Treat.target(levers.id("screen-timeout:15000")))
        assertNull(Treat.target(levers.id("reboot")))
        assertNull(Treat.target(levers.id("clear:$drain")))
        assertTrue(Treat.alreadyDone(levers.id("bucket-restricted:$drain"), "restricted"))
        assertTrue(Treat.alreadyDone(levers.id("bucket-restricted:$drain"), "45"))
        assertFalse(Treat.alreadyDone(levers.id("bucket-restricted:$drain"), "10"))
        assertFalse(Treat.alreadyDone(levers.id("wifi-scan-off"), null))
        assertFalse(Treat.alreadyDone(levers.id("reboot"), "anything"))
    }

    // ───────── run (Main 을 통해) ─────────

    private val addr = "192.168.0.23:5555"
    private val pkg = Grant.PKG
    private val noneGranted = sample("app_package.txt").replace("granted=true", "granted=false")
    private val allGranted = sample("app_package.txt").replace("granted=false", "granted=true")

    /** 수집에 답하는 가짜 기기 + 권한 조회(앞: 없음 / 뒤: 전부) + 조치 상태 */
    private fun treatAdb() = FakeAdb(addr).apply {
        on("devices", "List of devices attached\n$addr\tdevice\n\n")
        for ((name, args) in Collect.COMMANDS) on("shell " + args.joinToString(" "), sample("$name.txt"))
        on("shell ls " + Collect.WATCH_CSV, Collect.WATCH_CSV + "\n")
        pullable[Collect.WATCH_CSV] = sample("watch_log.csv")
        onSequence("shell dumpsys package $pkg", noneGranted, allGranted)
        onSequence("shell appops get $pkg GET_USAGE_STATS", "GET_USAGE_STATS: default\n", "GET_USAGE_STATS: allow\n")
        onSequence("shell appops get $pkg WRITE_SETTINGS", "WRITE_SETTINGS: default\n", "WRITE_SETTINGS: allow\n")
        // 계획 때 읽기 → Fixer 가 이전 값 읽기 → 적용 뒤 다시 읽기
        onSequence("shell settings get global wifi_scan_always_enabled", "1\n", "1\n", "0\n")
        onSequence("shell settings get global ble_scan_always_enabled", "1\n", "1\n", "0\n")
        onSequence("shell settings get global adaptive_battery_management_enabled", "0\n", "0\n", "1\n")
        on("shell settings get global app_standby_enabled", "1\n")
        onSequence("shell cmd appops get $drain RUN_ANY_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND: allow\n", "RUN_ANY_IN_BACKGROUND: allow\n", "RUN_ANY_IN_BACKGROUND: ignore\n")
        onSequence("shell am get-standby-bucket $drain", "10\n", "10\n", "45\n")
        on("shell dumpsys batterystats --reset", "Battery stats reset.\n")
    }

    private var lastIo = FakeConsole()

    private fun run(adb: FakeAdb, data: File, vararg args: String, answers: List<String> = emptyList()): Pair<Int, String> {
        val io = FakeConsole(answers)
        lastIo = io
        val code = Main.run(args.toList().toTypedArray(), io, data, { adb }, zone, LocalDate.of(2026, 10, 20), clock = { 1792414865000L }, open = { false })
        return code to io.text()
    }

    private val grantIds = Grant.STEPS.map { it.id }
    private val autoIds = listOf("wifi-scan-off", "ble-scan-off", "adaptive-battery-on", "bg-restrict:$drain", "bucket-restricted:$drain", "doze-unwhitelist:$drain")

    @Test
    fun treatRunsWholePipelineWithoutQuestionsAndUndoAllRevertsIt() {
        val data = tmp.newFolder("data")
        val adb = treatAdb()
        val (code, out) = run(adb, data, "treat")
        assertEquals(out, 0, code)
        assertTrue(lastIo.prompts.isEmpty())          // 질문 없음
        val calls = adb.joined()
        // (b) 권한 → (c) 수집 → (d) 조치 → (e) 초기화 순서
        val iGrant = calls.indexOf("shell pm grant $pkg android.permission.BATTERY_STATS")
        val iCollect = calls.indexOf("shell dumpsys batterystats")
        val iApply = calls.indexOf("shell settings put global wifi_scan_always_enabled 0")
        val iReset = calls.lastIndexOf("shell dumpsys batterystats --reset")
        assertTrue(calls.toString(), iGrant in 0 until iCollect && iCollect < iApply && iApply < iReset)
        assertTrue(calls.contains("shell cmd appops set $drain RUN_ANY_IN_BACKGROUND ignore"))
        assertTrue(calls.contains("shell am set-standby-bucket $drain restricted"))
        assertTrue(calls.contains("shell cmd deviceidle whitelist -$drain"))
        assertFalse(calls.any { it.startsWith("shell pm clear") || it.contains("uninstall-system-updates") || it == "reboot" || it.contains("ambient_enabled") || it.contains("disable-user") })

        val journal = Journal(File(data, "journal.jsonl"))
        assertEquals(grantIds + autoIds, journal.entries().map { it.id })
        assertTrue(journal.entries().all { it.ok && !it.undone })
        assertEquals("1", journal.entries().first { it.id == "wifi-scan-off" }.before)
        assertTrue(Collect.latest(data) != null && File(Collect.latest(data)!!.dir, "report.html").isFile)

        assertTrue(out, out.contains("자동 치료 요약"))
        assertTrue(out, out.contains("적용한 조치 6개"))
        assertTrue(out, out.contains("Wi-Fi 꺼도 스캔 허용 끄기 (wifi-scan-off): 1 → 0"))
        assertTrue(out, out.contains("(adaptive-battery-on): 0 → 1"))
        assertTrue(out, out.contains("(bg-restrict:$drain): allow → ignore"))
        assertTrue(out, out.contains("(bucket-restricted:$drain): 10 → restricted(45)"))
        assertTrue(out, out.contains("범인 앱: ") && out.contains(drain))
        assertTrue(out, out.contains("건너뛴 추천"))
        assertTrue(out, out.contains("clear:$drain") && out.contains("--strong"))      // 강함 후보는 이유와 함께 알려만 준다
        assertTrue(out, out.contains("24시간 뒤 메뉴 2"))
        assertTrue(out, out.contains("report.html"))
        assertTrue(out, out.contains("권한 주기: ${Grant.STEPS.size}개 모두 성공"))
        assertTrue(out, out.contains("배터리 통계 초기화: 완료"))
        assertTrue(out, out.contains(Cause.SOFTWARE.label))

        // undo --all 이 조치와 권한을 모두 되돌린다
        adb.calls.clear()
        val (code2, out2) = run(adb, data, "undo", "--all")
        assertEquals(out2, 0, code2)
        val undo = adb.joined()
        assertTrue(undo.contains("shell pm revoke $pkg android.permission.BATTERY_STATS"))
        assertTrue(undo.contains("shell pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(undo.contains("shell appops set $pkg GET_USAGE_STATS default"))
        assertTrue(undo.contains("shell settings put global wifi_scan_always_enabled 1"))
        assertTrue(undo.contains("shell settings put global adaptive_battery_management_enabled 0"))
        assertTrue(undo.contains("shell cmd appops set $drain RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(undo.contains("shell am set-standby-bucket $drain 10"))
        assertTrue(undo.contains("shell cmd deviceidle whitelist +$drain"))
        assertTrue(undo.indexOf("shell cmd deviceidle whitelist +$drain") < undo.indexOf("shell pm revoke $pkg android.permission.BATTERY_STATS"))   // 역순
        assertTrue(journal.entries().all { it.undone })
        assertTrue(out2.contains("되돌린 조치: ${grantIds.size + autoIds.size}개"))
    }

    @Test
    fun noGrantSkipsPermissionsAndSaysSo() {
        val data = tmp.newFolder("data")
        val adb = treatAdb()
        val (code, out) = run(adb, data, "treat", "--no-grant")
        assertEquals(out, 0, code)
        assertFalse(adb.joined().any { it.contains("pm grant") || it.contains("appops set $pkg") })
        assertTrue(out.contains("--no-grant"))
        assertEquals(autoIds, Journal(File(data, "journal.jsonl")).entries().map { it.id })
    }

    @Test
    fun strongAsksYesPerLeverUnlessYesFlag() {
        val data = tmp.newFolder("data")
        val adb = treatAdb()
        // yes 를 안 치면 건너뜀
        val (code, out) = run(adb, data, "treat", "--strong", "--no-grant", answers = listOf("아니오", "아니오", "아니오"))
        assertEquals(out, 0, code)
        assertFalse(adb.joined().any { it.startsWith("shell pm clear") })
        assertTrue(lastIo.prompts.any { it.contains("yes") })
        assertTrue(out.contains("건너뜀: clear:$drain"))
        assertTrue(out.contains("yes 로 확인하지 않아"))
        // --yes 면 묻지 않고 적용
        val data2 = tmp.newFolder("data2")
        val adb2 = treatAdb()
        val io = FakeConsole()
        val code2 = Main.run(arrayOf("treat", "--strong", "--yes", "--no-grant"), io, data2, { adb2 }, zone, LocalDate.of(2026, 10, 20), clock = { 1792414865000L }, open = { false })
        assertEquals(io.text(), 0, code2)
        assertTrue(io.prompts.none { it.contains("yes") })
        assertTrue(adb2.joined().contains("shell pm clear $drain"))
        assertFalse(adb2.joined().any { it == "reboot" })
        val ids = Journal(File(data2, "journal.jsonl")).entries().map { it.id }
        assertTrue(ids.contains("clear:$drain"))
        assertEquals(autoIds, ids.filter { it in autoIds })
    }

    @Test
    fun treatWithoutDeviceStopsBeforeDoingAnything() {
        val adb = FakeAdb().apply { on("devices", "List of devices attached\n\n") }
        val (code, out) = run(adb, tmp.newFolder("data"), "treat")
        assertEquals(1, code)
        assertTrue(out.contains("메뉴 1"))
        assertEquals(listOf("devices"), adb.joined())
    }

    @Test
    fun failedLeverIsReportedAndCodeIsOne() {
        val data = tmp.newFolder("data")
        val adb = treatAdb().apply { on("shell am set-standby-bucket $drain restricted", "", code = 1, err = "Error: Unknown package: $drain") }
        val (code, out) = run(adb, data, "treat", "--no-grant")
        assertEquals(1, code)
        assertTrue(out, out.contains("실패한 조치 1개"))
        assertTrue(out, out.contains("bucket-restricted:$drain"))
        assertTrue(out, out.contains("적용한 조치 5개"))
        val e = Journal(File(data, "journal.jsonl")).entries().first { it.id == "bucket-restricted:$drain" }
        assertFalse(e.ok)
        assertNotNull(e.note)
        assertTrue(adb.joined().contains("shell dumpsys batterystats --reset"))    // 실패해도 측정 시작까지 간다
    }
}
