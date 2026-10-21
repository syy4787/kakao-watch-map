package doctor

import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.ChangeFinder
import com.mywatch.battery.core.DayTrend
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.Rate
import com.mywatch.battery.core.Thresholds
import doctor.parse.Durations
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 한국어 단일 파일 HTML 보고서(CSS 인라인, 폰 화면 대응) */
object HtmlReport {
    private const val RAW_LIMIT = 200_000

    fun write(snapshot: Snapshot, f: Facts, findings: List<Finding>, v: DoctorVerdict, zone: ZoneId = ZoneId.systemDefault(), comparison: Comparison? = null): File {
        val file = File(snapshot.dir, "report.html")
        file.writeText(render(f, findings, v, rawSections(snapshot), zone, comparison), Charsets.UTF_8)
        return file
    }

    /** 원자료 섹션 — 수집한 텍스트 전부(너무 길면 앞부분만) */
    fun rawSections(snapshot: Snapshot): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, _) in Collect.COMMANDS) {
            val t = snapshot.text(name) ?: snapshot.error(name)?.let { "(수집 실패)\n$it" } ?: continue
            out[name] = if (t.length > RAW_LIMIT) t.substring(0, RAW_LIMIT) + "\n… (${t.length - RAW_LIMIT}자 생략 — 원본은 스냅샷 폴더의 $name.txt)" else t
        }
        snapshot.watchCsv?.let { csv ->
            val lines = csv.readLines(Charsets.UTF_8)
            out["watch_log.csv"] = if (lines.size > 400) (lines.take(2) + listOf("… (${lines.size - 402}줄 생략)") + lines.takeLast(400)).joinToString("\n") else lines.joinToString("\n")
        }
        return out
    }

    /** 기본 브라우저로 열기. 헤드리스면 false */
    fun open(file: File): Boolean = try {
        if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.Desktop.isDesktopSupported()) false
        else { java.awt.Desktop.getDesktop().browse(file.toURI()); true }
    } catch (_: Throwable) { false }

    fun render(
        f: Facts, findings: List<Finding>, v: DoctorVerdict, raw: Map<String, String> = emptyMap(), zone: ZoneId = ZoneId.systemDefault(),
        comparison: Comparison? = null,
    ): String {
        val sb = StringBuilder(64 * 1024)
        val when_ = f.collectedAt?.let { stamp(it, zone) } ?: "?"
        sb.append("<!doctype html>\n<html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>워치9 배터리 닥터 보고서 — ${esc(when_)}</title>\n<style>").append(CSS).append("</style></head>\n<body>\n")
        sb.append("<header><h1>워치9 배터리 닥터</h1><p class=\"meta\">모델 ${esc(f.model ?: "?")}${f.nominalMah?.let { " (공칭 $it mAh)" } ?: ""} · 펌웨어 ${esc(f.build ?: "?")} ${esc(f.incremental ?: "")} · 수집 ${esc(when_)}</p></header>\n")

        // 1. 결론
        sb.append("<section class=\"card verdict cause-${v.cause.name}\"><h2>결론</h2>")
        sb.append("<p class=\"cause\">${esc(v.cause.label)}</p><p class=\"headline\">${esc(v.headline)}</p>")
        sb.append("<p class=\"conf\">신뢰도: <b>${esc(v.confidence)}</b>${if (!f.hasWatchLog) " (워치 기록 없이 dumpsys 만으로 판정 — 워치 앱의 배터리 화면을 켜 두면 다음 분석이 더 정확합니다)" else ""}</p>")
        if (v.why.isNotEmpty()) { sb.append("<ul class=\"why\">"); v.why.forEach { sb.append("<li>${esc(it)}</li>") }; sb.append("</ul>") }
        if (v.culprits.isNotEmpty()) sb.append("<p>범인 후보: ${v.culprits.joinToString(", ") { "<code>${esc(it)}</code>" }}</p>")
        sb.append("</section>\n")

        // 2. 할 일
        sb.append("<section class=\"card\"><h2>지금 할 일 3가지</h2><ol class=\"todo\">")
        v.todo.forEach { sb.append("<li>${esc(it)}</li>") }
        sb.append("</ol></section>\n")

        // 2-1. 치료 전후 비교 (지난 수집이 있을 때)
        comparison?.let { c ->
            sb.append("<section class=\"card\"><h2>치료 전후 비교</h2>")
            sb.append("<p class=\"headline\">${esc(c.headline)}</p>")
            sb.append("<p class=\"meta\">지난 수집 ${esc(stamp(c.beforeAt, zone))} → 이번 수집 ${esc(stamp(c.afterAt, zone))}")
            if (c.actions.isNotEmpty()) sb.append(" · 사이에 한 조치: ${c.actions.joinToString(", ") { "<code>${esc(it)}</code>" }}")
            sb.append("</p><table><tr><th>항목</th><th>전</th><th>후</th><th>변화</th></tr>")
            for (r in c.rows) {
                val cls = when (r.change) { Change.BETTER -> "good"; Change.WORSE -> "bad"; Change.SAME -> "" }
                sb.append("<tr><td>${esc(r.label)}</td><td>${esc(r.before)}</td><td>${esc(r.after)}</td><td class=\"$cls\">${esc(r.change.label)}</td></tr>")
            }
            sb.append("</table><p class=\"legend\">15% 넘게(항목마다 정한 만큼 넘게) 달라져야 변화로 봅니다. 사용 습관이 같은 날끼리 비교해야 정확합니다.</p></section>\n")
        }

        // 3. 수치 요약
        sb.append("<section class=\"card\"><h2>수치 요약</h2>")
        summaryTable(sb, f, zone)
        sb.append("</section>\n")

        // 4. 소견
        sb.append("<section class=\"card\"><h2>소견 ${findings.size}개</h2>")
        if (findings.isEmpty()) sb.append("<p>소견이 없습니다.</p>")
        for (fd in findings) {
            sb.append("<div class=\"finding sev-${fd.severity.name}\"><div class=\"fhead\"><span class=\"badge\">${esc(fd.severity.label)}</span> ${esc(fd.title)}</div>")
            if (fd.evidence.isNotEmpty()) { sb.append("<ul>"); fd.evidence.forEach { sb.append("<li>${esc(it)}</li>") }; sb.append("</ul>") }
            if (fd.packages.isNotEmpty()) sb.append("<p class=\"pkgs\">${fd.packages.joinToString(" ") { "<code>${esc(it)}</code>" }}</p>")
            fd.source?.let { sb.append("<p class=\"source\">기준 출처: ${esc(it)}</p>") }
            sb.append("</div>")
        }
        sb.append("</section>\n")

        // 5. 범인 앱 표
        sb.append("<section class=\"card\"><h2>범인 앱</h2>")
        culpritTable(sb, f, findings, v)
        sb.append("</section>\n")

        // 6. 워치 기록 그래프
        sb.append("<section class=\"card\"><h2>워치 기록</h2>")
        if (f.watchSamples.isEmpty()) {
            sb.append("<p>워치 기록이 없습니다. 워치 앱(갤럭시워치 전용앱)의 '배터리' 화면을 한 번 열면 15분마다 기록이 쌓이고, 다음 수집부터 그래프와 대기 소모율이 나옵니다.</p>")
        } else {
            watchSummary(sb, f, zone)
            trendTable(sb, f, zone)
            sb.append(svg(f.watchSamples, f, zone))
            sb.append("<p class=\"legend\">선: 잔량 % · 회색 음영: 충전 중 · 파란 음영: 실험 구간 · 빨간 점: 잔량 표시 이상</p>")
        }
        sb.append("</section>\n")

        // 7. 설정 점검
        sb.append("<section class=\"card\"><h2>설정 점검</h2>")
        settingsTable(sb, f)
        sb.append("</section>\n")

        // 8. 원자료
        sb.append("<section class=\"card\"><h2>원자료 보기</h2><p>수집한 덤프 원문입니다(접혀 있음). 공유할 때는 증거 패키지의 <code>share/</code> 폴더(마스킹본)를 쓰세요.</p>")
        if (raw.isEmpty()) sb.append("<p>(원자료 없음)</p>")
        for ((name, text) in raw) sb.append("<details><summary>${esc(name)}</summary><pre>${esc(text)}</pre></details>")
        sb.append("</section>\n")

        sb.append("<footer><h2>기준 출처</h2><ul>")
        listOf(Thresholds.SOURCE_IDLE, Thresholds.SOURCE_MIXED, Thresholds.SOURCE_NIGHT, Thresholds.SOURCE_EXPERIMENT, Rules.SOURCE_WAKELOCK, Rules.SOURCE_AWAKE, Rules.SOURCE_ALARM, Rules.SOURCE_GMS, Rules.SOURCE_GAUGE, Rules.SOURCE_CAPACITY)
            .forEach { sb.append("<li>${esc(it)}</li>") }
        sb.append("</ul><p>퍼센트 기준이라 40mm(390 mAh)도 같은 표를 씁니다. 이 보고서는 PC 에서만 만들어지며 어디에도 전송되지 않습니다.</p></footer>\n</body></html>\n")
        return sb.toString()
    }

    private fun summaryTable(sb: StringBuilder, f: Facts, zone: ZoneId) {
        val rows = ArrayList<Pair<String, String>>()
        f.now?.let { n ->
            rows += "지금(dumpsys battery)" to listOfNotNull(n.level?.let { "잔량 $it%" }, n.voltageMv?.let { "${it} mV" }, n.tempDeciC?.let { "${it / 10.0}℃" }, n.statusLabel, n.healthLabel?.let { "건강 $it" }, n.chargeCounterUah?.let { "전하량 ${it} µAh" }).joinToString(" · ")
            if (n.samsung.isNotEmpty()) rows += "삼성 확장 필드" to n.samsung.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
        f.stats?.let { s ->
            rows += "배터리 사용 시간(마지막 충전 뒤)" to Durations.human(s.onBatteryMs)
            rows += "화면 켜짐" to (s.screenOnMs?.let { "${Durations.human(it)}${s.onBatteryMs?.takeIf { b -> b > 0 }?.let { b -> " (${pct(it * 100.0 / b)}%)" } ?: ""}" } ?: "-")
            rows += "화면 꺼짐 중 깨어 있음" to (s.screenOffAwakeMs?.let { "${Durations.human(it)}${s.screenOffAwakeRatio?.let { r -> " (${pct(r * 100)}%)" } ?: ""}" } ?: "-")
            rows += "소모" to listOfNotNull(s.dischargePercent?.let { "${it.first}~${it.second}포인트" }, s.dischargeMah?.let { "$it mAh" }, s.percentPerHour?.let { "시간당 ${fmt1(it)}%" }).joinToString(" · ").ifEmpty { "-" }
            rows += "추정 전력(계산 / 실측)" to listOfNotNull(s.computedDrainMah?.let { "${fmt1(it)} mAh" }, s.actualDrainMah?.let { "${fmt1(it.first)}~${fmt1(it.second)} mAh" }).joinToString(" / ").ifEmpty { "-" }
            rows += "무선·GPS" to listOfNotNull(s.radioActiveMs?.let { "셀룰러 라디오 ${Durations.human(it)}" }, s.wifiScanCount?.let { "Wi-Fi 스캔 ${it}회" }, s.gpsMs?.let { "GPS ${Durations.human(it)}" }).joinToString(" · ").ifEmpty { "-" }
        }
        f.watch?.let { w ->
            rows += "워치 기록 표본" to "${w.samples}개, ${fmt1(w.spanHours)}시간"
            rows += "워치 소모율 1h / 8h / 24h" to listOf(w.w1, w.w8, w.w24).joinToString(" / ") { rate(it) }
            rows += "워치 대기(화면 꺼짐) 24h" to rate(w.idle24)
            w.overnight?.let { rows += (w.overnightLabel ?: "밤사이") to rate(it) }
            w.capacity?.let { rows += "충전 곡선 추정 용량" to "${fmt1(it.estimatedMah)} mAh${it.percentOfNominal?.let { p -> " (공칭의 ${pct(p)}%)" } ?: ""}, 세션 ${it.sessions}개" }
            rows += "잔량 표시 점검(쉬는 전압 기준)" to (w.drift?.let { d -> "${d.headline} (신뢰도 ${d.confidence}, 쉬는 표본 ${d.restSamples}개, ${String.format(Locale.US, "%.2f", d.restMv / 1000.0)}V)" } ?: "쉬는 표본(화면 꺼짐·충전 안 함)이 3개 미만이라 아직 모름")
            w.recoveries.firstOrNull()?.let { r -> rows += "마지막 재시작 전후 잔량" to "${stamp(r.t, zone)} ${r.before}% → ${r.after}% (${if (r.gained >= 0) "+" else ""}${r.gained})${if (r.userMarked) " · 사용자가 표시" else ""}" }
            rows += "워치 판정(battery-core)" to "${w.verdict.grade.label} — ${w.verdict.headline}"
        }
        f.idle?.let { rows += "Doze 상태" to "${it.state ?: "?"}${it.lightState?.let { l -> " / light $l" } ?: ""}, 예외 ${it.whitelist.size}개(사용자 ${it.userWhitelist.size})" }
        f.gmsVersion?.let { rows += "Google Play 서비스" to it }
        f.power?.let { rows += "덤프 시점 화면" to "${if (it.interactive) "켜짐" else "꺼짐"} (${it.wakefulness ?: "?"}, display ${it.displayState ?: "?"})" }
        if (f.missing.isNotEmpty()) rows += "수집 실패" to f.missing.joinToString(", ")
        sb.append("<table class=\"kv\">")
        for ((k, vv) in rows) sb.append("<tr><th>${esc(k)}</th><td>${esc(vv)}</td></tr>")
        sb.append("</table>")
        sb.append("<p class=\"source\">기준: 대기 ${Thresholds.IDLE_NORMAL}%/h 이하 정상·${Thresholds.IDLE_ABNORMAL}%/h 초과 비정상(${esc(Thresholds.SOURCE_IDLE)}); 24시간 평균 ${Thresholds.MIXED_NORMAL}/${Thresholds.MIXED_ABNORMAL}%/h(${esc(Thresholds.SOURCE_MIXED)}); 실험 ${Thresholds.EXPERIMENT_NORMAL}/${Thresholds.EXPERIMENT_ABNORMAL}%/h(${esc(Thresholds.SOURCE_EXPERIMENT)})</p>")
    }

    private fun culpritTable(sb: StringBuilder, f: Facts, findings: List<Finding>, v: DoctorVerdict) {
        val s = f.stats
        val pkgs = LinkedHashSet<String>()
        pkgs += v.culprits
        findings.filter { it.blames }.forEach { pkgs += it.packages }
        s?.powerItems?.sortedByDescending { it.mah }?.take(5)?.mapNotNull { it.pkg }?.let { pkgs += it }
        if (pkgs.isEmpty()) { sb.append("<p>범인으로 지목된 앱이 없습니다.</p>"); return }
        val hours = s?.onBatteryHours
        val alarmHours = (f.alarmElapsedMs ?: s?.onBatteryMs)?.takeIf { it > 0 }?.let { it / 3_600_000.0 }
        sb.append("<table><tr><th>패키지</th><th>소견</th><th>추정 전력</th><th>웨이크락</th><th>깨움 알람</th><th>센서</th></tr>")
        for (p in pkgs) {
            val fs = findings.filter { p in it.packages && it.blames }
            val power = s?.powerItems?.filter { it.pkg == p }?.sumOf { it.mah }?.takeIf { it > 0 }
            val wl = s?.partialWakeLocks?.filter { (it.pkg ?: f.packageByUid(it.uid)) == p }
            val wlMs = wl?.sumOf { it.totalMs }?.takeIf { it > 0 }
            val al = f.alarms.firstOrNull { it.pkg == p }
            val sensors = f.sensors.filter { it.pkg == p && it.active }
            sb.append("<tr><td><code>${esc(p)}</code></td>")
            sb.append("<td>${fs.joinToString("<br>") { "<span class=\"badge sev-${it.severity.name}\">${esc(it.severity.label)}</span> ${esc(it.title)}" }.ifEmpty { "-" }}</td>")
            sb.append("<td>${power?.let { "${fmt1(it)} mAh" } ?: "-"}</td>")
            sb.append("<td>${wlMs?.let { "${Durations.human(it)}${hours?.let { h -> " (${fmt1(it / 1000.0 / h)}초/시간)" } ?: ""}" } ?: "-"}</td>")
            sb.append("<td>${al?.let { a -> "${a.wakeups}회${alarmHours?.let { h -> " (시간당 ${fmt1(a.wakeups / h)})" } ?: ""}" } ?: "-"}</td>")
            sb.append("<td>${sensors.joinToString(", ") { esc(it.sensor) }.ifEmpty { "-" }}</td></tr>")
        }
        sb.append("</table>")
    }

    private fun settingsTable(sb: StringBuilder, f: Facts) {
        data class Check(val ns: String, val key: String, val good: String, val meaning: String)
        val checks = listOf(
            Check("global", "wifi_scan_always_enabled", "0", "Wi-Fi 꺼도 스캔 허용"),
            Check("global", "ble_scan_always_enabled", "0", "블루투스 꺼도 BLE 스캔 허용"),
            Check("global", "adaptive_battery_management_enabled", "1", "적응형 배터리"),
            Check("global", "app_standby_enabled", "1", "앱 대기(App Standby)"),
            Check("global", "low_power", "", "절전 모드"),
            Check("global", "airplane_mode_on", "", "비행기 모드"),
            Check("global", "wifi_on", "", "Wi-Fi"),
            Check("global", "bluetooth_on", "", "블루투스"),
            Check("secure", "ambient_enabled", "", "AOD(ambient)"),
            Check("global", "ambient_enabled", "", "AOD(ambient, Wear 전역)"),
            Check("global", "ambient_tilt_to_wake", "", "손목 올려 켜기(1=켬)"),
            Check("global", "ambient_touch_to_wake", "", "화면 터치로 켜기(1=켬)"),
            Check("global", "hotword_detection_enabled", "", "'Hey Google' 음성 호출(1=켬)"),
            Check("global", "battery_saver_mode", "", "Wear 절전 방식(0 없음·1 가볍게·2 시계만·3 시간만·4 제조사)"),
            Check("global", "bedtime_mode", "", "취침 모드(1=켬)"),
            Check("secure", "location_mode", "", "위치 모드"),
            Check("system", "screen_off_timeout", "", "화면 자동 꺼짐(ms)"),
            Check("system", "screen_brightness_mode", "", "자동 밝기(1=켬)"),
        )
        sb.append("<table><tr><th>설정</th><th>키</th><th>값</th><th>판단</th></tr>")
        for (c in checks) {
            val v = f.settings[c.ns]?.get(c.key)
            val judge = when {
                v == null -> "해당 없음(키 없음)"
                c.good.isEmpty() -> "참고"
                v.trim() == c.good -> "정상"
                else -> "위험 — 메뉴 4에서 바꿀 수 있음"
            }
            sb.append("<tr><td>${esc(c.meaning)}</td><td><code>${esc(c.ns)}/${esc(c.key)}</code></td><td>${esc(v ?: "-")}</td><td class=\"${if (judge.startsWith("위험")) "bad" else ""}\">${esc(judge)}</td></tr>")
        }
        sb.append("</table>")
        // 삼성 워치는 제 이름의 키를 더 쓸 수 있다 — 화면 켜기·AOD·절전에 관한 키를 모두 보여 줘 실제 이름을 확인하게
        val related = f.settings.flatMap { (ns, m) -> m.filterKeys { RELATED_KEY.containsMatchIn(it) }.map { "$ns/${it.key}=${it.value}" } }.sorted()
        if (related.isNotEmpty()) {
            sb.append("<details><summary>이 워치의 화면 켜기·AOD·절전 관련 키 ${related.size}개</summary><pre class=\"raw\">")
            sb.append(esc(related.take(80).joinToString("\n")))
            sb.append("</pre></details>")
        }
        f.idle?.userWhitelist?.takeIf { it.isNotEmpty() }?.let { sb.append("<p>Doze 예외(사용자 추가): ${it.joinToString(", ") { p -> "<code>${esc(p)}</code>" }} — 배터리 최적화에서 빠진 앱들입니다.</p>") }
    }

    private fun watchSummary(sb: StringBuilder, f: Facts, zone: ZoneId) {
        f.watchSummary?.let { sb.append("<details open><summary>워치 앱이 쓴 요약 (사용 내역·알려진 문제·밤사이 범인)</summary><pre class=\"raw\">${esc(it)}</pre></details>") }
        val w = f.watch ?: return
        sb.append("<p>표본 ${w.samples}개 (${fmt1(w.spanHours)}시간) · ${esc(w.verdict.headline)}</p>")
        w.drift?.takeIf { it.suspicious }?.let { d ->
            sb.append("<p class=\"${if (d.certain) "bad" else ""}\"><b>잔량 표시 점검:</b> ${esc(d.headline)} (신뢰도 ${esc(d.confidence)})")
            d.advice?.let { sb.append(" — ${esc(it)}") }
            sb.append("</p>")
        }
        if (w.experiments.isNotEmpty()) {
            sb.append("<ul>")
            for (e in w.experiments) sb.append("<li>실험 '${esc(e.name)}' ${stamp(e.start, zone)} ~ ${e.end?.let { stamp(it, zone) } ?: "진행 중"}: ${rate(e.rate)} — ${e.grade.label}</li>")
            sb.append("</ul>")
        }
    }

    /** 날마다 대기 소모 표 + 꺾인 날 + 그 무렵 바뀐 것(할 일) + 최근 7일 바뀐 것 */
    private fun trendTable(sb: StringBuilder, f: Facts, zone: ZoneId) {
        val t = f.watchTrend ?: return
        if (t.days.none { it.pph != null }) return
        sb.append("<h3>날마다 대기 소모 (시간당 %, 화면 꺼짐·충전 안 함)</h3><table class=\"days\"><tr>")
        t.shownDays.forEach { sb.append("<th>${esc(ChangeFinder.day(it.date))}</th>") }
        sb.append("</tr><tr>")
        t.shownDays.forEach { d ->
            val cls = when (Thresholds.idle(d.pph)) { com.mywatch.battery.core.Grade.ABNORMAL -> " class=\"bad\""; else -> "" }
            sb.append("<td$cls>${d.pph?.let { fmt1(it) } ?: "-"}</td>")
        }
        sb.append("</tr></table>")
        // 워치 화면·요약은 사용 통계가 있으면 화면이 켜졌던 구간을 빼고 잰다 — 이 표(15분 기록의 화면 상태)와 숫자가 다를 수 있다
        if (f.watchSummary?.contains("사용 통계 기준") == true) {
            sb.append("<p class=\"legend\">워치 화면의 '최근 며칠'은 사용 통계로 화면이 켜졌던 구간을 빼고 재서, 15분 기록으로 잰 이 표와 숫자가 조금 다를 수 있습니다.</p>")
        }
        val shift = t.shift
        if (shift != null) {
            sb.append("<p class=\"${if (shift.worse) "bad" else "good"}\"><b>${esc(ChangeFinder.headline(shift))}</b></p>")
            when {
                t.suspects.isNotEmpty() -> {
                    sb.append("<p>그 무렵 바뀐 것 (가능성 높은 순):</p><ul>")
                    t.suspects.take(5).forEach { sb.append("<li>${esc(stamp(it.t, zone))} ${esc(ChangeFinder.label(it) { null })}</li>") }
                    sb.append("</ul>")
                }
                !t.known -> sb.append("<p>그때는 워치가 아직 바뀐 것을 적기 전이라 그 무렵 변화는 모릅니다.</p>")
                else -> sb.append("<p>그 무렵 워치 앱이 아는 변화(소프트웨어·앱·시계 화면·주요 설정)는 없습니다.</p>")
            }
            t.advice { null }?.let { (todo, how) -> sb.append("<p>할 일: <b>${esc(todo)}</b> — ${esc(how)}</p>") }
        } else if (t.validDays < 2 * DayTrend.MIN_SIDE_DAYS) {
            sb.append("<p>날마다 견주려면 대기 기록이 ${DayTrend.MIN_HOURS.toInt()}시간 넘는 날이 ${2 * DayTrend.MIN_SIDE_DAYS}일 필요합니다 (지금 ${t.validDays}일).</p>")
        } else {
            sb.append("<p>날마다 대기 소모에 뚜렷한 꺾임은 없습니다.</p>")
        }
        val others = t.recent.filter { it !in t.suspects.take(5) }.take(8)
        if (others.isNotEmpty()) {
            sb.append("<details><summary>최근 7일 바뀐 것 ${others.size}개</summary><ul>")
            others.forEach { sb.append("<li>${esc(stamp(it.t, zone))} ${esc(ChangeFinder.label(it) { null })}</li>") }
            sb.append("</ul></details>")
        }
    }

    /** 잔량 vs 시간 선그래프 — 충전 구간 음영, 실험 구간 음영, 이상 지점 표시 */
    fun svg(samples: List<BatterySample>, f: Facts, zone: ZoneId): String {
        val sorted = samples.sortedBy { it.t }
        if (sorted.size < 2) return "<p>표본이 2개 미만이라 그래프를 그릴 수 없습니다.</p>"
        val w = 720.0; val h = 260.0; val l = 40.0; val r = 12.0; val top = 12.0; val bottom = 36.0
        val t0 = sorted.first().t; val t1 = sorted.last().t
        val span = (t1 - t0).coerceAtLeast(1)
        fun x(t: Long) = l + (t - t0).toDouble() / span * (w - l - r)
        fun y(level: Int) = top + (100 - level) / 100.0 * (h - top - bottom)
        val sb = StringBuilder()
        sb.append("<svg viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" width=\"100%\" role=\"img\" aria-label=\"잔량 그래프\" xmlns=\"http://www.w3.org/2000/svg\">")
        // 충전 구간
        var i = 0
        while (i < sorted.size) {
            if (sorted[i].plugged) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].plugged) j++
                val x1 = x(sorted[i].t); val x2 = x(sorted[minOf(j + 1, sorted.size - 1)].t)
                sb.append("<rect class=\"charging\" x=\"${fmt1(x1)}\" y=\"$top\" width=\"${fmt1((x2 - x1).coerceAtLeast(1.0))}\" height=\"${fmt1(h - top - bottom)}\" fill=\"#ddd\"/>")
                i = j + 1
            } else i++
        }
        // 실험 구간
        f.watch?.experiments?.forEach { e ->
            val x1 = x(e.start); val x2 = x(e.end ?: t1)
            sb.append("<rect class=\"experiment\" x=\"${fmt1(x1)}\" y=\"$top\" width=\"${fmt1((x2 - x1).coerceAtLeast(1.0))}\" height=\"${fmt1(h - top - bottom)}\" fill=\"#cfe3ff\" opacity=\"0.6\"/>")
        }
        // 격자·축
        for (lv in listOf(0, 25, 50, 75, 100)) {
            sb.append("<line x1=\"$l\" x2=\"${w - r}\" y1=\"${fmt1(y(lv))}\" y2=\"${fmt1(y(lv))}\" stroke=\"#e5e5e5\"/>")
            sb.append("<text x=\"${l - 6}\" y=\"${fmt1(y(lv) + 4)}\" font-size=\"11\" text-anchor=\"end\" fill=\"#555\">$lv%</text>")
        }
        val tickFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
        val step = when { span > 3L * 86_400_000 -> 12L * 3_600_000; span > 36L * 3_600_000 -> 6L * 3_600_000; else -> 3L * 3_600_000 }
        var tick = (t0 / step + 1) * step
        while (tick < t1) {
            sb.append("<line x1=\"${fmt1(x(tick))}\" x2=\"${fmt1(x(tick))}\" y1=\"$top\" y2=\"${fmt1(h - bottom)}\" stroke=\"#f0f0f0\"/>")
            sb.append("<text x=\"${fmt1(x(tick))}\" y=\"${fmt1(h - bottom + 14)}\" font-size=\"10\" text-anchor=\"middle\" fill=\"#555\">${Instant.ofEpochMilli(tick).atZone(zone).format(tickFmt)}</text>")
            tick += step
        }
        // 선: 2시간 넘게 끊긴 곳은 선을 끊는다
        val segments = ArrayList<MutableList<BatterySample>>()
        for (s in sorted) {
            val last = segments.lastOrNull()?.lastOrNull()
            if (last == null || s.t - last.t > 2 * 3_600_000L) segments += ArrayList<BatterySample>().apply { add(s) } else segments.last() += s
        }
        for (seg in segments) {
            sb.append("<polyline fill=\"none\" stroke=\"#1976d2\" stroke-width=\"2\" points=\"")
            seg.forEach { sb.append(fmt1(x(it.t))).append(',').append(fmt1(y(it.level))).append(' ') }
            sb.append("\"/>")
        }
        // 이상 지점
        f.watch?.anomalies?.forEach { a ->
            sb.append("<circle class=\"anomaly\" cx=\"${fmt1(x(a.t))}\" cy=\"${fmt1(y(a.toLevel))}\" r=\"5\" fill=\"#e53935\"><title>${esc(stamp(a.t, zone))} ${esc(a.label)}: ${esc(a.detail)}</title></circle>")
        }
        // 부팅 표시
        sorted.filter { it.event == Event.BOOT }.forEach { b ->
            sb.append("<text x=\"${fmt1(x(b.t))}\" y=\"${fmt1(top + 10)}\" font-size=\"10\" text-anchor=\"middle\" fill=\"#e53935\">부팅</text>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun rate(r: Rate): String {
        val p = r.percentPerHour ?: return "-"
        return "시간당 ${fmt1(p)}%" + (r.mahPerHour?.let { " (${fmt1(it)} mAh/h)" } ?: "")
    }

    fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    private fun fmt1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun pct(x: Double) = String.format(Locale.US, "%.1f", x)
    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 화면 켜기·AOD·절전에 관한 설정 키 이름 (삼성 고유 키 찾기용) */
    private val RELATED_KEY = Regex("aod|ambient|always_on|wake|tilt|wrist|raise|gesture|hotword|talk|assistant|theater|bedtime|power_sav|battery_saver|low_power|doze", RegexOption.IGNORE_CASE)
    private fun stamp(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).format(stampFmt)

    private const val CSS = """
body{font-family:-apple-system,"Malgun Gothic","Apple SD Gothic Neo","Noto Sans KR",sans-serif;margin:0;padding:12px;background:#f6f7f9;color:#1b1b1b;line-height:1.5}
header{margin:4px 0 12px}h1{font-size:1.4rem;margin:0}h2{font-size:1.1rem;margin:0 0 8px;border-bottom:1px solid #e3e3e3;padding-bottom:4px}
.meta{color:#555;font-size:.9rem;margin:2px 0}
.card{background:#fff;border-radius:10px;padding:14px;margin:0 0 12px;box-shadow:0 1px 2px rgba(0,0,0,.06)}
.verdict{border-left:8px solid #888}.cause-GAUGE,.cause-HARDWARE{border-left-color:#e53935}.cause-SOFTWARE{border-left-color:#fb8c00}.cause-USAGE{border-left-color:#fdd835}.cause-NORMAL{border-left-color:#43a047}.cause-INSUFFICIENT{border-left-color:#90a4ae}
.cause{font-size:1.3rem;font-weight:700;margin:4px 0}.headline{font-size:1.05rem;margin:4px 0}.conf{margin:4px 0}
.why{margin:6px 0 0 18px;padding:0;color:#333}
ol.todo{padding-left:22px}ol.todo li{margin:6px 0}
table{border-collapse:collapse;width:100%;font-size:.92rem}th,td{border:1px solid #e6e6e6;padding:6px 8px;text-align:left;vertical-align:top;word-break:break-word}th{background:#fafafa}
table.kv th{width:38%}
h3{font-size:1rem;margin:12px 0 6px}table.days{width:auto}table.days th,table.days td{text-align:center;padding:4px 6px;min-width:2.6em}
.finding{border-left:5px solid #bbb;padding:8px 10px;margin:8px 0;background:#fcfcfc;border-radius:6px}.finding ul{margin:4px 0 0 18px;padding:0}
.sev-ABNORMAL{border-left-color:#e53935}.sev-CAUTION{border-left-color:#fb8c00}.sev-INFO{border-left-color:#9e9e9e}
.badge{display:inline-block;font-size:.75rem;padding:1px 6px;border-radius:4px;background:#9e9e9e;color:#fff;margin-right:4px}.finding.sev-ABNORMAL .badge,.badge.sev-ABNORMAL{background:#e53935}.finding.sev-CAUTION .badge,.badge.sev-CAUTION{background:#fb8c00}
.fhead{font-weight:600}.source{font-size:.8rem;color:#666;margin:4px 0 0}.pkgs{margin:4px 0 0}code{background:#f1f1f1;padding:0 4px;border-radius:3px;font-size:.88em}
.bad{color:#c62828;font-weight:600}.good{color:#2e7d32;font-weight:600}.legend{font-size:.85rem;color:#555}
details{margin:6px 0}summary{cursor:pointer;font-weight:600}pre{background:#f4f4f4;padding:8px;overflow:auto;font-size:.78rem;max-height:60vh;white-space:pre-wrap;word-break:break-all}
footer{color:#555;font-size:.85rem;padding:8px 4px}footer ul{padding-left:18px}
svg text{font-family:inherit}
"""
}
