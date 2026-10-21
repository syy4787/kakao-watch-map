package com.mywatch.kakaomap

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.expression.VersionBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.tiles.renderer.TileRenderer
import com.mywatch.battery.core.Forecast
import com.mywatch.battery.core.TodoText
import com.mywatch.kakaomap.tile.BatteryTileService
import com.mywatch.kakaomap.tile.TileText
import com.mywatch.kakaomap.tile.batteryTileLayout
import com.mywatch.kakaomap.tile.textWidthDp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 배터리 타일 글이 44mm(226dp)·40mm(203dp)·작은 원형(192·186dp)에서, 글꼴 크기를 키워도, 잘리지 않고 줄이 바뀌어도 낱말 가운데서
 * 갈라지지 않는지 — 타일 렌더러로 실제 타일처럼 그려(창에 붙여) 잰다. 타일은 시스템이 그려 앱 테마의 낱말 단위 줄바꿈이 안 먹어
 * '않습'/'니다', '원래'/'대로'로 갈라졌다 (렌더로 찾음).
 * 워치처럼: 기기 값에 글꼴 배율과 렌더러 버전을 준다(안 주면 글꼴 배율 0 → '자세히' 단추 글 크기가 무한대, 렌더러 1.0 → 예전 단추 모양).
 * 렌더러 버전은 최근 워치 어림(1.500)과 예전 모양(1.0) 둘 다. 안드로이드 15·16 둘 다 — 워치9(Wear OS 6~7 = 안드로이드 16~17)와 같거나
 * 가장 가까운 16 에서 M3 가 16 이상의 시스템 글꼴 토큰을 쓴다 (Robolectric 은 아직 17 이 없다).
 * 글꼴은 AOSP 것이라 삼성 글꼴과 조금 다를 수 있다 — 넉넉히 본다
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35, 36], qualifiers = "ko-rKR-w226dp-h226dp-round-340dpi", application = Application::class)
class BatteryTileFitTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val zone = ZoneId.of("Asia/Seoul")
    private val now = at(21, 21, 50)
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** 예상 줄은 실제 [Forecast] 글로 (작은 워치용 한 줄도) */
    private fun snap(title: String, rate: String, f: Forecast?, status: String, fallback: String = BatteryTileService.FORECAST_LATER) =
        BatteryTileService.Snapshot(title, rate, f?.short(zone, now) ?: fallback, status, f?.compact(zone, now) ?: fallback)

    private val tomorrow = Forecast(55, 4.0, at(22, 3, 24), at(22, 11, 40))          // 내일 15% 새벽 3:24 · 0% 오전 11:40
    private val tonight = Forecast(31, 12.5, at(21, 22, 5), at(22, 2, 10))           // 15% 오후 10:05 · 0% 내일 새벽 2:10
    private val later = Forecast(64, 1.2, at(22, 12, 40), at(23, 3, 5))              // 15% 내일 오후 12:40 · 0% 10/23 새벽 3:05
    /** 가장 긴 예상 — 천천히 닳아 양쪽 다 날짜가 붙음 */
    private val farOff = Forecast(80, 0.4, at(27, 12, 40), at(29, 12, 40))           // 15% 10/27 오후 12:40 · 0% 10/29 오후 12:40

    /** 줄마다 가장 긴 글 — 코드가 실제로 만드는 문구로 */
    private val cases: List<BatteryTileService.Snapshot> = run {
        val t = BatteryTileService
        val longApp = TodoText.fitTitle("Samsung Galaxy Watch Manager Plugin", " 업데이트 뒤 더 닳음")
        listOf(
            snap("배터리 55%", "시간당 12.5% · 비정상", tomorrow, t.STATUS_NIGHT),
            snap("배터리 31%", "시간당 4.0% · 주의", tonight, "Play 서비스 과소모 — 캐시 지우기"),
            snap("충전 중 100%", t.RATE_CHARGING, null, longApp, t.FORECAST_CHARGING),
            snap("충전 중 100%", "하루 평균 시간당 12.5%", null, t.STATUS_ABNORMAL_SAVER, t.FORECAST_CHARGING),
            snap("배터리 8%", t.RATE_COLLECTING, null, "잔량 표시가 25포인트 낮음 — 실제론 더 남음"),
            snap("배터리 64%", t.RATE_UNKNOWN, null, "시계 화면이 많이 씀 — 기본 화면으로"),
            snap("배터리 64%", t.RATE_UNKNOWN, null, "Play 서비스 과소모 — 캐시 지우기"),
            snap("배터리 64%", "시간당 1.2% · 정상", later, t.STATUS_ABNORMAL),
            snap("배터리 64%", "시간당 1.2% · 정상", later, "잔량 표시가 38포인트 높음 — 미리 충전"),
            snap("배터리 92%", "시간당 0.4% · 정상", farOff, "One UI Watch 홈 과소모 — 강제 재시작"),
            // 재시작해도 그대로일 때의 다음 단계 줄
            snap("배터리 92%", "시간당 0.4% · 정상", farOff, "One UI Watch 홈 과소모 — 홈 캐시 지우기"),
            snap("배터리 31%", "시간당 4.0% · 주의", tonight, "Play 서비스 과소모 — 캐시·PC 치료"),
        )
    }

    /** 최근 워치의 렌더러 어림 (예전 단추 모양은 1.303 아래) */
    private val modern = 500
    private val old = 0

    private class Drawn(val root: ViewGroup, val device: DeviceParametersBuilders.DeviceParameters)

    @After
    fun normalFont() = RuntimeEnvironment.setFontScale(1f)

    private fun inflate(widthDp: Int, s: BatteryTileService.Snapshot, fontScale: Float = 1f, rendererMinor: Int = modern): Drawn {
        RuntimeEnvironment.setQualifiers("+w${widthDp}dp-h${widthDp}dp")
        RuntimeEnvironment.setFontScale(fontScale)
        val density = app.resources.displayMetrics.density
        val device = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(widthDp).setScreenHeightDp(widthDp).setScreenDensity(density).setFontScale(fontScale)
            .setRendererSchemaVersion(VersionBuilders.VersionInfo.Builder().setMajor(1).setMinor(rendererMinor).build())
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
            .build()
        val root = materialScope(app, device, allowDynamicTheme = false) { batteryTileLayout(app, s) }
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(root).build()
        // 창에 붙여 그린다 — 렌더러는 붙은 뷰에서만 그린 뒤 줄 수를 다시 맞춘다
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().requestWindowFeature(Window.FEATURE_NO_TITLE)
        controller.setup()
        val activity = controller.get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val future = TileRenderer(activity, ContextCompat.getMainExecutor(activity)) { }
            .inflateAsync(layout, ResourceBuilders.Resources.Builder().setVersion("1").build(), parent)
        shadowOf(Looper.getMainLooper()).idle()
        future.get(10, TimeUnit.SECONDS)
        // 화면 그리기(Choreographer)는 다음 프레임에 — 그리고 렌더러가 그린 뒤 줄 수를 다시 맞추면 한 번 더
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val px = parent.width
        assertTrue("창 크기 $px", kotlin.math.abs(px - widthDp * density) <= 1f)
        // 렌더러는 그린 뒤 칸 높이에 맞춰 줄 수(maxLines)를 고치고 글을 다시 넣는다(새 렌더러의 '칸에 맞춰 …') — 글 배치는 그릴 때
        // 다시 만들어지므로 실제로 한 번 그려 그 모습을 잰다
        android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888).let { parent.draw(android.graphics.Canvas(it)) }
        return Drawn(parent, device)
    }

    /** 화면(타일 뿌리) 기준 왼쪽 위 — 부모를 따라 올라가며 더한다 (옮김 포함) */
    private fun origin(v: View, root: View): FloatArray {
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left + cur.translationX - cur.scrollX
            y += cur.top + cur.translationY - cur.scrollY
            cur = cur.parent as? View
        }
        return floatArrayOf(x, y)
    }

    /** 눌리는 것 — 타일엔 아래 '자세히' 단추 하나 */
    private fun clickables(v: View): List<View> =
        (if (v.isClickable) listOf(v) else emptyList()) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { clickables(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertFits(widthDp: Int, s: BatteryTileService.Snapshot, fontScale: Float = 1f, rendererMinor: Int = modern) {
        val drawn = inflate(widthDp, s, fontScale, rendererMinor)
        val root = drawn.root
        val shown = texts(root)
        val px = root.width
        val button = clickables(root).single()
        val buttonTop = origin(button, root)[1]
        val forecast = s.forecastFor(textWidthDp(drawn.device))
        for ((which, want) in listOf("제목" to s.title, "소모율" to s.rate, "예상" to forecast, "상태" to s.status)) {
            val tv = shown.firstOrNull { TileText.plain(it.text.toString()) == want }
            val tag = "${widthDp}dp×$fontScale(렌더러 1.$rendererMinor) $which '$want'"
            assertTrue("$tag: 타일에 없음 (${shown.map { it.text }})", tv != null)
            tv!!
            // 읽어 주기(TalkBack)는 넣은 표시 없는 원문 (가운뎃점·줄표는 쉼표로)
            val said = generateSequence(tv as View) { it.parent as? View }.take(3).mapNotNull { it.contentDescription?.toString() }.firstOrNull()
            assertEquals("$tag: 읽어 주기", TileText.spoken(want), said)
            val l = tv.layout
            val text = tv.text.toString()
            assertTrue("$tag: 두 줄 넘음 (${l.lineCount})", l.lineCount <= 2)
            for (i in 0 until l.lineCount) assertEquals("$tag: ${i + 1}째 줄이 '…'로 잘림", 0, l.getEllipsisCount(i))
            assertEquals("$tag: 끝까지 안 보임", text.length, l.getLineEnd(l.lineCount - 1))
            // 줄이 바뀌면 띄어쓰기에서만 — 낱말이 가운데서 갈라지지 않는다
            val lines = (0 until l.lineCount).map { TileText.plain(text.substring(l.getLineStart(it), l.getLineEnd(it))) }
            for (i in 0 until l.lineCount - 1) assertTrue("$tag: 낱말이 갈라짐 $lines", text[l.getLineEnd(i) - 1] == ' ')
            // 칸에 가려 잘리지 않고(위아래·옆), 글자가 둥근 화면 밖으로 나가지 않는다
            val r = Rect()
            assertTrue("$tag: 안 보임", tv.getGlobalVisibleRect(r))
            assertEquals("$tag: 아래·위가 가려짐 $r", tv.height, r.height())
            assertEquals("$tag: 옆이 가려짐 $r", tv.width, r.width())
            // 칸이 모자라면 세로 줄(column)이 마지막 글의 높이를 줄여 글 아래가 그 글 상자 안에서 잘린다 (192dp 렌더에서 찾음)
            val inBox = tv.totalPaddingTop + l.getLineBaseline(l.lineCount - 1) + tv.paint.descent()
            assertTrue("$tag: 글 상자 안에서 아래가 잘림 (${inBox.roundToInt()} > ${tv.height - tv.extendedPaddingBottom}) $lines", inBox <= tv.height - tv.extendedPaddingBottom + 1)
            val o = origin(tv, root)
            // 아래 '자세히' 단추 위로 — 글꼴이 삼성 것과 조금 달라도 되게 2px 여유
            val glyphBottom = o[1] + inBox
            assertTrue("$tag: 단추에 가려짐 (글 아래 ${glyphBottom.roundToInt()} > 단추 위 ${buttonTop.roundToInt()}) $lines", glyphBottom <= buttonTop - 2)
            for (i in 0 until l.lineCount) {
                val h = (l.getLineBottom(i) - l.getLineTop(i)) * 0.25f
                val top = o[1] + tv.totalPaddingTop + l.getLineTop(i) + h
                val bottom = o[1] + tv.totalPaddingTop + l.getLineBottom(i) - h
                val left = o[0] + tv.totalPaddingLeft + l.getLineLeft(i)
                val right = o[0] + tv.totalPaddingLeft + l.getLineRight(i)
                for ((x, y) in listOf(left to top, right to top, left to bottom, right to bottom)) {
                    val d = hypot(x - px / 2.0, y - px / 2.0)
                    assertTrue("$tag: ${i + 1}째 줄 '${lines[i]}' 이 둥근 화면 밖 (${d.roundToInt()} > ${px / 2})", d <= px / 2.0 + 2)
                }
            }
        }
    }

    @Test
    fun everyLineFitsOn44mm() = cases.forEach { assertFits(226, it) }

    @Test
    fun everyLineFitsOn40mm() = cases.forEach { assertFits(203, it) }

    @Test
    fun everyLineFitsOnTheSmallestRound() = cases.forEach { assertFits(192, it) }

    /** 더 작은 원형(예전 40mm 워치 크기 어림) — 여유 확인 */
    @Test
    fun everyLineFitsEvenSmaller() = cases.forEach { assertFits(186, it) }

    /** 예상을 한 줄로 줄이는 경계 바로 위·아래 (200dp 는 두 부분, 199dp 는 한 줄) */
    @Test
    fun everyLineFitsAroundTheSmallThreshold() {
        cases.forEach { assertFits(BatteryTileService.SMALL_DP, it) }
        cases.forEach { assertFits(BatteryTileService.SMALL_DP - 1, it) }
    }

    /** 예전 렌더러(단추가 다른 모양 — 높이·여백이 다름)에서도 */
    @Test
    fun everyLineFitsWithAnOlderRenderer() {
        cases.forEach { assertFits(226, it, rendererMinor = old) }
        cases.forEach { assertFits(203, it, rendererMinor = old) }
    }

    /**
     * 글꼴 크기를 키운 워치(설정 › 디스플레이 › 글꼴 크기) — 모든 줄이 함께 커지므로(본문 글꼴은 배율을 따름) 글이 쓸 수 있는 폭이
     * 좁은 화면처럼 예상은 한 줄·소모율은 작은 글씨로. 44mm 1.15·1.3배, 40mm 1.15·1.24배(Wear OS 글꼴 크기 가장 큰 단계),
     * 작은 원형 1.15배 (리뷰에서 찾음: 배율 1 만 재고 있었다)
     */
    @Test
    fun everyLineFitsWithLargerText() {
        cases.forEach { assertFits(226, it, 1.15f) }
        cases.forEach { assertFits(226, it, 1.3f) }
        cases.forEach { assertFits(203, it, 1.15f) }
        cases.forEach { assertFits(203, it, 1.24f) }
        cases.forEach { assertFits(192, it, 1.15f) }
    }

    /** 글을 키우면 큰 화면에서도 예상이 한 줄('0% …')로 바뀐다 — 경계는 화면 폭 ÷ 글꼴 배율 */
    @Test
    fun largerTextUsesTheOneLineForecast() {
        val s = cases.first()
        val big = inflate(226, s, 1.3f)
        assertTrue(texts(big.root).any { TileText.plain(it.text.toString()) == s.forecastSmall })
        val normal = inflate(226, s)
        assertTrue(texts(normal.root).any { TileText.plain(it.text.toString()) == s.forecast })
    }

    /** 아래 '자세히' 단추 글이 보인다 (기기 값에 글꼴 배율이 없으면 글 크기가 무한대가 되어 비어 보였다 — 리뷰에서 찾음) */
    @Test
    fun theOpenButtonLabelShows() {
        val drawn = inflate(226, cases.first())
        val label = texts(drawn.root).single { it.text.toString() == app.getString(R.string.battery_tile_open) }
        assertTrue("글 크기 ${label.textSize}", label.textSize.isFinite() && label.textSize > 0f)
        assertTrue("크기 ${label.width}x${label.height}", label.width > 0 && label.height > 0)
    }

    /** 넣은 결합자는 폭이 0 — 네모(□)로 그려지거나 글이 넓어지지 않는다 */
    @Test
    fun theJoinersAreInvisible() {
        val drawn = inflate(226, cases.first())
        val tv = texts(drawn.root).first { it.text.isNotEmpty() && it.width > 0 }
        for (s in listOf("충전 중엔 소모율 측정 쉼", "지킴이: 밤 모드 중 (아침·충전 때 원래대로)", "Play 서비스 과소모 — 캐시 지우기")) {
            val plain = tv.paint.measureText(s)
            val kept = tv.paint.measureText(TileText.keepWords(s))
            assertEquals("'$s' 폭", plain, kept, 0.5f)
        }
    }
}
