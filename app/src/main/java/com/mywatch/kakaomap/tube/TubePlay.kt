package com.mywatch.kakaomap.tube

import android.os.Build
import java.io.File

/** 고른 동영상 다음에 무엇을 이어 틀지 */
sealed interface Queue {
    /** 유튜브 믹스 — 이 동영상으로 시작해 유튜브가 비슷한 동영상을 끝없이 이어 준다 */
    data object Mix : Queue

    /** 이 동영상만 */
    data object Single : Queue

    /** 앱의 목록 그대로 (검색 결과·구독 피드·좋아요·기록·채널 동영상 등) — [next] = 이 동영상 다음에 틀 것들 */
    data class Ids(val next: List<String>) : Queue

    /** 재생목록 — 이 동영상의 자리부터 유튜브가 목록 끝까지 이어 준다 */
    data class Playlist(val id: String) : Queue
}

data class PlayRequest(val video: TubeVideo, val queue: Queue)

/** 어디서 틀지: 앱 안 엔진(GeckoView) / 워치 인터넷 앱(삼성 인터넷) / 폰 유튜브 */
enum class PlayRoute { IN_APP, BROWSER, PHONE }

object TubePlay {

    /**
     * 어디서 틀지. [page] = 재생 화면 주소를 앎(저장소가 있는 빌드), [engine] = 이 워치에서 엔진을 쓸 수 있음,
     * [engineCrashed] = 엔진이 최근에 죽음([PlayerGuard]), [browser] = 워치에 인터넷 앱이 있음.
     * 올린 사람이 다른 곳 재생을 막았거나 나이 제한인 동영상은 워치 플레이어로 안 나오니 바로 폰으로
     */
    fun route(video: TubeVideo, page: Boolean, engine: Boolean, engineCrashed: Boolean, browser: Boolean): PlayRoute = when {
        !video.embeddable || !page -> PlayRoute.PHONE
        engine && !engineCrashed -> PlayRoute.IN_APP
        browser -> PlayRoute.BROWSER
        else -> PlayRoute.PHONE
    }

    /** 앱에 든 엔진(GeckoView)은 ARM 용만 — 이 워치의 CPU 가 그중 하나인지 */
    fun engineSupported(abis: Array<String> = Build.SUPPORTED_ABIS): Boolean =
        abis.any { it == "armeabi-v7a" || it == "arm64-v8a" }

    /** 목록에서 [index] 번째를 누름 → 그 동영상부터, 뒤의 (워치에서 틀 수 있는) 동영상들을 이어서 */
    fun fromList(list: List<TubeVideo>, index: Int): PlayRequest {
        val next = list.drop(index + 1).filter { it.embeddable }.map { it.id }.take(TubeLinks.MAX_QUEUE - 1)
        return PlayRequest(list[index], if (next.isEmpty()) Queue.Single else Queue.Ids(next))
    }
}

/**
 * 앱 안 엔진이 이 워치에서 죽은 적이 있는지 — 있으면 6시간 동안은 삼성 인터넷으로 연다 (앱이 새 버전이면 바로 다시 해 봄).
 * 재생 화면은 따로 된 프로세스(:player)라 파일로 남긴다 (SharedPreferences 는 프로세스끼리 바로 보이지 않음).
 *  · 엔진을 띄우기 직전 [starting], 첫 화면이 그려지면 [started], 그리기 전에 화면을 떠나면 [stopped]
 *  · 엔진이 죽었다고 알려 오거나 못 띄우면 [failed]
 *  · 시작 표시가 1분 넘게 남아 있으면 그리기 전에 프로세스가 죽은 것 (알릴 틈도 없이)
 */
class PlayerGuard(private val dir: File, private val version: Int, private val now: () -> Long = System::currentTimeMillis) {

    private val startingFile get() = File(dir, "starting")
    private val crashedFile get() = File(dir, "crashed")

    fun starting() = write(startingFile, now())

    fun started() {
        startingFile.delete()
        crashedFile.delete()
    }

    fun stopped() {
        startingFile.delete()
    }

    fun failed() {
        startingFile.delete()
        write(crashedFile, now())
    }

    fun crashedRecently(): Boolean {
        val starting = read(startingFile)
        if (starting != null && now() - starting.first > STALE_MS) failed()
        val crashed = read(crashedFile) ?: return false
        return crashed.second == version && now() - crashed.first in 0 until FALLBACK_MS
    }

    private fun write(f: File, at: Long) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText("$at $version")
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun read(f: File): Pair<Long, Int>? = runCatching {
        val parts = f.readText().trim().split(' ')
        parts[0].toLong() to parts[1].toInt()
    }.getOrNull()

    companion object {
        const val STALE_MS = 60_000L
        const val FALLBACK_MS = 6 * 3_600_000L
    }
}
