package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.TokenStore
import com.mywatch.kakaomap.tube.Tokens
import com.mywatch.kakaomap.tube.TubeAccount
import com.mywatch.kakaomap.tube.TubeAuth
import com.mywatch.kakaomap.tube.TubeRevokeWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** 인터넷이 없을 때 로그아웃해 못 보낸 철회를 인터넷이 되면 보낸다 (점검 C4) — 다 보낼 때까지 다시 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TubeRevokeWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private class Store : TokenStore {
        var revokes = listOf("RT-OLD")
        override fun load(): Tokens? = null
        override fun save(t: Tokens) = Unit
        override fun clear() = Unit
        override fun pendingRevokes() = revokes
        override fun setPendingRevokes(tokens: List<String>) { revokes = tokens }
    }

    @After
    fun tearDown() {
        TubeAccount.override = null
    }

    @Test
    fun itRetriesUntilTheRevokeIsSent() = runBlocking {
        val store = Store()
        var online = false
        val sent = mutableListOf<String>()
        TubeAccount.override = TubeAuth("id", "secret", { url, form ->
            if (!online) throw IOException("offline")
            sent += url + " " + form["token"]
            Http.Response(200, "")
        }, store)
        val worker = TestListenableWorkerBuilder<TubeRevokeWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(listOf("RT-OLD"), store.revokes)
        online = true
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<TubeRevokeWorker>(context).build().doWork())
        assertTrue(store.revokes.isEmpty())
        assertEquals(listOf("https://oauth2.googleapis.com/revoke RT-OLD"), sent)
    }
}
