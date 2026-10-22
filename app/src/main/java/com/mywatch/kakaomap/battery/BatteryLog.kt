package com.mywatch.kakaomap.battery

import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.SampleCsv
import java.io.File
import java.io.RandomAccessFile

/**
 * 배터리 표본 CSV 파일. 앱 전용 외부 저장 공간(PC 에서 `adb pull` 로 가져갈 수 있는 곳)에 두고, 없으면 내부 저장 공간.
 * 덧붙이기 전용이고 30일보다 오래된 줄은 하루에 한 번 잘라낸다. 워치 밖으로 보내지 않는다.
 */
class BatteryLog(context: Context) {

    private val app = context.applicationContext
    val dir: File = ((runCatching { app.getExternalFilesDir(null) }.getOrNull()) ?: app.filesDir).resolve("battery")
    val file: File = dir.resolve("log.csv")
    val summaryFile: File = dir.resolve("summary.txt")

    fun append(sample: BatterySample) = synchronized(LOCK) {
        dir.mkdirs()
        if (!file.exists() || file.length() == 0L) file.writeText(SampleCsv.MAGIC + "\n" + SampleCsv.HEADER + "\n")
        file.appendText(SampleCsv.line(sample) + "\n")
    }

    fun readAll(): List<BatterySample> = synchronized(LOCK) {
        if (file.exists()) SampleCsv.parse(file.readText()) else emptyList()
    }

    /**
     * 최근 [days]일의 표본만 — 파일 끝부분만 읽는다(15분마다 30일 파일 전체를 읽지 않게). 끝부분에 그 기간이 다 들어 있지
     * 않으면 읽는 양을 두 배씩 늘린다. [readAll] 처럼 시각 순으로 — 부팅 줄은 켜진 때로 앞당겨 덧붙이므로 파일 순서와 다를 수 있다
     */
    fun readRecent(days: Int, now: Long = System.currentTimeMillis()): List<BatterySample> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        val from = now - days * 86_400_000L
        val len = file.length()
        var want = (days * 96L * 100L).coerceAtLeast(16_384L)
        while (want < len) {
            val bytes = ByteArray(want.toInt())
            RandomAccessFile(file, "r").use { raf -> raf.seek(len - want); raf.readFully(bytes) }
            // 첫 줄은 잘렸을 수 있어 버린다
            val parsed = String(bytes, Charsets.UTF_8).substringAfter('\n').lineSequence().mapNotNull { SampleCsv.parseLine(it) }.toList()
            if (parsed.isNotEmpty() && parsed.minOf { it.t } <= from) {
                return parsed.filter { it.t >= from }.distinctBy { it.t to it.event }.sortedBy { it.t }
            }
            want *= 2
        }
        SampleCsv.parse(file.readText()).filter { it.t >= from }
    }

    /** 오래된 줄을 지우고 남은 줄 수를 돌려준다 (임시 파일에 다시 써서 교체 — 쓰다 꺼져도 원본은 남게) */
    fun pruneOlderThan(days: Int = KEEP_DAYS, now: Long = System.currentTimeMillis()): Int = synchronized(LOCK) {
        if (!file.exists()) return 0
        val keepFrom = now - days * 86_400_000L
        val kept = SampleCsv.parse(file.readText()).filter { it.t >= keepFrom }
        val tmp = File(dir, "log.csv.tmp")
        tmp.writeText(buildString {
            append(SampleCsv.MAGIC).append('\n').append(SampleCsv.HEADER).append('\n')
            kept.forEach { append(SampleCsv.line(it)).append('\n') }
        })
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        kept.size
    }

    /** 하루에 한 번만 잘라낸다 (15분 작업마다 파일을 다시 쓰지 않게) */
    fun pruneIfDue(now: Long = System.currentTimeMillis()) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_PRUNED_AT, 0L)
        // 시계가 뒤로 가 미래가 된 '마지막으로 자른 때'는 기다리지 않는다
        if (now - last in 0 until 86_400_000L) return
        runCatching { pruneOlderThan(KEEP_DAYS, now) }
        prefs.edit().putLong(KEY_PRUNED_AT, now).apply()
    }

    /**
     * 서비스센터에 보여 줄 요약 글 — PC 없이도 꺼내 볼 수 있게 같은 폴더에. 통째로 바꿔 쓴다: 쓰는 도중에 읽는 쪽(PC 수집·시험)이
     * 빈 파일·반쪽 글을 보지 않게
     */
    fun writeSummary(text: String): File = synchronized(LOCK) {
        dir.mkdirs()
        val tmp = File(dir, summaryFile.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(summaryFile)) { summaryFile.writeText(text); tmp.delete() }
        summaryFile
    }

    companion object {
        const val KEEP_DAYS = 30
        const val PREFS = "battery"
        private const val KEY_PRUNED_AT = "pruned_at"
        private val LOCK = Any()
    }
}
