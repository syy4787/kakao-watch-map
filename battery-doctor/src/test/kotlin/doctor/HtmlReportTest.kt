package doctor

import com.mywatch.battery.core.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class HtmlReportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun sampleReport(): Triple<Facts, List<Finding>, String> {
        val snap = sampleSnapshot(tmp.root)
        val f = Analyzer.facts(snap, zone)
        val fs = Analyzer.findings(f)
        val v = Analyzer.verdict(f, fs)
        return Triple(f, fs, HtmlReport.render(f, fs, v, HtmlReport.rawSections(snap), zone))
    }

    @Test
    fun conclusionCardTodoAndSectionsInOrder() {
        val (_, fs, html) = sampleReport()
        assertTrue(html.startsWith("<!doctype html>", ignoreCase = true))
        assertTrue(html.contains("<meta name=\"viewport\""))
        assertTrue(html.contains("<html lang=\"ko\""))
        // 결론 카드: 판정·신뢰도
        assertTrue(html.contains(Cause.SOFTWARE.label))
        assertTrue(html.contains("신뢰도"))
        // 할 일 3가지
        val todo = Regex("<ol class=\"todo\">(.*?)</ol>", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        assertEquals(3, Regex("<li>").findAll(todo).count())
        // 섹션 순서
        val order = listOf("결론", "지금 할 일", "수치 요약", "소견", "범인 앱", "워치 기록", "설정 점검", "원자료")
        val idx = order.map { html.indexOf("<h2>$it") }
        assertTrue(idx.joinToString(), idx.all { it >= 0 })
        assertEquals(idx.sorted(), idx)
        // 소견이 심각도 순으로 나오고 첫 소견은 비정상
        val first = Regex("class=\"finding sev-(\\w+)\"").find(html)!!.groupValues[1]
        assertEquals("ABNORMAL", first)
        assertTrue(fs.first().severity == Severity.ABNORMAL)
        // 범인 표에 패키지
        val table = Regex("<h2>범인 앱.*?</table>", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(table.contains("com.example.drain"))
    }

    @Test
    fun watchChartOnlyWhenSamplesExist() {
        val (_, _, html) = sampleReport()
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("class=\"charging\""))     // 충전 구간 음영
        assertTrue(html.contains("<polyline"))
        // 표본이 없으면 svg 없음
        val snap = sampleSnapshot(tmp.newFolder("nowatch"), except = setOf("watch_log.csv"))
        val f = Analyzer.facts(snap, zone)
        val fs = Analyzer.findings(f)
        val none = HtmlReport.render(f, fs, Analyzer.verdict(f, fs), emptyMap(), zone)
        assertFalse(none.contains("<svg"))
        assertTrue(none.contains("워치 기록이 없습니다"))
    }

    @Test
    fun anomalyMarkersAppearOnTheChart() {
        val (f, fs, _) = sampleReport()
        val s = f.watchSamples.toMutableList()
        val i = s.size - 10
        s[i] = s[i].copy(level = s[i - 1].level + 20, event = com.mywatch.battery.core.Event.BOOT)
        val f2 = f.copy(watchSamples = s, watch = com.mywatch.battery.core.DrainAnalyzer(445, zone).analyze(s, f.collectedAt!!))
        val html = HtmlReport.render(f2, fs, Analyzer.verdict(f2, Analyzer.findings(f2)), emptyMap(), zone)
        assertTrue(html.contains("class=\"anomaly\""))
    }

    @Test
    fun gaugeDriftShowsUpInTheWatchTable() {
        val (f, _, _) = sampleReport()
        val now = f.collectedAt!!
        val q = 15 * 60_000L
        val s = (0..24).map { i -> com.mywatch.battery.core.BatterySample(t = now - (24 - i) * q, level = 25 - i / 4, voltageMv = 3950, currentUa = -15_000, interactive = false) }
        val f2 = f.copy(watchSamples = s, watch = com.mywatch.battery.core.DrainAnalyzer(445, zone).analyze(s, now))
        val fs = Analyzer.findings(f2)
        val html = HtmlReport.render(f2, fs, Analyzer.verdict(f2, fs), emptyMap(), zone)
        assertTrue(html.contains("잔량 표시 점검"))
        assertTrue(html.contains("낮게 세는 중"))
        assertTrue(html.contains("강제 재시작"))
    }

    @Test
    fun theWatchAppSummaryIsShownEscaped() {
        val (f, fs, _) = sampleReport()
        val f2 = f.copy(watchSummary = "[알려진 문제] Google Play 서비스 과소모 <b>CPU</b>")
        val html = HtmlReport.render(f2, fs, Analyzer.verdict(f2, fs), emptyMap(), zone)
        assertTrue(html.contains("워치 앱이 쓴 요약"))
        assertTrue(html.contains("[알려진 문제] Google Play 서비스 과소모 &lt;b&gt;CPU&lt;/b&gt;"))
    }

    @Test
    fun escapesHtmlAndShowsSources() {
        val f = Facts(model = "SM-L350 <test> & co")
        val fs = listOf(Finding("x", Severity.ABNORMAL, "태그 <script>alert(1)</script> & 끝", listOf("근거 <b>"), listOf("com.example.<evil>"), source = Thresholds.SOURCE_IDLE, category = "drain"))
        val v = DoctorVerdict(Cause.HARDWARE, "낮음", "헤드라인 <h1>", listOf("왜 &"), listOf("1", "2", "3"), listOf("com.example.<evil>"))
        val html = HtmlReport.render(f, fs, v, mapOf("getprop" to "[ro.serialno]: [<x>]"), zone)
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; 끝"))
        assertTrue(html.contains("SM-L350 &lt;test&gt; &amp; co"))
        assertTrue(html.contains("헤드라인 &lt;h1&gt;"))
        assertTrue(html.contains("[ro.serialno]: [&lt;x&gt;]"))
        assertTrue(html.contains(Thresholds.SOURCE_IDLE))
        assertTrue(html.contains("<details"))
    }

    @Test
    fun thresholdSourcesAndSettingsCheckAreListed() {
        val (_, _, html) = sampleReport()
        assertTrue(html.contains(Thresholds.SOURCE_IDLE))
        assertTrue(html.contains(Rules.SOURCE_WAKELOCK))
        assertTrue(html.contains("wifi_scan_always_enabled"))
        assertTrue(html.contains("Android vitals"))
        // 수치 요약에 dumpsys 와 워치 수치
        assertTrue(html.contains("8.2시간") || html.contains("8시간 12분"))
        assertTrue(html.contains("mSavedBatteryAsoc"))
    }

    @Test
    fun writeCreatesReportInSnapshot() {
        val snap = sampleSnapshot(tmp.root)
        val f = Analyzer.facts(snap, zone)
        val fs = Analyzer.findings(f)
        val file = HtmlReport.write(snap, f, fs, Analyzer.verdict(f, fs), zone)
        assertEquals(File(snap.dir, "report.html"), file)
        assertTrue(file.length() > 10_000)
        assertTrue(file.readText().contains("<svg"))
    }
}
