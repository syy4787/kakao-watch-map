package doctor

import doctor.fix.Catalog
import doctor.fix.Lever
import doctor.fix.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

class CatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun sampleLevers(): Pair<Facts, List<Lever>> {
        val f = Analyzer.facts(sampleSnapshot(tmp.root), zone)
        return f to Catalog.levers(f, Analyzer.findings(f))
    }

    private fun List<Lever>.id(id: String) = firstOrNull { it.id == id }

    @Test
    fun denyListBlocksCoreSystemPackages() {
        listOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.samsung.android.wear.shealth", "com.samsung.android.wearable.sysui",
            "com.android.systemui", "com.android.settings", "com.samsung.android.watch.watchface", "com.samsung.android.watch.watchface.foo",
            "com.samsung.android.wear.wearable.manager", "com.google.android.wearable.app", "com.android.phone", "com.android.bluetooth",
            "com.samsung.android.app.watchmanager", "com.samsung.android.app.watchmanagerstub", "com.mywatch.kakaomap", "android")
            .forEach { assertTrue(it, Catalog.isDenied(it)) }
        listOf("com.example.drain", "com.samsung.android.bixby.agent", "com.google.android.googlequicksearchbox", "com.example.weather")
            .forEach { assertFalse(it, Catalog.isDenied(it)) }
    }

    @Test
    fun settingsLeversOnlyWhenKeyExistsAndRecommendedWhenRisky() {
        val (_, levers) = sampleLevers()
        val wifi = levers.id("wifi-scan-off")!!
        assertEquals(Tier.SAFE, wifi.tier)
        assertTrue(wifi.recommended)
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "0")), wifi.apply)
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "wifi_scan_always_enabled", "1")), wifi.undo("1"))
        assertEquals(listOf(listOf("shell", "settings", "delete", "global", "wifi_scan_always_enabled")), wifi.undo("null"))
        assertTrue(levers.id("ble-scan-off")!!.recommended)
        assertTrue(levers.id("adaptive-battery-on")!!.recommended)     // 값이 0
        assertFalse(levers.id("app-standby-on")!!.recommended)         // 이미 1
        // 키가 없으면 항목 자체가 없음
        val none = Catalog.levers(Facts(), emptyList())
        assertNull(none.id("wifi-scan-off"))
        assertNull(none.id("screen-timeout:15000"))
        assertNull(none.id("aod-off"))
        assertNotNull(none.id("batterystats-reset"))
    }

    @Test
    fun optionalAppDisableOnlyForInstalledEnabledNonCore() {
        val (_, levers) = sampleLevers()
        val bixby = levers.id("disable:com.samsung.android.bixby.agent")!!
        assertEquals(Tier.SAFE, bixby.tier)
        assertTrue(bixby.title.contains("Bixby"))
        assertEquals(listOf(listOf("shell", "pm", "disable-user", "--user", "0", "com.samsung.android.bixby.agent")), bixby.apply)
        assertEquals(listOf(listOf("shell", "pm", "enable", "--user", "0", "com.samsung.android.bixby.agent")), bixby.undo("enabled"))
        assertNull(levers.id("disable:com.google.android.apps.messaging"))   // 이미 비활성
        assertNull(levers.id("disable:com.google.android.gms"))              // 거부 목록
        assertNull(levers.id("disable:com.mywatch.kakaomap"))                // 거부 목록(이 앱)
        assertNull(levers.id("disable:com.samsung.android.app.reminder"))    // 설치 안 됨
        assertNotNull(levers.id("disable:com.example.weather"))              // 서드파티 앱도 후보
        assertFalse(levers.any { it.id.startsWith("disable:") && it.recommended })   // 비활성화는 자동 추천하지 않음
    }

    @Test
    fun culpritPackagesGetBackgroundLeversRecommended() {
        val (_, levers) = sampleLevers()
        val bg = levers.id("bg-restrict:com.example.drain")!!
        assertEquals(Tier.CAUTION, bg.tier)
        assertTrue(bg.recommended)
        assertEquals(listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "ignore")), bg.apply)
        assertEquals(listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "allow")), bg.undo("allow"))
        assertEquals(listOf(listOf("shell", "cmd", "appops", "set", "com.example.drain", "RUN_ANY_IN_BACKGROUND", "default")), bg.undo(null))
        val bucket = levers.id("bucket-restricted:com.example.drain")!!
        assertTrue(bucket.recommended)
        assertEquals(listOf(listOf("shell", "am", "set-standby-bucket", "com.example.drain", "restricted")), bucket.apply)
        assertEquals(listOf(listOf("shell", "am", "set-standby-bucket", "com.example.drain", "10")), bucket.undo("10"))
        val doze = levers.id("doze-unwhitelist:com.example.drain")!!
        assertTrue(doze.recommended)
        assertEquals(listOf(listOf("shell", "cmd", "deviceidle", "whitelist", "-com.example.drain")), doze.apply)
        assertEquals(listOf(listOf("shell", "cmd", "deviceidle", "whitelist", "+com.example.drain")), doze.undo("whitelisted"))
        assertNull(levers.id("doze-unwhitelist:com.example.weather"))       // 화이트리스트에 없으면 항목 없음
        val stop = levers.id("force-stop:com.example.drain")!!
        assertFalse(stop.recommended)
        assertNull(stop.undo(null))
        // 거부 목록 패키지에는 패키지 조치가 없다 (GMS 는 롤백만, 추천 아님)
        assertNull(levers.id("bg-restrict:com.google.android.gms"))
        assertNull(levers.id("bucket-restricted:com.google.android.gms"))
        val gms = levers.id("gms-rollback")!!
        assertEquals(Tier.STRONG, gms.tier)
        assertFalse(gms.recommended)
        assertEquals(listOf(listOf("shell", "pm", "uninstall-system-updates", "com.google.android.gms")), gms.apply)
        assertNull(gms.undo(null))
    }

    @Test
    fun strongLeversAreNeverRecommendedAndClearHasNoUndo() {
        val (_, levers) = sampleLevers()
        assertTrue(levers.filter { it.tier == Tier.STRONG }.isNotEmpty())
        assertTrue(levers.filter { it.tier == Tier.STRONG }.none { it.recommended })
        val clear = levers.id("clear:com.example.drain")!!
        assertEquals(listOf(listOf("shell", "pm", "clear", "com.example.drain")), clear.apply)
        assertNull(clear.undo("x"))
        assertEquals(listOf(listOf("reboot")), levers.id("reboot")!!.apply)
        val rollback = levers.id("rollback:com.google.android.googlequicksearchbox")!!
        assertEquals(Tier.STRONG, rollback.tier)
        assertNull(levers.id("clear:com.google.android.gms"))
        assertNull(levers.id("rollback:com.example.drain"))   // 서드파티엔 시스템 업데이트 제거가 없음
    }

    @Test
    fun aodAndScreenTimeoutLevers() {
        val (_, levers) = sampleLevers()
        val aod = levers.id("aod-off")!!
        assertEquals(Tier.CAUTION, aod.tier)
        assertFalse(aod.recommended)
        assertEquals(listOf(listOf("shell", "settings", "put", "secure", "ambient_enabled", "0")), aod.apply)
        assertEquals(listOf(listOf("shell", "settings", "put", "secure", "ambient_enabled", "1")), aod.undo("1"))
        val st = levers.id("screen-timeout:15000")!!
        assertEquals(Tier.SAFE, st.tier)
        assertEquals(listOf(listOf("shell", "settings", "put", "system", "screen_off_timeout", "15000")), st.apply)
        assertEquals(listOf(listOf("shell", "settings", "put", "system", "screen_off_timeout", "30000")), st.undo("30000"))
        val reset = levers.id("batterystats-reset")!!
        assertEquals(listOf(listOf("shell", "dumpsys", "batterystats", "--reset")), reset.apply)
        assertNull(reset.undo(null))
    }

    /** 손목 올림·터치로 켜기·'Hey Google' — 키가 있고 켜져 있을 때만. 손목 올림은 워치 앱이 센 '알림 없이 금방 꺼진 화면'이 많으면 추천 */
    @Test
    fun wakeGestureAndHotwordLevers() {
        val g = mapOf("ambient_tilt_to_wake" to "1", "ambient_touch_to_wake" to "1", "hotword_detection_enabled" to "1")
        val f = Facts(settings = mapOf("global" to g))
        val levers = Catalog.levers(f, emptyList())
        val tilt = levers.id("tilt-wake-off")!!
        assertEquals(Tier.CAUTION, tilt.tier)
        assertFalse(tilt.recommended)
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "ambient_tilt_to_wake", "0")), tilt.apply)
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "ambient_tilt_to_wake", "1")), tilt.undo("1"))
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "ambient_touch_to_wake", "0")), levers.id("touch-wake-off")!!.apply)
        assertEquals(listOf(listOf("shell", "settings", "put", "global", "hotword_detection_enabled", "0")), levers.id("hotword-off")!!.apply)
        assertFalse(levers.id("hotword-off")!!.recommended)
        // 이미 꺼져 있거나 키가 없으면 항목 없음
        assertNull(Catalog.levers(Facts(settings = mapOf("global" to mapOf("ambient_tilt_to_wake" to "0"))), emptyList()).id("tilt-wake-off"))
        assertNull(Catalog.levers(Facts(), emptyList()).id("tilt-wake-off"))
        // 워치 앱 요약의 지표: 알림 없이 5초 안에 꺼진 화면 120번 → 추천
        val busy = f.copy(watchMetrics = mapOf("gesture_wakes" to 120.0))
        assertTrue(Catalog.levers(busy, emptyList()).id("tilt-wake-off")!!.recommended)
    }

    @Test
    fun readStateUsesAdb() {
        val (_, levers) = sampleLevers()
        val adb = FakeAdb().apply {
            on("shell settings get global wifi_scan_always_enabled", "1\n")
            on("shell cmd appops get com.example.drain RUN_ANY_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND: allow\n")
            on("shell cmd appops get com.example.weather RUN_ANY_IN_BACKGROUND", "No operations.\n")
            on("shell am get-standby-bucket com.example.drain", "10\n")
            on("shell cmd deviceidle whitelist", "user,com.example.drain,10123\n")
            on("shell pm list packages -d", "package:com.google.android.apps.messaging\n")
            on("shell settings get secure ambient_enabled", "1\n")
        }
        assertEquals("1", levers.id("wifi-scan-off")!!.readState(adb))
        assertEquals("allow", levers.id("bg-restrict:com.example.drain")!!.readState(adb))
        assertEquals("default", Catalog.byId(sampleLevers().first, emptyList(), "bg-restrict:com.example.weather")!!.readState(adb))
        assertEquals("10", levers.id("bucket-restricted:com.example.drain")!!.readState(adb))
        assertEquals("whitelisted", levers.id("doze-unwhitelist:com.example.drain")!!.readState(adb))
        assertEquals("enabled", levers.id("disable:com.samsung.android.bixby.agent")!!.readState(adb))
        assertEquals("1", levers.id("aod-off")!!.readState(adb))
        assertNull(levers.id("reboot")!!.readState(adb))
    }

    @Test
    fun byIdBuildsPackageLeversOnDemandButRespectsDeny() {
        val (f, _) = sampleLevers()
        val w = Catalog.byId(f, emptyList(), "bg-restrict:com.example.weather")!!
        assertEquals(Tier.CAUTION, w.tier)
        assertFalse(w.recommended)
        assertNull(Catalog.byId(f, emptyList(), "bg-restrict:com.google.android.gms"))
        assertNull(Catalog.byId(f, emptyList(), "disable:com.android.settings"))
        assertNull(Catalog.byId(f, emptyList(), "disable:com.not.installed"))
        assertNull(Catalog.byId(f, emptyList(), "nonsense"))
        assertNotNull(Catalog.byId(f, emptyList(), "wifi-scan-off"))
        assertNotNull(Catalog.byId(f, emptyList(), "reboot"))
    }

    @Test
    fun orderIsSafeThenCautionThenStrongWithRecommendedFirst() {
        val (_, levers) = sampleLevers()
        val tiers = levers.map { it.tier.ordinal }
        assertEquals(tiers.sorted(), tiers)
        val safe = levers.filter { it.tier == Tier.SAFE }
        val firstNotRecommended = safe.indexOfFirst { !it.recommended }
        val lastRecommended = safe.indexOfLast { it.recommended }
        assertTrue(firstNotRecommended < 0 || lastRecommended < firstNotRecommended)
    }
}
