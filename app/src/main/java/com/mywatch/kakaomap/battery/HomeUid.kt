package com.mywatch.kakaomap.battery

import android.content.Context
import android.os.Process
import com.mywatch.battery.core.KnownIssues

/**
 * One UI Watch 홈의 uid 와 그 uid 를 같이 쓰는 패키지 수. 앱별 통계(HealthStats)는 uid 단위라 홈이 시스템 uid(1000)를 같이 쓰면
 * 홈만 따로 셀 수 없어(묶음이 'android' 로 보임) '홈 과소모'를 짚지 못한다 — 실제 워치가 어떤지 하루 요약의 '[홈]' 줄로 확인한다
 * (설계 문서 5-7 의 '남은 확인')
 */
data class HomeUid(val uid: Int, val packages: Int) {
    val line: String
        get() = "One UI Watch 홈 uid $uid" + when {
            uid == Process.SYSTEM_UID -> " — 시스템과 같은 uid 라 홈만 따로 셀 수 없음('android' 로 보임): 홈 과소모는 못 짚음"
            packages > 1 -> " (패키지 ${packages}개가 같이 씀 — 홈 이름으로 셈)"
            else -> " (따로 셈)"
        }

    companion object {
        /** 홈이 없거나 못 읽으면 null (모든 앱 보기 권한으로 읽는다) */
        fun of(c: Context): HomeUid? = runCatching {
            val pm = c.packageManager
            val uid = pm.getApplicationInfo(KnownIssues.HOME, 0).uid
            HomeUid(uid, pm.getPackagesForUid(uid)?.size?.coerceAtLeast(1) ?: 1)
        }.getOrNull()
    }
}
