package com.mywatch.battery.core

/** 원인과 해결 순서가 알려진 문제 하나 */
data class KnownIssue(
    val id: String,
    /** 문제를 일으킨 앱 (공유 uid 의 '+N' 은 뗀 것) */
    val pkg: String,
    val title: String,
    /** 이번에 잰 근거 한 줄 */
    val why: String,
    /** 해결 순서 */
    val steps: List<String>,
    /** 비정상이면 알림까지, 주의면 화면에만 */
    val grade: Grade = Grade.CAUTION,
)

/**
 * 알려진 문제를 앱별 사용량 순위에서 짚어 낸다.
 *
 * Google Play 서비스: 2026년 4·6·7월 갤럭시워치(6·7·8·울트라 등, 워치9 사용자 보고 포함)에서 Play 서비스가 멈춘 채 돌며
 * 화면이 꺼져 있어도 배터리를 먹는 문제가 되풀이됐다(배터리 사용 1위, 16%~99%). 알려진 해결은 Play 서비스 캐시 지우기 →
 * 재시작 → 그래도 안 되면 업데이트를 기다리거나 초기화 (Android Authority 2026-07-24, 9to5Google 2026-04-13 보도).
 * Play 서비스가 순위 1위이거나 전체 사용량의 40% 넘게 차지하면서 '주의' 이상일 때만 짚는다 — 다른 앱이 훨씬 많이 쓰면 엉뚱한 해결을 주지 않게.
 */
object KnownIssues {
    const val GMS = "com.google.android.gms"
    const val PLAY_SERVICES_LOOP = "play-services-loop"
    const val SHARE = 0.4

    fun detect(ranks: List<AppDrainRank>): List<KnownIssue> {
        if (ranks.isEmpty()) return emptyList()
        val total = ranks.sumOf { it.score }
        val out = ArrayList<KnownIssue>()
        ranks.withIndex().firstOrNull { (_, r) -> r.use.pkg.substringBefore('+') == GMS }?.let { (i, r) ->
            if (r.grade != Grade.NORMAL && (i == 0 || r.score >= total * SHARE)) {
                out += KnownIssue(
                    PLAY_SERVICES_LOOP, GMS,
                    "Google Play 서비스 과소모 — 2026년 갤럭시워치에 되풀이된 문제",
                    (r.flags.filter { it != "이 앱" } + r.summary).joinToString(" · "),
                    listOf(
                        "Play 서비스 앱 정보 > 저장공간 > 캐시 지우기 (데이터 지우기는 하지 마세요 — 계정이 다시 동기화됩니다)",
                        "30분 뒤에도 그대로면 홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작",
                        "또 돌아오면 PC 툴킷 메뉴 8(자동 치료) — 모든 앱 캐시 정리와 Play 서비스 다시 시작을 대신 합니다. 워치 Play 스토어의 업데이트도 확인하세요",
                    ),
                    r.grade,
                )
            }
        }
        return out
    }
}
