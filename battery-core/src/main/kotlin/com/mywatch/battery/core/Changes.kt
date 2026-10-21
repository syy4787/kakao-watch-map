package com.mywatch.battery.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 바뀐 것의 종류 — [code] 는 표본 note 에 쓰는 이름 */
enum class ChangeKind(val code: String) {
    /** 워치 소프트웨어(펌웨어) — [Change.from] → [Change.to] */
    FIRMWARE("fw"),
    /** 앱 업데이트·설치·지움 — [Change.subject] = 패키지, [Change.to] = 버전("+버전" = 새로 깖, "-" = 지움) */
    APP("app"),
    /** 시계 화면 — [Change.to] = 새 시계 화면 패키지 */
    FACE("face"),
    /** 사용자가 바꾼 설정 — [Change.subject] = aod·tilt·touch·hotword·location, 값 "0" = 꺼짐 */
    SETTING("set"),
    /** 지킴이 설정 — [Change.subject] = night_aod 등, "1" = 켬 */
    CONFIG("cfg"),
    /** 한 번에 바뀐 앱이 많아 다 적지 못한 수 ([Change.to]) */
    MORE_APPS("apps"),
    /** 워치가 바뀐 것을 적기 시작한 때 (이 앱이 처음 본 값을 기억한 때) — 그 앞의 변화는 모른다 */
    TRACKING("on"),
}

/** 바뀐 것 하나 — [t] 는 워치가 알아챈 때(15분 표본) */
data class Change(val t: Long, val kind: ChangeKind, val subject: String, val from: String? = null, val to: String? = null) {
    /** 켜졌는가 (설정·지킴이 설정) */
    val turnedOn: Boolean get() = to != null && to != "0" && to != "false" && to != "-"
}

/**
 * 표본 note 의 '바뀐 것' 표시. 형식 `chg:` + 항목('|' 로 구분), 항목 = 종류 `=` 값:
 * `fw=이전>이후` · `app=패키지@버전` · `face=이전>이후` · `set=id:이전>이후` · `cfg=id:이전>이후` · `apps=수` · `on=1`(적기 시작).
 * 값은 영문·숫자·`._+-` 만 남기고 [MAX_VALUE] 자로 자른다 — CSV(쉼표)·note 구분 글자가 섞이지 않게.
 * 워치 CSV 는 PC 툴킷도 읽으므로 양쪽이 같은 표시를 본다(예전 툴킷은 note 를 무시).
 */
object ChangeNote {
    const val PREFIX = "chg:"
    const val SEP = "|"
    const val MAX_VALUE = 64
    /** 이 안에 되돌린 설정·시계 화면 변화는 잠깐 바뀐 것(물놀이 잠금·운동·낮잠 모드 등)으로 보고 뺀다 */
    const val REVERTED_WITHIN_MS = 12 * 3_600_000L

    data class Item(val kind: ChangeKind, val subject: String, val from: String? = null, val to: String? = null)

    fun encode(items: List<Item>): String {
        val parts = items.mapNotNull { i ->
            val body = when (i.kind) {
                ChangeKind.FIRMWARE -> "${clean(i.from)}>${clean(i.to)}"
                ChangeKind.APP -> "${clean(i.subject)}@${clean(i.to?.trim()?.substringBefore(' '))}"
                ChangeKind.FACE -> (i.from?.let { "${clean(it)}>" } ?: "") + clean(i.to)
                ChangeKind.SETTING, ChangeKind.CONFIG -> "${clean(i.subject)}:${clean(i.from)}>${clean(i.to)}"
                ChangeKind.MORE_APPS -> clean(i.to)
                ChangeKind.TRACKING -> "1"
            }
            if (body.isEmpty()) null else "${i.kind.code}=$body"
        }
        return if (parts.isEmpty()) "" else PREFIX + parts.joinToString(SEP)
    }

    fun decode(t: Long, note: String): List<Change> {
        if (!note.startsWith(PREFIX)) return emptyList()
        return note.removePrefix(PREFIX).split(SEP).mapNotNull { part ->
            val code = part.substringBefore('=', "")
            val v = part.substringAfter('=', "")
            if (code.isEmpty() || v.isEmpty()) return@mapNotNull null
            when (code) {
                ChangeKind.FIRMWARE.code -> arrow(v)?.takeIf { it.second.isNotEmpty() }
                    ?.let { (f, to) -> Change(t, ChangeKind.FIRMWARE, "fw", f.ifEmpty { null }, to) }
                ChangeKind.APP.code -> v.substringBefore('@').takeIf { it.isNotEmpty() }
                    ?.let { pkg -> Change(t, ChangeKind.APP, pkg, null, if ('@' in v) v.substringAfter('@') else null) }
                ChangeKind.FACE.code -> (arrow(v) ?: ("" to v)).takeIf { it.second.isNotEmpty() }
                    ?.let { (f, to) -> Change(t, ChangeKind.FACE, "face", f.ifEmpty { null }, to) }
                ChangeKind.SETTING.code, ChangeKind.CONFIG.code -> {
                    val id = v.substringBefore(':', "")
                    val ft = arrow(v.substringAfter(':', ""))
                    if (id.isEmpty() || ft == null || ft.second.isEmpty()) null
                    else Change(t, if (code == ChangeKind.SETTING.code) ChangeKind.SETTING else ChangeKind.CONFIG, id, ft.first.ifEmpty { null }, ft.second)
                }
                ChangeKind.MORE_APPS.code -> Change(t, ChangeKind.MORE_APPS, "apps", null, v)
                ChangeKind.TRACKING.code -> Change(t, ChangeKind.TRACKING, "on", null, v)
                else -> null
            }
        }
    }

    /** 표본들의 바뀐 것, 시간순 ('적기 시작' 표시는 빼고 — [trackedSince]) */
    fun all(samples: List<BatterySample>): List<Change> = raw(samples).filter { it.kind != ChangeKind.TRACKING }

    /** 워치가 바뀐 것을 적기 시작한 때 (표시가 없으면 null — 이 기능 전의 기록) */
    fun trackedSince(samples: List<BatterySample>): Long? = raw(samples).firstOrNull { it.kind == ChangeKind.TRACKING }?.t

    private fun raw(samples: List<BatterySample>): List<Change> =
        samples.filter { it.note.startsWith(PREFIX) }.sortedBy { it.t }.flatMap { decode(it.t, it.note) }

    /**
     * 잠깐 바뀌었다 [REVERTED_WITHIN_MS] 안에 되돌아간 설정·지킴이 설정·시계 화면은 둘 다 뺀다 — 삼성 모드(물놀이 잠금·운동·낮잠 등)가
     * 잠시 바꾼 것이 매일 'AOD 켬/끔' 짝으로 남아 며칠 단위 꺾임의 용의자가 되지 않게
     */
    fun settled(changes: List<Change>, withinMs: Long = REVERTED_WITHIN_MS): List<Change> {
        val sorted = changes.sortedBy { it.t }
        val drop = HashSet<Int>()
        val revertible = setOf(ChangeKind.SETTING, ChangeKind.CONFIG, ChangeKind.FACE)
        // 대상마다, 이웃한 두 변화 중 되돌림인 짝을 가장 짧은 것부터 뺀다 — 아침에 켜고 저녁에 잠깐 껐다 다시 켰으면
        // 저녁의 끔·켬이 짝이고 아침에 켠 것이 남는다 (앞에서부터 짝지으면 '켬'이 저녁으로 밀린다)
        sorted.indices.filter { sorted[it].kind in revertible }.groupBy { sorted[it].kind to sorted[it].subject }.values.forEach { group ->
            val alive = group.toMutableList()
            while (true) {
                var best = -1
                var bestGap = Long.MAX_VALUE
                for (k in 0 until alive.size - 1) {
                    val a = sorted[alive[k]]
                    val b = sorted[alive[k + 1]]
                    val gap = b.t - a.t
                    if (gap <= withinMs && gap < bestGap && a.from != null && b.to == a.from) { best = k; bestGap = gap }
                }
                if (best < 0) break
                drop += alive[best]
                drop += alive[best + 1]
                alive.removeAt(best + 1)
                alive.removeAt(best)
            }
        }
        return sorted.filterIndexed { i, _ -> i !in drop }
    }

    private fun arrow(v: String): Pair<String, String>? = if ('>' in v) v.substringBefore('>') to v.substringAfter('>') else null

    private fun clean(s: String?): String =
        s.orEmpty().filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._+-" }.take(MAX_VALUE)
}

/**
 * 꺾인 날 무렵 바뀐 것 고르기와 그 글. 꺾인 쪽을 설명할 수 있는 변화만 앞으로:
 * 늘었으면 소프트웨어 → 켠 설정 → Play 서비스·Samsung Health·시계 화면 → 새 앱·지킴이 절약 끔 → 다른 앱 업데이트,
 * 줄었으면 끈 설정·지킴이 절약 켬 → 시계 화면·지운 앱 → 소프트웨어·Play 서비스 → 다른 앱 업데이트.
 * 설명할 수 없는 것(늘었는데 끈 설정·지운 앱 등)은 뒤로 — 할 일은 설명할 수 있는 것 중 첫째로만 정한다.
 */
object ChangeFinder {
    const val GMS = "com.google.android.gms"
    const val SHEALTH = "com.samsung.android.wear.shealth"
    private val HEAVY = setOf(GMS, SHEALTH)
    private const val NOT_IT = 8

    /** 이름을 모를 때 쓰는 잘 알려진 패키지 */
    val KNOWN = mapOf(
        GMS to "Google Play 서비스",
        SHEALTH to "Samsung Health",
        "com.android.vending" to "Play 스토어",
        "com.google.android.wearable.app" to "Wear OS",
        // 워치 배터리 사용량 화면·삼성 커뮤니티에 보이는 이름 (Play 스토어 이름 'One UI Watch Home')
        "com.samsung.android.wearable.sysui" to "One UI Watch 홈",
    )
    val SETTING_NAMES = mapOf(
        "aod" to "AOD", "tilt" to "손목 올려 켜기", "touch" to "화면 터치로 켜기", "hotword" to "'Hey Google'", "location" to "위치",
    )
    val CONFIG_NAMES = mapOf(
        "night_saver" to "밤 절전 모드", "night_aod" to "밤에 AOD 끄기", "night_wake" to "밤에 손목·터치 켜기 끄기",
        "night_hotword" to "밤에 'Hey Google' 끄기", "night_location" to "밤에 위치 끄기", "night_sync" to "밤에 동기화 끄기",
        "abnormal" to "비정상 소모면 바로 절전", "reminder" to "잠자기 전 충전 알림",
    )
    /** 대기 소모를 실제로 줄이는 지킴이 설정 (충전 알림·비정상 때만 절전은 평소 대기에 영향이 없다) */
    private val SAVING_CONFIGS = setOf("night_saver", "night_aod", "night_wake", "night_hotword", "night_location", "night_sync")

    /** 꺾이기 전 마지막 날 0시 ~ 꺾인 뒤 첫날 끝 사이의 바뀐 것, 가능성 높은 순 (같으면 늦은 것 먼저) */
    fun around(shift: Shift, changes: List<Change>, zone: ZoneId): List<Change> {
        val (from, to) = window(shift, zone)
        return changes.filter { it.t in from until to }.sortedWith(compareBy<Change>({ rank(it, shift.worse) }, { -it.t }))
    }

    /** 용의자를 찾는 때: 꺾이기 전 마지막 날 0시 ~ 꺾인 뒤 첫날 끝 */
    fun window(shift: Shift, zone: ZoneId): Pair<Long, Long> =
        shift.lastBefore.atStartOfDay(zone).toInstant().toEpochMilli() to shift.firstAfter.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** 이 변화가 꺾인 쪽(늘었나/줄었나)을 설명할 수 있나 */
    fun explains(c: Change, worse: Boolean): Boolean = rank(c, worse) < NOT_IT

    private fun rank(c: Change, worse: Boolean): Int = if (worse) when (c.kind) {
        ChangeKind.FIRMWARE -> 0
        ChangeKind.SETTING -> if (c.turnedOn) 1 else NOT_IT
        ChangeKind.FACE -> 2
        ChangeKind.APP -> when {
            c.to == "-" -> NOT_IT
            c.subject in HEAVY -> 2
            c.to?.startsWith("+") == true -> 3
            else -> 4
        }
        ChangeKind.CONFIG -> if (!c.turnedOn && c.subject in SAVING_CONFIGS) 3 else NOT_IT
        ChangeKind.MORE_APPS -> 5
        ChangeKind.TRACKING -> NOT_IT
    } else when (c.kind) {
        ChangeKind.SETTING -> if (c.turnedOn) NOT_IT else 0
        ChangeKind.CONFIG -> if (c.turnedOn && c.subject in SAVING_CONFIGS) 0 else NOT_IT
        ChangeKind.FACE -> 1
        ChangeKind.APP -> when {
            c.to == "-" -> 1
            c.subject in HEAVY -> 2
            c.to?.startsWith("+") == true -> NOT_IT
            else -> 4
        }
        ChangeKind.FIRMWARE -> 2
        ChangeKind.MORE_APPS -> 5
        ChangeKind.TRACKING -> NOT_IT
    }

    fun appName(pkg: String, names: (String) -> String?): String = names(pkg) ?: KNOWN[pkg] ?: pkg

    /** "워치 소프트웨어 업데이트 (L350…)" · "Google Play 서비스 업데이트 (26.40.31)" · "AOD 켬" · "지킴이: 밤에 AOD 끄기 켬" … */
    fun label(c: Change, names: (String) -> String?): String = when (c.kind) {
        ChangeKind.FIRMWARE -> "워치 소프트웨어 업데이트" + (c.to?.let { " ($it)" } ?: "")
        ChangeKind.APP -> {
            val n = appName(c.subject, names)
            when {
                c.to == "-" -> "$n 지움"
                c.to?.startsWith("+") == true -> "$n 설치"
                else -> "$n 업데이트" + (c.to?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
            }
        }
        ChangeKind.FACE -> "시계 화면 바꿈" + (c.to?.let { " (${appName(it, names)})" } ?: "")
        ChangeKind.SETTING -> "${SETTING_NAMES[c.subject] ?: c.subject} ${if (c.turnedOn) "켬" else "끔"}"
        ChangeKind.CONFIG -> "지킴이: ${CONFIG_NAMES[c.subject] ?: c.subject} ${if (c.turnedOn) "켬" else "끔"}"
        ChangeKind.MORE_APPS -> "앱 ${c.to}개 더 업데이트"
        ChangeKind.TRACKING -> "바뀐 것 적기 시작"
    }

    fun day(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"

    fun day(t: Long, zone: ZoneId): String = day(Instant.ofEpochMilli(t).atZone(zone).toLocalDate())

    /** "10/18부터" — 사이에 기록이 없는 날(충전기 위)이 끼면 "10/16~10/18 사이부터" (그 어느 날 바뀌었는지 모르므로) */
    fun since(shift: Shift): String {
        val first = shift.lastBefore.plusDays(1)
        return if (!first.isBefore(shift.firstAfter)) "${day(shift.firstAfter)}부터" else "${day(first)}~${day(shift.firstAfter)} 사이부터"
    }

    /** 화면 제목: "10/18부터 대기 소모가 늘었습니다" */
    fun title(shift: Shift): String = "${since(shift)} 대기 소모가 ${if (shift.worse) "늘었습니다" else "줄었습니다"}"

    /** "시간당 1.0% → 2.4%" */
    fun numbers(shift: Shift): String = "시간당 ${pct(shift.before)}% → ${pct(shift.after)}%"

    /** "10/18부터 대기 소모가 시간당 1.0% → 2.4%로 늘었습니다" */
    fun headline(shift: Shift): String =
        "${since(shift)} 대기 소모가 시간당 ${pct(shift.before)}% → ${pct(shift.after)}%로 ${if (shift.worse) "늘었습니다" else "줄었습니다"}"

    /**
     * 늘어난 꺾임의 할 일 (제목, 설명 — 워치 '지금 할 일' 줄에 들어가게 짧게). [suspects] = [around] 의 결과,
     * [known] = 그 무렵에 이미 바뀐 것을 적고 있었나 (아니면 '모름')
     */
    fun advice(shift: Shift, suspects: List<Change>, names: (String) -> String?, known: Boolean = true): Pair<String, String> {
        val head = "${since(shift)} 더 닳음"
        val c = suspects.firstOrNull { explains(it, shift.worse) } ?: return head to when {
            !known -> "그때는 적기 전이라 모릅니다 — 앱별 소모 1위(Play 서비스인지)를 보세요"
            suspects.isEmpty() -> "그 무렵 바뀐 것 없음 — 앱별 소모 1위(Play 서비스인지)·추위를 보세요"
            else -> "늘릴 만한 것은 없습니다 — 앱별 소모 1위(Play 서비스인지)를 보세요"
        }
        // 제목은 '지금 할 일' 줄(아이콘·번호 옆 두 줄)에 들어가게 날짜 없이 — 날짜는 '최근 며칠' 칸에
        return when (c.kind) {
            ChangeKind.FIRMWARE -> "소프트웨어 업데이트 뒤 더 닳음" to
                "길면 1주 적응(삼성) — 그 뒤엔 Play 서비스 캐시 지우기 → 강제 재시작"
            ChangeKind.APP -> when (c.subject) {
                GMS -> "Play 서비스 업데이트 뒤 더 닳음" to "Play 서비스 앱 정보 › 저장공간 › 캐시 삭제 → 강제 재시작"
                // 이름이 길면 줄에 들어가게 이름을 줄인다 (전체 이름은 '그 무렵 바뀐 것' 목록에)
                else -> TodoText.fitTitle(SHORT[c.subject] ?: appName(c.subject, names), " ${if (c.to?.startsWith("+") == true) "설치" else "업데이트"} 뒤 더 닳음") to
                    "그 앱의 동기화·알림·측정 설정을 보거나, 하루 지워 두고 비교"
            }
            ChangeKind.FACE -> "시계 화면을 바꾼 뒤 더 닳음" to "삼성 기본 시계 화면으로 하루 써 보고 비교"
            ChangeKind.SETTING -> when (c.subject) {
                "aod" -> "AOD를 켠 뒤 더 닳음" to "필요 없으면 끄고, 밤엔 지킴이 '밤에 AOD 끄기'"
                "tilt", "touch" -> "${obj(SETTING_NAMES.getValue(c.subject))} 켠 뒤 더 닳음" to "끄거나, 밤엔 지킴이 '밤에 손목·터치 켜기 끄기'"
                "hotword" -> "'Hey Google'을 켠 뒤 더 닳음" to "쓰지 않으면 끄기 — 늘 듣고 있느라 전기를 씀"
                "location" -> "위치를 켠 뒤 더 닳음" to "앱별 소모에서 GPS 쓰는 앱을 보세요"
                else -> "${obj(SETTING_NAMES[c.subject] ?: c.subject)} 켠 뒤 더 닳음" to "꺼 보고 비교"
            }
            ChangeKind.CONFIG -> {
                // 이름에 따옴표가 있으면('Hey Google') 겹치지 않게 그대로
                val n = (CONFIG_NAMES[c.subject] ?: c.subject).let { if ('\'' in it) it else "'$it'" }
                ("지킴이 ${obj(n)} 끈 뒤 더 닳음".takeIf { TodoText.fitsTitle(it) } ?: "지킴이 설정을 끈 뒤 더 닳음") to
                    "지킴이에서 ${obj(n)} 다시 켜 보세요"
            }
            ChangeKind.MORE_APPS -> "앱 여럿 업데이트 뒤 더 닳음" to "앱별 소모 1위 앱을 보세요 (Play 서비스면 캐시 지우기)"
            ChangeKind.TRACKING -> head to "앱별 소모 1위(Play 서비스인지)를 보세요"
        }
    }

    /** 할 일 제목에 쓰는 짧은 이름 */
    private val SHORT = mapOf(SHEALTH to "삼성 헬스")

    /**
     * 목적격 조사를 붙인다: 받침 있으면 '을', 없으면 '를' ("손목 올려 켜기를", "'잠자기 전 충전 알림'을").
     * 따옴표·괄호는 건너뛰고 마지막 글자로, 영문은 소리로 (l·m·n·-le 로 끝나면 '을')
     */
    fun obj(word: String): String {
        val core = word.trimEnd('\'', '"', ')', ' ', '’')
        val last = core.lastOrNull() ?: return word + "를"
        val batchim = when {
            last in '가'..'힣' -> (last - '가') % 28 != 0
            last.isDigit() -> last in "013678"   // 영·일·삼·육·칠·팔
            last.isLetter() -> core.lowercase().let { it.endsWith("le") || it.last() in "lmn" }
            else -> false
        }
        return word + if (batchim) "을" else "를"
    }

    private fun pct(v: Double) = String.format(Locale.US, "%.1f", v)
}
