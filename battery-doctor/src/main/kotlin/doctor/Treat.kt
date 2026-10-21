package doctor

import doctor.fix.Catalog
import doctor.fix.Fixer
import doctor.fix.Journal
import doctor.fix.Lever
import doctor.fix.Tier
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 자동 치료: 고를 것 없이 한 번에 — 권한 주기 → 수집·분석 → 조치 자동 적용 → 측정 시작(통계 초기화) → 요약.
 * 어떤 조치를 자동으로 걸지는 [plan] 이 정한다(순수 함수). 적용은 전부 [Fixer.apply] 를 거치므로 `undo --all` 로 되돌릴 수 있다.
 */
object Treat {
    data class Options(val strong: Boolean = false, val yes: Boolean = false, val grant: Boolean = true, val bugreport: Boolean = false)

    /** 손실 없는 설정 — 추천 표시가 없어도 현재 값이 목표와 다르면 적용한다 */
    val NO_LOSS: Set<String> = setOf("wifi-scan-off", "ble-scan-off", "adaptive-battery-on", "app-standby-on")

    /** 범인 앱에 자동으로 거는 '주의' 등급 조치 종류 */
    val AUTO_CAUTION: Set<String> = setOf("bg-restrict", "bucket-restricted", "doze-unwhitelist", "gms-restart")

    /** 자동 치료가 절대 건드리지 않는 앱 — 우리 전용앱, Samsung Health */
    val PROTECTED: Set<String> = setOf(Grant.PKG, "com.samsung.android.wear.shealth")

    /** [apply] 는 적용할 조치(입력 순서 유지), [skipped] 는 사용자에게 알려 줄 '건너뛴 추천'과 이유 */
    data class Plan(val apply: List<Lever>, val skipped: List<Pair<Lever, String>>)

    /**
     * 규칙:
     *  - 안전: 추천된 것, 그리고 [NO_LOSS] 설정은 현재 값([states])이 목표와 다를 때 (값을 모르면 추천 여부를 따른다)
     *  - 주의: 분석이 범인 앱에 추천한 [AUTO_CAUTION] 종류만. `aod-off` 와 [PROTECTED] 앱 관련은 절대 자동 적용 안 함
     *  - 강함: [strong] 일 때만, 추천되었거나 범인([culprits]) 앱 관련인 것. 재시작은 제외
     *  - 이미 목표 상태인 조치는 건너뛴다
     */
    fun plan(levers: List<Lever>, strong: Boolean, states: Map<String, String?> = emptyMap(), culprits: Collection<String> = emptyList()): List<Lever> =
        decide(levers, strong, states, culprits).apply

    fun decide(levers: List<Lever>, strong: Boolean, states: Map<String, String?> = emptyMap(), culprits: Collection<String> = emptyList()): Plan {
        val apply = ArrayList<Lever>()
        val skipped = ArrayList<Pair<Lever, String>>()
        for (l in levers) {
            val kind = l.id.substringBefore(':')
            val state = states[l.id]
            val done = alreadyDone(l, state)
            val culpritRelated = l.packages.any { it in culprits }
            // 건너뛴 이유를 사용자에게 보여 줄 조치인가 — 추천된 것, 그리고 --strong 없이 건너뛴 범인 관련 강함 조치
            val worthTelling = l.recommended || (l.tier == Tier.STRONG && culpritRelated && l.id != "reboot")
            val reason: String? = when {
                l.packages.any { it in PROTECTED } -> "전용앱·Samsung Health 는 자동 치료가 건드리지 않습니다"
                l.tier == Tier.SAFE -> when {
                    l.id in NO_LOSS && state != null -> if (done) "이미 적용된 상태(현재 값 ${human(state)})" else null
                    l.recommended -> if (done) "이미 적용된 상태(현재 값 ${human(state)})" else null
                    else -> "자동 치료 대상이 아닙니다 (원하면 메뉴 4에서 직접)"
                }
                l.tier == Tier.CAUTION -> when {
                    l.id == "aod-off" -> "AOD 는 자동으로 끄지 않습니다 — 쓰고 있다면 그대로 두세요 (원하면 메뉴 4에서 직접)"
                    kind !in AUTO_CAUTION -> "자동 치료 대상이 아닙니다 (원하면 메뉴 4에서 직접)"
                    !l.recommended -> "분석이 추천하지 않은 앱입니다"
                    done -> "이미 적용된 상태(현재 값 ${human(state)})"
                    else -> null
                }
                else -> when {
                    l.id == "reboot" -> "재시작은 자동 치료에서 뺍니다 (원하면 메뉴 4에서 직접)"
                    !(l.recommended || culpritRelated) -> "범인 앱과 무관한 강함 조치"
                    !strong -> "강함 등급 — treat --strong 을 붙였을 때만 적용합니다 (항목마다 yes 확인, --yes 로 생략)"
                    else -> null
                }
            }
            if (reason == null) apply += l else if (worthTelling) skipped += l to reason
        }
        return Plan(apply, skipped)
    }

    /** 조치가 만들려는 상태를 readState 와 같은 표현으로. 모르면 null */
    fun target(l: Lever): String? {
        val c = l.apply.singleOrNull() ?: return null
        return when {
            c.size >= 6 && c[1] == "settings" && c[2] == "put" -> c[5]
            c.size >= 7 && c[1] == "cmd" && c[2] == "appops" && c[3] == "set" -> c[6]
            c.size >= 5 && c[1] == "am" && c[2] == "set-standby-bucket" -> c[4]
            c.size >= 5 && c[1] == "cmd" && c[2] == "deviceidle" && c[3] == "whitelist" && c[4].startsWith("-") -> "not whitelisted"
            c.size >= 3 && c[1] == "pm" && c[2] == "disable-user" -> "disabled"
            else -> null
        }
    }

    /** 현재 값이 이미 목표와 같은가 (모르면 false) */
    fun alreadyDone(l: Lever, state: String?): Boolean {
        val t = target(l) ?: return false
        val s = state?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return s == t || (t == "restricted" && s == "45")   // am get-standby-bucket 은 숫자(45 = restricted)로 답한다
    }

    /** 적용 결과 한 줄 */
    data class Applied(val lever: Lever, val ok: Boolean, val before: String?, val after: String?, val error: String?)

    /**
     * 파이프라인 (기기는 호출자가 고른다). 적용할 조치가 모두 성공하고 통계 초기화까지 되면 0, 아니면 1.
     * 권한 주기의 부분 실패는 요약에만 표시하고 실패로 치지 않는다(펌웨어가 막은 권한은 지킴이가 '권한 없음'으로 다룬다).
     */
    fun run(
        adb: AdbRunner, io: Console, dataDir: File, journal: Journal, opts: Options,
        zone: ZoneId = ZoneId.systemDefault(), clock: () -> Long = { System.currentTimeMillis() }, open: (File) -> Boolean = { HtmlReport.open(it) },
    ): Int {
        io.println()
        io.println("━━ 자동 치료를 시작합니다 (기기 ${adb.serial ?: "?"}) — 권한 주기 → 수집·분석 → 조치 자동 적용 → 측정 시작. " +
            (if (opts.strong) "강함 등급은 항목마다 확인합니다." else "질문 없이 끝까지 갑니다."))

        // (b) 권한
        val grantCode: Int? = if (opts.grant) Grant.run(adb, io, journal, clock()) else { io.println("[권한 주기] --no-grant 옵션으로 건너뜁니다."); null }

        // (c) 수집·분석
        io.println()
        io.println("[수집·분석] 1~3분 걸립니다. 워치 화면을 켜 두면 Wi-Fi 가 안 끊깁니다.")
        val snap = Collect.run(adb, dataDir, opts.bugreport, { io.println("  $it") }, clock())
        io.println("스냅샷 저장: ${snap.dir.absolutePath}")
        val missing = snap.failed()
        if (missing.isNotEmpty()) io.println("  수집 실패 ${missing.size}개: ${missing.joinToString(", ")} (보고서에 표시됩니다)")
        val f = Analyzer.facts(snap, zone)
        val findings = Analyzer.findings(f)
        val v = Analyzer.verdict(f, findings)
        val report = HtmlReport.write(snap, f, findings, v, zone)
        Main.printVerdict(io, findings, v, report, opened = null)

        // (d) 조치
        io.println()
        val levers = Catalog.levers(f, findings)
        val candidates = (plan(levers, opts.strong, emptyMap(), v.culprits) + levers.filter { it.id in NO_LOSS }).distinctBy { it.id }
        io.println("[조치] 후보 ${candidates.size}개의 현재 상태를 읽는 중…")
        val states: Map<String, String?> = candidates.associate { it.id to runCatching { it.readState(adb) }.getOrNull() }
        val plan = decide(levers, opts.strong, states, v.culprits)
        val applied = ArrayList<Applied>()
        val skipped = plan.skipped.toMutableList()
        if (plan.apply.isEmpty()) io.println("자동으로 적용할 조치가 없습니다.")
        for (l in plan.apply) {
            if (l.tier == Tier.STRONG && !opts.yes) {
                io.println("경고: '${l.title}'은(는) 강함 등급입니다. ${l.detail}")
                val ans = io.readLine("정말 적용하려면 yes 를 입력하세요: ")?.trim()
                if (ans != "yes") { io.println("건너뜀: ${l.id}"); skipped += l to "강함 등급 — yes 로 확인하지 않아 건너뜀"; continue }
            }
            val ok = Fixer.apply(adb, l, journal, clock())
            val after = if (ok) runCatching { l.readState(adb) }.getOrNull() else null
            applied += Applied(l, ok, states[l.id], after, if (ok) null else Fixer.lastError)
            io.println("${if (ok) "적용" else "실패"}: ${l.title} (${l.id})${if (!ok) " — ${Fixer.lastError ?: ""}" else ""}")
        }

        // (e) 측정 시작
        val reset = try { adb.run(listOf("shell", "dumpsys", "batterystats", "--reset"), 60) } catch (e: Exception) { Result(-3, "", e.toString()) }

        // (f) 요약
        val opened = open(report)
        io.println()
        io.println("━━ 자동 치료 요약 (${stamp(clock(), zone)})")
        io.println(" 판정: ${v.cause.label} (신뢰도 ${v.confidence}) — ${v.headline}")
        io.println(" 범인 앱: ${v.culprits.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "지목된 앱 없음"}")
        io.println(" 권한 주기: " + when (grantCode) { null -> "건너뜀(--no-grant)"; 0 -> "${Grant.STEPS.size}개 모두 성공"; else -> "일부 실패 — 위 표 참고 (실패한 기능만 지킴이에서 '권한 없음'으로 표시)" })
        val okApplied = applied.filter { it.ok }
        io.println(" 적용한 조치 ${okApplied.size}개 (이전 값 → 새 값):")
        if (okApplied.isEmpty()) io.println("  - 없음")
        okApplied.forEach { io.println("  - ${it.lever.title} (${it.lever.id}): ${human(it.before)} → ${human(it.after ?: target(it.lever))}") }
        applied.filter { !it.ok }.takeIf { it.isNotEmpty() }?.let { bad ->
            io.println(" 실패한 조치 ${bad.size}개:")
            bad.forEach { io.println("  - ${it.lever.title} (${it.lever.id}): ${it.error ?: "?"}") }
        }
        io.println(" 건너뛴 추천:")
        if (skipped.isEmpty()) io.println("  - 없음")
        skipped.forEach { (l, why) -> io.println("  - ${l.title} (${l.id}): $why") }
        io.println(if (reset.ok) " 배터리 통계 초기화: 완료 — 지금부터가 깨끗한 측정 구간입니다"
            else " 배터리 통계 초기화: 실패 (${(reset.err.ifBlank { reset.out }).trim().lineSequence().firstOrNull() ?: "exit ${reset.code}"}) — 메뉴 3(측정 시작)을 따로 실행하세요")
        io.println(" 다음: 워치 개발자 옵션의 '자동 Wi-Fi 끄기'를 다시 끄고, 충전기에서 내려 평소처럼 쓰세요. 24시간 뒤 메뉴 2(수집·분석)로 비교하세요.")
        io.println("       효과가 없거나 불편하면 메뉴 5(되돌리기) → all 로 조치와 권한을 모두 되돌립니다. 기록: ${journal.file.absolutePath}")
        io.println(" 보고서: ${report.absolutePath}" + if (opened) " (브라우저로 열었습니다)" else " (브라우저를 열 수 없어 경로만 표시합니다 — 파일을 더블클릭해 여세요)")
        return if (applied.all { it.ok } && reset.ok) 0 else 1
    }

    /** readState 가 돌려주는 영문 상태를 읽기 쉽게 */
    fun human(s: String?): String = when (val t = s?.trim()) {
        null, "" -> "—"
        "null" -> "(없음)"
        "whitelisted" -> "Doze 예외"
        "not whitelisted" -> "예외 아님"
        "disabled" -> "비활성"
        "enabled" -> "활성"
        "45" -> "restricted(45)"
        else -> t
    }

    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun stamp(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).format(stampFmt)
}
