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

    /** 워치 기록 칸에 날마다 대기 소모 표, 꺾인 날, 그 무렵 바뀐 것 */
    @Test
    fun theDailyTrendAndWhatChangedAreInTheWatchSection() {
        val q = 15 * 60_000L
        fun at(d: Int, h: Int) = java.time.LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant().toEpochMilli()
        val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
        listOf(1.0, 1.0, 1.1, 1.0, 2.6, 2.5, 2.4).forEachIndexed { i, r ->
            val start = at(12 + i, 0)
            var t = start
            while (t <= start + 20 * 3_600_000L) {
                val note = if (t == at(16, 3)) com.mywatch.battery.core.ChangeNote.encode(listOf(com.mywatch.battery.core.ChangeNote.Item(
                    com.mywatch.battery.core.ChangeKind.APP, "com.google.android.gms", null, "26.40.31"))) else ""
                samples += com.mywatch.battery.core.BatterySample(t = t, level = (100 - r * (t - start) / 3_600_000.0).toInt(), note = note); t += q
            }
        }
        val now = at(19, 22)
        val f = Facts(model = "SM-L350", nominalMah = 445, collectedAt = now, watchSamples = samples,
            watch = com.mywatch.battery.core.DrainAnalyzer(445, zone).analyze(samples, now),
            watchTrend = com.mywatch.battery.core.TrendReport.of(samples, now, zone))
        val fs = Analyzer.findings(f)
        val html = HtmlReport.render(f, fs, Analyzer.verdict(f, fs), emptyMap(), zone)
        val section = Regex("<h2>워치 기록.*?</section>", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(section.contains("날마다 대기 소모"))
        assertTrue(section.contains("<th>10/12</th>") && section.contains("<td>1.0</td>") && section.contains("<td>2.6</td>"))
        assertTrue(section.contains("10/16부터 대기 소모가 시간당 1.0% → 2.5%로 늘었습니다"))
        assertTrue(section.contains("Google Play 서비스 업데이트 (26.40.31)"))
        assertTrue(section, section.contains("캐시 지우기"))
        assertFalse("워치가 표본으로 쟀으면 다를 까닭 없음", section.contains("사용 통계로"))
        // 워치 요약이 사용 통계 기준이면 이 표(15분 기록)와 숫자가 조금 다를 수 있다고 적는다
        val fx = f.copy(watchSummary = "[최근 며칠] 10/12 0.9 · 10/13 0.9 (대기 %/h, 사용 통계 기준)")
        val withNote = HtmlReport.render(fx, fs, Analyzer.verdict(fx, fs), emptyMap(), zone)
        assertTrue(withNote.contains("사용 통계로 화면이 켜졌던 구간을 빼고"))
        // 날이 모자라면 몇 시간 넘는 날이 며칠 필요한지 (워치와 같은 6시간)
        val few = f.copy(watchTrend = com.mywatch.battery.core.TrendReport.of(samples.filter { it.t < at(15, 0) }, at(14, 22), zone))
        val short = HtmlReport.render(few, fs, Analyzer.verdict(few, fs), emptyMap(), zone)
        assertTrue(short, short.contains("6시간 넘는 날이 4일 필요합니다 (지금 3일)"))
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

    /** 설정 점검: Wear 의 화면 켜기 설정과, 이 워치에 있는 화면 켜기·AOD·절전 관련 키 전부(삼성 키 이름 확인용) */
    @Test
    fun settingsCheckListsWakeKeysAndEveryRelatedKeyOnThisWatch() {
        val f = Facts(settings = mapOf(
            "global" to mapOf("ambient_tilt_to_wake" to "1", "hotword_detection_enabled" to "1", "wifi_on" to "1", "samsung_aod_style" to "2"),
            "system" to mapOf("aod_mode" to "1", "screen_brightness" to "120", "noti_screen_on" to "1", "turn_on_screen_by_noti" to "0"),
        ))
        val html = HtmlReport.render(f, emptyList(), DoctorVerdict(Cause.NORMAL, "낮음", "h", emptyList(), listOf("1", "2", "3"), emptyList()), emptyMap(), zone)
        val section = Regex("<h2>설정 점검.*?</section>", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(section.contains("손목 올려 켜기"))
        assertTrue(section.contains("global/ambient_tilt_to_wake"))
        assertTrue(section.contains("Hey Google"))
        assertTrue(section.contains("화면 켜기·AOD·절전 관련 키"))
        assertTrue(section.contains("global/samsung_aod_style=2"))
        assertTrue(section.contains("system/aod_mode=1"))
        // 알림이 오면 화면 켜기('알림 받으면 화면 켜기')일 법한 키도 — 삼성 키 이름을 실제 수집으로 찾으려고
        assertTrue(section.contains("system/noti_screen_on=1"))
        assertTrue(section.contains("system/turn_on_screen_by_noti=0"))
        assertFalse(section.contains("screen_brightness=120"))
    }

    @Test
    fun theBeforeAfterComparisonIsASectionAfterTheTodoList() {
        val (f, fs, _) = sampleReport()
        val c = Comparison(0L, 1L, listOf("trim-caches"), listOf(CompareRow(Compare.SCREEN_OFF, "시간당 3.0%", "시간당 1.0%", Change.BETTER)),
            "조치 뒤 좋아졌습니다: 화면 꺼짐 소모 시간당 3.0% → 1.0% (66% 줄어듦)")
        val html = HtmlReport.render(f, fs, Analyzer.verdict(f, fs), emptyMap(), zone, comparison = c)
        val todo = html.indexOf("<h2>지금 할 일")
        val cmp = html.indexOf("<h2>치료 전후 비교")
        assertTrue(cmp > todo)
        assertTrue(cmp < html.indexOf("<h2>수치 요약"))
        assertTrue(html.contains("trim-caches"))
        assertTrue(html.contains("좋아짐"))
        assertFalse(HtmlReport.render(f, fs, Analyzer.verdict(f, fs), emptyMap(), zone).contains("치료 전후 비교"))
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
