package com.mywatch.kakaomap

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.mywatch.battery.core.KnownIssues
import com.mywatch.kakaomap.battery.BatterySummary
import com.mywatch.kakaomap.battery.HomeUid
import com.mywatch.kakaomap.battery.Insights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One UI Watch 홈의 uid — 앱별 통계(HealthStats)는 uid 단위라, 홈이 시스템 uid(1000)를 같이 쓰면 홈만 따로 셀 수 없어 '홈 과소모'를
 * 못 짚는다. 실제 워치가 어떤지(설계 문서의 열린 가정) 하루 요약의 '[홈]' 줄로 확인한다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeUidTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun install(uid: Int, vararg sharing: String) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = KnownIssues.HOME
            applicationInfo = ApplicationInfo().apply { packageName = KnownIssues.HOME; this.uid = uid }
        })
        if (sharing.isNotEmpty()) pm.setPackagesForUid(uid, *sharing)
    }

    @Test
    fun aHomeWithItsOwnUidIsCountedSeparately() {
        install(10123)
        val h = HomeUid.of(context)!!
        assertEquals(10123, h.uid)
        assertEquals("One UI Watch 홈 uid 10123 (따로 셈)", h.line)
    }

    @Test
    fun aHomeSharingItsUidIsNamedAfterTheHome() {
        install(10123, KnownIssues.HOME, "com.samsung.android.wearable.sysui.overlay", "com.example.other")
        assertEquals("One UI Watch 홈 uid 10123 (패키지 3개가 같이 씀 — 홈 이름으로 셈)", HomeUid.of(context)!!.line)
    }

    @Test
    fun aHomeOnTheSystemUidCannotBeTold() {
        install(1000, "android", KnownIssues.HOME)
        val line = HomeUid.of(context)!!.line
        assertTrue(line, line.startsWith("One UI Watch 홈 uid 1000 — 시스템과 같은 uid"))
        assertTrue(line, line.contains("홈 과소모는 못 짚음"))
    }

    @Test
    fun noHomeNoLine() {
        assertNull(HomeUid.of(context))
    }

    @Test
    fun theDailySummaryShowsIt() {
        val a = com.mywatch.battery.core.DrainAnalyzer(445, java.time.ZoneId.of("Asia/Seoul")).analyze(emptyList(), 1_000_000L)
        val ins = Insights(a, null, emptyList(), emptyList(), com.mywatch.battery.core.LeverEffect(emptyList(), emptyList()), home = HomeUid(10123, 1))
        val text = BatterySummary.text(a, null, "SM-L335", 1_000_000L, ins)
        assertTrue(text, text.contains("[홈] One UI Watch 홈 uid 10123 (따로 셈)"))
    }
}
