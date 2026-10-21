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
}

/** 바뀐 것 하나 — [t] 는 워치가 알아챈 때(15분 표본) */
data class Change(val t: Long, val kind: ChangeKind, val subject: String, val from: String? = null, val to: String? = null) {
    /** 켜졌는가 (설정·지킴이 설정) */
    val turnedOn: Boolean get() = to != null && to != "0" && to != "false" && to != "-"
}

/**
 * 표본 note 의 '바뀐 것' 표시. 형식 `chg:` + 항목('|' 로 구분), 항목 = 종류 `=` 값:
 * `fw=이전>이후` · `app=패키지@버전` · `face=이전>이후` · `set=id:이전>이후` · `cfg=id:이전>이후` · `apps=수`.
 * 값은 영문·숫자·`._+-` 만 남기고 [MAX_VALUE] 자로 자른다 — CSV(쉼표)·note 구분 글자가 섞이지 않게.
 * 워치 CSV 는 PC 툴킷도 읽으므로 양쪽이 같은 표시를 본다(예전 툴킷은 note 를 무시).
 */
object ChangeNote {
    const val PREFIX = "chg:"
    const val SEP = "|"
    const val MAX_VALUE = 64

    data class Item(val kind: ChangeKind, val subject: String, val from: String? = null, val to: String? = null)

    fun encode(items: List<Item>): String {
        val parts = items.mapNotNull { i ->
            val body = when (i.kind) {
                ChangeKind.FIRMWARE -> "${clean(i.from)}>${clean(i.to)}"
                ChangeKind.APP -> "${clean(i.subject)}@${clean(i.to?.trim()?.substringBefore(' '))}"
                ChangeKind.FACE -> (i.from?.let { "${clean(it)}>" } ?: "") + clean(i.to)
                ChangeKind.SETTING, ChangeKind.CONFIG -> "${clean(i.subject)}:${clean(i.from)}>${clean(i.to)}"
                ChangeKind.MORE_APPS -> clean(i.to)
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
                else -> null
            }
        }
    }

    /** 표본들의 바뀐 것, 시간순 */
    fun all(samples: List<BatterySample>): List<Change> =
        samples.filter { it.note.startsWith(PREFIX) }.sortedBy { it.t }.flatMap { decode(it.t, it.note) }

    private fun arrow(v: String): Pair<String, String>? = if ('>' in v) v.substringBefore('>') to v.substringAfter('>') else null

    private fun clean(s: String?): String =
        s.orEmpty().filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._+-" }.take(MAX_VALUE)
}

/**
 * 꺾인 날 무렵 바뀐 것 고르기와 그 글. 늘었으면: 소프트웨어 → 켠 설정 → Play 서비스·Samsung Health·시계 화면 → 새 앱·지킴이 끔 → 다른 앱.
 * 줄었으면: 끈 설정·지킴이 켬 → 시계 화면·지운 앱 → 소프트웨어·Play 서비스 → 다른 앱.
 */
object ChangeFinder {
    const val GMS = "com.google.android.gms"
    const val SHEALTH = "com.samsung.android.wear.shealth"
    private val HEAVY = setOf(GMS, SHEALTH)

    /** 이름을 모를 때 쓰는 잘 알려진 패키지 */
    val KNOWN = mapOf(
        GMS to "Google Play 서비스",
        SHEALTH to "Samsung Health",
        "com.android.vending" to "Play 스토어",
        "com.google.android.wearable.app" to "Wear OS",
        "com.samsung.android.wearable.sysui" to "시스템 UI",
    )
    val SETTING_NAMES = mapOf(
        "aod" to "AOD", "tilt" to "손목 올려 켜기", "touch" to "화면 터치로 켜기", "hotword" to "'Hey Google'", "location" to "위치",
    )
    val CONFIG_NAMES = mapOf(
        "night_saver" to "밤 절전 모드", "night_aod" to "밤에 AOD 끄기", "night_wake" to "밤에 손목·터치 켜기 끄기",
        "night_hotword" to "밤에 'Hey Google' 끄기", "night_location" to "밤에 위치 끄기", "night_sync" to "밤에 동기화 끄기",
        "abnormal" to "비정상 소모면 바로 절전", "reminder" to "잠자기 전 충전 알림",
    )

    /** 꺾이기 전 마지막 날 0시 ~ 꺾인 뒤 첫날 끝 사이의 바뀐 것, 가능성 높은 순 (같으면 늦은 것 먼저) */
    fun around(shift: Shift, changes: List<Change>, zone: ZoneId): List<Change> {
        val from = shift.lastBefore.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = shift.firstAfter.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return changes.filter { it.t in from until to }.sortedWith(compareBy<Change>({ rank(it, shift.worse) }, { -it.t }))
    }

    private fun rank(c: Change, worse: Boolean): Int = if (worse) when (c.kind) {
        ChangeKind.FIRMWARE -> 0
        ChangeKind.SETTING -> if (c.turnedOn) 1 else 8
        ChangeKind.FACE -> 2
        ChangeKind.APP -> when {
            c.to == "-" -> 7
            c.subject in HEAVY -> 2
            c.to?.startsWith("+") == true -> 3
            else -> 4
        }
        ChangeKind.CONFIG -> if (c.turnedOn) 8 else 3
        ChangeKind.MORE_APPS -> 5
    } else when (c.kind) {
        ChangeKind.SETTING -> if (c.turnedOn) 8 else 0
        ChangeKind.CONFIG -> if (c.turnedOn) 0 else 8
        ChangeKind.FACE -> 1
        ChangeKind.APP -> when {
            c.to == "-" -> 1
            c.subject in HEAVY -> 2
            c.to?.startsWith("+") == true -> 6
            else -> 4
        }
        ChangeKind.FIRMWARE -> 2
        ChangeKind.MORE_APPS -> 5
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
    }

    fun day(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"

    fun day(t: Long, zone: ZoneId): String = day(Instant.ofEpochMilli(t).atZone(zone).toLocalDate())

    /** 화면 제목: "10/18부터 대기 소모가 늘었습니다" */
    fun title(shift: Shift): String = "${day(shift.firstAfter)}부터 대기 소모가 ${if (shift.worse) "늘었습니다" else "줄었습니다"}"

    /** "시간당 1.0% → 2.4%" */
    fun numbers(shift: Shift): String = "시간당 ${pct(shift.before)}% → ${pct(shift.after)}%"

    /** "10/18부터 대기 소모가 시간당 1.0% → 2.4%로 늘었습니다" */
    fun headline(shift: Shift): String =
        "${day(shift.firstAfter)}부터 대기 소모가 시간당 ${pct(shift.before)}% → ${pct(shift.after)}%로 ${if (shift.worse) "늘었습니다" else "줄었습니다"}"

    /** 늘어난 꺾임의 할 일 (제목, 설명) — [top] = 가장 그럴듯한 바뀐 것 (없으면 null) */
    fun advice(shift: Shift, top: Change?, names: (String) -> String?): Pair<String, String> {
        val md = day(shift.firstAfter)
        val none = "${md}부터 더 닳음" to
            "그 무렵 이 앱이 아는 변화(소프트웨어·앱·시계 화면·주요 설정)는 없습니다 — 새로 쓴 기능(운동·수면 측정)·추위, 앱별 소모 1위가 Play 서비스인지 보세요"
        val c = top ?: return none
        return when (c.kind) {
            ChangeKind.FIRMWARE -> "$md 소프트웨어 업데이트 뒤 더 닳음" to
                "업데이트 뒤 며칠(삼성 안내: 길면 1주)은 새로 적응하느라 더 닳을 수 있습니다. 1주가 지나도 그대로면 Play 서비스 캐시 지우기 → 강제 재시작(홈+뒤로 7초)"
            ChangeKind.APP -> when {
                c.to == "-" -> none
                c.subject == GMS -> "$md Play 서비스 업데이트 뒤 더 닳음" to
                    "설정 > 애플리케이션 > Google Play 서비스 > 저장공간 > 캐시 지우기 → 강제 재시작 (2026년에 되풀이된 문제의 알려진 해결)"
                else -> "$md ${appName(c.subject, names)} ${if (c.to?.startsWith("+") == true) "설치" else "업데이트"} 뒤 더 닳음" to
                    "그 앱의 동기화·알림·측정 설정을 보거나, 하루 지워 두고 비교하세요"
            }
            ChangeKind.FACE -> "$md 시계 화면을 바꾼 뒤 더 닳음" to
                "삼성 기본 시계 화면으로 하루 써 보고 비교하세요 — 움직이는 화면·초침·잦은 날씨 새로 고침이 전기를 씁니다"
            ChangeKind.SETTING -> if (!c.turnedOn) none else when (c.subject) {
                "aod" -> "$md AOD 를 켠 뒤 더 닳음" to "AOD 는 화면을 늘 켜 둡니다 — 필요 없으면 끄고, 쓰더라도 밤엔 지킴이 '밤에 AOD 끄기'를 켜 두세요"
                "tilt", "touch" -> "$md ${SETTING_NAMES[c.subject]}를 켠 뒤 더 닳음" to
                    "화면이 자주 켜지면 화면 몫이 커집니다 — 끄거나, 밤엔 지킴이 '밤에 손목·터치 켜기 끄기'"
                "hotword" -> "$md 'Hey Google'을 켠 뒤 더 닳음" to "늘 듣고 있느라 전기를 씁니다 — 쓰지 않으면 끄세요"
                "location" -> "$md 위치를 켠 뒤 더 닳음" to "위치를 쓰는 앱이 있으면 늡니다 — 앱별 소모에서 GPS 를 쓰는 앱을 보세요"
                else -> "$md ${SETTING_NAMES[c.subject] ?: c.subject} 켠 뒤 더 닳음" to "꺼 보고 비교하세요"
            }
            ChangeKind.CONFIG -> if (c.turnedOn) none else
                "$md 지킴이 '${CONFIG_NAMES[c.subject] ?: c.subject}'를 끈 뒤 더 닳음" to "다시 켜 보세요"
            ChangeKind.MORE_APPS -> "$md 앱 여럿 업데이트 뒤 더 닳음" to "앱별 소모에서 1위 앱을 보세요 — Play 서비스면 캐시 지우기 → 강제 재시작"
        }
    }

    private fun pct(v: Double) = String.format(Locale.US, "%.1f", v)
}
