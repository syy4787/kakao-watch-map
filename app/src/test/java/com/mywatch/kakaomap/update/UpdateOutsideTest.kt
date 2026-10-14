package com.mywatch.kakaomap.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Looper
import android.view.WindowManager
import androidx.wear.widget.WearableRecyclerView
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.PlayerPresence
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 집 밖(카페·길)에서도 업데이트가 되는지 — 워치는 폰 블루투스로 인터넷을 쓰고(느리고 잠깐씩 끊김), 충전 중도 아니다.
 *  · 새 버전이 나왔으면 업데이트 화면을 여는 순간 알아서 확인해 '지금 업데이트'가 보인다
 *  · 받는 동안 화면이 꺼지지 않는다 (꺼지면 느린 블루투스 내려받기가 멈출 수 있음)
 *  · 받다 끊기면 같은 한 번의 누름 안에서 받은 데부터 이어받는다
 *  · 자동 설치도 Wi-Fi·충전을 기다리지 않는다 (사용자가 폰 데이터로 받기로 함)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateOutsideTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val server = FakeReleaseServer()

    @Before
    fun setUp() {
        server.apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
        UpdateManager.baseUrl = server.base
        UpdateManager.setOwner(context, "tester")
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        outsideOnPhoneBluetooth()
    }

    @After
    fun tearDown() {
        UpdateManager.baseUrl = UpdateManager.GITHUB
        server.close()
    }

    /** 폰 블루투스로 인터넷 (Wi-Fi 아님 — 무제한으로 잡혀도), 충전 중 아님 */
    private fun outsideOnPhoneBluetooth() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
        )
    }

    private fun info() = UpdateInfo(999, "2.899", server.apkUrl, FakeReleaseServer.sha256Hex(server.apk), "")

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("5초 안에 안 됨: $what")
    }

    private fun rows(activity: UpdateActivity): List<Row> =
        (activity.findViewById<WearableRecyclerView>(R.id.list).adapter as RowAdapter).rows

    private fun actionTexts(activity: UpdateActivity) = rows(activity).filterIsInstance<Row.Action>().map { it.text }

    private fun keepsScreenOn(activity: UpdateActivity) =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun tap(activity: UpdateActivity, text: String) {
        val list = activity.findViewById<WearableRecyclerView>(R.id.list)
        val pos = rows(activity).indexOfFirst { it is Row.Action && it.text == text }
        assertTrue("'$text' 버튼이 없음: ${actionTexts(activity)}", pos >= 0)
        list.measure(480, 480)
        list.layout(0, 0, 480, 480)
        list.scrollToPosition(pos)
        list.measure(480, 480)   // 스크롤은 다시 재고 놓아야 적용된다
        list.layout(0, 0, 480, 480)
        val holder = list.findViewHolderForAdapterPosition(pos) ?: throw AssertionError("'$text' 줄이 그려지지 않음")
        holder.itemView.performClick()
    }

    @Test
    fun openingTheUpdateScreenLooksForTheNewVersionSoTheUpdateButtonAppears() {
        // v117 이 나왔지만 워치는 아직 모른다 (마지막 확인이 그 전)
        server.updateJson = server.updateJsonFor(999)
        val activity = Robolectric.buildActivity(UpdateActivity::class.java).setup().get()
        waitUntil("업데이트 화면을 열면 새 버전을 확인해 '지금 업데이트'가 보임 (지금: ${actionTexts(activity)})") {
            "지금 업데이트" in actionTexts(activity)
        }
    }

    @Test
    fun theScreenStaysOnWhileTheUpdateDownloads() {
        server.updateJson = server.updateJsonFor(999)
        UpdateManager.check(context)   // 방금 확인해 새 버전을 알고 있음
        val hold = CountDownLatch(1).also { server.holdApk = it }
        val activity = Robolectric.buildActivity(UpdateActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        tap(activity, "지금 업데이트")
        waitUntil("내려받기 시작") { server.apkRequests.isNotEmpty() }
        assertTrue("받는 동안 화면이 켜져 있어야 함", keepsScreenOn(activity))
        hold.countDown()
        // 테스트 파일은 진짜 APK 가 아니라 확인에서 걸린다 → 받기가 끝나 화면 켜 두기도 풀린다
        waitUntil("받기 끝") { rows(activity).any { it is Row.Note && it.text.startsWith("⚠") } }
        assertFalse("다 받은 뒤엔 화면 켜 두기를 푼다", keepsScreenOn(activity))
    }

    @Test
    fun theUpdateButtonChecksAndStartsDownloadingWithoutAnotherTap() {
        // 첫 화면의 '업데이트' 버튼으로 열면: 확인 → 새 버전이면 그대로 받아 설치 (버튼을 또 찾아 누르지 않게)
        server.updateJson = server.updateJsonFor(999)
        val intent = Intent(context, UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_UPDATE_NOW, true)
        val activity = Robolectric.buildActivity(UpdateActivity::class.java, intent).setup().get()
        waitUntil("확인하고 바로 내려받기 시작") { server.apkRequests.isNotEmpty() }
        // 테스트 파일은 진짜 APK 가 아니라 설치 전 확인에서 걸린다
        waitUntil("받기 끝") { rows(activity).any { it is Row.Note && it.text.startsWith("⚠") } }
    }

    @Test
    fun theUpdateButtonWithNothingNewJustSaysItIsUpToDate() {
        server.updateJson = server.updateJsonFor(1)   // 워치에 깔린 것보다 옛 버전
        val intent = Intent(context, UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_UPDATE_NOW, true)
        val activity = Robolectric.buildActivity(UpdateActivity::class.java, intent).setup().get()
        waitUntil("최신 버전이라고 알림") { rows(activity).any { it is Row.Note && it.text == "최신 버전이에요" } }
        assertTrue(server.apkRequests.isEmpty())
    }

    @Test
    fun aDownloadCutHalfwayResumesFromWhereItStoppedInTheSameTap() {
        server.cutFirstApkAt = 120_000
        val file = UpdateManager.download(context, info())
        assertEquals(FakeReleaseServer.sha256Hex(server.apk), UpdateManager.sha256(file))
        assertEquals(server.requests.toString(), 2, server.apkRequests.size)
        val second = server.apkRequests[1]
        assertTrue("두 번째는 받은 데부터 이어받기여야 함: $second", Regex("""bytes=[1-9]\d*-""").containsMatchIn(second))
    }

    @Test
    fun aConnectionResetHalfwayAlsoResumes() {
        // 워치(OkHttp)는 끊기면 읽다가 예외가 난다 — 그 길로도 이어받아야 함
        server.resetFirstApkAt = 120_000
        val file = UpdateManager.download(context, info())
        assertEquals(FakeReleaseServer.sha256Hex(server.apk), UpdateManager.sha256(file))
        assertEquals(server.requests.toString(), 2, server.apkRequests.size)
        assertTrue(server.apkRequests[1], Regex("""bytes=[1-9]\d*-""").containsMatchIn(server.apkRequests[1]))
    }

    @Test
    fun aCorruptReleaseIsNotDownloadedOverAndOverOnPhoneData() {
        // update.json 의 SHA-256 과 실제 파일이 다르다(손으로 바꾼 파일 등) — 받을 때마다 13MB 를 버리게 된다
        server.updateJson = server.updateJsonFor(999).replace(FakeReleaseServer.sha256Hex(server.apk), "0".repeat(64))
        UpdateManager.check(context)
        repeat(3) { runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() } }
        assertEquals("자동으로는 두 번까지만 받아 본다", 2, server.apkRequests.size)

        // 새 릴리스(다른 파일)가 나오면 다시 자동으로 받는다
        server.apk = ByteArray(1_000) { 7 }
        server.updateJson = server.updateJsonFor(1000)
        UpdateManager.check(context)
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(3, server.apkRequests.size)
    }

    @Test
    fun whyAnAutomaticUpdateFailedIsStillThereAfterTheNextCheck() {
        ErrorLog.init(context)
        server.updateJson = server.updateJsonFor(999)
        server.apkMissing = true
        UpdateManager.check(context)
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        // 업데이트 화면을 열면 다시 확인한다 — 화면 위쪽 ⚠ 문구는 그때 지워지므로 '최근 오류 기록'에 남아야 한다
        UpdateManager.check(context)
        val logged = ErrorLog.entries().map { "${it.where}: ${it.message}" }
        assertTrue(logged.toString(), logged.any { it.contains("다운로드 실패 (404)") })
    }

    @Test
    fun tappingUpdateWhileTheAutomaticUpdateIsAlreadyDownloadingSaysSo() {
        server.updateJson = server.updateJsonFor(999)
        UpdateManager.check(context)
        val hold = CountDownLatch(1).also { server.holdApk = it }
        val background = thread { runCatching { UpdateManager.download(context, info()) } }
        waitUntil("자동 업데이트가 받기 시작") { server.apkRequests.isNotEmpty() }
        val activity = Robolectric.buildActivity(UpdateActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        tap(activity, "지금 업데이트")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(rows(activity).toString(), rows(activity).any { it is Row.Note && it.text.contains("자동 업데이트가 받는 중") })
        hold.countDown()
        background.join(5_000)
        waitUntil("받기 끝") { rows(activity).any { it is Row.Note && it.text.startsWith("⚠") } }
        assertEquals("같은 파일을 두 번 받지 않는다", 1, server.apkRequests.size)
    }

    @Test
    fun aMissingFileIsNotAskedForAgainAndAgain() {
        server.apkMissing = true
        assertThrows(IOException::class.java) { UpdateManager.download(context, info()) }
        assertEquals(1, server.apkRequests.size)
    }

    @Test
    fun aVideoPlayingInTheAppIsNotCutOffByAnAutomaticUpdate() {
        // 재생 화면은 따로 된 프로세스(:player) — 이 프로세스의 '화면 수'로는 안 보인다
        server.updateJson = server.updateJsonFor(999)
        UpdateManager.check(context)
        val presence = PlayerPresence.of(context)
        presence.shown()
        val worker = TestListenableWorkerBuilder<UpdateInstallWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
        assertEquals("동영상을 보는 동안은 받지도 않는다", 0, server.apkRequests.size)
        // 재생 화면을 닫은 직전에도 (잠깐 목록을 보고 다시 틀 수 있음) 10분은 기다린다
        presence.hidden()
        assertEquals(ListenableWorker.Result.retry(), runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() })
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(1, server.apkRequests.size)
    }

    @Test
    fun outsideOnPhoneDataTheAutomaticUpdateDownloadsWithoutWaitingForWifiOrCharging() {
        server.updateJson = server.updateJsonFor(999)
        UpdateManager.check(context)   // 백그라운드 확인이 새 버전을 찾음
        val worker = TestListenableWorkerBuilder<UpdateInstallWorker>(context).build()
        runBlocking { worker.doWork() }
        assertEquals("집 밖(폰 블루투스·충전 아님)에서도 내려받아야 함", 1, server.apkRequests.size)
    }
}
