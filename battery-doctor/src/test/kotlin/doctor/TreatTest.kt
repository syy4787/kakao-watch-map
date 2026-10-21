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
        assertEquals(autoIds, ids)
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

    /** 2026년 Play 서비스 과소모: 분석이 Play 서비스를 짚으면 캐시 정리(안전)와 Play 서비스 다시 시작(주의)을 자동으로 */
    @Test
    fun playServicesFlaggedGetsCacheTrimAndRestartAutomatically() {
        val (f, findings, _) = sampleLevers()
        val gms = Finding("gms-share", Severity.ABNORMAL, "Google Play 서비스가 추정 전력의 40%", emptyList(), listOf(Analyzer.GMS), category = "gms")
        val levers = Catalog.levers(f, findings + gms)
        val trim = levers.id("trim-caches")
        assertTrue(trim.recommended)
        assertEquals(Tier.SAFE, trim.tier)
        assertEquals(listOf(listOf("shell", "pm", "trim-caches", "999999999999")), trim.apply)
        val restart = levers.id("gms-restart")
        assertEquals(Tier.CAUTION, restart.tier)
        assertEquals(listOf(listOf("shell", "am", "force-stop", Analyzer.GMS)), restart.apply)
        val ids = Treat.plan(levers, strong = false).ids()
        assertTrue(ids.toString(), ids.contains("trim-caches") && ids.contains("gms-restart"))
        assertTrue(ids.indexOf("trim-caches") < ids.indexOf("gms-restart"))
        // Play 서비스는 여전히 끄기·데이터 삭제·백그라운드 제한 대상이 아니다
        assertFalse(levers.any { it.id in setOf("disable:${Analyzer.GMS}", "clear:${Analyzer.GMS}", "bg-restrict:${Analyzer.GMS}") })
    }

    @Test
    fun withoutPlayServicesTroubleTheCacheTrimIsOfferedButNotApplied() {
        val (f, _, _) = sampleLevers()
        val levers = Catalog.levers(f, emptyList())
        assertFalse(levers.id("trim-caches").recommended)
        assertFalse(levers.any { it.id == "gms-restart" })
        assertFalse(Treat.plan(levers, strong = false).ids().contains("trim-caches"))
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
        on(treated, "Broadcasting: Intent { flg=0x400000 }\nBroadcast completed: result=0, data=\"treated:ok\"\n")
    }

    /** 자동 치료를 마치고 워치 앱에 보내는 '치료함' (셸만 보낼 수 있는 받는 곳) — 실제로 한 치료를 붙여서 */
    private val treated = "shell am broadcast --include-stopped-packages -n ${Collect.SHELL_RECEIVER} -a ${Collect.TREATED_ACTION}"

    private var lastIo = FakeConsole()

    private fun run(adb: FakeAdb, data: File, vararg args: String, answers: List<String> = emptyList()): Pair<Int, String> {
        val io = FakeConsole(answers)
        lastIo = io
        val code = Main.run(args.toList().toTypedArray(), io, data, { adb }, zone, LocalDate.of(2026, 10, 20), clock = { 1792414865000L }, open = { false })
        return code to io.text()
    }

    private val grantIds = Grant.STEPS.map { it.id }
    /** 견본 덤프는 Play 서비스도 짚으므로(2026년 패턴) 캐시 정리·Play 서비스 다시 시작이 함께 들어간다 */
    private val autoIds = listOf("wifi-scan-off", "ble-scan-off", "adaptive-battery-on", "trim-caches", "bg-restrict:$drain", "bucket-restricted:$drain", "doze-unwhitelist:$drain", "gms-restart")

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
        // 캐시 정리·Play 서비스 다시 시작을 했으면 측정 시작 뒤 워치 앱에 알린다 — 워치가 치료 전 기록으로 또 짚지 않게 거기서 새로 잰다
        val iTold = calls.indexOfFirst { it.startsWith(treated) }
        assertTrue(calls.toString(), iTold > iReset)
        // 워치가 문제마다 단계를 넘기도록 무엇을 했는지도 (Play 서비스를 다시 시작했으면 Play 서비스만 다음 단계로 — 리뷰)
        assertTrue(calls[iTold], calls[iTold].endsWith("--es did trim-caches,gms-restart"))
        assertTrue(calls.contains("shell cmd appops set $drain RUN_ANY_IN_BACKGROUND ignore"))
        assertTrue(calls.contains("shell am set-standby-bucket $drain restricted"))
        assertTrue(calls.contains("shell cmd deviceidle whitelist -$drain"))
        assertTrue(calls.contains("shell pm trim-caches 999999999999"))
        assertTrue(calls.contains("shell am force-stop com.google.android.gms"))
        assertFalse(calls.any { it.startsWith("shell pm clear") || it.contains("uninstall-system-updates") || it == "reboot" || it.contains("ambient_enabled") || it.contains("disable-user") })

        val journal = Journal(File(data, "journal.jsonl"))
        assertEquals(grantIds + autoIds, journal.entries().map { it.id })
        assertTrue(journal.entries().all { it.ok && !it.undone })
        assertEquals("1", journal.entries().first { it.id == "wifi-scan-off" }.before)
        assertTrue(Collect.latest(data) != null && File(Collect.latest(data)!!.dir, "report.html").isFile)

        assertTrue(out, out.contains("자동 치료 요약"))
        assertTrue(out, out.contains("적용한 조치 ${autoIds.size}개"))
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
        assertTrue(out, out.contains("워치 앱에 치료를 알렸습니다"))
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
        // 캐시 정리·Play 서비스 다시 시작은 되돌릴 것이 없는 일회성 조치 — 나머지는 모두 되돌린다. 끝에 '모두 되돌리기' 기록 (리뷰)
        assertEquals(doctor.fix.Fixer.UNDO_ALL, journal.entries().last().id)
        val applied = journal.entries().dropLast(1)
        assertTrue(applied.all { it.undone || it.undo == null })
        val oneShot = applied.count { it.undo == null }
        assertEquals(2, oneShot)
        assertTrue(out2, out2.contains("되돌린 조치: ${grantIds.size + autoIds.size - oneShot}개"))
    }

    /** 예전 워치 앱(이 신호를 모름)·앱별 통계 권한 없음이면 그렇다고 — 워치의 알려진 문제는 1~2시간 치료 전 기록으로 보일 수 있다 */
    @Test
    fun anOldWatchAppIsNotToldAndTheSummarySaysWhatThatMeans() {
        val data = tmp.newFolder("data")
        val adb = treatAdb().apply { on(treated, "Broadcasting: Intent { flg=0x400000 }\nBroadcast completed: result=0\n") }
        val (_, out) = run(adb, data, "treat")
        assertTrue(out, out.contains("워치 앱에 치료를 알리지 못했습니다"))
    }

    /**
     * 워치 앱이 받았으면 워치와 같은 말(10분 뒤부터 앱별 기록을 새로)로, 받았지만 앱별 통계 권한이 없으면(펌웨어가 막음 등) 그렇다고 — 비정상
     * 소모만 치료 뒤로 다시 재고 알려진 문제는 못 본다 (리뷰)
     */
    @Test
    fun theSummarySaysWhatTheWatchAppCanSeeAfterTheTreatment() {
        val (_, out) = run(treatAdb(), tmp.newFolder("data"), "treat")
        val told = out.lines().single { it.contains("워치 앱에 치료를 알렸습니다") }
        assertTrue(told, told.contains("10분 뒤부터") && !told.contains("지금부터"))
        val adb = treatAdb().apply { on(treated, "Broadcasting: Intent { flg=0x400000 }\nBroadcast completed: result=0, data=\"treated:nostats\"\n") }
        val (_, out2) = run(adb, tmp.newFolder("data2"), "treat")
        val told2 = out2.lines().single { it.contains("워치 앱에 치료를 알렸습니다") }
        assertTrue(told2, told2.contains("앱별 통계 권한이 없어") && told2.contains("메뉴 7"))
        // 권한 주기를 건너뛰었으면(--no-grant) 권한 표가 없다 — '위 표'를 가리키지 않는다 (리뷰)
        val adb3 = treatAdb().apply { on(treated, "Broadcasting: Intent { flg=0x400000 }\nBroadcast completed: result=0, data=\"treated:nostats\"\n") }
        val (_, out3) = run(adb3, tmp.newFolder("data3"), "treat", "--no-grant")
        val told3 = out3.lines().single { it.contains("워치 앱에 치료를 알렸습니다") }
        assertTrue(told3, told3.contains("메뉴 7(권한 주기)을 해 보세요") && !told3.contains("위 표"))
    }

    /** 워치가 Play 서비스 과소모를 '다음 단계 뒤에도 계속'으로 적었으면, 또 한 캐시 정리·다시 시작이 끝이 아니라고 — 남은 것은 업데이트·오류 보내기 (리뷰) */
    @Test
    fun aPlayServicesIssueThatOutlastedTheTreatmentPointsOnward() {
        val at = sample("date.txt").trim().toLong() * 1000 + 5_000
        val adb = treatAdb().apply {
            on("shell am broadcast --include-stopped-packages -n ${Collect.SHELL_RECEIVER} -a ${Collect.SUMMARY_ACTION}", "Broadcast completed: result=0, data=\"summary:ok\"\n")
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[지표] at=$at idle_pph=3.4 app_window_min=120 issue_gms=2 issue_home=0 issue_face=0 back_gms=2 back_home=0\n"
        }
        val (_, out) = run(adb, tmp.newFolder("data"), "treat")
        assertTrue(adb.joined().contains("shell am force-stop com.google.android.gms"))
        val note = out.lines().single { it.startsWith(" 참고: 워치는 Play 서비스") }
        assertTrue(note, note.contains("모두 업데이트") && note.contains("오류 보내기"))
        // 처음 단계면 그런 말이 없다
        val (_, plain) = run(treatAdb(), tmp.newFolder("data2"), "treat")
        assertTrue(plain.lines().none { it.startsWith(" 참고: 워치는 Play 서비스") })
    }

    /**
     * 워치가 One UI Watch 홈 과소모(재시작 뒤에도 또)를 짚어 캐시 정리를 했으면 '다음:'에 강제 재시작을 — 캐시만 지우면 돌고 있는 홈은
     * 그대로다 (워치 앱 할 일과 같은 순서 · 시나리오 점검)
     */
    @Test
    fun aHomeCacheTrimIsFollowedByAForcedRestart() {
        val data = tmp.newFolder("data")
        val at = sample("date.txt").trim().toLong() * 1000 + 5_000
        val adb = treatAdb().apply {
            on("shell am broadcast --include-stopped-packages -n ${Collect.SHELL_RECEIVER} -a ${Collect.SUMMARY_ACTION}", "Broadcast completed: result=0, data=\"summary:ok\"\n")
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[지표] at=$at idle_pph=3.4 app_window_min=120 issue_gms=0 issue_home=2 issue_face=0 back_gms=0 back_home=1\n"
        }
        val (_, out) = run(adb, data, "treat")
        assertTrue(adb.joined().contains("shell pm trim-caches 999999999999"))
        val next = out.lines().dropWhile { !it.contains(" 다음:") }.take(3).joinToString("\n")
        assertTrue(next, next.contains("강제 재시작(홈 + 뒤로 7초 이상)") && next.contains("One UI Watch 홈"))
        // 그다음 단계(재시작 뒤에도 계속)면 이미 해 본 캐시 정리·재시작을 되풀이하지 않는다 (리뷰)
        val data2 = tmp.newFolder("data2")
        val adb2 = treatAdb().apply {
            on("shell am broadcast --include-stopped-packages -n ${Collect.SHELL_RECEIVER} -a ${Collect.SUMMARY_ACTION}", "Broadcast completed: result=0, data=\"summary:ok\"\n")
            on("shell ls " + Collect.WATCH_SUMMARY, Collect.WATCH_SUMMARY + "\n")
            pullable[Collect.WATCH_SUMMARY] = "[지표] at=$at idle_pph=3.4 app_window_min=120 issue_gms=0 issue_home=2 issue_face=0 back_gms=0 back_home=2\n"
        }
        val (_, out2) = run(adb2, data2, "treat")
        val next2 = out2.lines().dropWhile { !it.contains(" 다음:") }.take(3).joinToString("\n")
        assertFalse(next2, next2.contains("워치를 한 번 강제 재시작"))
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
        assertTrue(out, out.contains("적용한 조치 ${autoIds.size - 1}개"))
        val e = Journal(File(data, "journal.jsonl")).entries().first { it.id == "bucket-restricted:$drain" }
        assertFalse(e.ok)
        assertNotNull(e.note)
        assertTrue(adb.joined().contains("shell dumpsys batterystats --reset"))    // 실패해도 측정 시작까지 간다
    }
}
