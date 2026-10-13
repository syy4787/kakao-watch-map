package com.mywatch.kakaomap.model

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 시간표대로 다니는 장거리 탈 것 (카카오맵 결과의 열차 · 고속버스 · 시외버스) */
enum class IntercityKind(val label: String) {
    TRAIN("열차"), EXPRESS("고속버스"), INTERCITY("시외버스");
}

/**
 * 시간표의 한 편 (기차 한 대 · 버스 한 대) — 공공데이터 TAGO 열차·고속버스·시외버스 정보.
 * [number] = 열차 번호(앞의 0은 뗌, 버스는 빈 값), [grade] = KTX·ITX-새마을·무궁화호 / 우등·프리미엄·고속·일반 …
 */
data class ScheduledRun(
    val kind: IntercityKind,
    val number: String,
    val grade: String,
    val depName: String,
    val arrName: String,
    val depAtMs: Long,
    val arrAtMs: Long,
    /** 어른 운임 (원) */
    val fare: Int?,
    /** 오늘 시간표에 빠져 있어 다음 주 같은 요일 시간표로 채운 편 */
    val weekly: Boolean = false,
)

/** TAGO 의 역·터미널. 좌표가 없어서 카카오 장소와 이름(+시도)으로 맞춘다. [cityName] = 시외버스 터미널의 시도 */
data class TagoNode(val id: String, val name: String, val cityName: String = "")

/** TAGO 시간표·역·터미널 응답 → 모델 (응답 모양은 2026-10 실제 응답 기준) */
object ScheduleParsers {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val PAREN = Regex("""\s*\(.*?\)""")

    /**
     * "20261013052800"(열차, 문자열) · 202610132400(고속, 숫자 12자리 — 24시는 다음 날 0시) · 20261013071200(시외, 숫자 14자리)
     * → 한국 시각 epoch ms. 모양이 다르면 null
     */
    fun time(raw: String): Long? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // 숫자가 지수 표기(2.0261014021E13)나 소수(….0)로 올 수도 있다
        if (s.any { it == '.' || it == 'E' || it == 'e' }) {
            s = runCatching { BigDecimal(s).toBigIntegerExact().toString() }.getOrNull() ?: return null
        }
        if ((s.length != 12 && s.length != 14) || !s.all { it.isDigit() }) return null
        val hour = s.substring(8, 10).toInt()
        val minute = s.substring(10, 12).toInt()
        val second = if (s.length == 14) s.substring(12, 14).toInt() else 0
        if (hour > 30 || minute > 59 || second > 59) return null
        val date = runCatching {
            LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt())
        }.getOrNull() ?: return null
        // '24시 00분'(고속버스의 자정 출발)도 그대로 더해 다음 날 0시가 되게
        return date.atStartOfDay(SEOUL).plusHours(hour.toLong()).plusMinutes(minute.toLong()).plusSeconds(second.toLong())
            .toInstant().toEpochMilli()
    }

    /** 열차 (출발 시각 순). 열차 번호의 앞 0은 떼고, 등급의 "(A-type)" 같은 꼬리는 뗀다 */
    fun trains(json: JSONObject): List<ScheduledRun> = TagoParsers.items(json).mapNotNull { o ->
        val dep = time(o.text("depplandtime")) ?: return@mapNotNull null
        val arr = time(o.text("arrplandtime")) ?: return@mapNotNull null
        val no = o.text("trainno")
        ScheduledRun(
            kind = IntercityKind.TRAIN,
            number = no.trimStart('0').ifEmpty { no },
            grade = o.text("traingradename").replace(PAREN, "").trim(),
            depName = o.text("depplacename"),
            arrName = o.text("arrplacename"),
            depAtMs = dep,
            arrAtMs = arr,
            fare = fare(o.text("adultcharge")),
        )
    }.filter { it.arrAtMs > it.depAtMs }.sortedBy { it.depAtMs }

    /** 고속·시외버스 (출발 시각 순 — 시외버스는 순서가 섞여 온다) */
    fun buses(json: JSONObject, kind: IntercityKind): List<ScheduledRun> = TagoParsers.items(json).mapNotNull { o ->
        val dep = time(o.text("depPlandTime")) ?: return@mapNotNull null
        val arr = time(o.text("arrPlandTime")) ?: return@mapNotNull null
        ScheduledRun(
            kind = kind,
            number = "",
            grade = o.text("gradeNm"),
            depName = o.text("depPlaceNm"),
            arrName = o.text("arrPlaceNm"),
            depAtMs = dep,
            arrAtMs = arr,
            fare = fare(o.text("charge")),
        )
    }.filter { it.arrAtMs > it.depAtMs }.sortedBy { it.depAtMs }

    /** 도시별 기차역 목록 (nodeid · nodename) */
    fun trainStations(json: JSONObject): List<TagoNode> = TagoParsers.items(json).mapNotNull { o ->
        val id = o.text("nodeid").ifEmpty { null } ?: return@mapNotNull null
        TagoNode(id, o.text("nodename"))
    }

    /** 고속(terminalId · terminalNm) · 시외(+ cityName) 터미널 목록 */
    fun terminals(json: JSONObject): List<TagoNode> = TagoParsers.items(json).mapNotNull { o ->
        val id = o.text("terminalId").ifEmpty { null } ?: return@mapNotNull null
        TagoNode(id, o.text("terminalNm"), o.text("cityName"))
    }

    private fun fare(raw: String): Int? = raw.toDoubleOrNull()?.roundToInt()?.takeIf { it > 0 }

    /** 문자열·숫자 어느 쪽으로 와도 글자로 (없거나 null 이면 빈 값) */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else opt(key).toString().trim()
}

/** 시도 이름 맞추기: 카카오 주소("대전 동구 …", "세종특별자치시 …") · TAGO 시도("충청북도", "서울시" …) → "대전"·"충북" … */
object Regions {
    /** (이름 앞부분들 → 시도) — '경기'와 '경상', '충북'과 '충청북도'처럼 쓰는 방식이 여러 가지 */
    private val PREFIXES = listOf(
        listOf("서울") to "서울", listOf("부산") to "부산", listOf("대구") to "대구", listOf("인천") to "인천",
        listOf("광주") to "광주", listOf("대전") to "대전", listOf("울산") to "울산", listOf("세종") to "세종",
        listOf("경기") to "경기", listOf("강원") to "강원",
        listOf("충북", "충청북") to "충북", listOf("충남", "충청남") to "충남",
        listOf("전북", "전라북") to "전북", listOf("전남", "전라남") to "전남",
        listOf("경북", "경상북") to "경북", listOf("경남", "경상남") to "경남", listOf("제주") to "제주",
    )

    /** TAGO 열차정보의 도시 코드 */
    private val TRAIN_CITY = mapOf(
        "서울" to 11, "세종" to 12, "부산" to 21, "대구" to 22, "인천" to 23, "광주" to 24, "대전" to 25, "울산" to 26,
        "경기" to 31, "강원" to 32, "충북" to 33, "충남" to 34, "전북" to 35, "전남" to 36, "경북" to 37, "경남" to 38,
    )

    /**
     * 이름에 든 시도. 합쳐진 시도(2026 카카오 주소의 '전남광주통합특별시')는 둘 다 → "광주+전남".
     * 없으면 null
     */
    private fun canonical(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val found = PREFIXES.filter { (names, _) -> names.any { t.contains(it) } }.map { it.second }.distinct()
        return if (found.isEmpty()) null else found.sorted().joinToString("+")
    }

    fun ofAddress(address: String): String? = canonical(address.trim().split(' ').first())

    fun ofTagoCity(cityName: String): String? = canonical(cityName)

    /** TAGO 열차정보의 도시 코드 (제주 등 기차가 없는 곳은 null) */
    fun trainCityCode(region: String): Int? = TRAIN_CITY[region]

    /** 카카오 주소의 시도와 TAGO 터미널의 시도가 같은지 (TAGO 쪽 시도를 모르면 같다고 본다, 합쳐진 시도는 어느 한쪽이면) */
    fun matches(region: String, tagoCityName: String): Boolean {
        val other = ofTagoCity(tagoCityName) ?: return true
        val mine = region.split('+').toSet()
        return other.split('+').any { it in mine }
    }

    /** 이 시도의 TAGO 열차정보 도시 코드들 (합쳐진 시도는 둘 다 — 광주 24 · 전남 36) */
    fun trainCityCodes(region: String): List<Int> = region.split('+').mapNotNull { TRAIN_CITY[it] }.sorted()
}

/** 카카오 장소 이름 ↔ TAGO 역·터미널 이름 */
object TagoMatching {
    private val PAREN = Regex("""\(.*?\)""")
    private val PAREN_INNER = Regex("""\((.*?)\)""")
    /** 가벼운 키: 이 꼬리 하나만 뗀다 ("대전복합터미널" → "대전복합") */
    private val LIGHT_TAILS = listOf("버스터미널", "버스정류소", "버스정류장", "터미널", "정류소", "정류장")
    /** 무거운 키: 이 말들을 끝에서 되풀이해 뗀다 ("세종고속시외버스터미널" → "세종") */
    private val HEAVY_TAILS = listOf("터미널", "정류소", "정류장", "매표소", "환승센터", "버스", "고속", "시외", "종합", "복합", "공용", "여객")
    /** 카카오와 TAGO 고속버스 목록의 이름이 다른 큰 터미널 (무거운 키 기준) */
    private val ALIASES = mapOf("부산서부" to "부산사상", "서울" to "서울경부", "유스퀘어광주" to "광주")

    /** "대전역" → "대전", "울산역(통도사)" → "울산", "광명역 KTX" → "광명" */
    fun stationKey(name: String): String =
        name.replace(PAREN, " ").trim().split(Regex("""\s+""")).first().removeSuffix("역").trim()

    fun station(kakaoName: String, stations: List<TagoNode>): TagoNode? {
        val key = stationKey(kakaoName)
        if (key.isEmpty()) return null
        return stations.firstOrNull { stationKey(it.name) == key }
    }

    /**
     * 터미널 이름 키. [heavy] = false 면 '터미널·정류소' 같은 꼬리만, true 면 '고속·시외·복합·종합' 까지 뗀다.
     * 괄호 안 말은 붙인 것도 후보로: "서울고속버스터미널(경부)" → 가벼운 키 [서울고속, 서울고속경부], 무거운 키 [서울, 서울경부]
     */
    fun terminalKeys(name: String, heavy: Boolean): List<String> {
        val inner = PAREN_INNER.find(name)?.groupValues?.get(1)?.trim().orEmpty()
        val base = name.replace(PAREN, " ").trim().split(Regex("""\s+""")).first()
        val key = if (heavy) heavyStrip(base) else lightStrip(base)
        val keys = mutableListOf(key)
        if (inner.isNotEmpty()) keys += key + inner
        return keys.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 카카오 터미널 → TAGO 터미널 (가장 그럴듯한 것부터). 가벼운 키로 먼저 맞추고, 없으면 무거운 키로.
     * [region] 이 있으면 시도가 다른 TAGO 터미널(전남 '대전' 등)은 뺀다.
     */
    fun terminals(kakaoName: String, region: String?, nodes: List<TagoNode>): List<TagoNode> {
        val usable = nodes.filter { n -> region == null || Regions.matches(region, n.cityName) }
        val light = terminalKeys(kakaoName, heavy = false).toSet()
        usable.filter { n -> terminalKeys(n.name, heavy = false).any { it in light } }
            .sortedBy { it.id }
            .let { if (it.isNotEmpty()) return it }
        val heavyKeys = terminalKeys(kakaoName, heavy = true).let { keys -> keys + keys.mapNotNull { ALIASES[it] } }.toSet()
        return usable.filter { n -> terminalKeys(n.name, heavy = true).any { it in heavyKeys } }.sortedBy { it.id }
    }

    private fun lightStrip(s: String): String {
        val tail = LIGHT_TAILS.firstOrNull { s.endsWith(it) && s.length >= it.length + 2 } ?: return s
        return s.removeSuffix(tail)
    }

    private fun heavyStrip(s: String): String {
        var x = s
        while (true) {
            val tail = HEAVY_TAILS.firstOrNull { x.endsWith(it) && x.length >= it.length + 2 } ?: return x
            x = x.removeSuffix(tail)
        }
    }
}
