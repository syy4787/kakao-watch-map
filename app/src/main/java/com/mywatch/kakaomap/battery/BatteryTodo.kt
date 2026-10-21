package com.mywatch.kakaomap.battery

import com.mywatch.battery.core.Change
import com.mywatch.battery.core.ChangeFinder
import com.mywatch.battery.core.ChangeKind
import com.mywatch.battery.core.GaugeDriftRule
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Thresholds
import kotlin.math.abs

/**
 * 배터리 화면 맨 위 '지금 할 일' — 여러 칸(알려진 문제·잔량 표시·화면 켜짐·밤사이·충전 습관)에 흩어진 조언 중 급한 것 [MAX] 개.
 * 차례: 비정상 알려진 문제 → 최근 7일 안에 늘어난 꺾임(그 무렵 바뀐 것에 맞는 할 일) → 확실한 잔량 표시 오차 → 주의 알려진 문제 →
 * 오래 켜 둔 워치(7일+, 대기 소모 높음)면 재시작 → 화면 켜짐(손목 올림·알림) → 밤사이(삼성 헬스 등) →
 * 권한이 없으면 PC 툴킷 → 충전 습관
 */
object BatteryTodo {
    const val MAX = 3

    /** 할 일 아래 버튼으로 바로 여는 화면 — 워치 설정을 손가락으로 찾아 들어가지 않게 */
    sealed class Action(val label: String) {
        /** 앱 정보 (캐시 지우기·알림·강제 종료) */
        data class AppInfo(val pkg: String, val name: String) : Action("$name 앱 정보")
        /** 설정 > 디스플레이 (AOD·손목 올려 켜기·화면 자동 꺼짐) */
        data object Display : Action("디스플레이 설정")
        /** 설정 > 위치 */
        data object Location : Action("위치 설정")
        /** 설정 > 배터리 (배터리 보호·절전) */
        data object Battery : Action("배터리 설정")
    }

    data class Todo(val title: String, val detail: String?, val grade: Grade, val action: Action? = null)

    /** 이만큼 넘게 재시작하지 않았고 대기 소모가 높으면 재시작을 권한다 */
    const val RESTART_AFTER_DAYS = 7

    fun pick(ins: Insights, permitted: Boolean, label: (String) -> String = { it }, now: Long = System.currentTimeMillis()): List<Todo> =
        candidates(ins, permitted, label, now).distinctBy { it.title }.take(MAX)

    /**
     * 할 일 후보 전부(차례대로). 글은 모두 '지금 할 일' 줄(제목 두 줄·설명 세 줄 — [TodoText])에 맞춘 짧은 것 — 넘쳐 잘리면
     * 뒤가 안 보이고 잘린 자리가 다른 뜻으로 읽힐 수 있다(점검에서 찾음: '데이터 지…')
     */
    fun candidates(ins: Insights, permitted: Boolean, label: (String) -> String = { it }, now: Long = System.currentTimeMillis()): List<Todo> {
        val out = ArrayList<Todo>()
        fun todo(p: Pair<String, String>, g: Grade, action: Action? = null) = Todo(p.first, p.second, g, action)
        // 버튼 글은 두 줄 안에 — Play 서비스는 짧은 이름으로
        fun appInfo(pkg: String) = Action.AppInfo(pkg, if (pkg == ChangeFinder.GMS) "Play 서비스" else label(pkg).takeIf { it != pkg } ?: ChangeFinder.KNOWN[pkg] ?: pkg)
        fun issue(i: com.mywatch.battery.core.KnownIssue) = Todo(i.todo?.first ?: i.title, i.todo?.second ?: i.steps.firstOrNull(), i.grade, appInfo(i.pkg))
        ins.issues.filter { it.grade == Grade.ABNORMAL }.forEach { out += issue(it) }
        // 최근 7일 안에 대기 소모가 늘어 꺾였으면 그 무렵 가장 그럴듯한 변화에 맞는 할 일
        ins.trend?.takeIf { it.fresh }?.let { t ->
            val shift = t.shift ?: return@let
            val (title, detail) = t.advice { pkg -> label(pkg).takeIf { it != pkg } } ?: return@let
            out += Todo(title, detail, if (Thresholds.idle(shift.after) == Grade.ABNORMAL) Grade.ABNORMAL else Grade.CAUTION, actionFor(t.top, ::appInfo))
        }
        ins.analysis?.drift?.takeIf { abs(it.driftPoints) >= GaugeDriftRule.CERTAIN_POINTS }?.todo?.let { out += todo(it, Grade.CAUTION) }
        ins.issues.filter { it.grade != Grade.ABNORMAL }.forEach { out += issue(it) }
        // 오래 켜 둔 워치 + 대기 소모가 높음 → 한 번 재시작 (멈춘 서비스가 풀리는 일이 많다 — 2026년 광탈 글들의 공통 해결담)
        ins.bootAt?.let { boot ->
            val days = ((now - boot) / (24 * 3_600_000L)).toInt()
            val idle = ins.breakdown?.idlePph ?: ins.analysis?.idle24?.percentPerHour
            val high = Thresholds.idle(idle).let { it == Grade.CAUTION || it == Grade.ABNORMAL } || ins.trend?.fresh == true
            if (days >= RESTART_AFTER_DAYS && high) {
                out += Todo("재시작한 지 ${days}일 — 한 번 재시작", "홈+뒤로 버튼을 7초 넘게 눌러 끄고 켜기 — 멈춘 서비스가 풀림", Grade.CAUTION)
            }
        }
        ins.wakes?.let { w ->
            // 밤에 저절로 켜진 화면 — 밤에만 끄는 길(취침 모드·이 화면의 지킴이 설정)을 권하므로 설정 바로 가기는 두지 않는다
            w.gestureTodo()?.let { out += todo(it, Grade.CAUTION) }
            w.notificationTodo()?.let { out += todo(it, Grade.CAUTION) }
        }
        ins.night?.todos?.forEach { out += todo(it, Grade.CAUTION) }
        if (!permitted) {
            out += Todo("지킴이 권한 주기", "PC 툴킷 메뉴 8(자동 치료) 한 번 — 밤·과소모 때 워치가 알아서 조절", Grade.UNKNOWN)
        }
        ins.charging?.let { ch ->
            ch.todos.forEachIndexed { i, t -> out += todo(t, Grade.CAUTION, if (i == 0 && ch.protectAdvised) Action.Battery else null) }
        }
        return out
    }

    /** 꺾임을 설명하는 변화 → 바로 열 화면 (소프트웨어 업데이트는 그 뒤 할 일이 Play 서비스 캐시라 그 앱 정보) */
    private fun actionFor(c: Change?, appInfo: (String) -> Action.AppInfo): Action? = when (c?.kind) {
        ChangeKind.FIRMWARE -> appInfo(ChangeFinder.GMS)
        ChangeKind.APP -> c.subject.takeIf { c.to != "-" }?.let(appInfo)
        ChangeKind.SETTING -> when (c.subject) {
            "aod", "tilt", "touch" -> Action.Display
            "location" -> Action.Location
            else -> null
        }
        else -> null
    }
}
