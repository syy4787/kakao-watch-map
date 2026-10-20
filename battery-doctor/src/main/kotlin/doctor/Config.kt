package doctor

import java.io.File
import java.util.Properties

/** `data/config.properties` — 마지막 연결 주소, adb 경로(선택), 구매일 */
class Config(val dir: File) {
    private val file = File(dir, "config.properties")
    private val props = Properties()

    init {
        if (file.isFile) {
            try { file.reader(Charsets.UTF_8).use { props.load(it) } } catch (_: Exception) {}
        }
    }

    var lastAddress: String?
        get() = props.getProperty("lastAddress")?.takeIf { it.isNotBlank() }
        set(v) { put("lastAddress", v) }

    var adbPath: String?
        get() = props.getProperty("adbPath")?.takeIf { it.isNotBlank() }
        set(v) { put("adbPath", v) }

    /** yyyy-MM-dd */
    var purchaseDate: String?
        get() = props.getProperty("purchaseDate")?.takeIf { it.isNotBlank() }
        set(v) { put("purchaseDate", v) }

    private fun put(key: String, v: String?) {
        if (v == null) props.remove(key) else props.setProperty(key, v)
    }

    fun save() {
        dir.mkdirs()
        file.writer(Charsets.UTF_8).use { props.store(it, "battery-doctor") }
    }
}
