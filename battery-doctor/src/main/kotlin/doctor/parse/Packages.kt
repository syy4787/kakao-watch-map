package doctor.parse

data class PackageInfo(val name: String, val uid: Int?, val enabled: Boolean, val thirdParty: Boolean)

/** `pm list packages -U / -e / -d / -3` 네 목록 합치기 */
object Packages {
    private val withUid = Regex("^package:(\\S+)\\s+uid:(\\d+)")
    private val plain = Regex("^package:(\\S+)")

    fun parse(u: String?, e: String?, d: String?, third: String?): List<PackageInfo> {
        val uids = LinkedHashMap<String, Int?>()
        u?.lines()?.forEach { line ->
            val t = line.trim()
            withUid.find(t)?.let { uids[it.groupValues[1]] = it.groupValues[2].toIntOrNull(); return@forEach }
            plain.find(t)?.let { uids.putIfAbsent(it.groupValues[1], null) }
        }
        val enabledSet = names(e)
        val disabledSet = names(d)
        val thirdSet = names(third)
        // -U 가 없으면 나머지 목록의 합집합으로라도 만든다
        for (n in enabledSet + disabledSet + thirdSet) uids.putIfAbsent(n, null)
        return uids.map { (name, uid) ->
            val enabled = when {
                name in disabledSet -> false
                enabledSet.isNotEmpty() -> name in enabledSet
                else -> true
            }
            PackageInfo(name, uid, enabled, name in thirdSet)
        }
    }

    fun byUid(list: List<PackageInfo>, uid: Int): List<PackageInfo> = list.filter { it.uid == uid }

    private fun names(text: String?): Set<String> {
        if (text == null) return emptySet()
        val out = LinkedHashSet<String>()
        text.lines().forEach { line -> plain.find(line.trim())?.let { out += it.groupValues[1] } }
        return out
    }
}
