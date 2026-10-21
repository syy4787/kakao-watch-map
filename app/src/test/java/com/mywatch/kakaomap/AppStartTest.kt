package com.mywatch.kakaomap

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mywatch.kakaomap.update.UpdateWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 앱 프로세스가 뒤에서(15분 배터리 기록·타일·업데이트 확인) 뜰 때 하는 일은 가볍게:
 * 16MB 지도 엔진(네이티브)은 지도 화면을 열 때만 읽고, 6시간 업데이트 확인 예약은 이미 있으면 건드리지 않는다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MapApp::class)
class AppStartTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    companion object {
        /** 시험들이 한 프로세스를 같이 쓰므로, 앞에서 지도 화면을 연 시험이 있어도 이 시험의 앱 시작은 새 프로세스처럼 (@BeforeClass 는 앱 생성 전에 돈다) */
        @BeforeClass
        @JvmStatic
        fun freshProcess() = MapApp.resetMapSdkForTest()
    }

    @Test
    fun aBackgroundStartDoesNotLoadTheMapEngine() {
        assertFalse("앱 시작만으로 지도 엔진을 읽으면 안 됨", MapApp.mapSdkTried)
        // 지도 화면이 부르면 한 번만 시도 (이 시험 환경엔 ARM 엔진이 없어 실패로 기억)
        val first = MapApp.ensureMapSdk(context)
        assertTrue(MapApp.mapSdkTried)
        assertSame(first, MapApp.ensureMapSdk(context))
        assertSame(first, MapApp.initError)
    }

    @Test
    fun theUpdateCheckIsNotRescheduledOnEveryStart() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val wm = WorkManager.getInstance(context)
        UpdateWorker.schedule(context)
        val first = wm.getWorkInfosForUniqueWork(UpdateWorker.NAME).get().single()
        // 다음 시작들: 이미 같은 예약이 있으면 그대로 (UPDATE 로 다시 넣으면 시스템 작업을 지웠다 다시 만든다)
        repeat(3) { UpdateWorker.schedule(context) }
        val again = wm.getWorkInfosForUniqueWork(UpdateWorker.NAME).get().single()
        assertEquals(first.id, again.id)
        assertEquals(first.generation, again.generation)
        assertEquals(WorkInfo.State.ENQUEUED, again.state)
    }
}
