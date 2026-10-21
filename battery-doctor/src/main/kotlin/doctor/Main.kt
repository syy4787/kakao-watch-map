package doctor

import doctor.fix.Catalog
import doctor.fix.Fixer
import doctor.fix.Journal
import doctor.fix.Lever
import doctor.fix.Tier
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/** 진입점: 인자가 없으면 대화형 메뉴, 있으면 하위 명령 */
fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))
    val dataDir = File(System.getProperty("bd.data")?.takeIf { it.isNotBlank() } ?: "data")
    // adb 경로: .bat 이 넘긴 -Dbd.adb → data/config.properties 의 adbPath → PATH 의 adb
    val adbPath = System.getProperty("bd.adb")?.takeIf { it.isNotBlank() } ?: Config(dataDir).adbPath ?: "adb"
    val code = try {
        Main.run(args, StdConsole(), dataDir, { serial -> RealAdb(adbPath, serial) })
    } catch (e: Exception) {
        System.err.println("오류: $e")
        3
    }
    exitProcess(code)
}

object Main {
    data class Args(val command: String?, val positional: List<String>, val flags: Map<String, String?>) {
        fun has(name: String) = flags.containsKey(name)
        fun flag(name: String): String? = flags[name]
    }

    /** `cmd --flag value --other=value positional` */
    fun parse(args: Array<String>): Args {
        val list = args.toList()
        if (list.isEmpty()) return Args(null, emptyList(), emptyMap())
        if (list[0] == "--help" || list[0] == "-h" || list[0] == "help") return Args("help", emptyList(), emptyMap())
        val command = list[0]
        val pos = ArrayList<String>()
        val flags = LinkedHashMap<String, String?>()
        var i = 1
        while (i < list.size) {
            val a = list[i]
            if (a.startsWith("--")) {
                val body = a.removePrefix("--")
                val eq = body.indexOf('=')
                if (eq >= 0) flags[body.substring(0, eq)] = body.substring(eq + 1)
                else if (body in VALUE_FLAGS && i + 1 < list.size && !list[i + 1].startsWith("--")) { flags[body] = list[i + 1]; i++ }
                else flags[body] = null
            } else pos += a
            i++
        }
        return Args(command, pos, flags)
    }

    private val VALUE_FLAGS = setOf("apply", "purchase", "snapshot")

    val HELP: List<String> = listOf(
        "사용법: BatteryDoctor.bat [명령] [옵션]   (명령 없이 실행하면 메뉴)",
        "  connect [ip:port]           워치와 무선 디버깅으로 연결 (페어링 안내 포함)",
        "  collect [--bugreport]       dumpsys·워치 기록 수집 → 바로 분석 → report.html",
        "  analyze [스냅샷 폴더]        마지막(또는 지정한) 스냅샷을 다시 분석",
        "  reset                       dumpsys batterystats --reset — 깨끗한 측정 구간 시작",
        "  fix [--apply id,id] [--yes] 차단 조치 목록 → 적용 (journal.jsonl 에 기록)",
        "  undo [--all | 번호]          적용한 조치 되돌리기 (번호는 목록에 표시된 번호)",
        "  evidence [--purchase yyyy-MM-dd]  증거 패키지(보고서·원자료·신고문 3종·공유용 마스킹본·zip)",
        "  grant                       워치 앱(배터리 지킴이)에 특수 권한 주기 — 기록되며 undo --all 로 회수",
        "  treat [--strong] [--yes] [--no-grant] [--bugreport]",
        "                              자동 치료: 권한 → 수집·분석 → 안전·추천 조치 자동 적용 → 측정 시작 (질문 없음)",
        "                              --strong 은 강함 등급도 후보에 넣고 항목마다 yes 확인(--yes 로 생략), --no-grant 는 권한 주기 생략",
    )

    fun run(
        args: Array<String>, io: Console, dataDir: File, adbFactory: (String?) -> AdbRunner,
        zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(),
        clock: () -> Long = { System.currentTimeMillis() }, open: (File) -> Boolean = { HtmlReport.open(it) },
    ): Int {
        val ctx = Ctx(io, dataDir, adbFactory, zone, today, clock, open)
        val a = parse(args)
        return when (a.command) {
            null -> ctx.menu()
            "help" -> { HELP.forEach { io.println(it) }; 0 }
            "connect" -> ctx.connect(a.positional.firstOrNull())
            "collect" -> ctx.collect(a.has("bugreport"))
            "analyze" -> ctx.analyze(a.positional.firstOrNull() ?: a.flag("snapshot"))
            "reset" -> ctx.reset()
            "fix" -> ctx.fix(a.flag("apply"), a.has("yes"))
            "undo" -> ctx.undo(a.has("all"), a.positional.firstOrNull())
            "evidence" -> ctx.evidence(a.flag("purchase"))
            "grant" -> ctx.grant()
            "treat" -> ctx.treat(Treat.Options(strong = a.has("strong"), yes = a.has("yes"), grant = !a.has("no-grant"), bugreport = a.has("bugreport")))
            else -> { io.println("모르는 명령입니다: ${a.command}"); HELP.forEach { io.println(it) }; 2 }
        }
    }

    /**
     * 이 수집을 치료 전 수집([Collect.baseline] — 조치가 없으면 바로 앞 수집)과 비교해 콘솔에 보이고 돌려준다 (보고서에도 싣는다) —
     * 처음 수집이거나 비교할 값이 없으면 null. collect / analyze / treat 가 같이 쓴다
     */
    fun compareWithPrevious(io: Console, dataDir: File, snap: Snapshot, after: Facts, journal: Journal, zone: ZoneId): Comparison? {
        val entries = journal.entries()
        val prev = Collect.baseline(dataDir, snap, entries) ?: return null
        val between = (prev.pcTime + 1)..snap.pcTime
        val c = runCatching { Compare.of(Analyzer.facts(prev, zone), after, entries, zone, actionsBetween = between) }.getOrNull() ?: return null
        val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")
        // 바로 앞 수집을 건너뛰고 치료 전 수집과 견주면 그렇다고
        val title = if (prev.dir == Collect.before(dataDir, snap)?.dir) "지난 수집과 비교" else "치료 전 수집과 비교"
        io.println()
        io.println("━━ $title (${Instant.ofEpochMilli(c.beforeAt).atZone(zone).format(fmt)} → ${Instant.ofEpochMilli(c.afterAt).atZone(zone).format(fmt)})" +
            (if (c.actions.isNotEmpty()) " — 사이에 한 조치: ${c.actions.joinToString(", ")}" else ""))
        io.println("   ${c.headline}")
        c.rows.forEach { r -> io.println(String.format("    - %s: %s → %s (%s)", r.label, r.before, r.after, r.change.label)) }
        return c
    }

    /** 판정 요약을 콘솔에 — collect / analyze / treat 가 같이 쓴다. [opened] 가 null 이면 브라우저 안내를 생략 */
    fun printVerdict(io: Console, findings: List<Finding>, v: DoctorVerdict, report: File, opened: Boolean?) {
        io.println()
        io.println("━━ 판정: ${v.cause.label} (신뢰도 ${v.confidence})")
        io.println("   ${v.headline}")
        if (v.why.isNotEmpty()) { io.println("   근거:"); v.why.take(8).forEach { io.println("    - $it") } }
        io.println("   지금 할 일:")
        v.todo.forEachIndexed { i, t -> io.println("    ${i + 1}. $t") }
        val abnormal = findings.count { it.severity == Severity.ABNORMAL }
        val caution = findings.count { it.severity == Severity.CAUTION }
        io.println("   소견: 비정상 ${abnormal}개, 주의 ${caution}개, 참고 ${findings.size - abnormal - caution}개")
        io.println("   보고서: ${report.absolutePath}")
        when (opened) {
            true -> io.println("   (브라우저로 열었습니다)")
            false -> io.println("   (브라우저를 열 수 없어 경로만 표시합니다 — 파일을 더블클릭해 여세요)")
            null -> {}
        }
    }

    private class Ctx(
        val io: Console, val dataDir: File, val adbFactory: (String?) -> AdbRunner, val zone: ZoneId,
        val today: LocalDate, val clock: () -> Long, val open: (File) -> Boolean,
    ) {
        val cfg = Config(dataDir)
        val journal get() = Journal(File(dataDir, "journal.jsonl"))

        fun menu(): Int {
            while (true) {
                io.println()
                io.println("워치9 배터리 닥터  (데이터 폴더: ${dataDir.absolutePath})")
                io.println(" 1  연결         워치와 무선 디버깅으로 연결 (처음이면 페어링 안내)")
                io.println(" 2  수집·분석    dumpsys·워치 기록을 가져와 보고서(report.html)를 만들고 엽니다")
                io.println(" 3  측정 시작    배터리 통계 초기화(reset) — 깨끗한 측정 구간 시작")
                io.println(" 4  차단 조치    추천 조치를 골라 적용 (모두 기록되고 되돌릴 수 있음)")
                io.println(" 5  되돌리기     적용한 조치를 역순으로 복구")
                io.println(" 6  증거 패키지  보고서·원자료·신고문 3종 + 공유용 마스킹본(share/) + zip")
                io.println(" 7  권한 주기    워치 앱(배터리 지킴이)에 앱별 소모 보기·설정 자동 조절 권한을 줍니다 (기록되고 되돌릴 수 있음)")
                io.println(" 8  자동 치료    권한 주기 → 수집·분석 → 안전·추천 조치 자동 적용 → 측정 시작까지 한 번에 (고를 것 없음)")
                io.println(" 0  종료")
                val pick = io.readLine("선택: ")?.trim() ?: return 0
                when (pick) {
                    "0", "q", "exit" -> return 0
                    "1" -> connect(null)
                    "2" -> collect(false)
                    "3" -> reset()
                    "4" -> fix(null, false)
                    "5" -> undo(false, null)
                    "6" -> evidence(null)
                    "7" -> grant()
                    "8" -> treat(Treat.Options())
                    "" -> {}
                    else -> io.println("0~8 중에서 고르세요.")
                }
            }
        }

        fun connect(address: String?): Int = if (Connect.run(adbFactory(null), cfg, io, address)) 0 else 1

        /** 연결된 워치 하나를 고른다. 없으면 null + 안내 */
        fun device(): AdbRunner? {
            val states = Adb.parseDevices(adbFactory(null).run(listOf("devices"), 30).out)
            val ready = states.filterValues { it == "device" }.keys.toList()
            val offline = states.filterValues { it != "device" }
            if (ready.isEmpty()) {
                io.println("연결된 워치가 없습니다. 먼저 메뉴 1(연결)을 실행하세요." + (if (offline.isNotEmpty()) " (상태: ${offline.entries.joinToString { "${it.key}=${it.value}" }})" else ""))
                return null
            }
            val pick = when {
                cfg.lastAddress in ready -> cfg.lastAddress!!
                ready.size == 1 -> ready[0]
                else -> {
                    io.println("기기가 여러 개입니다: ${ready.joinToString(", ")}")
                    val typed = io.readLine("쓸 기기 주소 [엔터 = ${ready[0]}]: ")?.trim().orEmpty()
                    if (typed in ready) typed else ready[0]
                }
            }
            return adbFactory(pick)
        }

        fun collect(bugreport: Boolean): Int {
            val adb = device() ?: return 1
            io.println("수집을 시작합니다 (기기 ${adb.serial ?: "?"}). 1~3분 걸립니다. 워치 화면을 켜 두면 Wi-Fi 가 안 끊깁니다.")
            val snap = Collect.run(adb, dataDir, bugreport, { io.println("  $it") }, clock())
            io.println("스냅샷 저장: ${snap.dir.absolutePath}")
            val failed = snap.failed()
            if (failed.isNotEmpty()) io.println("  수집 실패 ${failed.size}개: ${failed.joinToString(", ")} (보고서에 표시됩니다)")
            return analyzeSnapshot(snap)
        }

        fun analyze(path: String?): Int {
            val snap = if (path != null) File(path).takeIf { it.isDirectory }?.let { Snapshot(it) } else Collect.latest(dataDir)
            if (snap == null) {
                io.println(if (path != null) "스냅샷 폴더가 없습니다: $path" else "분석할 스냅샷이 없습니다. 먼저 메뉴 2(수집·분석)를 실행하세요. (data/snapshots/ 아래에 생깁니다)")
                return 1
            }
            return analyzeSnapshot(snap)
        }

        private fun analyzeSnapshot(snap: Snapshot): Int {
            val f = Analyzer.facts(snap, zone)
            val findings = Analyzer.findings(f)
            val v = Analyzer.verdict(f, findings)
            val cmp = compareWithPrevious(io, dataDir, snap, f, journal, zone)
            val report = HtmlReport.write(snap, f, findings, v, zone, cmp)
            printVerdict(io, findings, v, report, open(report))
            return 0
        }

        /** 메뉴 7: 워치 앱(배터리 지킴이)에 특수 권한 주기 */
        fun grant(): Int {
            val adb = device() ?: return 1
            return Grant.run(adb, io, journal, clock())
        }

        /** 메뉴 8: 자동 치료 — 권한 → 수집·분석 → 조치 → 측정 시작 */
        fun treat(opts: Treat.Options): Int {
            val adb = device() ?: return 1
            return Treat.run(adb, io, dataDir, journal, opts, zone, clock, open)
        }

        fun reset(): Int {
            val adb = device() ?: return 1
            val r = adb.run(listOf("shell", "dumpsys", "batterystats", "--reset"), 60)
            if (!r.ok) { io.println("초기화 실패: ${r.err.ifBlank { r.out }}"); return 1 }
            io.println("배터리 통계를 초기화했습니다 (${stamp(clock())}). 지금부터가 깨끗한 측정 구간입니다.")
            io.println(" 1) 워치 개발자 옵션의 '자동 Wi-Fi 끄기'를 다시 끄고(측정 오염 방지), 무선 디버깅은 그대로 둬도 됩니다")
            io.println(" 2) 충전기에서 내려 평소처럼 쓰거나, 워치 배터리 화면의 '실험 시작'으로 비행기 모드/절전/시계만 모드를 재세요 (8시간 이상, 가능하면 밤사이 포함)")
            io.println(" 3) 충전기에 올리기 전에 Wi-Fi 를 켜고 메뉴 2(수집·분석)를 실행하세요 — 충전을 시작하면 '마지막 충전 이후' 통계가 섞입니다")
            return 0
        }

        fun fix(applyIds: String?, yes: Boolean): Int {
            val snap = Collect.latest(dataDir)
            if (snap == null) { io.println("조치 목록은 수집 결과를 바탕으로 만듭니다. 먼저 메뉴 2(수집·분석)를 실행하세요."); return 1 }
            val f = Analyzer.facts(snap, zone)
            val findings = Analyzer.findings(f)
            val levers = Catalog.levers(f, findings)
            val adb = device() ?: return 1
            val chosen: List<Lever>
            if (applyIds != null) {
                val ids = applyIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val found = ArrayList<Lever>()
                for (id in ids) {
                    val lever = Catalog.byId(f, findings, id)
                    if (lever == null) { io.println("없는 조치이거나 거부 목록(핵심 시스템) 패키지입니다: $id"); return 1 }
                    found += lever
                }
                val strong = found.filter { it.tier == Tier.STRONG }
                if (strong.isNotEmpty() && !yes) {
                    io.println("강함 등급 조치는 --yes 를 붙여야 적용합니다: ${strong.joinToString(", ") { it.id }}")
                    strong.forEach { io.println("  ${it.id}: ${it.detail}") }
                    return 1
                }
                chosen = found
            } else {
                chosen = pickInteractive(levers, adb) ?: return 0
            }
            if (chosen.isEmpty()) { io.println("적용할 조치가 없습니다."); return 0 }
            val j = journal
            var okAll = true
            for (lever in chosen) {
                val ok = Fixer.apply(adb, lever, j, clock())
                io.println("${if (ok) "적용" else "실패"}: ${lever.title} (${lever.id})${if (!ok) " — ${Fixer.lastError ?: ""}" else ""}")
                okAll = okAll && ok
            }
            io.println("기록: ${j.file.absolutePath} — 메뉴 5(되돌리기)로 역순 복구할 수 있습니다.")
            io.println("이제 메뉴 3(측정 시작)으로 통계를 초기화하고 24시간 뒤 다시 수집해 비교하세요.")
            return if (okAll) 0 else 1
        }

        private fun pickInteractive(levers: List<Lever>, adb: AdbRunner): List<Lever>? {
            io.println()
            io.println("[차단 조치] 현재 상태를 읽는 중…")
            val states = levers.map { l -> runCatching { l.readState(adb) }.getOrNull() }
            io.println(" 번호  등급   추천  현재 상태        조치")
            levers.forEachIndexed { i, l ->
                io.println(String.format(" %3d   [%s]  %s  %-14s  %s", i + 1, l.tier.label, if (l.recommended) "★" else " ", (states[i] ?: "-").take(14), l.title))
                io.println("                                  ${l.detail}")
            }
            io.println("추천(★)은 분석 소견의 범인 앱·위험 설정에서 고른 것입니다. 강함 등급은 빨간 경고 — 기본 꺼짐, 한 번 더 확인합니다.")
            val typed = io.readLine("적용할 번호를 쉼표로 입력 (엔터 = 추천 항목 전부, 0 = 취소): ")?.trim() ?: return null
            val picked: List<Lever> = when {
                typed == "0" -> return null
                typed.isEmpty() -> levers.filter { it.recommended }
                else -> typed.split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }.mapNotNull { levers.getOrNull(it - 1) }
            }
            val out = ArrayList<Lever>()
            for (l in picked) {
                if (l.tier == Tier.STRONG) {
                    io.println("경고: '${l.title}'은(는) 강함 등급입니다. ${l.detail}")
                    val ans = io.readLine("정말 적용하려면 yes 를 입력하세요: ")?.trim()
                    if (ans != "yes") { io.println("건너뜀: ${l.id}"); continue }
                }
                out += l
            }
            return out
        }

        fun undo(all: Boolean, number: String?): Int {
            val j = journal
            val entries = j.entries()
            if (entries.isEmpty()) { io.println("되돌릴 기록이 없습니다 (data/journal.jsonl 비어 있음)."); return 0 }
            val index: Int? = when {
                all -> null
                number != null -> number.toIntOrNull()?.minus(1)?.takeIf { it in entries.indices } ?: run { io.println("번호가 맞지 않습니다: $number (1~${entries.size})"); return 1 }
                else -> {
                    io.println(" 번호  시각              상태        조치")
                    entries.forEachIndexed { i, e ->
                        val state = when { e.undone -> "되돌림"; e.undo == null -> "되돌릴 수 없음"; !e.ok -> "적용 실패"; else -> "적용됨" }
                        io.println(String.format(" %3d   %s  %-8s  %s (%s)%s", i + 1, stamp(e.time), state, Evidence.describeLever(e.id), e.id, e.before?.let { "  이전 값: $it" } ?: ""))
                    }
                    val typed = io.readLine("되돌릴 번호 (all = 전부 역순, 엔터 = 취소): ")?.trim().orEmpty()
                    when {
                        typed.isEmpty() -> return 0
                        typed == "all" -> null
                        else -> typed.toIntOrNull()?.minus(1)?.takeIf { it in entries.indices } ?: run { io.println("번호가 맞지 않습니다."); return 1 }
                    }
                }
            }
            val adb = device() ?: return 1
            val n = Fixer.undo(adb, j, index)
            io.println("되돌린 조치: ${n}개" + (Fixer.lastError?.let { " — 실패: $it" } ?: ""))
            return if (Fixer.lastError == null) 0 else 1
        }

        fun evidence(purchaseArg: String?): Int {
            val snap = Collect.latest(dataDir)
            if (snap == null) { io.println("증거 패키지는 수집 결과로 만듭니다. 먼저 메뉴 2(수집·분석)를 실행하세요."); return 1 }
            var purchaseText = purchaseArg ?: cfg.purchaseDate
            if (purchaseText == null && purchaseArg == null) {
                purchaseText = io.readLine("구입일(yyyy-MM-dd, 모르면 엔터): ")?.trim()?.takeIf { it.isNotEmpty() }
            }
            val purchase = purchaseText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (purchaseText != null && purchase == null) io.println("구입일 형식이 맞지 않아 비워 둡니다: $purchaseText (예 2026-08-07)")
            if (purchase != null && cfg.purchaseDate != purchase.toString()) { cfg.purchaseDate = purchase.toString(); cfg.save() }
            val f = Analyzer.facts(snap, zone)
            val findings = Analyzer.findings(f)
            val v = Analyzer.verdict(f, findings)
            HtmlReport.write(snap, f, findings, v, zone)
            val out = Evidence.build(dataDir, snap, f, findings, v, journal, purchase, today, zone)
            io.println("증거 패키지: ${out.dir.absolutePath}")
            io.println("  - report.html, summary.json, watch_log.csv, snapshot/ (원본 — 서비스센터·삼성멤버스 제출용)")
            io.println("  - ${Evidence.LETTER_MEMBERS}, ${Evidence.LETTER_CENTER}, ${Evidence.LETTER_LAW} ([  ] 칸은 직접 채우세요)")
            io.println("  - share/ : 개인정보 마스킹본 — 커뮤니티·채팅에 올릴 때는 이 폴더만 쓰세요 (location 덤프 제외)")
            io.println("  - zip: ${out.zip.absolutePath}")
            return 0
        }

        private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private fun stamp(t: Long) = Instant.ofEpochMilli(t).atZone(zone).format(stampFmt)
    }
}
