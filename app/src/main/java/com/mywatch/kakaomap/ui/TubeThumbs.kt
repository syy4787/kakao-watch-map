package com.mywatch.kakaomap.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.mywatch.kakaomap.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 유튜브 미리보기 그림을 받아 목록에 넣는다 (라이브러리 없이).
 * 워치는 블루투스로 폰 인터넷을 빌려 쓰므로 작게(160×90, RGB_565) 받아 메모리에만 잠깐 둔다 — 파일로 남기지 않는다.
 *  · 같은 그림은 한 번만 받고, 목록을 빨리 넘겨 이미 지나간 칸의 그림은 받지 않는다(지금 보이는 칸 먼저)
 *  · 연결이 잠깐 끊겨 못 받은 그림은 다음에 다시 시도, 없는 그림(404)·깨진 그림만 다시 받지 않는다
 *  · 칸(화면)은 약하게만 잡아 닫힌 화면이 메모리에 남지 않게
 */
object TubeThumbs {

    /** 화면 그림 테스트에서는 네트워크를 쓰지 않는다 */
    @VisibleForTesting
    @Volatile
    var enabled = true

    private const val MAX_BYTES = 400 * 1024
    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** 없거나 깨진 그림 (앱이 살아 있는 동안 다시 받지 않음) */
    private val broken: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    /** 받는 중인 그림 → 그 그림을 기다리는 칸들 (메인 스레드에서만 만짐) */
    private val waiting = HashMap<String, MutableList<WeakReference<ImageView>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Dispatchers.IO.limitedParallelism(3)

    private sealed class Fetch {
        class Ok(val bitmap: Bitmap) : Fetch()
        /** 없음·깨짐 — 다시 받지 않는다 */
        object Broken : Fetch()
        /** 연결 문제 — 다음에 다시 */
        object Retry : Fetch()
        /** 받기 전에 그 칸이 다른 영상으로 바뀜 */
        object Skipped : Fetch()
    }

    @MainThread
    fun into(view: ImageView, url: String) {
        view.setTag(R.id.thumb, url)
        val hit = cache.get(url)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        if (!enabled || url in broken) return
        waiting[url]?.let { views ->
            views += WeakReference(view)   // 이미 받는 중 — 끝나면 같이 넣는다
            return
        }
        waiting[url] = mutableListOf(WeakReference(view))
        scope.launch {
            val result = withContext(io) {
                // 차례를 기다리는 사이 칸이 지나갔으면 받지 않는다
                if (!withContext(Dispatchers.Main) { wanted(url) }) Fetch.Skipped else fetch(url)
            }
            val views = waiting.remove(url).orEmpty()
            when (result) {
                is Fetch.Ok -> {
                    cache.put(url, result.bitmap)
                    views.forEach { ref -> ref.get()?.takeIf { it.getTag(R.id.thumb) == url }?.setImageBitmap(result.bitmap) }
                }
                Fetch.Broken -> broken += url
                Fetch.Retry, Fetch.Skipped -> Unit
            }
        }
    }

    @MainThread
    private fun wanted(url: String): Boolean = waiting[url]?.any { it.get()?.getTag(R.id.thumb) == url } == true

    private fun fetch(url: String): Fetch = try {
        // disconnect() 하지 않는다 — 다 읽고 닫으면 연결을 다음 그림에 다시 쓴다(블루투스로 매번 새로 잇는 것보다 빠름)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        val code = conn.responseCode
        when {
            code !in 200..299 -> {
                conn.errorStream?.close()
                if (code in 400..499) Fetch.Broken else Fetch.Retry
            }
            conn.contentLength > MAX_BYTES -> {
                conn.inputStream.close()
                Fetch.Broken
            }
            else -> {
                val bytes = conn.inputStream.use(::readLimited)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2                              // 320×180 → 160×90 (워치 칸 58dp ≈ 123px)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
                if (bmp != null) Fetch.Ok(bmp) else Fetch.Broken
            }
        }
    } catch (e: IOException) {
        Fetch.Retry
    }

    /** 너무 크면 null */
    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
    }
}
