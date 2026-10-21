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
    /** '지금 할 일' 줄에 맞춘 짧은 제목·설명 ([TodoText]) — 없으면 제목과 첫 단계 */
    val todo: Pair<String, String>? = null,
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
    const val WATCH_FACE_HEAVY = "watch-face-heavy"
    const val SHARE = 0.4

    /** 삼성·구글 기본 시계 화면과 Watch Face Format 렌더러 — 시계 화면 문제로 짚지 않는다 */
    private val SYSTEM_FACES = listOf(
        "com.samsung.android.watch.watchface", "com.samsung.android.wear", "com.samsung.android.app.watchface",
        "com.google.android.wearable", "com.google.wear.watchface", "com.google.android.apps.wearable",
    )

    fun isSystemFace(pkg: String): Boolean = SYSTEM_FACES.any { pkg.startsWith(it) }

    /**
     * [watchFace] = 지금 시계 화면의 패키지 (모르면 null), [windowMs] = 순위를 모은 시간 — 시계 화면은 늘 '보이는' 상태라
     * 화면 밖 CPU 표시가 붙지 않으므로 CPU 를 시간당으로 따로 본다
     */
    fun detect(ranks: List<AppDrainRank>, watchFace: String? = null, windowMs: Long? = null): List<KnownIssue> {
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
                    // 줄에 맞게 — '캐시만'(데이터는 지우지 않음)이 잘려 '데이터 지…'로 읽히지 않게
                    todo = "Play 서비스 과소모" to "앱 정보 › 저장공간 › 캐시만 지우기 → 30분 뒤 그대로면 강제 재시작",
                )
            }
        }
        // 타사 시계 화면이 웨이크락·CPU 를 많이 씀 — 기본 시계 화면으로 바꿔 비교 (Play 스토어도 2025년부터 배터리 경고를 단다)
        if (watchFace != null && !isSystemFace(watchFace)) {
            ranks.firstOrNull { it.use.pkg.substringBefore('+') == watchFace }?.let { r ->
                val cpuPerH = windowMs?.let { w -> r.use.cpuMs / (w / 3_600_000.0).coerceAtLeast(0.25) }
                val busyCpu = cpuPerH != null && cpuPerH > AppDrain.CPU_CAUTION_MS_PER_H
                if (r.grade != Grade.NORMAL || busyCpu) {
                    val cpuNote = cpuPerH?.takeIf { busyCpu }?.let { "CPU 시간당 ${(it / 60_000).toInt()}분" }
                    out += KnownIssue(
                        WATCH_FACE_HEAVY, watchFace,
                        "지금 시계 화면이 배터리를 많이 씀",
                        (listOfNotNull(cpuNote) + r.flags.filter { it != "이 앱" } + r.summary).distinct().joinToString(" · "),
                        listOf(
                            "시계 화면을 길게 눌러 기본 삼성 시계 화면으로 바꾸고 하루 소모를 비교해 보세요",
                            "계속 쓰려면 그 시계 화면의 날씨·심박 같은 정보 칸(컴플리케이션)과 초침을 줄이세요",
                            "Play 스토어의 그 시계 화면 정보에 '배터리 사용량이 많음' 경고가 있으면 다른 것으로",
                        ),
                        if (r.grade == Grade.NORMAL) Grade.CAUTION else r.grade,
                        todo = "시계 화면이 많이 씀" to "기본 삼성 시계 화면으로 하루 써 보고 비교",
                    )
                }
            }
        }
        return out
    }
}
