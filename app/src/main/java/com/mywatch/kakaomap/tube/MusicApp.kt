package com.mywatch.kakaomap.tube

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * 워치의 YouTube Music 앱 (구글 공식 앱 — 워치에 로그인된 계정, 프리미엄이면 광고 없이·화면이 꺼져도 재생).
 * 워치 앱엔 검색이 없어서, 이 앱에서 찾은 곡을 곡 링크로 넘긴다 — 그 앱이 곡 링크를 받을 때만 (안 받으면 앱만 연다).
 * 패키지가 보이도록 AndroidManifest 의 queries 에 넣어 두었다.
 */
object MusicApp {
    const val PACKAGE = "com.google.android.apps.youtube.music"

    /** 워치 YouTube Music 이 이 곡(믹스면 노래 라디오)을 바로 트는 링크 */
    fun songIntent(id: String, mix: Boolean): Intent =
        Intent(Intent.ACTION_VIEW, TubeLinks.musicUrl(id, mix).toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(PACKAGE)
            // 제 작업(task)에서 — 타일에서 틀고 이 앱을 닫아도 같이 닫히지 않게
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 워치 YouTube Music 이 곡 링크를 받는지 (받으면 고른 곡을 워치에서 프리미엄으로 바로 튼다) */
    fun canPlaySongs(context: Context): Boolean = runCatching {
        songIntent(SAMPLE_ID, mix = true).resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    /** 워치에 YouTube Music 이 있으면 그 앱을 여는 인텐트 (계정 전환·라이브러리·믹스) */
    fun openIntent(context: Context): Intent? = runCatching {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    fun installed(context: Context): Boolean = openIntent(context) != null || canPlaySongs(context)

    /** 워치 Play 스토어의 YouTube Music 페이지 (설치) */
    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW, "market://details?id=$PACKAGE".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 곡 링크를 받는지 볼 때 쓰는 아무 영상 번호 (모양만 맞으면 됨) */
    private const val SAMPLE_ID = "aaaaaaaaaaa"
}
