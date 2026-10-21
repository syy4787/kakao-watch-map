package com.mywatch.kakaomap

import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.KnownIssue
import com.mywatch.battery.core.LeverEffect
import com.mywatch.battery.core.WakeStats
import com.mywatch.kakaomap.battery.BatteryTodo
import com.mywatch.kakaomap.battery.Insights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 배터리 화면 맨 위 '지금 할 일' — 여러 칸에 흩어진 조언 중 가장 급한 셋을 차례대로 */
class BatteryTodoTest {
    private val none = LeverEffect(emptyList(), emptyList())

    private fun insights(issues: List<KnownIssue> = emptyList(), wakes: WakeStats? = null) =
        Insights(analysis = null, breakdown = null, culprits = emptyList(), issues = issues, nightEffect = none, wakes = wakes)

    private val gms = KnownIssue("play-services-loop", "com.google.android.gms", "Google Play 서비스 과소모", "CPU 시간당 16분",
        listOf("Play 서비스 앱 정보 > 저장공간 > 캐시 지우기", "강제 재시작"), Grade.ABNORMAL)
    private val face = KnownIssue("watch-face-heavy", "com.example.face", "지금 시계 화면이 배터리를 많이 씀", "CPU",
        listOf("기본 삼성 시계 화면으로 바꿔 비교"), Grade.CAUTION)
    private val busyWrist = WakeStats(wakes = 300, shortWakes = 200, notificationWakes = 10, gestureLikeWakes = 190, gestureLikeMs = 190 * 3_000L,
        notifications = 40, topNotifiers = emptyList(), screenPph = 20.0)

    @Test
    fun anAbnormalKnownIssueComesFirstThenWristWakesThenTheRest() {
        val t = BatteryTodo.pick(insights(listOf(face, gms), busyWrist), permitted = true)
        assertEquals(3, t.size)
        assertEquals("Google Play 서비스 과소모", t[0].title)
        assertEquals("Play 서비스 앱 정보 > 저장공간 > 캐시 지우기", t[0].detail)
        assertEquals("지금 시계 화면이 배터리를 많이 씀", t[1].title)
        assertTrue(t[2].title, t[2].title.contains("5초 안에 꺼진 화면"))
    }

    @Test
    fun withoutThePermissionsTheToolkitIsSuggested() {
        val t = BatteryTodo.pick(insights(), permitted = false)
        assertEquals(1, t.size)
        assertTrue(t[0].title, t[0].title.contains("권한"))
        assertTrue(t[0].detail ?: "", (t[0].detail ?: "").contains("메뉴 8"))
        assertTrue("권한이 있으면 할 일 없음", BatteryTodo.pick(insights(), permitted = true).isEmpty())
    }
}
