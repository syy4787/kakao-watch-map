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
    /** 타일 한 줄 (문제와 그 할 일) — 없으면 제목 */
    val tile: String? = null,
    /** 재시작해도 그대로라 다음 단계를 주는 것 ([KnownIssues.detect] 의 cameBack) */
    val cameBack: Boolean = false,
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
    const val HOME = "com.samsung.android.wearable.sysui"
    const val HOME_HOG = "watch-home-hog"
    const val SHARE = 0.4

    /** 삼성·구글 기본 시계 화면과 Watch Face Format 렌더러 — 시계 화면 문제로 짚지 않는다 */
    private val SYSTEM_FACES = listOf(
        "com.samsung.android.watch.watchface", "com.samsung.android.wear", "com.samsung.android.app.watchface",
        "com.google.android.wearable", "com.google.wear.watchface", "com.google.android.apps.wearable",
    )

    fun isSystemFace(pkg: String): Boolean = SYSTEM_FACES.any { pkg.startsWith(it) }

    /**
     * [watchFace] = 지금 시계 화면의 패키지 (모르면 null), [windowMs] = 순위를 모은 시간 — 시계 화면은 늘 '보이는' 상태라
     * 화면 밖 CPU 표시가 붙지 않으므로 CPU 를 시간당으로 따로 본다. [cameBack] = 재시작한 뒤에도 또 나타난 문제(id) — 그대로였든 몇 시간
     * 뒤 다시 나타났든 같은 '재시작'을 되풀이하지 않고 다음 단계로 ('재시작해도 그대로'라고 단정하지 않는다)
     */
    fun detect(
        ranks: List<AppDrainRank>, watchFace: String? = null, windowMs: Long? = null, screenOnMs: Long? = null,
        notifications: Int = 0, label: (String) -> String? = { null }, cameBack: Set<String> = emptySet(),
    ): List<KnownIssue> {
        if (ranks.isEmpty()) return emptyList()
        // 창이 1시간쯤(55분)은 돼야 — 짧으면(첫 스냅샷·재시작 직후·폰에서 알림 다시 받기) 잠깐의 일이 시간당으로 부풀려진다.
        // 창을 모르면(시험 등) 그대로 본다
        if (windowMs != null && windowMs < MIN_WINDOW_MS) return emptyList()
        val total = ranks.sumOf { it.score }
        val out = ArrayList<KnownIssue>()
        ranks.withIndex().firstOrNull { (_, r) -> r.use.pkg.substringBefore('+') == GMS }?.let { (i, r) ->
            if (r.grade != Grade.NORMAL && (i == 0 || r.score >= total * SHARE)) {
                // 메뉴 이름은 삼성 한국어 화면 그대로 (저장공간 › 캐시 삭제 / 데이터 삭제)
                val cache = "Play 서비스 앱 정보 > 저장공간 > 캐시 삭제 (데이터 삭제는 하지 마세요 — 계정이 다시 동기화됩니다)"
                // PC 툴킷은 자기 보고서(충전 뒤 통계)가 Play 서비스를 짚어야 캐시 정리·다시 시작을 한다 — 늘 해 준다고 하지 않는다 (리뷰)
                val pc = "PC 툴킷 메뉴 8(자동 치료) — PC 보고서도 Play 서비스를 짚으면 모든 앱 캐시 정리와 Play 서비스 다시 시작을 대신 합니다. 워치 Play 스토어의 업데이트도 확인하세요"
                val why = (r.flags.filter { it != "이 앱" } + r.summary).joinToString(" · ")
                out += if (PLAY_SERVICES_LOOP in cameBack) KnownIssue(
                    PLAY_SERVICES_LOOP, GMS,
                    "Google Play 서비스 과소모 — 재시작 뒤에도 또 나타남",
                    why,
                    listOf("아직 안 했으면 $cache", "그래도 그대로면 $pc", "고쳐질 때까지 밤엔 지킴이 밤 모드"),
                    r.grade,
                    todo = "재시작 뒤에도 Play 서비스 과소모" to "캐시만 지우기(아직이면) → 그대로면 PC 툴킷 메뉴 8(자동 치료)",
                    tile = "Play 서비스 과소모 — 캐시·PC 치료",
                    cameBack = true,
                ) else KnownIssue(
                    PLAY_SERVICES_LOOP, GMS,
                    "Google Play 서비스 과소모 — 2026년 갤럭시워치에 되풀이된 문제",
                    why,
                    listOf(cache, "30분 뒤에도 그대로면 홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작", "또 돌아오면 $pc"),
                    r.grade,
                    // 줄에 맞게 — '캐시만'(데이터는 지우지 않음)이 잘려 '데이터 지…'로 읽히지 않게
                    todo = "Play 서비스 과소모" to "앱 정보 › 저장공간 › 캐시만 지우기 → 30분 뒤 그대로면 강제 재시작",
                    tile = "Play 서비스 과소모 — 캐시 지우기",
                )
            }
        }
        // 타사 시계 화면이 웨이크락·CPU 를 많이 씀 — 기본 시계 화면으로 바꿔 비교 (Play 스토어도 2025년부터 배터리 경고를 단다)
        if (watchFace != null && !isSystemFace(watchFace)) {
            ranks.firstOrNull { it.use.pkg.substringBefore('+') == watchFace }?.let { r ->
                val cpuPerH = windowMs?.let { w -> r.use.cpuMs / (w / 3_600_000.0) }
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
                        tile = "시계 화면이 많이 씀 — 기본 화면으로",
                    )
                }
            }
        }
        // One UI Watch 홈(시스템 UI): 화면을 그리는 프로세스라 '화면에 떠 있던 시간'이 화면 켜짐과 맞지 않아 앱별 등급([AppDrain])으로는
        // 매기지 않는다 → 화면이 켜져 있던 동안 홈 화면·알림·타일을 그렸을 몫과 받은 알림을 보여 준 몫을 빼고 남은 CPU 를 시간당으로 본다.
        // 화면 켜진 시간·창 길이를 모르거나 오래 켜고 썼으면 짚지 않는다
        if (windowMs != null && screenOnMs != null && screenOnMs <= windowMs * HOME_MAX_SCREEN_SHARE) {
            ranks.withIndex().firstOrNull { (_, r) -> r.use.pkg.substringBefore('+') == HOME }?.let { (i, r) ->
                val hours = windowMs / 3_600_000.0
                // 다른 앱의 알림을 받아 보여 주는 일도 홈이 한다 — 알림 하나에 [HOME_CPU_PER_NOTIFICATION_MS] 씩은 빼고 본다
                val allowance = screenOnMs * HOME_SCREEN_CPU_SHARE + notifications * HOME_CPU_PER_NOTIFICATION_MS
                val idleCpuMs = (r.use.cpuMs - allowance).coerceAtLeast(0.0)
                val perH = idleCpuMs / hours
                val cpuGrade = when {
                    perH > AppDrain.CPU_ABNORMAL_MS_PER_H -> Grade.ABNORMAL
                    perH > HOME_CAUTION_MS_PER_H -> Grade.CAUTION
                    else -> Grade.NORMAL
                }
                // 웨이크락은 '멈춘 채 쥠'(창의 절반 넘게)만 — 알림·진동으로 홈이 잠깐씩 쥐는 것(다른 앱의 '주의' 기준 시간당 18.5초)은 흔하다
                val stuck = r.use.wakelockMs >= windowMs * AppDrain.STUCK_RATIO
                val grade = Grade.worst(listOf(cpuGrade, if (stuck) Grade.ABNORMAL else Grade.NORMAL))
                if (grade != Grade.NORMAL && (i == 0 || r.score >= total * SHARE)) {
                    val cpuNote = "화면이 거의 꺼져 있던 ${fmtHours(hours)} 동안 CPU ${(r.use.cpuMs / 60_000).toInt()}분 (화면 켜짐 ${(screenOnMs / 60_000).toInt()}분" +
                        (if (notifications > 0) " · 알림 ${notifications}개" else "") + ")"
                    val name = label(HOME) ?: "One UI Watch 홈"
                    val why = (listOf(cpuNote) + r.flags.filter { it != "이 앱" }).joinToString(" · ")
                    val cache = "설정 › 애플리케이션 › $name › 저장공간 › 캐시 삭제 (데이터 삭제는 하지 마세요 — 홈 화면 배치가 초기화됩니다)"
                    val report = "계속되면 Samsung Members 앱으로 오류 보고(삼성 안내) — 고쳐질 때까지 밤엔 지킴이 밤 모드"
                    out += if (HOME_HOG in cameBack) KnownIssue(
                        HOME_HOG, HOME,
                        "One UI Watch 홈이 화면 꺼진 채 많이 씀 — 재시작 뒤에도 또 나타남",
                        why,
                        listOf(cache, "캐시를 지운 뒤 한 번 더 강제 재시작(홈 + 뒤로 7초 이상) — 캐시만 지우면 돌고 있는 홈은 그대로라서", report),
                        grade,
                        // 캐시만 지우면 돌고 있는 홈은 그대로 — 할 일 줄에도 강제 재시작을 같이 (리뷰)
                        todo = "재시작 뒤에도 홈 과소모" to "앱 정보 › 저장공간 › 캐시만 지우고 강제 재시작 → 그대로면 오류 보고",
                        tile = "One UI Watch 홈 과소모 — 홈 캐시 지우기",
                        cameBack = true,
                    ) else KnownIssue(
                        HOME_HOG, HOME,
                        "One UI Watch 홈이 화면 꺼진 채 많이 씀 — 2026년 업데이트 뒤 보고된 문제",
                        why,
                        listOf(
                            "홈 + 뒤로 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작 — 보통 재시작으론 안 풀리고 강제 재시작으로 나아졌다는 보고가 있습니다",
                            "업데이트한 지 며칠 안 됐으면 적응 기간일 수 있습니다 — 충전기 위에서 하룻밤 지나도 그대로면 다음 단계",
                            cache,
                            report,
                        ),
                        grade,
                        todo = "One UI Watch 홈 과소모" to "홈+뒤로 버튼 7초 넘게 눌러 강제 재시작 → 그대로면 홈 앱 캐시만 지우기",
                        tile = "One UI Watch 홈 과소모 — 강제 재시작",
                    )
                }
            }
        }
        return out
    }

    /** 화면이 켜져 있던 동안 홈이 쓸 만한 CPU — 화면 켜짐 시간의 이 몫까지는 홈 화면·알림·타일을 그린 것으로 본다 */
    const val HOME_SCREEN_CPU_SHARE = 0.5

    /** 창의 이만큼 넘게 화면이 켜져 있었으면 홈을 짚지 않는다 (홈을 쓴 것) */
    const val HOME_MAX_SCREEN_SHARE = 0.3

    /**
     * 홈의 '주의'는 다른 앱(시간당 3분)보다 높게 5분 — AOD 갱신처럼 화면이 꺼져 있어도 홈이 조금 하는 일이 있어서.
     * (배터리 사용량 순위에서 홈이 위쪽이라는 것만으로는 고장이라 단정할 수 없다 — 그래서 순위가 아니라 화면 꺼진 동안의 CPU 로 본다)
     */
    const val HOME_CAUTION_MS_PER_H = 5L * 60_000L

    /**
     * 알려진 문제를 판단하려면 앱별 통계 창이 이만큼은 돼야 한다 — '1시간쯤': 워치 앱은 앱별 통계를 55분이 지나면 남기므로(15분 기록
     * 넷에 한 번) 재시작 뒤 기준값 다음의 첫 한 줄(55분 이상)로 볼 수 있게 그 간격과 같게
     */
    const val MIN_WINDOW_MS = 55L * 60_000L

    /** 알림 하나를 받아 보여 주는 데 홈이 쓸 만한 CPU (어림 — 진동·화면 표시·묶음 정리) */
    const val HOME_CPU_PER_NOTIFICATION_MS = 2_000L

    private fun fmtHours(h: Double): String = "${DrainAnalyzer.fmt(h)}시간".replace(".0시간", "시간")
}
