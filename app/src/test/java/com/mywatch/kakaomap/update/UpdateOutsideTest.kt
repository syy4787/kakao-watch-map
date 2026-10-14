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
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.tube.PlayerPresence
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
        UpdateNet.override = { _, _ -> null }   // 밖: 저장된 Wi-Fi 가 가까이 없음
    }

    @After
    fun tearDown() {
        UpdateManager.baseUrl = UpdateManager.GITHUB
        UpdateManager.clock = System::currentTimeMillis
        UpdateNet.override = null
        server.close()
    }

    /** 집: 워치가 저장된 Wi-Fi 를 잠깐 켤 수 있다 — 그 망으로 연 연결 수를 센다. [failAfter] 번째부터는 Wi-Fi 가 사라진 것처럼 */
    private class FakeWifi(val failAfter: Int = Int.MAX_VALUE) : UpdateNet.Lease {
        val opened = AtomicInteger()
        @Volatile var closed = false
        override fun open(url: URL): URLConnection {
            if (opened.incrementAndGet() > failAfter) throw IOException("Wi-Fi 끊김")
            return url.openConnection()
        }
        override fun close() { closed = true }
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
        server.updateJson = server.updateJsonFor(999, size = server.apk.size.toLong()).replace(FakeReleaseServer.sha256Hex(server.apk), "0".repeat(64))
        UpdateManager.check(context)
        repeat(3) { runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() } }
        assertEquals("자동으로는 두 번까지만 받아 본다", 2, server.apkRequests.size)

        // 새 릴리스(다른 파일)가 나오면 다시 자동으로 받는다
        server.apk = ByteArray(1_000) { 7 }
        server.updateJson = server.updateJsonFor(1000, size = server.apk.size.toLong())
        UpdateManager.check(context)
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(3, server.apkRequests.size)
    }

    @Test
    fun whyAnAutomaticUpdateFailedIsStillThereAfterTheNextCheck() {
        ErrorLog.init(context)
        server.updateJson = server.updateJsonFor(999, size = server.apk.size.toLong())
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
    fun aStoppedDownloadStopsRightAwayKeepsWhatItGotAndLetsGoOfTheWifi() {
        // 자동 업데이트 작업이 멈춰지면(앱을 열었다 닫아 작업이 갈아 끼워짐·배터리 부족 등) 받기도 바로 멈춰야 한다 —
        // 계속 받으면 Wi-Fi 를 붙잡고 다음 작업은 그동안 기다린다
        server.apk = ByteArray(3_000_000) { (it * 13 % 251).toByte() }
        val wifi = FakeWifi()
        UpdateNet.override = { _, _ -> wifi }
        val stop = AtomicBoolean(false)
        var progress = 0
        assertThrows(UpdateManager.Cancelled::class.java) {
            UpdateManager.download(context, info(), cancelled = { stop.get() }) { if (++progress >= 3) stop.set(true) }
        }
        val partial = File(File(context.cacheDir, "update"), UpdateManager.cacheFileName(info()))
        assertTrue("받은 데까지는 남김: ${partial.length()}", partial.length() in 1 until server.apk.size)
        assertTrue("Wi-Fi 를 놓아줌", wifi.closed)
        assertEquals("멈춘 뒤 다시 묻지 않음", 1, server.apkRequests.size)
        // 다음 번엔 받은 데부터
        val file = UpdateManager.download(context, info())
        assertEquals(FakeReleaseServer.sha256Hex(server.apk), UpdateManager.sha256(file))
        assertTrue(server.apkRequests.toString(), Regex("""bytes=[1-9]\d*-""").containsMatchIn(server.apkRequests.last()))
    }

    @Test
    fun aDownloadAlreadyStoppedDoesNotStart() {
        assertThrows(UpdateManager.Cancelled::class.java) { UpdateManager.download(context, info(), cancelled = { true }) }
        assertEquals(0, server.apkRequests.size)
    }

    @Test
    fun aManualUpdateThatFinishesAfterILeftTheScreenWaitsInsteadOfClosingWhatIAmDoing() {
        // '지금 업데이트'를 누르고 홈으로 가 유튜브를 틀었는데, 다 받은 순간 설치돼 재생이 끊기면 안 된다
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val installed = mutableListOf<File>()
        UpdateActivity.verifyApk = { _, _, _ -> null }
        UpdateActivity.installApk = { _, f -> installed += f }
        try {
            server.updateJson = server.updateJsonFor(999)
            UpdateManager.check(context)
            val hold = CountDownLatch(1).also { server.holdApk = it }
            val controller = Robolectric.buildActivity(UpdateActivity::class.java).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            tap(activity, "지금 업데이트")
            waitUntil("내려받기 시작") { server.apkRequests.isNotEmpty() }
            controller.pause().stop()   // 홈으로 나감
            hold.countDown()
            waitUntil("다 받고 미룸") { UpdateManager.lastError(context)?.contains("설치") == true }
            assertTrue("화면을 떠났으면 바로 설치하지 않는다", installed.isEmpty())
            val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork("update-install").get()
            assertTrue("앱을 다 닫으면 자동 설치가 이어서", queued.isNotEmpty())
        } finally {
            UpdateActivity.verifyApk = UpdateManager::verify
            UpdateActivity.installApk = UpdateManager::install
        }
    }

    @Test
    fun aManualUpdateInstallsRightAwayWhileIAmStillWatchingTheScreen() {
        val installed = mutableListOf<File>()
        UpdateActivity.verifyApk = { _, _, _ -> null }
        UpdateActivity.installApk = { _, f -> installed += f }
        try {
            server.updateJson = server.updateJsonFor(999)
            UpdateManager.check(context)
            val activity = Robolectric.buildActivity(UpdateActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            tap(activity, "지금 업데이트")
            waitUntil("설치") { installed.isNotEmpty() }
        } finally {
            UpdateActivity.verifyApk = UpdateManager::verify
            UpdateActivity.installApk = UpdateManager::install
        }
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
        server.updateJson = server.updateJsonFor(999, size = server.apk.size.toLong())
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
    fun atHomeABigDownloadGoesOverTheWatchsOwnWifiAndLetsItGo() {
        // APK 가 커서(재생 엔진 포함 약 110MB) 블루투스로는 10분 넘게 걸린다 — 저장된 Wi-Fi 를 잠깐 켜서 받는다
        val wifi = FakeWifi()
        var asked = 0L
        UpdateNet.override = { _, waitMs -> asked = waitMs; wifi }
        val file = UpdateManager.download(context, info())
        assertEquals(FakeReleaseServer.sha256Hex(server.apk), UpdateManager.sha256(file))
        assertEquals("APK 요청은 모두 Wi-Fi 로", server.apkRequests.size, wifi.opened.get())
        assertTrue("다 받으면 Wi-Fi 를 놓아준다 (배터리)", wifi.closed)
        assertTrue("Wi-Fi 를 켜는 데 몇 초는 기다려 준다: $asked", asked >= 5_000)
    }

    @Test
    fun ifTheWifiGoesAwayMidwayTheDownloadCarriesOnOverThePhone() {
        // 집을 나서며 Wi-Fi 가 끊김 → 받은 데까지는 두고 폰 블루투스로 이어받는다
        server.cutFirstApkAt = 120_000
        val wifi = FakeWifi(failAfter = 1)
        UpdateNet.override = { _, _ -> wifi }
        val file = UpdateManager.download(context, info())
        assertEquals(FakeReleaseServer.sha256Hex(server.apk), UpdateManager.sha256(file))
        assertTrue("이어받기: ${server.apkRequests}", server.apkRequests.drop(1).all { Regex("""bytes=[1-9]\d*-""").containsMatchIn(it) })
        assertTrue(wifi.closed)
    }

    @Test
    fun aFileThatIsNotThereIsNotAskedForAgainOverThePhone() {
        // 없는 파일(404)은 Wi-Fi 든 폰이든 같다 — 폰으로 또 묻지 않는다
        server.apkMissing = true
        val wifi = FakeWifi()
        UpdateNet.override = { _, _ -> wifi }
        assertThrows(IOException::class.java) { UpdateManager.download(context, info()) }
        assertEquals(1, server.apkRequests.size)
        assertTrue(wifi.closed)
    }

    @Test
    fun outsideOnPhoneDataASmallAutomaticUpdateDownloadsWithoutWaitingForWifiOrCharging() {
        server.updateJson = server.updateJsonFor(999, size = 13_000_000)
        UpdateManager.check(context)   // 백그라운드 확인이 새 버전을 찾음
        val worker = TestListenableWorkerBuilder<UpdateInstallWorker>(context).build()
        runBlocking { worker.doWork() }
        assertEquals("집 밖(폰 블루투스·충전 아님)에서도 내려받아야 함", 1, server.apkRequests.size)
    }

    @Test
    fun aBigAutomaticUpdateWaitsForTheWatchWifiInsteadOfPhoneData() {
        // 재생 엔진이 들어 있어 약 105MB — 폰 블루투스(폰 데이터)로 매번 받지 않는다. '지금 업데이트'는 어디서나
        server.updateJson = server.updateJsonFor(999, size = 105_000_000)
        UpdateManager.check(context)
        assertEquals(ListenableWorker.Result.success(), runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() })
        assertEquals("Wi-Fi 가 없으면 받지 않고 다음 확인 때", 0, server.apkRequests.size)
        assertTrue("실패로 남기지 않는다", UpdateManager.lastError(context) == null)
        // 집: 워치가 Wi-Fi 를 켤 수 있으면 받는다
        val wifi = FakeWifi()
        UpdateNet.override = { _, _ -> wifi }
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(1, server.apkRequests.size)
        assertEquals(1, wifi.opened.get())
    }

    @Test
    fun aBigUpdateThatWaitedTwoDaysWithoutWifiComesOverThePhone() {
        // 이틀 넘게 Wi-Fi 를 못 만나면 그때는 폰으로라도 (업데이트가 한없이 밀리지 않게)
        server.updateJson = server.updateJsonFor(999, size = 105_000_000)
        UpdateManager.check(context)
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(0, server.apkRequests.size)
        val start = System.currentTimeMillis()
        UpdateManager.clock = { start + Duration.ofDays(2).plusMinutes(1).toMillis() }
        UpdateManager.check(context)
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(1, server.apkRequests.size)
    }

    private fun autoInstallOnce() = runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }

    @Test
    fun newReleasesDoNotRestartTheTwoDayWifiWait() {
        // 새 버전은 하루에도 여러 번 나온다 — '이틀 기다림'이 새 버전마다 처음부터면 Wi-Fi 없는 워치는 영영 업데이트를 못 받는다
        val start = System.currentTimeMillis()
        UpdateManager.clock = { start }
        server.updateJson = server.updateJsonFor(999, size = 105_000_000)
        UpdateManager.check(context)
        autoInstallOnce()
        assertEquals(0, server.apkRequests.size)
        // 하루 뒤 또 새 버전
        UpdateManager.clock = { start + Duration.ofDays(1).toMillis() }
        server.apk = ByteArray(310_000) { (it * 7 % 251).toByte() }
        server.updateJson = server.updateJsonFor(1000, size = 105_000_000)
        UpdateManager.check(context)
        autoInstallOnce()
        assertEquals("아직 이틀이 안 됨", 0, server.apkRequests.size)
        // 처음 기다리기 시작한 때부터 이틀이 지나면 폰으로라도
        UpdateManager.clock = { start + Duration.ofDays(2).plusMinutes(1).toMillis() }
        autoInstallOnce()
        assertEquals(1, server.apkRequests.size)
    }

    @Test
    fun onceUpToDateTheNextBigUpdateWaitsAFreshTwoDays() {
        val start = System.currentTimeMillis()
        UpdateManager.clock = { start }
        server.updateJson = server.updateJsonFor(999, size = 105_000_000)
        UpdateManager.check(context)
        autoInstallOnce()
        // '지금 업데이트' 등으로 최신이 됨 (update.json 이 지금 버전 이하)
        server.updateJson = server.updateJsonFor(0, size = 105_000_000)
        UpdateManager.check(context)
        // 사흘 뒤 나온 큰 새 버전은 다시 Wi-Fi 부터 기다린다
        UpdateManager.clock = { start + Duration.ofDays(3).toMillis() }
        server.updateJson = server.updateJsonFor(999, size = 105_000_000)
        UpdateManager.check(context)
        autoInstallOnce()
        assertEquals(0, server.apkRequests.size)
    }

    @Test
    fun theWaitStoredByTheOldVersionStillCounts() {
        // 예전 앱은 "버전:시각" 으로 저장했다 — 버전이 달라도 그 시각부터 센다
        val start = System.currentTimeMillis()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("waiting_since", "998:$start").commit()
        val big = UpdateInfo(999, "2.899", server.apkUrl, "aa", "", sizeBytes = 105_000_000)
        assertTrue(UpdateManager.autoNeedsWifi(context, big, now = start + Duration.ofDays(1).toMillis()))
        assertFalse(UpdateManager.autoNeedsWifi(context, big, now = start + Duration.ofDays(2).plusMinutes(1).toMillis()))
    }

    @Test
    fun aWaitStartTimeInTheFutureIsNotTrusted() {
        // 시계를 바꿨거나 잘못 저장된 미래 시각이면 지금부터 센다 (이틀 + 엉뚱한 만큼 밀리지 않게)
        val now = System.currentTimeMillis()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("waiting_since", (now + Duration.ofDays(30).toMillis()).toString()).commit()
        val big = UpdateInfo(999, "2.899", server.apkUrl, "aa", "", sizeBytes = 105_000_000)
        assertTrue(UpdateManager.autoNeedsWifi(context, big, now = now))
        assertFalse(UpdateManager.autoNeedsWifi(context, big, now = now + Duration.ofDays(2).plusMinutes(1).toMillis()))
    }

    @Test
    fun aBigUpdateWhoseWifiDropsMidwayWaitsInsteadOfFinishingOverThePhone() {
        server.updateJson = server.updateJsonFor(999, size = 105_000_000)
        server.cutFirstApkAt = 120_000
        UpdateManager.check(context)
        UpdateNet.override = { _, _ -> FakeWifi(failAfter = 1) }
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals("Wi-Fi 로 받다 끊기면 거기서 멈춘다 (받은 데까지는 남김)", 1, server.apkRequests.size)
        // 다음에 Wi-Fi 를 만나면 이어받는다
        UpdateNet.override = { _, _ -> FakeWifi() }
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertTrue(server.apkRequests.toString(), Regex("""bytes=[1-9]\d*-""").containsMatchIn(server.apkRequests.last()))
    }

    @Test
    fun theUpdateJsonCarriesTheSizeAndItSurvivesBeingRemembered() {
        val info = UpdateInfo.parse("""{"versionCode":5,"versionName":"2.5","apkUrl":"u","sha256":"AA","notes":"",""" +
            """"apks":{"armeabi-v7a":{"url":"v7","sha256":"BB","size":104528312}},"size":186413091}""", listOf("armeabi-v7a"))
        assertEquals(104_528_312L, info.sizeBytes)
        assertEquals(104_528_312L, UpdateInfo.parse(info.toJson(), listOf("armeabi-v7a")).sizeBytes)
        assertEquals("모든 CPU 용이면 그 크기", 186_413_091L, UpdateInfo.parse("""{"versionCode":5,"apkUrl":"u","sha256":"AA","size":186413091}""", listOf("x86")).sizeBytes)
        assertEquals("예전 update.json 엔 크기가 없다", null, UpdateInfo.parse("""{"versionCode":5,"apkUrl":"u","sha256":"AA"}""", listOf("x86")).sizeBytes)
    }
}
