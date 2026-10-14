package com.mywatch.kakaomap.update

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/** 큰 업데이트를 받을 워치 Wi-Fi: 부탁하고, 오면 그 망으로, 안 오면 기다린 만큼만 — 어느 쪽이든 부탁은 꼭 거둔다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateNetTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    @Test
    fun withoutWifiNearbyItWaitsOnlyThatLongAndWithdrawsTheRequest() {
        val start = System.currentTimeMillis()
        assertNull(UpdateNet.wifi(context, waitMs = 300))
        assertTrue("기다린 만큼만", System.currentTimeMillis() - start < 2_000)
        assertTrue("부탁을 거둔다 (Wi-Fi 를 켜 둔 채 두지 않게)", shadowOf(cm).networkCallbacks.isEmpty())
    }

    @Test
    fun whenTheWifiComesUpItIsUsedAndReleasedAfterwards() {
        var lease: UpdateNet.Lease? = null
        val t = thread { lease = UpdateNet.wifi(context, waitMs = 5_000) }
        val until = System.currentTimeMillis() + 3_000
        while (shadowOf(cm).networkCallbacks.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals("Wi-Fi 를 부탁했다", 1, shadowOf(cm).networkCallbacks.size)
        shadowOf(cm).networkCallbacks.single().onAvailable(cm.activeNetwork!!)
        t.join(3_000)
        assertNotNull("Wi-Fi 가 오면 그 망을 쓴다", lease)
        assertEquals("쓰는 동안은 부탁을 유지", 1, shadowOf(cm).networkCallbacks.size)
        lease!!.close()
        assertTrue("다 쓰면 놓아준다", shadowOf(cm).networkCallbacks.isEmpty())
    }
}
