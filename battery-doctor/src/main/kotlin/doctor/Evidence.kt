package doctor

import com.mywatch.battery.core.Thresholds
import com.mywatch.battery.core.WatchModels
import doctor.fix.Journal
import doctor.fix.JournalEntry
import doctor.fix.Json
import doctor.parse.Durations
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 증거 패키지: `data/evidence/<yyyyMMdd>/` 에 보고서·스냅샷 원본·워치 CSV·요약·신고문 3종,
 * 공유용 마스킹본 `share/`, 그리고 전체를 묶은 zip.
 */
object Evidence {
    data class Output(val dir: File, val shareDir: File, val zip: File)

    const val LETTER_MEMBERS = "01_삼성멤버스_오류보내기.txt"
    const val LETTER_CENTER = "02_서비스센터_접수메모.txt"
    const val LETTER_LAW = "03_소비자분쟁해결기준_요구서.txt"
    const val BLANK = "[  ]"

    fun build(
        dataDir: File, snapshot: Snapshot, f: Facts, findings: List<Finding>, v: DoctorVerdict, journal: Journal,
        purchase: LocalDate?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): Output {
        val stamp = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        val dir = File(dataDir, "evidence/$stamp").apply { mkdirs() }
        val share = File(dir, "share").apply { mkdirs() }

        // 원본
        val report = File(snapshot.dir, "report.html").takeIf { it.isFile }
            ?: File(snapshot.dir, "report.html").also { it.writeText(HtmlReport.render(f, findings, v, HtmlReport.rawSections(snapshot), zone), Charsets.UTF_8) }
        report.copyTo(File(dir, "report.html"), overwrite = true)
        val snapDir = File(dir, "snapshot").apply { mkdirs() }
        snapshot.dir.listFiles()?.filter { it.isFile && it.name != "report.html" && it.name != "watch_log.csv" }?.forEach { it.copyTo(File(snapDir, it.name), overwrite = true) }
        snapshot.watchCsv?.copyTo(File(dir, "watch_log.csv"), overwrite = true)
        File(dir, "summary.json").writeText(summaryJson(f, findings, v, journal.entries()), Charsets.UTF_8)
        val letters = letters(f, findings, v, journal.entries(), purchase, today, zone)
        for ((name, text) in letters) File(dir, name).writeText(text, Charsets.UTF_8)

        // 공유본: location 제외, 텍스트 전부 마스킹, bugreport(통째로 민감) 제외
        File(share, "snapshot").mkdirs()
        val originals = dir.walkTopDown().filter { it.isFile && !it.startsWith(share) }.toList()
        for (file in originals) {
            val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (rel.startsWith("snapshot/location") || rel.endsWith(".zip") || !isText(file)) continue
            val target = File(share, rel)
            target.parentFile.mkdirs()
            target.writeText(redact(file.readText(Charsets.UTF_8)), Charsets.UTF_8)
        }
        File(share, "README_공유안내.txt").writeText(
            "이 share 폴더는 개인정보를 마스킹한 공유용 사본입니다 (이메일·전화번호·좌표·일련번호·Wi-Fi 이름·기기 이름 → [masked] 류, dumpsys location 제외).\n" +
                "커뮤니티·채팅에 올리거나 다른 사람에게 보낼 때는 이 폴더만 쓰세요. 상위 폴더의 원본은 서비스센터·삼성멤버스 제출용입니다.\n" +
                "마스킹은 정규식 기반이라 완벽하지 않습니다 — 올리기 전에 한 번 훑어보세요.\n", Charsets.UTF_8)

        val zip = File(dataDir, "evidence/$stamp.zip")
        zipDir(dir, zip)
        return Output(dir, share, zip)
    }

    private fun isText(f: File) = f.extension.lowercase() in setOf("txt", "html", "json", "csv", "properties", "md", "log")

    private fun zipDir(dir: File, zip: File) {
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            for (file in dir.walkTopDown().filter { it.isFile }) {
                val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                out.putNextEntry(ZipEntry(rel).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    // ───────────────────────── 마스킹 ─────────────────────────

    private val ssid = Regex("(?i)SSID[^\\n]*")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phoneIntl = Regex("\\+82[- ]?1[016789][- ]?\\d{3,4}[- ]?\\d{4}")
    private val phoneMobile = Regex("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b")
    private val phoneLand = Regex("\\b0\\d{1,2}-\\d{3,4}-\\d{4}\\b")
    private val coord = Regex("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{2,}")
    private val props = Regex("\\[(ro\\.(?:boot\\.)?serialno|ril\\.serialnumber|ro\\.boot\\.em\\.did|ro\\.boot\\.(?:wifimacaddr|btmacaddr)|ro\\.ril\\.oem\\.imei\\w*|persist\\.radio\\.imei\\w*|ro\\.boot\\.un)\\]: \\[[^\\]]*\\]")
    private val mac = Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")
    private val imei = Regex("\\b\\d{15}\\b")
    private val namedSettings = Regex("(?m)^(device_name|bluetooth_name|android_id|wifi_p2p_device_name|lock_screen_owner_info|bluetooth_address|wifi_country_code)=.*$")

    /** 공유본용 마스킹: 이메일·전화번호·좌표·일련번호/IMEI·SSID·MAC·기기 이름 */
    fun redact(text: String): String {
        var t = text
        t = ssid.replace(t, "SSID [masked]")
        t = email.replace(t, "[email]")
        t = phoneIntl.replace(t, "[phone]")
        t = phoneMobile.replace(t, "[phone]")
        t = phoneLand.replace(t, "[phone]")
        t = coord.replace(t, "[coord]")
        t = props.replace(t) { "[${it.groupValues[1]}]: [[masked]]" }
        t = mac.replace(t, "[mac]")
        t = imei.replace(t, "[masked]")
        t = namedSettings.replace(t) { "${it.groupValues[1]}=[masked]" }
        return t
    }

    // ───────────────────────── 요약 JSON ─────────────────────────

    fun summaryJson(f: Facts, findings: List<Finding>, v: DoctorVerdict, journal: List<JournalEntry>): String {
        val w = f.watch
        val m = linkedMapOf<String, Any?>(
            "generated" to System.currentTimeMillis(),
            "collectedAt" to f.collectedAt,
            "model" to f.model, "build" to f.build, "incremental" to f.incremental, "nominalMah" to f.nominalMah,
            "cause" to v.cause.name, "causeLabel" to v.cause.label, "confidence" to v.confidence, "headline" to v.headline,
            "why" to v.why, "todo" to v.todo, "culprits" to v.culprits,
            "dumpsys" to linkedMapOf(
                "onBatteryHours" to f.stats?.onBatteryHours, "dischargePercent" to f.stats?.dischargePercent?.first, "dischargeMah" to f.stats?.dischargeMah,
                "percentPerHour" to f.stats?.percentPerHour, "screenOffAwakeRatio" to f.stats?.screenOffAwakeRatio, "screenOnMs" to f.stats?.screenOnMs,
            ),
            "now" to linkedMapOf("level" to f.now?.level, "chargeCounterUah" to f.now?.chargeCounterUah, "voltageMv" to f.now?.voltageMv, "tempDeciC" to f.now?.tempDeciC, "health" to f.now?.healthLabel, "samsung" to (f.now?.samsung ?: emptyMap<String, String>())),
            "watch" to if (w == null) null else linkedMapOf(
                "samples" to w.samples, "spanHours" to w.spanHours,
                "idle24" to w.idle24.percentPerHour, "w24" to w.w24.percentPerHour, "w8" to w.w8.percentPerHour, "w1" to w.w1.percentPerHour,
                "overnight" to w.overnight?.percentPerHour, "overnightLabel" to w.overnightLabel,
                "anomalies" to w.anomalies.map { "${it.label} ${it.detail}" },
                "capacityMah" to w.capacity?.estimatedMah, "capacityPercent" to w.capacity?.percentOfNominal,
                "experiments" to w.experiments.map { "${it.name}: ${it.rate.percentPerHour?.let { p -> fmt1(p) } ?: "-"}%/h ${it.grade.label}" },
                "verdict" to w.verdict.headline,
            ),
            "findings" to findings.map { linkedMapOf("id" to it.id, "severity" to it.severity.name, "title" to it.title, "packages" to it.packages) },
            "actions" to journal.map { linkedMapOf("time" to it.time, "id" to it.id, "before" to it.before, "undone" to it.undone, "ok" to it.ok) },
        )
        return Json.write(m)
    }

    // ───────────────────────── 신고문 3종 ─────────────────────────

    fun letters(f: Facts, findings: List<Finding>, v: DoctorVerdict, journal: List<JournalEntry>, purchase: LocalDate?, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Map<String, String> {
        val model = f.model ?: BLANK
        val size = WatchModels.sizeLabel(f.model)?.let { " ($it)" } ?: ""
        val firmware = if (f.build == null && f.incremental == null) BLANK else "${f.build ?: BLANK} (${f.incremental ?: BLANK})"
        val purchaseText = purchase?.toString() ?: BLANK
        val days = purchase?.let { ChronoUnit.DAYS.between(it, today) }
        val elapsed = days?.let { " (경과 ${it}일)" } ?: ""
        val collected = f.collectedAt?.let { stamp(it, zone) } ?: BLANK
        val measures = measures(f)
        val mainRate = mainRate(f)
        val anomalies = f.watch?.anomalies?.takeIf { it.isNotEmpty() }?.let { list -> "${list.size}회 (" + list.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.first().label} ${it.value.size}회" } + ")" } ?: (if (f.watch != null) "없음" else BLANK)
        val health = listOfNotNull(f.now?.asoc?.let { "삼성 기록 건강도(ASOC) $it%" }, f.watch?.capacity?.let { c -> "충전 곡선 추정 용량 ${fmt1(c.estimatedMah)} mAh${c.percentOfNominal?.let { p -> " (공칭의 ${fmt1(p)}%)" } ?: ""}" }).joinToString(", ").ifEmpty { BLANK }
        val keyFindings = findings.filter { it.severity != Severity.INFO }.take(6).map { "- [${it.severity.label}] ${it.title}" }.ifEmpty { listOf("- $BLANK") }
        val actions = journal.map { "- ${stamp(it.time, zone)} ${describeLever(it.id)} (${it.id})${if (it.undone) " — 되돌림" else ""}${if (!it.ok) " — 실패" else ""}" }.ifEmpty { listOf("- $BLANK (아직 조치 없음)") }
        val culprits = v.culprits.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: BLANK

        val members = buildString {
            appendLine("[삼성멤버스 오류 보내기 — 붙여 넣을 내용]")
            appendLine("제출 경로: Galaxy Wearable 앱 > 하단 가운데 대시보드 > 우상단 ⋮(더보기) > 설정 > 문의하기 > 오류 보내기")
            appendLine("  · 기기: 워치 선택, '시스템 로그 데이터 보내기' 체크 (Wi-Fi 권장)")
            appendLine("  · 증상이 나타난 직후 5분 안에(삼성전자서비스 안내는 2~3분 안에) 보내야 로그가 남습니다. 로그 생성 중 앱을 닫지 마세요")
            appendLine("  · 빈도: 자주 / 카테고리: 배터리")
            appendLine()
            appendLine("제목: 갤럭시 워치9 배터리 비정상 소모 — $mainRate")
            appendLine()
            appendLine("[증상]")
            appendLine("- 모델: $model$size, 펌웨어: $firmware, Google Play 서비스: ${f.gmsVersion ?: BLANK}")
            appendLine("- 구입일: $purchaseText$elapsed, 수집 시각: $collected")
            measures.forEach { appendLine("- $it") }
            appendLine("- 잔량 표시 이상(워치 15분 기록): $anomalies")
            appendLine("- 배터리 건강도: $health")
            appendLine("- 자동 분석 판정: ${v.cause.label} (신뢰도 ${v.confidence}) — ${v.headline}")
            appendLine("- 범인 후보 패키지: $culprits")
            appendLine("- 주요 소견:")
            keyFindings.forEach { appendLine("  $it") }
            appendLine()
            appendLine("[시도한 조치]")
            appendLine("- 강제 재시작(홈 + 뒤로 가기 7초 이상): $BLANK (날짜·효과)")
            actions.forEach { appendLine(it) }
            appendLine()
            appendLine("[요청]")
            appendLine("- 시스템 로그에서 화면 꺼짐 중 깨어 있는 원인(wakelock·알람·서비스)을 확인해 주시고, 펌웨어 결함이면 수정 일정을 알려 주세요.")
            appendLine()
            appendLine("[첨부]")
            appendLine("- 증거 패키지 report.html, snapshot/batterystats.txt, snapshot/batterystats_checkin.txt, watch_log.csv (서비스센터용 원본은 상위 폴더, 공유용은 share/)")
        }

        val warranty = days?.let { if (it <= 365) "품질보증기간(1년) 이내" else "품질보증기간(1년) 경과" } ?: "품질보증기간 1년 (구입일 기준)"
        val center = buildString {
            appendLine("[서비스센터 접수 메모] — 삼성전자서비스 1588-3366 (센터 예약: samsungsvc.co.kr)")
            appendLine()
            appendLine("접수 내용: 갤럭시 워치9 배터리 과소모 — 정상 사용 중 $mainRate (공식 사용시간 최대 30~40시간 ≈ 시간당 2.5~3.3%)")
            appendLine("기기: 모델 $model$size, 펌웨어 $firmware")
            appendLine("구입일: $purchaseText$elapsed — $warranty")
            appendLine()
            appendLine("측정 요약 (PC 툴킷 battery-doctor, 수집 $collected):")
            measures.forEach { appendLine("- $it") }
            appendLine("- 잔량 표시 이상: $anomalies")
            appendLine("- 배터리 건강도: $health")
            appendLine("- 자동 분석 판정: ${v.cause.label} (신뢰도 ${v.confidence})")
            appendLine()
            appendLine("요구 사항:")
            appendLine("1. 배터리 효율(용량) 측정값을 서면(점검 결과지)으로 받겠습니다 — 효율 %, 사이클 수, 측정 장비·기준")
            appendLine("2. 소프트웨어 재설치·초기화를 하셨다면 수리내역서에 '배터리 과소모 증상으로 접수, 소프트웨어 재설치'라고 증상과 조치를 그대로 기재해 주세요 (소비자분쟁해결기준의 동일 하자 수리 횟수 산정 근거)")
            appendLine("3. 접수증과 점검 결과지를 수령합니다 — 점검 결과가 '정상'이어도 접수 기록을 남깁니다")
            appendLine("4. 증상이 센터에서 재현되지 않으면 첨부한 측정 기록(report.html, 워치 15분 기록 CSV, dumpsys batterystats)을 참고 자료로 접수해 주세요")
            appendLine("5. 동일 증상으로 2회째 접수라면: 소비자분쟁해결기준에 따라 제품교환 또는 구입가 환급을 요구합니다 (03_소비자분쟁해결기준_요구서.txt)")
            appendLine()
            appendLine("시도한 조치:")
            actions.forEach { appendLine(it) }
            appendLine()
            appendLine("첨부: 증거 패키지 폴더 전체 (report.html, snapshot/, watch_log.csv, summary.json)")
            appendLine("이전 접수 이력: 1차 $BLANK / 2차 $BLANK (접수번호·날짜·조치)")
        }

        val stage = when {
            days == null -> "구입일을 모름 — 아래 세 단계 중 해당하는 것을 고르세요 (--purchase yyyy-MM-dd 로 다시 만들면 자동 계산)"
            days <= 10 -> "지금 단계: 구입 10일 이내(경과 ${days}일) → 중요한 수리를 요하는 하자이면 제품교환 또는 구입가 환급"
            days <= 30 -> "지금 단계: 구입 1개월 이내(경과 ${days}일) → 중요한 수리를 요하는 하자이면 제품교환 또는 무상수리"
            days <= 365 -> "지금 단계: 구입 1개월 이후, 품질보증기간(1년) 이내(경과 ${days}일) → 무상수리 + 수리내역서 확보; 동일 하자 2회 수리 후 재발 시 수리 불가능으로 보아 제품교환 또는 구입가 환급"
            else -> "지금 단계: 품질보증기간(1년) 경과(경과 ${days}일) → 유상수리 원칙; 부품보유기간 내 수리 불가 시 정액 감가상각한 금액에 10%를 가산하여 환급"
        }
        val law = buildString {
            appendLine("[소비자분쟁해결기준에 따른 요구서]")
            appendLine()
            appendLine("수신: 삼성전자(주) / 삼성전자서비스        작성일: $today")
            appendLine("성명: $BLANK   연락처: $BLANK   구입처: $BLANK   구입가: ${BLANK}원")
            appendLine("제품: 갤럭시 워치9 (모델 $model$size, 펌웨어 $firmware)   구입일: $purchaseText$elapsed")
            appendLine()
            appendLine("1. 하자 내용")
            appendLine("정상적인 사용 상태에서 배터리가 $mainRate 소모되어 하루를 쓰지 못합니다 (공식 사용시간 최대 30~40시간).")
            measures.forEach { appendLine("- $it") }
            appendLine("- 잔량 표시 이상: $anomalies / 배터리 건강도: $health")
            appendLine("- 측정 근거: 첨부 report.html, 워치 15분 기록(watch_log.csv), dumpsys batterystats 원본")
            appendLine()
            appendLine("2. 근거 — 공정거래위원회 고시 「소비자분쟁해결기준」, 품목 \"스마트폰 (명칭 불문하고 이동통신 3세대 이후의 모든 휴대전화 포함)\" (품목명에 \"(스마트워치 포함)\" 명시)")
            appendLine("전자제품 공통 기준(원문):")
            appendLine("- \"구입 후 10일 이내에 정상적인 사용 상태에서 발생한 성능․기능상의 하자로 중요한 수리를 요할 때\" → \"제품교환 또는 구입가 환급\"")
            appendLine("- \"구입 후 1개월 이내에 정상적인 사용 상태에서 발생한 성능‧기능상의 하자로 중요한 수리를 요할 때\" → \"제품교환 또는 무상수리\"")
            appendLine("- \"품질보증기간 이내에 정상적인 사용 상태에서 발생한 성능․기능상의 하자\" — 하자 발생 시 \"무상수리\", 수리 불가능 시 \"제품교환 또는 구입가 환급\", 교환 불가능 시 \"구입가 환급\", 교환된 제품이 1개월 이내에 중요한 수리를 요할 때 \"구입가 환급\"")
            appendLine("- 각주(원문): \"품질보증기간 이내에 동일하자에 대해 2회까지 수리하였으나 하자가 재발하는 경우 또는 여러 부위 하자에 대해 4회까지 수리하였으나 하자가 재발하는 경우는 수리 불가능한 것으로 봄.\"")
            appendLine("- 스마트폰 품목 특칙(원문): 수리 횟수 산정 \"품질보증기간 중 최근 1년(수리접수일 기준) 이내에 동일하자에 대해 2회까지 수리하였으나 하자가 재발하는 경우\"; \"리퍼폰 교환은 무상수리로 봄.\"")
            appendLine("- 보증기간 경과 후(법제처 요약): 수리 불가 시 \"정액 감가상각한 금액에 10%를 가산하여 환급\" [부품보유기간(내용연수)에 한정]")
            appendLine("- 품질보증기간: 삼성전자 고객서비스 알림(2019-12-30) \"스마트폰 제품을 제외한 태블릿,웨어러블 제품의 품질보증기간은 기존과 동일한 1년임\" — 웨어러블 1년, 배터리 1년")
            appendLine("- 근거 법령: 소비자기본법 [시행 2025.1.1.] [법률 제20301호], 시행령 [대통령령 제35093호]")
            appendLine()
            appendLine("3. $stage")
            appendLine("수리 이력(동일 하자 '배터리 과소모'): 1차 $BLANK / 2차 $BLANK — 수리내역서·접수증 첨부")
            appendLine()
            appendLine("4. 요구 사항")
            appendLine("- 위 기준에 따라 [ 무상수리 / 제품교환 / 구입가 환급 ] 중 해당 조치를 요구합니다.")
            appendLine("- 점검 결과 '정상'으로 종결하실 경우, 배터리 효율 측정값과 측정 기준을 서면으로 제시해 주십시오.")
            appendLine("- 회신 기한: $BLANK 까지 서면(문자·이메일)으로.")
            appendLine()
            appendLine("5. 처리되지 않을 때의 절차")
            appendLine("- 1372 소비자상담센터(국번 없이 1372) 상담 → 한국소비자원 피해구제 신청 → 30일 내 합의 안 되면 소비자분쟁조정위원회 조정 (조정 성립 시 재판상 화해의 효력)")
            appendLine("- 준비물: 구매 증빙, 수리내역서·접수증, 이 증거 패키지(report.html, summary.json, watch_log.csv)")
        }
        return linkedMapOf(LETTER_MEMBERS to members, LETTER_CENTER to center, LETTER_LAW to law)
    }

    private fun measures(f: Facts): List<String> {
        val out = ArrayList<String>()
        f.watch?.let { w ->
            w.idle24.percentPerHour?.let { out += "워치 15분 기록 — 대기(화면 꺼짐) 시간당 ${fmt1(it)}% (기준: ${Thresholds.IDLE_ABNORMAL}%/h 초과 과소모, Android vitals)" }
            w.w24.percentPerHour?.let { out += "워치 15분 기록 — 24시간 평균 시간당 ${fmt1(it)}%${w.w24.mahPerHour?.let { m -> " (실측 ${fmt1(m)} mAh/h)" } ?: ""} (기준: ${Thresholds.MIXED_ABNORMAL}%/h 이상 비정상)" }
            w.overnight?.let { night -> night.percentPerHour?.let { out += "워치 15분 기록 — ${w.overnightLabel ?: "밤사이"} 시간당 ${fmt1(it)}%, 총 ${fmt1(night.dropPercent)}포인트" } }
            w.experiments.forEach { e -> e.rate.percentPerHour?.let { out += "실험 '${e.name}' 시간당 ${fmt1(it)}% — ${e.grade.label}" } }
        }
        f.stats?.let { s ->
            s.percentPerHour?.let { out += "dumpsys batterystats — 마지막 충전 뒤 ${Durations.human(s.onBatteryMs)} 동안 ${s.dischargePercent?.first ?: "?"}포인트${s.dischargeMah?.let { m -> "($m mAh)" } ?: ""} 소모, 시간당 ${fmt1(it)}%" }
            s.screenOffAwakeRatio?.let { out += "화면 꺼진 동안 깨어 있던 비율 ${fmt1(it * 100)}%" }
        }
        if (out.isEmpty()) out += "측정값: $BLANK (수집 뒤 다시 만들면 채워집니다)"
        return out
    }

    private fun mainRate(f: Facts): String {
        val w = f.watch
        val pph = w?.idle24?.percentPerHour ?: w?.w24?.percentPerHour ?: f.stats?.percentPerHour ?: return "시간당 $BLANK%"
        val label = when {
            w?.idle24?.percentPerHour != null -> "대기 중 시간당 ${fmt1(pph)}%"
            w?.w24?.percentPerHour != null -> "시간당 ${fmt1(pph)}%"
            else -> "시간당 ${fmt1(pph)}%"
        }
        return label
    }

    /** 기록된 조치 id → 사람이 읽는 이름(카탈로그와 같은 말) */
    fun describeLever(id: String): String {
        val fixed = mapOf(
            "wifi-scan-off" to "Wi-Fi 꺼도 스캔 허용 끄기", "ble-scan-off" to "블루투스 꺼도 BLE 스캔 허용 끄기", "adaptive-battery-on" to "적응형 배터리 켜기",
            "app-standby-on" to "앱 대기(App Standby) 켜기", "batterystats-reset" to "배터리 통계 초기화", "aod-off" to "AOD 끄기",
            "gms-rollback" to "Google Play 서비스 업데이트 제거", "reboot" to "워치 재시작",
        )
        fixed[id]?.let { return it }
        val colon = id.indexOf(':')
        if (colon <= 0) return id
        val arg = id.substring(colon + 1)
        return when (id.substring(0, colon)) {
            "disable" -> "앱 비활성화: $arg"; "bg-restrict" -> "백그라운드 실행 제한: $arg"; "bucket-restricted" -> "대기 버킷 restricted: $arg"
            "doze-unwhitelist" -> "Doze 예외 해제: $arg"; "force-stop" -> "강제 종료: $arg"; "clear" -> "앱 데이터 삭제: $arg"
            "rollback" -> "시스템 앱 업데이트 제거: $arg"; "screen-timeout" -> "화면 자동 꺼짐 ${arg.toLongOrNull()?.let { it / 1000 } ?: arg}초"
            "grant" -> "워치 앱 권한 주기: ${arg.removePrefix("android.permission.")}"; else -> id
        }
    }

    private fun fmt1(x: Double) = String.format(Locale.US, "%.1f", x)
    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun stamp(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).format(stampFmt)
}
