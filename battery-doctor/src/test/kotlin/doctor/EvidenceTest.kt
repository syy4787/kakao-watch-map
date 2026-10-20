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
