package com.mywatch.kakaomap.update

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.wear.widget.WearableRecyclerView
import androidx.work.testing.TestListenableWorkerBuilder
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * 작은 업데이트: 재생 엔진이 들어 있어 APK 가 약 105MB 인데, 버전마다 바뀌는 건 코드 몇 MB 뿐이다.
 * 워치에 깔린 APK 와 맞는 패치가 있으면 그것만 받아 새 APK 를 똑같이 다시 만든다 — 폰 블루투스로도 금방, Wi-Fi 를 기다리지 않고.
 * 패치가 깨졌거나 안 맞으면 전체 APK 로 (업데이트가 막히지 않게). 패치 파일은 CI 의 make-deltas.py 가 만든 것(시험 자원)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateDeltaTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val server = FakeReleaseServer()
    private val dir = File(javaClass.getResource("/delta/old.apk")!!.toURI()).parentFile!!
    private val installed = File(dir, "old.apk")
    private val target = File(dir, "new.apk")
    private val patch = File(dir, "new-from-old.kwdelta")
    private val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha(f: File) = sha(f.readBytes())

    @Before
    fun setUp() {
        server.apk = target.readBytes()
        server.files["p.kwdelta"] = patch.readBytes()
        UpdateManager.baseUrl = server.base
        UpdateManager.setOwner(context, "tester")
        UpdateManager.installedApk = { installed }
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        UpdateNet.override = { _, _ -> null }   // 밖: Wi-Fi 없음
        ErrorLog.init(context)
    }

    @After
    fun tearDown() {
        UpdateManager.retryDelaysMs = UpdateManager.DEFAULT_RETRY_DELAYS_MS
        UpdateManager.baseUrl = UpdateManager.GITHUB
        UpdateManager.installedApk = { c -> c.applicationInfo?.sourceDir?.let(::File)?.takeIf { it.canRead() } }
        UpdateNet.override = null
        server.close()
    }

    private fun updateJson(from: String = sha(installed), patchSha: String = sha(patch)) =
        """{"versionCode":999,"versionName":"2.899","apkUrl":"${server.apkUrl}","sha256":"${sha(target)}","size":186000000,""" +
            """"notes":"t","apks":{"$abi":{"url":"${server.apkUrl}","sha256":"${sha(target)}","size":105000000,""" +
            """"patches":[{"from":"$from","url":"${server.fileUrl("p.kwdelta")}","sha256":"$patchSha","size":${patch.length()}}]}}}"""

    private fun latest() = UpdateManager.latest(context)!!
    private fun patchRequests() = server.requests.count { it.contains("/files/p.kwdelta") }

    @Test
    fun onlyTheChangedPartsAreDownloadedAndTheNewApkIsRebuiltExactly() {
        server.updateJson = updateJson()
        UpdateManager.check(context)
        val file = UpdateManager.download(context, latest())
        assertEquals(sha(target), UpdateManager.sha256(file))
        assertEquals("전체 APK 는 받지 않음", 0, server.apkRequests.size)
        assertEquals(1, patchRequests())
    }

    @Test
    fun aBigUpdateWithASmallPatchDoesNotWaitForWifi() {
        // 전체로는 105MB 라 자동 업데이트가 Wi-Fi 를 기다리지만, 작은 업데이트는 폰 블루투스로도 바로
        server.updateJson = updateJson()
        UpdateManager.check(context)
        runBlocking { TestListenableWorkerBuilder<UpdateInstallWorker>(context).build().doWork() }
        assertEquals(1, patchRequests())
        assertEquals(0, server.apkRequests.size)
        // Wi-Fi 를 기다린 것이 아니므로 '이틀 기다림'은 세기 시작하지 않는다 (리뷰에서 찾음: 패치로 받아도 시계가 돌아
        // 릴리스가 잦은 며칠 뒤 패치가 한 번 안 맞으면 105MB 를 바로 폰으로 받았다)
        assertFalse(context.getSharedPreferences("updates", Context.MODE_PRIVATE).contains("waiting_since"))
    }

    @Test
    fun aCorruptPatchFallsBackToTheFullApkAndIsNotTriedAgain() {
        server.files["p.kwdelta"] = patch.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        server.updateJson = updateJson()
        UpdateManager.check(context)
        val file = UpdateManager.download(context, latest())
        assertEquals(sha(target), UpdateManager.sha256(file))
        assertEquals(1, server.apkRequests.size)
        assertTrue(ErrorLog.entries().toString(), ErrorLog.entries().any { it.where == "작은 업데이트" })
        // 다음에 또 받을 일이 생겨도 같은 패치는 다시 받지 않는다
        file.delete()
        UpdateManager.download(context, latest())
        assertEquals(1, patchRequests())
        assertEquals(2, server.apkRequests.size)
    }

    /**
     * 리뷰에서 찾음: 작은 업데이트를 받다 폰 연결이 잠깐 끊기면(재시도까지 다 실패) '깨진 패치'로 보고 그 릴리스는 다시 쓰지 않고
     * 받다 만 패치도 지운 채 바로 105MB 전체 APK 를 폰으로 받았다 → 끊긴 것은 실패로 알리고 받은 데까지 두었다가 다음에 이어받는다
     */
    @Test
    fun aDroppedConnectionWhileGettingThePatchKeepsItForNextTimeInsteadOfPullingTheFullApk() {
        UpdateManager.retryDelaysMs = listOf(0L, 0L, 0L)
        server.updateJson = updateJson()
        UpdateManager.check(context)
        server.filesDown = 4   // 처음 받기 + 다시 잇기 3번 모두 끊김
        try {
            UpdateManager.download(context, latest())
            org.junit.Assert.fail("끊겼으면 이번엔 실패")
        } catch (e: java.io.IOException) {
            assertFalse(e is UpdateManager.NeedsWifi)
        }
        assertEquals("전체 APK 로 넘어가지 않는다", 0, server.apkRequests.size)
        assertTrue("다음에 이어받으려고 받은 데까지 남긴다", File(context.cacheDir, "update").listFiles().orEmpty().any { it.name.endsWith(".kwdelta") && it.length() > 0 })
        // 다음 번: 같은 패치를 받은 데부터 이어받아 끝낸다
        val file = UpdateManager.download(context, latest())
        assertEquals(sha(target), UpdateManager.sha256(file))
        assertEquals(0, server.apkRequests.size)
        assertTrue(server.requests.toString(), server.requests.any { it.contains("/files/p.kwdelta (bytes=") })
    }

    @Test
    fun aPatchForAnotherInstalledApkIsNotUsed() {
        server.updateJson = updateJson(from = "0".repeat(64))
        UpdateManager.check(context)
        val file = UpdateManager.download(context, latest())
        assertEquals(sha(target), UpdateManager.sha256(file))
        assertEquals(0, patchRequests())
        assertEquals(1, server.apkRequests.size)
    }

    @Test
    fun aPatchThatDoesNotRebuildThisVersionIsRefusedAndTheFullApkIsUsed() {
        // 패치는 멀쩡한데 update.json 의 새 APK 와 다른 것을 만든다(잘못 올린 릴리스) → 설치하지 않고 전체로
        server.apk = target.readBytes() + byteArrayOf(1, 2, 3)
        server.updateJson = updateJson().replace(sha(target), sha(server.apk))
        UpdateManager.check(context)
        val file = UpdateManager.download(context, latest())
        assertEquals(sha(server.apk), UpdateManager.sha256(file))
        assertEquals(1, patchRequests())
        assertEquals(1, server.apkRequests.size)
    }

    @Test
    fun aPatchMissingOnTheServerFallsBackToTheFullApk() {
        server.files.clear()
        server.updateJson = updateJson()
        UpdateManager.check(context)
        val file = UpdateManager.download(context, latest())
        assertEquals(sha(target), UpdateManager.sha256(file))
        assertEquals(1, server.apkRequests.size)
    }

    @Test
    fun patchesAreRememberedWithTheRest() {
        server.updateJson = updateJson()
        UpdateManager.check(context)
        val p = latest().patches.single()
        assertEquals(sha(installed), p.from)
        assertEquals(patch.length(), p.size)
        assertEquals(UpdateInfo.parse(latest().toJson(), listOf(abi)).patches, latest().patches)
        assertFalse("다른 CPU 용 패치는 안 읽음", UpdateInfo.parse(updateJson(), listOf("x86_99")).patches.isNotEmpty())
    }

    @Test
    fun theUpdateScreenSaysItOnlyDownloadsWhatChanged() {
        server.updateJson = updateJson()
        UpdateManager.check(context)
        val activity = Robolectric.buildActivity(UpdateActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val rows = (activity.findViewById<WearableRecyclerView>(R.id.list).adapter as RowAdapter).rows
        val button = rows.filterIsInstance<Row.Action>().single { it.text == "지금 업데이트" }
        assertEquals("1MB 미만 · 바뀐 부분만 받아요", button.sub)
    }
}
