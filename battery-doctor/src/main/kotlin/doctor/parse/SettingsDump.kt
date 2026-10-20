package doctor.parse

/** `settings list global|secure|system` → key=value 맵 (첫 '=' 에서만 나눈다) */
object SettingsDump {
    fun parse(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            if (key.isEmpty() || key.contains(' ')) continue
            out[key] = line.substring(i + 1).trim()
        }
        return out
    }
}

/** `dumpsys package com.google.android.gms` 에서 버전만 */
object GmsPackage {
    private val name = Regex("versionName=(.+)")
    private val code = Regex("versionCode=(\\d+)")

    fun versionName(text: String?): String? = text?.let { name.find(it)?.groupValues?.get(1)?.trim() }
    fun versionCode(text: String?): Long? = text?.let { code.find(it)?.groupValues?.get(1)?.toLongOrNull() }
}
