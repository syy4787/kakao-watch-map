package doctor

import doctor.fix.Journal
import doctor.fix.JournalEntry
import doctor.fix.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipFile

class EvidenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    @Test
    fun redactionRules() {
        val text = """
            email a.b@gmail.com and Hong.Gildong+x@example.co.kr
            phone 010-1234-5678 / 01012345678 / 02-123-4567 / +82-10-1234-5678
            coord 36.1234,127.5678 and Location[fused 37.123456,-122.08 hAcc=12]
            [ro.serialno]: [R3CX1234567]
            [ro.boot.serialno]: [R3CX1234567]
            SSID: MyHomeWifi 5G, bssid=aa:bb:cc:dd:ee:ff
            mac 02:00:00:AB:CD:EF
            imei 356938035643809
            device_name=Galaxy Watch9 (Hong)
            bluetooth_name=Hong's Watch
            android_id=0123456789abcdef
            keep 1792414865000 and 9,0,l,bt,0,29523123,5710000 and level: 48 and 445 mAh
        """.trimIndent()
        val r = Evidence.redact(text)
        assertFalse(r.contains("gmail.com")); assertFalse(r.contains("Gildong")); assertTrue(r.contains("[email]"))
        assertFalse(r.contains("1234-5678")); assertFalse(r.contains("01012345678")); assertFalse(r.contains("02-123-4567")); assertTrue(r.contains("[phone]"))
        assertFalse(r.contains("36.1234")); assertFalse(r.contains("37.123456")); assertTrue(r.contains("[coord]"))
        assertTrue(r.contains("[ro.serialno]: [[masked]]")); assertTrue(r.contains("[ro.boot.serialno]: [[masked]]")); assertFalse(r.contains("R3CX1234567"))
        assertFalse(r.contains("MyHomeWifi")); assertFalse(r.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse(r.contains("02:00:00:AB:CD:EF")); assertTrue(r.contains("[mac]"))
        assertFalse(r.contains("356938035643809"))
        assertFalse(r.contains("Hong")); assertTrue(r.contains("device_name=[masked]")); assertTrue(r.contains("bluetooth_name=[masked]"))
        assertFalse(r.contains("0123456789abcdef"))
        assertTrue(r.contains("1792414865000")); assertTrue(r.contains("9,0,l,bt,0,29523123,5710000")); assertTrue(r.contains("level: 48")); assertTrue(r.contains("445 mAh"))
    }

    private fun build(purchase: LocalDate?, today: LocalDate = LocalDate.of(2026, 10, 20)): Evidence.Output {
        val data = File(tmp.root, "data")
        val snap = sampleSnapshot(data)
        val f = Analyzer.facts(snap, zone)
        val fs = Analyzer.findings(f)
        val v = Analyzer.verdict(f, fs)
        val html = HtmlReport.render(f, fs, v, HtmlReport.rawSections(snap), zone)
        File(snap.dir, "report.html").writeText(html)
        val journal = Journal(File(data, "journal.jsonl"))
        journal.append(JournalEntry(1792414900000L, "wifi-scan-off", "1", listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "0")), listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "1")), false))
        journal.append(JournalEntry(1792414901000L, "bg-restrict:com.example.drain", "allow", listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "ignore")), listOf(), false))
        return Evidence.build(data, snap, f, fs, v, journal, purchase, today, zone)
    }

    @Test
    fun folderLayoutShareMaskingAndZip() {
        val out = build(LocalDate.of(2026, 8, 7))
        val dir = out.dir
        assertEquals("20261020", dir.name)
        assertEquals(File(tmp.root, "data/evidence"), dir.parentFile)
        for (name in listOf("report.html", "summary.json", "watch_log.csv", "01_삼성멤버스_오류보내기.txt", "02_서비스센터_접수메모.txt", "03_소비자분쟁해결기준_요구서.txt")) {
            assertTrue(name, File(dir, name).isFile)
        }
        assertTrue(File(dir, "snapshot/batterystats.txt").isFile)
        assertTrue(File(dir, "snapshot/location.txt").isFile)          // 원본에는 있음
        assertTrue(File(dir, "snapshot/getprop.txt").readText().contains("R3CX1234567"))
        // share/: location 제외, 마스킹
        val share = out.shareDir
        assertEquals(File(dir, "share"), share)
        assertFalse(File(share, "snapshot/location.txt").exists())
        assertFalse(File(share, "location.txt").exists())
        assertTrue(File(share, "snapshot/getprop.txt").isFile)
        val getprop = File(share, "snapshot/getprop.txt").readText()
        assertFalse(getprop.contains("R3CX1234567"))
        assertTrue(getprop.contains("[ro.serialno]: [[masked]]"))
        assertFalse(File(share, "snapshot/settings_global.txt").readText().contains("ABCD"))
        assertFalse(File(share, "report.html").readText().contains("R3CX1234567"))
        assertTrue(File(share, "01_삼성멤버스_오류보내기.txt").isFile)
        assertTrue(File(share, "watch_log.csv").isFile)
        assertTrue(File(share, "README_공유안내.txt").readText().contains("share"))
        // zip
        val zip = out.zip
        assertEquals(File(tmp.root, "data/evidence/20261020.zip"), zip)
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("report.html"))
            assertTrue(names.contains("share/snapshot/getprop.txt"))
            assertTrue(names.contains("snapshot/location.txt"))
            assertFalse(names.contains("share/snapshot/location.txt"))
            assertTrue(names.contains("03_소비자분쟁해결기준_요구서.txt"))
        }
        // summary.json
        val summary = Json.parse(File(dir, "summary.json").readText()) as Map<*, *>
        assertEquals("SOFTWARE", summary["cause"])
        assertEquals("SM-L350", summary["model"])
        assertTrue((summary["culprits"] as List<*>).contains("com.example.drain"))
        assertTrue((summary["findings"] as List<*>).isNotEmpty())
    }

    @Test
    fun lettersAreFilledFromFactsAndJournal() {
        val dir = build(LocalDate.of(2026, 8, 7)).dir
        val l1 = File(dir, "01_삼성멤버스_오류보내기.txt").readText()
        assertTrue(l1.contains("SM-L350"))
        assertTrue(l1.contains("AZH4.260818.001"))
        assertTrue(l1.contains("L350XXU1AZH4"))
        assertTrue(l1.contains("시간당"))
        assertTrue(l1.contains("Galaxy Wearable"))
        assertTrue(l1.contains("오류 보내기"))
        assertTrue(l1.contains("5분"))
        // 워치 앱 할 일과 같은 길 — 삼성전자서비스 안내 그대로 (리뷰)
        assertTrue(l1, l1.contains("Galaxy Wearable 앱 › 아래 가운데 대시보드 › 오른쪽 위 ⋮(더보기) › 설정 › 문의하기 › 오류 보내기") &&
            l1.contains("Samsung Members › 도움 받기 › 질문/오류 보내기") && l1.contains("2~3분"))
        assertTrue(l1.contains("시스템 로그"))
        assertTrue(l1.contains("Wi-Fi 꺼도 스캔 허용 끄기") || l1.contains("wifi-scan-off"))
        assertTrue(l1.contains("com.example.drain"))
        val l2 = File(dir, "02_서비스센터_접수메모.txt").readText()
        assertTrue(l2.contains("1588-3366"))
        assertTrue(l2.contains("배터리 효율"))
        assertTrue(l2.contains("소프트웨어 재설치"))
        assertTrue(l2.contains("접수증"))
        assertTrue(l2.contains("2026-08-07"))
        val l3 = File(dir, "03_소비자분쟁해결기준_요구서.txt").readText()
        assertTrue(l3.contains("(스마트워치 포함)"))
        assertTrue(l3.contains("구입 후 10일 이내에 정상적인 사용 상태에서 발생한 성능․기능상의 하자로 중요한 수리를 요할 때"))
        assertTrue(l3.contains("제품교환 또는 구입가 환급"))
        assertTrue(l3.contains("구입 후 1개월 이내에 정상적인 사용 상태에서 발생한 성능‧기능상의 하자로 중요한 수리를 요할 때"))
        assertTrue(l3.contains("제품교환 또는 무상수리"))
        assertTrue(l3.contains("품질보증기간 이내에 동일하자에 대해 2회까지 수리하였으나 하자가 재발하는 경우 또는 여러 부위 하자에 대해 4회까지 수리하였으나 하자가 재발하는 경우는 수리 불가능한 것으로 봄."))
        assertTrue(l3.contains("1372"))
        assertTrue(l3.contains("1년"))
        assertTrue(l3.contains("74일"))                      // 2026-08-07 → 2026-10-20
        assertTrue(l3.contains("지금 단계: 구입 1개월 이후"))
    }

    /**
     * 신고문에 워치 기록의 켜짐(부팅)을 날짜·시각으로, 마지막 켜짐 전후 대기 소모, 워치 앱이 본 단계(다시 켜진 뒤에도 또 나타남 등)를 채운다 —
     * 기억해서 적지 않아도 '재시작해도 그대로'가 증거로 남게. 켜짐은 사용자가 한 재시작만이 아니라(방전 뒤 켜짐·업데이트도) 증상 쪽에 본 그대로
     * 적고(강제 재시작 표시가 앞뒤에 있으면 그렇다고, 충전 안 하던 1~3% 다음 충전기에서 켜졌으면 그 기록을), '시도한 조치'엔 사용자가 워치에
     * 남긴 '강제 재시작' 표시만 (리뷰: 모든 켜짐을 사용자가 해 본 재시작으로 적으면 하지 않은 조치를 했다고 쓰는 셈). 표시가 없으면 빈칸 그대로
     */
    @Test
    fun bootsFromTheWatchLogAreFactsAndOnlyMarkedRestartsAreTriedSteps() {
        val q = 15 * 60_000L
        val h = 3_600_000L
        val m = 60_000L
        val now = java.time.LocalDateTime.of(2026, 10, 20, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val start = now - 12 * h                                   // 10:00 부터 화면 꺼진 채 시간당 3%
        val boots = listOf(now - 10 * h, now - 5 * h)              // 12:00, 17:00 켜짐
        fun level(t: Long) = (90 - 3.0 * (t - start) / h).toInt()
        val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
        var t = start
        while (t <= now) { samples += com.mywatch.battery.core.BatterySample(t, level(t)); t += q }
        for (b in boots) samples += com.mywatch.battery.core.BatterySample(b + m, level(b), event = com.mywatch.battery.core.Event.BOOT)
        fun mark(at: Long) = com.mywatch.battery.core.BatterySample(at, level(at), event = com.mywatch.battery.core.Event.USER_NOTE,
            note = com.mywatch.battery.core.GaugeDriftRule.NOTE_FORCED_RESTART)
        samples += mark(boots[0] + 15 * m)                         // 켠 뒤에 남긴 표시 (12:15)
        samples += mark(boots[1] - 2 * m)                          // 켜기 전에 남긴 표시 (16:58)
        // 하루 전: 충전 안 하던 1% 다음 충전기에서 켜짐(17:00), 충전 중 2%에서 다시 켜짐(20:00 — 충전 중이라 방전 뒤라 하지 않는다)
        samples += com.mywatch.battery.core.BatterySample(now - 30 * h, 1)
        samples += com.mywatch.battery.core.BatterySample(now - 29 * h, 3, plugged = true, event = com.mywatch.battery.core.Event.BOOT)
        samples += com.mywatch.battery.core.BatterySample(now - 26 * h - 15 * m, 2, plugged = true)
        samples += com.mywatch.battery.core.BatterySample(now - 26 * h, 2, plugged = true, event = com.mywatch.battery.core.Event.BOOT)
        val wm = mapOf("at" to (now - 5 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 2.0, "back_home" to 2.0)
        val f = Facts(model = "SM-L350", collectedAt = now, watchSamples = samples.sortedBy { it.t }, watchMetrics = wm,
            packages = listOf(doctor.parse.PackageInfo(Analyzer.HOME, 10123, true, false)))
        val fs = Analyzer.findings(f)
        assertTrue(fs.any { it.id == "watch-home" })
        val letters = Evidence.letters(f, fs, Analyzer.verdict(f, fs), emptyList(), null, LocalDate.of(2026, 10, 20), zone)
        for (name in listOf(Evidence.LETTER_MEMBERS, Evidence.LETTER_CENTER)) {
            val l = letters.getValue(name)
            val boot = l.lines().single { it.startsWith("- 워치 켜짐(부팅) 기록") }
            assertTrue(boot, boot.contains("최근 7일 4번: 10/19 17:00(직전 기록 1% · 충전기에서 켜짐), 10/19 20:00, 10/20 12:01(강제 재시작 표시), 10/20 17:01(강제 재시작 표시)"))
            val effect = l.lines().single { it.startsWith("- 마지막 켜짐(10/20 17:01) 전후 대기") }
            assertTrue(effect, effect.contains("시간당 3.0% → 2.9%") && effect.endsWith("비슷합니다"))
            // 워치 앱이 본 단계는 워치가 아는 말로 — 다시 켜진 횟수(사용자가 재시작했다고 하지 않음)
            val stage = l.lines().single { it.startsWith("- 워치 앱 지킴이") }
            assertTrue(stage, stage.contains("[비정상] One UI Watch 홈") && stage.endsWith("처음 짚은 뒤 워치가 두 번 이상 다시 켜졌는데도 계속") && !stage.contains("재시작을"))
            // 켜짐·효과·단계는 증상 쪽 — '시도한 조치' 앞
            val tried = l.indexOf(if (name == Evidence.LETTER_MEMBERS) "[시도한 조치]" else "시도한 조치:")
            assertTrue(l, l.indexOf(boot) in 0 until tried && l.indexOf(effect) < tried && l.indexOf(stage) < tried)
            // 시도한 조치엔 사용자가 남긴 '강제 재시작' 표시만
            val mark = l.lines().single { it.startsWith("- 강제 재시작(홈 + 뒤로 가기 7초 이상)") }
            assertTrue(mark, mark.endsWith("10/20 12:15, 10/20 16:58") && l.indexOf(mark) > tried)
        }
        // 단계 줄이 풀어 적는 소견은 주요 소견에서 빼고 (같은 말을 두 번 적지 않게), 요구서에도 단계 줄
        assertTrue(letters.getValue(Evidence.LETTER_MEMBERS).lines().none { it.contains("워치 앱: One UI Watch 홈 과소모") })
        assertTrue(letters.getValue(Evidence.LETTER_LAW).lines().any { it.startsWith("- 워치 앱 지킴이") })
        // 켜짐·표시 기록이 없으면 빈칸 그대로
        val bare = Facts(model = "SM-L350", collectedAt = now, watchSamples = samples.filter { it.event == com.mywatch.battery.core.Event.PERIODIC })
        val l0 = Evidence.letters(bare, emptyList(), Analyzer.verdict(bare, emptyList()), emptyList(), null, LocalDate.of(2026, 10, 20), zone).getValue(Evidence.LETTER_MEMBERS)
        assertTrue(l0, l0.contains("강제 재시작(홈 + 뒤로 가기 7초 이상): ${Evidence.BLANK}"))
        assertTrue(l0, l0.lines().none { it.startsWith("- 워치 켜짐(부팅) 기록") })
    }

    /**
     * 느린 충전은 신고문에 사실 한 줄로 (서비스센터가 공식 시간과 견주게) — 주요 소견에선 그 소견을 뺀다. 잰 충전 수는 그 주의 충전 수와
     * 따로(잠깐 충전은 재지 않는다 · 리뷰), 공식 시간은 고속 충전 기준, 쓴 충전기는 사용자가 채우게. 요구서 하자 내용엔 충전 중 뜨거웠을 때만
     * (안 뜨거웠으면 먼저 의심할 것은 쓴 충전기라)
     */
    @Test
    fun lettersCarryTheSlowChargeFacts() {
        val q = 15 * 60_000L
        val now = java.time.LocalDateTime.of(2026, 10, 20, 22, 0).atZone(zone).toInstant().toEpochMilli()
        fun week(tempDeciC: Int): List<com.mywatch.battery.core.BatterySample> {
            val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
            var t = now - 3 * 24 * 3_600_000L
            repeat(3) {
                val dayStart = t
                while (t < dayStart + 12 * 3_600_000L) { samples += com.mywatch.battery.core.BatterySample(t, 95 - ((t - dayStart) / q).toInt() * 80 / 48); t += q }
                var lv = 15.0
                while (lv < 100.0) { samples += com.mywatch.battery.core.BatterySample(t, lv.toInt(), plugged = true, tempDeciC = tempDeciC); t += q; lv += 0.6 * 15 }
                samples += com.mywatch.battery.core.BatterySample(t, 100, plugged = true, tempDeciC = 400)
                samples += com.mywatch.battery.core.BatterySample(t + q, 99)
                // 낮에 잠깐 올려 둔 충전(70→80%) — 충전 횟수엔 들지만 빠르기는 재지 않는다
                t = dayStart + 18 * 3_600_000L
                samples += com.mywatch.battery.core.BatterySample(t, 70, plugged = true, tempDeciC = 330)
                samples += com.mywatch.battery.core.BatterySample(t + q, 80, plugged = true, tempDeciC = 330)
                samples += com.mywatch.battery.core.BatterySample(t + 2 * q, 79)
                t = dayStart + 24 * 3_600_000L
            }
            return samples
        }
        fun lettersOf(samples: List<com.mywatch.battery.core.BatterySample>): Map<String, String> {
            val f = Facts(model = "SM-L350", collectedAt = now, watchSamples = samples, zone = zone)
            val fs = Analyzer.findings(f)
            assertTrue(fs.any { it.id == "watch-charge-slow" })
            return Evidence.letters(f, fs, Analyzer.verdict(f, fs), emptyList(), null, LocalDate.of(2026, 10, 20), zone)
        }
        val hot = lettersOf(week(440))
        for (l in hot.values) {
            val line = l.lines().single { it.startsWith("- 충전 속도(워치 15분 기록)") }
            assertEquals("- 충전 속도(워치 15분 기록): 완충 어림 2시간 47분 넘게 — 삼성 공식(고속 충전) 44mm 90분, 최근 7일 충전 6번 중 3번을 잼" +
                "(10~85% 구간), 그 구간 최고 44℃, 사용한 충전기: ${Evidence.BLANK}", line)
        }
        assertTrue(hot.getValue(Evidence.LETTER_MEMBERS).lines().none { it.contains("충전이 공식보다 느림") })
        // 충전 중 뜨겁지 않았으면 요구서 하자 내용엔 넣지 않는다 (접수용 두 글엔 그대로)
        val cool = lettersOf(week(330))
        assertTrue(cool.getValue(Evidence.LETTER_LAW).lines().none { it.startsWith("- 충전 속도") })
        assertTrue(cool.getValue(Evidence.LETTER_MEMBERS).lines().any { it.startsWith("- 충전 속도") })
        assertTrue(cool.getValue(Evidence.LETTER_CENTER).lines().any { it.startsWith("- 충전 속도") })
    }

    /**
     * 켜지며 잔량이 뛴 것은 본 그대로 — '17%에서 꺼졌다 켜니 47%'(게이지가 낮게 세다 다시 맞춰짐)와 '하루를 못 버티고 꺼졌다가 충전기에서
     * 켜니 48%'(Samsung Community 10/4, 워치9) — 충전으로 그만큼 오를 수 없었으면 앞뒤 잔량을, 방전 뒤 충전기에서 켜졌으면 마지막 기록이
     * 얼마나 앞이었는지도 (충전기에 둔 시간은 워치가 모르니 판단은 읽는 사람이)
     */
    @Test
    fun bootsThatCameBackHigherShowTheLevels() {
        fun at(h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, 20, h, m).atZone(zone).toInstant().toEpochMilli()
        fun s(h: Int, m: Int, level: Int, plugged: Boolean = false, event: com.mywatch.battery.core.Event = com.mywatch.battery.core.Event.PERIODIC, note: String = "") =
            com.mywatch.battery.core.BatterySample(at(h, m), level, plugged = plugged, event = event, note = note)
        val boot = com.mywatch.battery.core.Event.BOOT
        val samples = listOf(
            s(10, 0, 40), s(11, 0, 35), s(11, 45, 30), s(12, 0, 52, event = boot), s(12, 15, 52), s(14, 0, 40), s(15, 0, 30), s(16, 0, 22),
            s(16, 45, 17), s(16, 50, 17, event = com.mywatch.battery.core.Event.USER_NOTE, note = com.mywatch.battery.core.GaugeDriftRule.NOTE_FORCED_RESTART),
            s(16, 55, 47, event = boot), s(16, 59, 47, event = boot), s(17, 0, 47), s(19, 0, 25), s(20, 0, 12), s(21, 0, 2),
            s(21, 40, 48, plugged = true, event = boot), s(21, 44, 49, plugged = true, event = boot), s(21, 45, 49, plugged = true), s(22, 0, 52, plugged = true),
        )
        fun bootLine(rows: List<com.mywatch.battery.core.BatterySample>): String {
            val f = Facts(model = "SM-L350", collectedAt = at(22, 5), watchSamples = rows)
            val l = Evidence.letters(f, emptyList(), Analyzer.verdict(f, emptyList()), emptyList(), null, LocalDate.of(2026, 10, 20), zone).getValue(Evidence.LETTER_MEMBERS)
            return l.lines().single { it.startsWith("- 워치 켜짐(부팅) 기록") }
        }
        // 곧이어 한 번 더 켜졌으면(16:59, 21:44) 앞 켜짐의 점프를 되풀이하지 않는다 — 직전 기록은 그 앞 켜짐 (리뷰: 같은 신고문의 잔량 표시
        // 이상 수와 어긋났다)
        val line = bootLine(samples)
        assertTrue(line, line.contains("최근 7일 5번: 10/20 12:00(켜지기 15분 전 기록 30% → 켜진 뒤 52%), " +
            "10/20 16:55(강제 재시작 표시 · 켜지기 5분 전 기록 17% → 켜진 뒤 47%), 10/20 16:59, " +
            "10/20 21:40(켜지기 40분 전 기록 2% · 충전기에서 켜짐 · 켜진 뒤 48%), 10/20 21:44 —"))
        // 충전기에 꽂힌 채 그동안 충전으로 오를 수 있었던 만큼이면 점프가 아니다
        assertTrue(bootLine(listOf(s(10, 0, 20, plugged = true), s(11, 0, 60, plugged = true, event = boot))).contains("최근 7일 1번: 10/20 11:00 — "))
        // 충전기 위에서 켜졌어도 그 사이 충전으로 오를 수 없는 만큼이면 점프 — 충전기에서 켜졌다고 함께
        val plugged = bootLine(listOf(s(10, 0, 30), s(10, 5, 60, plugged = true, event = boot)))
        assertTrue(plugged, plugged.contains("10/20 10:05(켜지기 5분 전 기록 30% → 켜진 뒤 60% · 충전기에서 켜짐) — "))
        // 1분이 안 되면 '0분 전'이 아니라 '직전'
        val quick = bootLine(listOf(s(9, 0, 30), com.mywatch.battery.core.BatterySample(at(9, 0) + 30_000L, 60, event = boot)))
        assertTrue(quick, quick.contains("10/20 09:00(켜지기 직전 기록 30% → 켜진 뒤 60%) — "))
    }

    private fun atD(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /**
     * 10/12~10/18 날마다 0~20시 화면 꺼진 채(대기 시간당 1.0·1.0·1.1·1.0·2.6·2.5·2.4% — 10/16부터 늘어 꺾임) · 20~24시 충전.
     * [notes] = 그 시각 표본의 '바뀐 것' 표시
     */
    private fun week(notes: Map<Long, List<com.mywatch.battery.core.ChangeNote.Item>>): List<com.mywatch.battery.core.BatterySample> {
        val q = 15 * 60_000L
        val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
        listOf(1.0, 1.0, 1.1, 1.0, 2.6, 2.5, 2.4).forEachIndexed { i, r ->
            val start = atD(12 + i, 0)
            var t = start
            while (t <= start + 20 * 3_600_000L) {
                val note = notes[t]?.let { com.mywatch.battery.core.ChangeNote.encode(it) } ?: ""
                samples += com.mywatch.battery.core.BatterySample(t = t, level = (100 - r * (t - start) / 3_600_000.0).toInt(), note = note); t += q
            }
            t = start + 20 * 3_600_000L + q
            while (t < start + 24 * 3_600_000L) { samples += com.mywatch.battery.core.BatterySample(t = t, level = 100, plugged = true); t += q }
        }
        return samples
    }

    private val tracking = com.mywatch.battery.core.ChangeNote.Item(com.mywatch.battery.core.ChangeKind.TRACKING, "on", null, "1")
    private val fw = com.mywatch.battery.core.ChangeNote.Item(com.mywatch.battery.core.ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1")

    private fun weekFacts(samples: List<com.mywatch.battery.core.BatterySample>, wm: Map<String, Double> = emptyMap()): Facts {
        val now = atD(19, 22)
        return Facts(model = "SM-L350", collectedAt = now, watchSamples = samples, watchMetrics = wm, zone = zone,
            watchTrend = com.mywatch.battery.core.TrendReport.of(samples, now, zone), packages = listOf(doctor.parse.PackageInfo(Analyzer.HOME, 10123, true, false)))
    }

    private fun lettersOf(f: Facts): Map<String, String> {
        val fs = Analyzer.findings(f)
        return Evidence.letters(f, fs, Analyzer.verdict(f, fs), emptyList(), null, LocalDate.of(2026, 10, 19), zone)
    }

    /**
     * 신고문 증상 칸에 '언제부터'(워치 기록으로 대기 소모가 늘어난 때와 그 무렵 바뀐 것 — 소프트웨어 업데이트 등)와 워치가 문제를 처음 짚은 때
     * — 업데이트 뒤 과소모라는 것이 날짜로 남게. 그 소견은 주요 소견에서 빼고(같은 말을 두 번 적지 않게) 요구서 하자 내용에도. 워치 앱이 본
     * 단계 줄엔 그 소견의 등급도 (주요 소견에서 빠지니)
     */
    @Test
    fun lettersSayWhenTheDrainStartedAndWhatChangedThen() {
        val now = atD(19, 22)
        val wm = mapOf("at" to (now - 5 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 2.0, "back_home" to 1.0,
            "first_home_at" to atD(18, 20).toDouble(), "issue_gms" to 2.0, "back_gms" to 1.0, "first_gms_at" to (now + 3_600_000L).toDouble())
        val f = weekFacts(week(mapOf(atD(12, 0) to listOf(tracking), atD(16, 3) to listOf(fw))), wm)
        assertTrue(Analyzer.findings(f).any { it.id == "watch-shift" })
        val letters = lettersOf(f)
        for (name in listOf(Evidence.LETTER_MEMBERS, Evidence.LETTER_CENTER, Evidence.LETTER_LAW)) {
            val l = letters.getValue(name)
            val since = l.lines().single { it.startsWith("- 대기 소모가 늘어난 때") }
            assertEquals("- 대기 소모가 늘어난 때(워치 15분 기록): 10/16부터 시간당 1.0% → 2.5% — 그 무렵 바뀐 것: 10/16 03:00 워치 소프트웨어 업데이트 (L350XXU1AZI1)", since)
            val stage = l.lines().filter { it.startsWith("- 워치 앱 지킴이") }
            assertEquals("- 워치 앱 지킴이(앱별 통계, 최근 2시간): [비정상] One UI Watch 홈이 화면 꺼진 채 CPU 를 많이 씀 — 처음 짚은 때 10/18 20:00, " +
                "그 뒤 워치가 다시 켜졌는데도 또 나타남", stage.single { it.contains("홈") })
            // 처음 짚은 때가 수집보다 늦으면(시계가 뒤로 감) 모르는 것으로 — 단계만
            assertTrue(stage.toString(), stage.single { it.contains("Play 서비스") }.endsWith("[비정상] Google Play 서비스 과소모 — 처음 짚은 뒤 워치가 다시 켜졌는데도 또 나타남"))
        }
        val members = letters.getValue(Evidence.LETTER_MEMBERS)
        assertTrue(members.lines().none { it.contains("워치 기록: 10/16부터") })
        // 주요 소견이 모두 위 증상 칸에 풀어 적혔으면 빈칸이 아니라 그렇다고 (리뷰: 소견이 있는데 '[  ]'이면 채울 칸으로 읽힘)
        assertTrue(members, members.contains("- 주요 소견:\n  - 위 증상 칸에 적음\n"))
    }

    /**
     * 그 무렵 바뀐 것은 가능성 높은 것 세 개를 시각 순으로, 나머지는 '외 N개' — 워치 앱이 적기 전이면 '모름'이라고(없다고 하지 않는다),
     * 적기 시작한 것이 그 무렵 안이면 그 앞은 모른다고 (리뷰)
     */
    @Test
    fun theTrendLineListsTheLikeliestChangesByTimeAndSaysWhatIsUnknown() {
        fun item(kind: com.mywatch.battery.core.ChangeKind, subject: String, from: String?, to: String?) = com.mywatch.battery.core.ChangeNote.Item(kind, subject, from, to)
        val app = com.mywatch.battery.core.ChangeKind.APP
        val five = weekFacts(week(mapOf(
            atD(12, 0) to listOf(tracking),
            atD(15, 10) to listOf(item(app, "com.example.app", null, "+1.0")),
            atD(15, 19) to listOf(item(com.mywatch.battery.core.ChangeKind.SETTING, "aod", "0", "1")),
            atD(16, 3) to listOf(fw),
            atD(16, 9) to listOf(item(com.mywatch.battery.core.ChangeKind.FACE, "face", "com.samsung.android.watch.watchface.basic", "com.example.face")),
            atD(16, 11) to listOf(item(app, "com.example.other", null, "2.0")),
        )))
        val line = lettersOf(five).getValue(Evidence.LETTER_MEMBERS).lines().single { it.startsWith("- 대기 소모가 늘어난 때") }
        assertTrue(line, line.endsWith("— 그 무렵 바뀐 것: 10/15 19:00 AOD 켬, 10/16 03:00 워치 소프트웨어 업데이트 (L350XXU1AZI1), " +
            "10/16 09:00 시계 화면 바꿈 (com.example.face) 외 2개"))
        // 워치 앱이 아직 적기 전 — '없음'이 아니라 '모름'
        val unknown = weekFacts(week(mapOf(atD(18, 5) to listOf(tracking))))
        val u = lettersOf(unknown).getValue(Evidence.LETTER_MEMBERS).lines().single { it.startsWith("- 대기 소모가 늘어난 때") }
        assertTrue(u, u.endsWith("— 그 무렵 바뀐 것은 모름(워치 앱이 아직 적기 전)"))
        // 그 무렵 안에서 적기 시작했다 — 본 것은 적고 그 앞은 모른다고
        val partly = weekFacts(week(mapOf(atD(15, 12) to listOf(tracking), atD(16, 3) to listOf(fw))))
        val pl = lettersOf(partly).getValue(Evidence.LETTER_MEMBERS).lines().single { it.startsWith("- 대기 소모가 늘어난 때") }
        assertTrue(pl, pl.endsWith("10/16 03:00 워치 소프트웨어 업데이트 (L350XXU1AZI1) (그 앞은 워치 앱이 적기 전이라 모름)"))
        // 이 진단 앱(지킴이) 설정은 삼성 쪽에서 알아보게
        assertEquals("배터리 진단 앱 설정: 밤 절전 모드 끔",
            Evidence.changeLabel(com.mywatch.battery.core.Change(0L, com.mywatch.battery.core.ChangeKind.CONFIG, "night_saver", "1", "0")))
    }

    /**
     * 처음 단계(재시작 뒤 아님)여도 처음 짚은 때를 알면 단계 줄로 — 등급과 함께, 주요 소견에선 뺀다. 지금은 안 보이지만 이틀 안에 짚었던
     * 문제(재시작·치료 직후 수집)도 처음 짚은 때를 알면 처음·마지막으로 짚은 때를 (리뷰)
     */
    @Test
    fun aKnownFirstSightingMakesAStageLineEvenAtTheFirstStep() {
        val now = atD(20, 22)
        val wm = mapOf("at" to (now - 5 * 60_000L).toDouble(), "app_window_min" to 120.0, "issue_home" to 1.0, "back_home" to 0.0,
            "first_home_at" to (now - 3 * 3_600_000L).toDouble(), "seen_gms_h" to 2.0, "first_gms_at" to (now - 30 * 3_600_000L).toDouble())
        val f = Facts(model = "SM-L350", collectedAt = now, watchMetrics = wm, zone = zone, packages = listOf(doctor.parse.PackageInfo(Analyzer.HOME, 10123, true, false)))
        val fs = Analyzer.findings(f)
        assertTrue(fs.map { it.id }.toString(), fs.any { it.id == "watch-home" } && fs.any { it.id == "watch-gms-earlier" })
        val l = Evidence.letters(f, fs, Analyzer.verdict(f, fs), emptyList(), null, LocalDate.of(2026, 10, 20), zone).getValue(Evidence.LETTER_MEMBERS)
        val stage = l.lines().filter { it.startsWith("- 워치 앱 지킴이") }
        assertTrue(stage.toString(), stage.contains("- 워치 앱 지킴이(앱별 통계, 최근 2시간): [주의] One UI Watch 홈이 화면 꺼진 채 CPU 를 많이 씀 — 처음 짚은 때 10/20 19:00"))
        assertTrue(stage.toString(), stage.contains("- 워치 앱 지킴이(최근 48시간): [참고] Google Play 서비스 과소모 — 처음 짚은 때 10/19 16:00 · 마지막으로 짚은 때 10/20 19:55(지금은 안 보임)"))
        assertTrue(l, l.lines().none { it.contains("[주의] 워치 앱: One UI Watch 홈 과소모") })
    }

    @Test
    fun purchaseDateBranches() {
        val within10 = File(build(LocalDate.of(2026, 10, 15)).dir, "03_소비자분쟁해결기준_요구서.txt").readText()
        assertTrue(within10.contains("5일"))
        assertTrue(within10.contains("지금 단계: 구입 10일 이내"))
        val within30 = File(build(LocalDate.of(2026, 9, 25)).dir, "03_소비자분쟁해결기준_요구서.txt").readText()
        assertTrue(within30.contains("25일"))
        assertTrue(within30.contains("지금 단계: 구입 1개월 이내"))
        val none = File(build(null).dir, "03_소비자분쟁해결기준_요구서.txt").readText()
        assertTrue(none.contains("[  ]"))
        assertTrue(none.contains("구입일"))
    }

    @Test
    fun unknownValuesBecomePlaceholders() {
        val letters = Evidence.letters(Facts(), emptyList(), DoctorVerdict(Cause.INSUFFICIENT, "낮음", "측정 더 필요", emptyList(), listOf("a", "b", "c"), emptyList()), emptyList(), null, LocalDate.of(2026, 10, 20), zone)
        val l1 = letters.getValue("01_삼성멤버스_오류보내기.txt")
        assertTrue(l1.contains("모델: [  ]"))
        assertTrue(l1.contains("[  ]"))
        assertEquals(setOf("01_삼성멤버스_오류보내기.txt", "02_서비스센터_접수메모.txt", "03_소비자분쟁해결기준_요구서.txt"), letters.keys)
    }
}
