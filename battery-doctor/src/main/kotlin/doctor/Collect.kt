package doctor

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

/** 수집 한 번의 결과 폴더 `data/snapshots/yyyyMMdd-HHmmss/` */
data class Snapshot(val dir: File) {
    /** `<name>.txt` 내용. 없거나 실패했으면 null */
    fun text(name: String): String? = File(dir, "$name.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)

    /** `<name>.error.txt` 내용(수집 실패 사유) */
    fun error(name: String): String? = File(dir, "$name.error.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)

    /** 수집에 실패한 이름들 */
    fun failed(): List<String> = Collect.COMMANDS.map { it.first }.filter { text(it) == null }

    val watchCsv: File? get() = File(dir, "watch_log.csv").takeIf { it.isFile && it.length() > 0 }

    /** 워치 앱이 하루에 한 번 쓰는 요약(사용 내역·알려진 문제·밤사이 범인 등) — 없으면 null */
    val watchSummary: String? get() = File(dir, "watch_summary.txt").takeIf { it.isFile && it.length() > 0 }?.readText(Charsets.UTF_8)

    val meta: Properties
        get() = Properties().also { p ->
            File(dir, "meta.properties").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { p.load(it) }
        }

    /**
     * 수집 시각(epoch ms). 워치 시계(`date +%s`)를 우선 — 워치 CSV 의 시각과 같은 시계라야 소모율 창이 맞는다.
     * 없으면 meta, 그다음 폴더 이름.
     */
    val time: Long
        get() {
            text("date")?.trim()?.toLongOrNull()?.let { return it * 1000 }
            meta.getProperty("time")?.toLongOrNull()?.let { return it }
            return runCatching {
                LocalDateTime.parse(dir.name, Collect.DIR_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrElse { dir.lastModified() }
        }
}

/** 스펙 3장의 수집 목록을 돌려 스냅샷 폴더를 만든다 */
object Collect {
    const val WATCH_CSV = "/sdcard/Android/data/com.mywatch.kakaomap/files/battery/log.csv"
    const val WATCH_SUMMARY = "/sdcard/Android/data/com.mywatch.kakaomap/files/battery/summary.txt"
    val DIR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 이름 → `adb shell` 뒤에 붙는 인자 */
    val COMMANDS: List<Pair<String, List<String>>> = listOf(
        "getprop" to listOf("getprop"),
        "battery" to listOf("dumpsys", "battery"),
        "batterystats" to listOf("dumpsys", "batterystats"),
        "batterystats_checkin" to listOf("dumpsys", "batterystats", "--checkin"),
        "power" to listOf("dumpsys", "power"),
        "alarm" to listOf("dumpsys", "alarm"),
        "sensorservice" to listOf("dumpsys", "sensorservice"),
        "location" to listOf("dumpsys", "location"),
        "deviceidle" to listOf("dumpsys", "deviceidle"),
        "jobscheduler" to listOf("dumpsys", "jobscheduler"),
        "wearable" to listOf("dumpsys", "activity", "service", "WearableService"),
        "gms_package" to listOf("dumpsys", "package", "com.google.android.gms"),
        "packages_u" to listOf("pm", "list", "packages", "-U"),
        "packages_e" to listOf("pm", "list", "packages", "-e"),
        "packages_d" to listOf("pm", "list", "packages", "-d"),
        "packages_3" to listOf("pm", "list", "packages", "-3"),
        "settings_global" to listOf("settings", "list", "global"),
        "settings_secure" to listOf("settings", "list", "secure"),
        "settings_system" to listOf("settings", "list", "system"),
        "doze_whitelist" to listOf("cmd", "deviceidle", "whitelist"),
        "date" to listOf("date", "+%s"),
    )

    /** 덤프가 큰 명령은 더 기다린다 */
    private fun timeoutFor(name: String) = when (name) {
        "batterystats", "batterystats_checkin", "jobscheduler" -> 300
        else -> 120
    }

    fun run(adb: AdbRunner, root: File, bugreport: Boolean, log: (String) -> Unit, now: Long = System.currentTimeMillis()): Snapshot {
        val stamp = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDateTime().format(DIR_FORMAT)
        var dir = File(root, "snapshots/$stamp")
        var n = 1
        while (dir.exists()) dir = File(root, "snapshots/$stamp-${n++}")
        dir.mkdirs()

        for ((name, args) in COMMANDS) {
            log("수집: $name (${args.joinToString(" ")})")
            val r = try {
                adb.run(listOf("shell") + args, timeoutFor(name))
            } catch (e: Exception) {
                Result(-3, "", e.toString())
            }
            if (r.ok && r.out.isNotBlank()) {
                File(dir, "$name.txt").writeText(r.out, Charsets.UTF_8)
            } else if (r.ok) {
                // 성공했지만 빈 출력 — 그래도 파일은 남긴다(비어 있음을 알 수 있게)
                File(dir, "$name.txt").writeText(r.out, Charsets.UTF_8)
            } else {
                File(dir, "$name.error.txt").writeText("exit=${r.code}\n${r.err}\n${r.out}", Charsets.UTF_8)
                log("  실패(${r.code}): ${r.err.lineSequence().firstOrNull() ?: ""}")
            }
        }

        // 워치 앱의 배터리 기록 CSV
        val ls = adb.run(listOf("shell", "ls", WATCH_CSV), 30)
        if (ls.ok && ls.out.contains("log.csv")) {
            log("수집: 워치 배터리 기록(watch_log.csv)")
            if (!adb.pull(WATCH_CSV, File(dir, "watch_log.csv"))) log("  워치 CSV 를 가져오지 못했습니다")
        } else {
            log("워치 배터리 기록이 아직 없습니다 (워치 앱의 '배터리' 화면을 한 번 열면 기록이 시작됩니다)")
        }
        // 워치 앱이 쓴 요약 (있으면)
        val lsSummary = adb.run(listOf("shell", "ls", WATCH_SUMMARY), 30)
        if (lsSummary.ok && lsSummary.out.contains("summary.txt")) {
            log("수집: 워치 앱 요약(watch_summary.txt)")
            if (!adb.pull(WATCH_SUMMARY, File(dir, "watch_summary.txt"))) log("  워치 요약을 가져오지 못했습니다")
        }

        if (bugreport) {
            log("bugreport 받는 중 — 무선이라 수 분 걸릴 수 있습니다. 끊지 마세요")
            val target = File(dir, "bugreport.zip")
            val r = adb.run(listOf("bugreport", target.path), 900)
            if (!r.ok) File(dir, "bugreport.error.txt").writeText("exit=${r.code}\n${r.err}\n${r.out}", Charsets.UTF_8)
        }

        val props = Properties()
        props.setProperty("time", now.toString())
        serialOf(adb)?.let { props.setProperty("serial", it) }
        val getprop = File(dir, "getprop.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: ""
        propOf(getprop, "ro.product.model")?.let { props.setProperty("model", it) }
        propOf(getprop, "ro.build.display.id")?.let { props.setProperty("build", it) }
        File(dir, "meta.properties").writer(Charsets.UTF_8).use { props.store(it, "battery-doctor snapshot") }
        return Snapshot(dir)
    }

    /** 가장 최근 스냅샷(폴더 이름 순) */
    fun latest(root: File): Snapshot? =
        File(root, "snapshots").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.let { Snapshot(it) }

    /** `[key]: [value]` 형태의 getprop 출력에서 값 */
    fun propOf(getprop: String, key: String): String? {
        val m = Regex("\\[" + Regex.escape(key) + "\\]: \\[([^\\]]*)\\]").find(getprop) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    private fun serialOf(adb: AdbRunner): String? = adb.serial
}
