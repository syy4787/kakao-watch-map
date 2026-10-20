package com.mywatch.kakaomap.battery

import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.SampleCsv
import java.io.File

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
        if (now - last < 86_400_000L) return
        runCatching { pruneOlderThan(KEEP_DAYS, now) }
        prefs.edit().putLong(KEY_PRUNED_AT, now).apply()
    }

    /** 서비스센터에 보여 줄 요약 글 — PC 없이도 꺼내 볼 수 있게 같은 폴더에 */
    fun writeSummary(text: String): File = synchronized(LOCK) {
        dir.mkdirs()
        summaryFile.writeText(text)
        summaryFile
    }

    companion object {
        const val KEEP_DAYS = 30
        const val PREFS = "battery"
        private const val KEY_PRUNED_AT = "pruned_at"
        private val LOCK = Any()
    }
}
