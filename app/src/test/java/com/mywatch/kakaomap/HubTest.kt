package com.mywatch.kakaomap

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mywatch.kakaomap.ui.HubActivity
import com.mywatch.kakaomap.ui.SearchActivity
import com.mywatch.kakaomap.ui.TubeActivity
import com.mywatch.kakaomap.update.FakeReleaseServer
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppTask

/**
 * '갤럭시워치 전용앱' 첫 화면: 카카오맵·유튜브를 고르면 이미 열려 있던 그 앱(안내 중인 지도 등)을 보던 그대로 앞으로 —
 * 없을 때만 새로 연다 (지도를 하나 더 만들어 카카오 지도 엔진을 또 띄우지 않게)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HubTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val am = context.getSystemService(ActivityManager::class.java)

    private fun task(base: Class<*>, activities: Int): ActivityManager.AppTask {
        val t = ShadowAppTask.newInstance()
        shadowOf(t).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            baseActivity = ComponentName(context, base)
            baseIntent = Intent().setComponent(baseActivity)
            numActivities = activities
        })
        return t
    }

    private fun pick(button: Int): HubActivity {
        val hub = Robolectric.buildActivity(HubActivity::class.java).setup().get()
        hub.findViewById<View>(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return hub
    }

    @Test
    fun theMapThatIsAlreadyOpenComesBackAsItWas() {
        // 타일의 '검색'으로 연 지도 작업 (검색 → 길찾기 → 상세 → 지도) 과 유튜브 작업이 떠 있다
        val map = task(SearchActivity::class.java, 4)
        val tube = task(TubeActivity::class.java, 1)
        shadowOf(am).setAppTasks(listOf(tube, map))
        val hub = pick(R.id.open_map)
        assertTrue(shadowOf(map).hasMovedToFront())
        assertFalse(shadowOf(tube).hasMovedToFront())
        assertNull("새 지도를 띄우지 않는다", shadowOf(hub).nextStartedActivity)
        assertTrue(hub.isFinishing)
    }

    @Test
    fun youtubeLikewise() {
        val map = task(MainActivity::class.java, 1)
        val tube = task(TubeActivity::class.java, 2)
        shadowOf(am).setAppTasks(listOf(map, tube))
        val hub = pick(R.id.open_tube)
        assertTrue(shadowOf(tube).hasMovedToFront())
        assertFalse(shadowOf(map).hasMovedToFront())
        assertNull(shadowOf(hub).nextStartedActivity)
    }

    @Test
    fun withNothingOpenItStartsInItsOwnTask() {
        // 최근 앱 목록에만 남은 빈 작업은 되살리지 않는다 (처음 열 때의 타일 요청이 다시 실행될 수 있음)
        val leftover = task(SearchActivity::class.java, 0)
        shadowOf(am).setAppTasks(listOf(leftover))
        val hub = pick(R.id.open_map)
        assertFalse(shadowOf(leftover).hasMovedToFront())
        val started = shadowOf(hub).nextStartedActivity
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue(hub.isFinishing)

        shadowOf(am).setAppTasks(emptyList())
        val tubeHub = pick(R.id.open_tube)
        assertEquals(TubeActivity::class.java.name, shadowOf(tubeHub).nextStartedActivity?.component?.className)
    }

    // ───── 새 버전 알기 (집 밖에서도) ─────

    @Before
    fun testWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After
    fun realReleases() {
        UpdateManager.baseUrl = UpdateManager.GITHUB
    }

    private fun checksQueued() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(UpdateWorker.CHECK_NOW_NAME).get()

    @Test
    fun openingTheAppLooksForANewVersionWhenTheLastLookIsOld() {
        // 마지막 확인이 오래됨(한 번도 안 함) → 앱을 열면 확인을 맡긴다 (첫 화면이 금방 닫혀도 끝까지)
        UpdateManager.setOwner(context, "tester")
        Robolectric.buildActivity(HubActivity::class.java).setup()
        assertEquals(1, checksQueued().size)
    }

    @Test
    fun justCheckedSoOpeningTheAppDoesNotAskAgain() {
        FakeReleaseServer().use { server ->
            server.updateJson = """{"versionCode":1,"versionName":"0","apkUrl":"${server.apkUrl}","sha256":"00","notes":""}"""
            UpdateManager.baseUrl = server.base
            UpdateManager.setOwner(context, "tester")
            UpdateManager.check(context)
            Robolectric.buildActivity(HubActivity::class.java).setup()
            assertEquals(0, checksQueued().size)
        }
    }

    @Test
    fun aNewVersionFoundWhileTheChooserIsOpenShowsRightAway() {
        FakeReleaseServer().use { server ->
            server.updateJson = server.updateJsonFor(999)
            UpdateManager.baseUrl = server.base
            UpdateManager.setOwner(context, "tester")
            val hub = Robolectric.buildActivity(HubActivity::class.java).setup().get()
            val button = hub.findViewById<TextView>(R.id.hub_update)
            assertEquals("업데이트", button.text.toString())
            assertEquals("업데이트", button.contentDescription.toString())
            UpdateManager.check(context)   // 맡긴 확인이 새 버전을 찾음
            shadowOf(Looper.getMainLooper()).idle()
            // 둥근 화면 아래는 좁아 글자는 그대로 짧게, 버튼이 파래지고 읽어 주기가 '새 버전 업데이트'
            assertEquals("업데이트", button.text.toString())
            assertEquals("새 버전 업데이트", button.contentDescription.toString())
            assertEquals(R.drawable.bg_action_accent, shadowOf(button.background).createdFromResId)
        }
    }

    @Test
    fun theFirstScreenHasAnUpdateButtonThatStartsTheUpdateInOneTap() {
        // '앱 정보 · 업데이트' 같은 작은 글씨 대신, 앱을 열면 바로 보이는 '업데이트' 버튼 — 누르면 확인하고 곧바로 받아 설치
        val hub = Robolectric.buildActivity(HubActivity::class.java).setup().get()
        val button = hub.findViewById<TextView>(R.id.hub_update)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isClickable)
        button.performClick()
        val started = shadowOf(hub).nextStartedActivity
        assertEquals(UpdateActivity::class.java.name, started?.component?.className)
        assertTrue(started.getBooleanExtra(UpdateActivity.EXTRA_UPDATE_NOW, false))
    }

    @Test
    fun appInfoComesBackToTheChooser() {
        // '앱 정보 · 업데이트'에서 뒤로 가면 고르는 화면으로 (첫 화면이 noHistory 면 워치 화면으로 나가 버림)
        val info = context.packageManager.getActivityInfo(ComponentName(context, HubActivity::class.java), 0)
        assertEquals(0, info.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY)
    }
}
