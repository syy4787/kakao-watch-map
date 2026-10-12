package com.mywatch.kakaomap.model

import kotlin.math.abs

/** 구간 시작점에서 할 일 — 배너 화살표·진동 구분용 */
enum class Action {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN,
    CROSSWALK, STAIRS, OVERPASS, UNDERPASS, ELEVATOR, INDOOR;

    val isLeft: Boolean get() = this == SLIGHT_LEFT || this == LEFT || this == SHARP_LEFT
    val isRight: Boolean get() = this == SLIGHT_RIGHT || this == RIGHT || this == SHARP_RIGHT
}

/** 한 구간의 시작에서 할 일 + 그곳 이름(…앞에서), 가는 곳(…까지), 길 이름 */
data class Maneuver(
    val action: Action,
    val at: String? = null,
    val to: String? = null,
    val road: String? = null,
    val count: Int = 1,
)

/**
 * 카카오 도보·자전거 안내 문구 → 짧은 회전 안내.
 * 카카오 응답에는 회전 종류 코드가 없고 "역전약국 앞에서 …까지 오른쪽길로 67m 이동(대전로)" 같은 문장만 있으므로,
 * 문장 속 낱말(좌회전·오른쪽길로·횡단보도 …)을 먼저 보고, 낱말이 없거나 정도(약간·크게)를 가릴 때는 경로 모양(꺾인 각도)을 쓴다.
 * 순수 계산이라 실제 응답 문구로 단위 테스트한다.
 */
object Maneuvers {

    data class Parsed(
        val action: Action?,
        val at: String?,
        val to: String?,
        val road: String?,
        val count: Int,
    )

    private val roadInParens = Regex("""\(([^()]+)\)\s*$""")
    private val roadAfterTurn = Regex("""후 ([^\s\d()][^\s()]*) \d+(?:\.\d+)?k?m 이동""")
    private val atRe = Regex("""^(.+?)에서 """)
    private val toAfterAtRe = Regex("""에서 (.+?)까지 """)
    private val toRe = Regex("""^(.+?)까지 """)
    private val countRe = Regex("""(\d+)개의 횡단보도""")

    fun parse(guidance: String): Parsed {
        val g = guidance.trim()
        // 할 일 낱말은 장소·길 이름을 뗀 나머지에서만 찾는다 ("갑천육교 앞에서 …"의 '육교', "3번출구까지"의 '출구' 등)
        val core = g.replace(atRe, "").replace(toAfterAtRe, "").replace(toRe, "").replace(roadInParens, "")
            .let { roadAfterTurn.find(it)?.let { m -> it.replace(m.groupValues[1], "") } ?: it }
        val action = when {
            "유턴" in core -> Action.UTURN
            "좌회전" in core -> Action.LEFT
            "우회전" in core -> Action.RIGHT
            Regex("""왼쪽\s?길로|왼쪽으로""").containsMatchIn(core) -> Action.SLIGHT_LEFT
            Regex("""오른쪽\s?길로|오른쪽으로""").containsMatchIn(core) -> Action.SLIGHT_RIGHT
            "횡단보도" in core -> Action.CROSSWALK
            "육교" in core -> Action.OVERPASS
            "지하도" in core || "지하보도" in core -> Action.UNDERPASS
            "계단" in core -> Action.STAIRS
            "엘리베이터" in core -> Action.ELEVATOR
            "역사 내" in core -> Action.INDOOR
            else -> null
        }
        val road = roadInParens.find(g)?.groupValues?.get(1)?.trim()
            ?: roadAfterTurn.find(g)?.groupValues?.get(1)?.trim()
        val at = atRe.find(g)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val to = (toAfterAtRe.find(g) ?: toRe.find(g))?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val count = countRe.find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return Parsed(action, at, to, road, count)
    }

    /**
     * 앞 구간 끝 [spanM] 만큼의 방향 → 다음 구간 처음 [spanM] 만큼의 방향이 꺾인 각도.
     * -180..180, 음수 = 왼쪽. 구간이 너무 짧으면 null.
     */
    fun turnAngle(prev: List<Coord>, next: List<Coord>, spanM: Double = 15.0): Double? {
        if (prev.size < 2 || next.size < 2) return null
        val end = prev.last()
        val back = pointBack(prev, spanM) ?: return null
        val start = next.first()
        val ahead = pointAhead(next, spanM) ?: return null
        if (Geo.distanceM(back, end) < 1.0 || Geo.distanceM(start, ahead) < 1.0) return null
        val d = Geo.bearingDeg(start, ahead) - Geo.bearingDeg(back, end)
        return ((d + 540.0) % 360.0) - 180.0
    }

    private fun pointBack(path: List<Coord>, span: Double): Coord? {
        var left = span
        for (i in path.size - 1 downTo 1) {
            val len = Geo.distanceM(path[i - 1], path[i])
            if (len >= left && len > 0) {
                val f = left / len
                return Coord(path[i].lat + (path[i - 1].lat - path[i].lat) * f, path[i].lng + (path[i - 1].lng - path[i].lng) * f)
            }
            left -= len
        }
        return path.first()
    }

    private fun pointAhead(path: List<Coord>, span: Double): Coord? {
        var left = span
        for (i in 0 until path.size - 1) {
            val len = Geo.distanceM(path[i], path[i + 1])
            if (len >= left && len > 0) {
                val f = left / len
                return Coord(path[i].lat + (path[i + 1].lat - path[i].lat) * f, path[i].lng + (path[i + 1].lng - path[i].lng) * f)
            }
            left -= len
        }
        return path.last()
    }

    /** 문장 낱말이 우선. 정도(약간·크게)와 낱말 없는 꺾임은 각도로 */
    fun classify(p: Parsed, angle: Double?): Action = when (p.action) {
        Action.LEFT -> if (angle != null && angle <= -SHARP_DEG) Action.SHARP_LEFT else Action.LEFT
        Action.RIGHT -> if (angle != null && angle >= SHARP_DEG) Action.SHARP_RIGHT else Action.RIGHT
        Action.SLIGHT_LEFT -> if (angle != null && angle <= -SLIGHT_MAX_DEG) Action.LEFT else Action.SLIGHT_LEFT
        Action.SLIGHT_RIGHT -> if (angle != null && angle >= SLIGHT_MAX_DEG) Action.RIGHT else Action.SLIGHT_RIGHT
        null -> when {
            angle == null -> Action.STRAIGHT
            abs(angle) >= UTURN_DEG -> Action.UTURN
            angle <= -SHARP_DEG -> Action.SHARP_LEFT
            angle >= SHARP_DEG -> Action.SHARP_RIGHT
            angle <= -UNNAMED_TURN_DEG -> Action.LEFT
            angle >= UNNAMED_TURN_DEG -> Action.RIGHT
            else -> Action.STRAIGHT      // 완만한 굽이·구간 나눔은 직진 (알리지 않음)
        }
        else -> p.action
    }

    /** 구간 i 의 시작에서 할 일. 0번(출발)과 도보·자전거 안내가 아닌 구간(대중교통)은 null */
    fun forSteps(steps: List<GuideStep>): List<Maneuver?> = steps.mapIndexed { i, s ->
        if (i == 0 || !s.turnGuidance) return@mapIndexed null
        val p = parse(s.text)
        Maneuver(classify(p, turnAngle(steps[i - 1].points, s.points)), p.at, p.to, p.road, p.count)
    }

    /** 배너에 쓰는 짧은 말 */
    fun phrase(action: Action, count: Int = 1): String = when (action) {
        Action.STRAIGHT -> "직진"
        Action.SLIGHT_LEFT -> "왼쪽 길로"
        Action.LEFT -> "좌회전"
        Action.SHARP_LEFT -> "크게 좌회전"
        Action.SLIGHT_RIGHT -> "오른쪽 길로"
        Action.RIGHT -> "우회전"
        Action.SHARP_RIGHT -> "크게 우회전"
        Action.UTURN -> "유턴"
        Action.CROSSWALK -> if (count > 1) "횡단보도 ${count}번" else "횡단보도 건너기"
        Action.STAIRS -> "계단 이용"
        Action.OVERPASS -> "육교 건너기"
        Action.UNDERPASS -> "지하도 이용"
        Action.ELEVATOR -> "엘리베이터 이용"
        Action.INDOOR -> "역 안 이동"
    }

    private const val SHARP_DEG = 135.0
    private const val UTURN_DEG = 165.0
    private const val SLIGHT_MAX_DEG = 50.0
    /** 문장에 방향 말이 없을 때 이만큼 넘게 꺾여야 회전으로 본다 (길이 휘는 것은 무시) */
    private const val UNNAMED_TURN_DEG = 60.0
}
