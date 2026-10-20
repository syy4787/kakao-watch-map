package doctor.fix

import doctor.AdbRunner
import doctor.Analyzer
import doctor.Facts
import doctor.Finding
import doctor.Severity
import doctor.parse.GmsPackage

enum class Tier { SAFE, CAUTION, STRONG;
    val label: String get() = when (this) { SAFE -> "안전"; CAUTION -> "주의"; STRONG -> "강함" }
}

/**
 * 차단 조치 하나. [apply]/[undo] 는 adb 인자 목록(보통 "shell" 로 시작).
 * [undo] 가 null 을 돌려주면 되돌릴 수 없는 조치, 빈 목록이면 되돌릴 것이 없는 상태.
 */
data class Lever(
    val id: String,
    val title: String,
    val detail: String,
    val tier: Tier,
    val packages: List<String> = emptyList(),
    val readState: (AdbRunner) -> String?,
    val apply: List<List<String>>,
    val undo: (before: String?) -> List<List<String>>?,
    val recommended: Boolean = false,
)

object Catalog {
    /** 비활성화·데이터 삭제·롤백·백그라운드 제한을 절대 제안하지 않는 핵심 패키지 */
    val DENY: List<Regex> = listOf(
        "android", "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.samsung.android.wear.shealth", "com.samsung.android.wearable.sysui", "com.android.systemui", "com.android.settings",
        "com.samsung.android.watch.watchface.*", "com.samsung.android.wear..*wearable.*", "com.google.android.wearable..*",
        "com.android.phone", "com.android.bluetooth", "com.samsung.android.app.watchmanager.*", "com.mywatch.kakaomap",
        "com.android.providers..*", "com.android.shell", "com.android.nfc", "com.android.se", "com.android.keychain",
        "com.android.location.fused", "com.android.wifi..*", "com.android.networkstack.*", "com.android.cellbroadcast.*",
        "com.google.android.ext.services", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
    ).map { Regex("^$it$") }

    fun isDenied(pkg: String): Boolean = DENY.any { it.matches(pkg) }

    /** 안 쓰면 꺼도 되는 선택 앱 — 설치되어 있고 켜진 것만 보인다(이름은 첫 실제 수집으로 확정) */
    val OPTIONAL_APPS: Map<String, String> = linkedMapOf(
        "com.samsung.android.bixby.agent" to "Bixby",
        "com.samsung.android.bixby.wakeup" to "Bixby 음성 호출",
        "com.google.android.googlequicksearchbox" to "Google 앱(어시스턴트)",
        "com.google.android.apps.bard" to "Gemini",
        "com.google.android.apps.maps" to "Google 지도",
        "com.google.android.apps.walletnfcrel" to "Google 월렛",
        "com.samsung.android.samsungpay.gear" to "삼성 월렛",
        "com.google.android.gm" to "Gmail",
        "com.google.android.calendar" to "Google 캘린더",
        "com.google.android.apps.messaging" to "Google 메시지",
        "com.google.android.keep" to "Google Keep",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.spotify.music" to "Spotify",
        "com.samsung.android.app.reminder" to "리마인더",
        "com.samsung.android.watch.weather" to "날씨",
        "com.samsung.android.weather" to "날씨",
    )

    private val GMS = Analyzer.GMS

    fun levers(f: Facts, findings: List<Finding>): List<Lever> {
        val out = ArrayList<Lever>()
        val global = f.settings["global"] ?: emptyMap()
        val secure = f.settings["secure"] ?: emptyMap()
        val system = f.settings["system"] ?: emptyMap()
        val culprits = findings.filter { it.severity != Severity.INFO }.flatMap { it.packages }.distinct().filterNot { isDenied(it) }
        val installed = f.packages.map { it.name }.toSet()
        fun known(pkg: String) = installed.isEmpty() || pkg in installed

        // ── 안전 ──
        global["wifi_scan_always_enabled"]?.let { v -> out += setting("wifi-scan-off", "global", "wifi_scan_always_enabled", "0", "Wi-Fi 꺼도 스캔 허용 끄기", "위치 정확도용 백그라운드 Wi-Fi 스캔을 끕니다. 기능 손실 거의 없음. 설정 > 위치에서 다시 켤 수 있습니다", v.trim() == "1") }
        global["ble_scan_always_enabled"]?.let { v -> out += setting("ble-scan-off", "global", "ble_scan_always_enabled", "0", "블루투스 꺼도 BLE 스캔 허용 끄기", "백그라운드 BLE 스캔을 끕니다. 휴대폰 연결(블루투스 자체)과는 무관합니다", v.trim() == "1") }
        global["adaptive_battery_management_enabled"]?.let { v -> out += setting("adaptive-battery-on", "global", "adaptive_battery_management_enabled", "1", "적응형 배터리 켜기", "안 쓰는 앱의 백그라운드를 시스템이 알아서 줄이게 합니다", v.trim() == "0") }
        global["app_standby_enabled"]?.let { v -> out += setting("app-standby-on", "global", "app_standby_enabled", "1", "앱 대기(App Standby) 켜기", "오래 안 쓴 앱의 네트워크·작업을 미루는 표준 기능을 켭니다", v.trim() == "0") }
        system["screen_off_timeout"]?.trim()?.toIntOrNull()?.let { ms ->
            if (ms > 15000) out += Lever("screen-timeout:15000", "화면 자동 꺼짐 15초로", "지금 ${ms / 1000}초. 설정 > 디스플레이 > 화면 자동 꺼짐에서 되돌릴 수 있습니다", Tier.SAFE,
                readState = { it.settingsGet("system", "screen_off_timeout") },
                apply = listOf(listOf("shell", "settings", "put", "system", "screen_off_timeout", "15000")),
                undo = { b -> listOf(if (b == null || b == "null") listOf("shell", "settings", "delete", "system", "screen_off_timeout") else listOf("shell", "settings", "put", "system", "screen_off_timeout", b)) })
        }
        for (p in f.packages) {
            if (!p.enabled || isDenied(p.name)) continue
            val name = OPTIONAL_APPS[p.name] ?: if (p.thirdParty) p.name else continue
            out += disable(p.name, name)
        }
        out += Lever("batterystats-reset", "배터리 통계 초기화(측정 시작)", "dumpsys batterystats --reset — 조치 뒤 깨끗한 측정 구간을 시작합니다. 되돌릴 것 없음", Tier.SAFE,
            readState = { null }, apply = listOf(listOf("shell", "dumpsys", "batterystats", "--reset")), undo = { null })

        // ── 주의: 범인 패키지 + 상위 전력 서드파티 ──
        val topThird = f.stats?.powerItems?.sortedByDescending { it.mah }?.take(10)?.mapNotNull { it.pkg }?.filter { f.isThirdParty(it) } ?: emptyList()
        val targets = (culprits + topThird).distinct().filterNot { isDenied(it) }.filter { known(it) }
        for (pkg in targets) {
            val rec = pkg in culprits
            out += bgRestrict(pkg, rec)
            out += bucket(pkg, rec)
            if (f.idle?.userWhitelist?.contains(pkg) == true) out += dozeUnwhitelist(pkg, rec)
            out += forceStop(pkg)
        }
        val aodNs = when { "ambient_enabled" in secure -> "secure"; "ambient_enabled" in global -> "global"; else -> null }
        aodNs?.let { ns ->
            out += Lever("aod-off", "AOD(항상 켜진 화면) 끄기", "Wear 설정 키 $ns/ambient_enabled 를 0 으로. AOD 를 쓰고 있다면 고르지 마세요(공식: AOD 끄면 30→40시간)", Tier.CAUTION,
                readState = { it.settingsGet(ns, "ambient_enabled") },
                apply = listOf(listOf("shell", "settings", "put", ns, "ambient_enabled", "0")),
                undo = { b -> listOf(if (b == null || b == "null") listOf("shell", "settings", "delete", ns, "ambient_enabled") else listOf("shell", "settings", "put", ns, "ambient_enabled", b)) })
        }

        // ── 강함 ──
        if (known(GMS)) out += Lever("gms-rollback", "Google Play 서비스 업데이트 제거(공장 버전으로)", "pm uninstall-system-updates — 2026년 Play 서비스 과소모의 최후 수단. Play 스토어가 다시 올릴 수 있으니 워치 Play 스토어의 자동 업데이트를 끄세요. 되돌리기 = Play 스토어에서 업데이트", Tier.STRONG, listOf(GMS),
            readState = { adb -> GmsPackage.versionName(adb.run(listOf("shell", "dumpsys", "package", GMS), 60).out) },
            apply = listOf(listOf("shell", "pm", "uninstall-system-updates", GMS)), undo = { null })
        for (p in f.packages) {
            if (p.thirdParty || !p.enabled || isDenied(p.name) || p.name !in OPTIONAL_APPS) continue
            out += rollback(p.name, OPTIONAL_APPS[p.name] ?: p.name)
        }
        for (pkg in culprits.filter { known(it) }) out += clear(pkg)
        out += Lever("reboot", "워치 재시작(adb reboot)", "지금 바로 재시작합니다. 통계는 유지되지만 쥐고 있던 웨이크락·서비스가 모두 풀립니다", Tier.STRONG,
            readState = { null }, apply = listOf(listOf("reboot")), undo = { null })

        return out.sortedWith(compareBy<Lever> { it.tier.ordinal }.thenBy { !it.recommended })
    }

    /** 목록에 없어도 사용자가 직접 입력한 id 로 조치를 만든다(설치·거부 목록 검사) */
    fun byId(f: Facts, findings: List<Finding>, id: String): Lever? {
        levers(f, findings).firstOrNull { it.id == id }?.let { return it }
        val colon = id.indexOf(':')
        if (colon <= 0) return null
        val kind = id.substring(0, colon)
        val arg = id.substring(colon + 1)
        if (kind == "screen-timeout") return arg.toIntOrNull()?.takeIf { it >= 5000 }?.let { ms ->
            Lever(id, "화면 자동 꺼짐 ${ms / 1000}초로", "", Tier.SAFE, readState = { it.settingsGet("system", "screen_off_timeout") },
                apply = listOf(listOf("shell", "settings", "put", "system", "screen_off_timeout", ms.toString())),
                undo = { b -> listOf(if (b == null || b == "null") listOf("shell", "settings", "delete", "system", "screen_off_timeout") else listOf("shell", "settings", "put", "system", "screen_off_timeout", b)) })
        }
        val pkg = arg
        if (isDenied(pkg) || !Regex("^[\\w.]+$").matches(pkg)) return null
        val info = f.packages.firstOrNull { it.name == pkg }
        if (f.packages.isNotEmpty() && info == null) return null
        return when (kind) {
            "bg-restrict" -> bgRestrict(pkg, false)
            "bucket-restricted" -> bucket(pkg, false)
            "doze-unwhitelist" -> dozeUnwhitelist(pkg, false)
            "force-stop" -> forceStop(pkg)
            "disable" -> disable(pkg, OPTIONAL_APPS[pkg] ?: pkg)
            "clear" -> clear(pkg)
            "rollback" -> if (info?.thirdParty == true) null else rollback(pkg, OPTIONAL_APPS[pkg] ?: pkg)
            else -> null
        }
    }

    // ── 조치 생성기 ──

    private fun setting(id: String, ns: String, key: String, value: String, title: String, detail: String, recommended: Boolean) = Lever(
        id, title, detail, Tier.SAFE,
        readState = { it.settingsGet(ns, key) },
        apply = listOf(listOf("shell", "settings", "put", ns, key, value)),
        undo = { b -> listOf(if (b == null || b == "null") listOf("shell", "settings", "delete", ns, key) else listOf("shell", "settings", "put", ns, key, b)) },
        recommended = recommended,
    )

    private fun disable(pkg: String, name: String) = Lever(
        "disable:$pkg", "앱 비활성화: $name", "$pkg — 안 쓰는 앱이면 끕니다(pm disable-user). 설정 > 앱에서 또는 되돌리기로 다시 켤 수 있습니다", Tier.SAFE, listOf(pkg),
        readState = { adb -> if (adb.run(listOf("shell", "pm", "list", "packages", "-d"), 60).out.lines().any { it.trim() == "package:$pkg" }) "disabled" else "enabled" },
        apply = listOf(listOf("shell", "pm", "disable-user", "--user", "0", pkg)),
        undo = { b -> if (b == "disabled") emptyList() else listOf(listOf("shell", "pm", "enable", "--user", "0", pkg)) },
    )

    private fun bgRestrict(pkg: String, rec: Boolean) = Lever(
        "bg-restrict:$pkg", "백그라운드 실행 제한: $pkg", "cmd appops set RUN_ANY_IN_BACKGROUND ignore — 화면 밖에서 돌지 못하게. 알림·동기화가 늦어질 수 있음", Tier.CAUTION, listOf(pkg),
        readState = { adb ->
            val out = adb.run(listOf("shell", "cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND"), 60)
            if (!out.ok) null else Regex("RUN_ANY_IN_BACKGROUND:\\s*(\\w+)").find(out.out)?.groupValues?.get(1) ?: "default"
        },
        apply = listOf(listOf("shell", "cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore")),
        undo = { b -> listOf(listOf("shell", "cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", b?.takeIf { it.isNotBlank() } ?: "default")) },
        recommended = rec,
    )

    private fun bucket(pkg: String, rec: Boolean) = Lever(
        "bucket-restricted:$pkg", "대기 버킷 restricted: $pkg", "am set-standby-bucket restricted — 작업·알람을 하루 몇 번으로 묶음. 시스템이 나중에 다시 조정할 수 있음", Tier.CAUTION, listOf(pkg),
        readState = { adb -> adb.run(listOf("shell", "am", "get-standby-bucket", pkg), 60).takeIf { it.ok }?.out?.trim()?.takeIf { it.isNotEmpty() } },
        apply = listOf(listOf("shell", "am", "set-standby-bucket", pkg, "restricted")),
        undo = { b -> listOf(listOf("shell", "am", "set-standby-bucket", pkg, b?.takeIf { it.isNotBlank() && it != "null" } ?: "active")) },
        recommended = rec,
    )

    private fun dozeUnwhitelist(pkg: String, rec: Boolean) = Lever(
        "doze-unwhitelist:$pkg", "Doze(배터리 최적화) 예외 해제: $pkg", "cmd deviceidle whitelist -pkg — 화면 꺼진 뒤 이 앱도 다른 앱처럼 잠들게", Tier.CAUTION, listOf(pkg),
        readState = { adb -> if (adb.run(listOf("shell", "cmd", "deviceidle", "whitelist"), 60).out.lines().any { it.contains(",$pkg,") }) "whitelisted" else "not whitelisted" },
        apply = listOf(listOf("shell", "cmd", "deviceidle", "whitelist", "-$pkg")),
        undo = { b -> if (b == "not whitelisted") emptyList() else listOf(listOf("shell", "cmd", "deviceidle", "whitelist", "+$pkg")) },
        recommended = rec,
    )

    private fun forceStop(pkg: String) = Lever(
        "force-stop:$pkg", "강제 종료(일회성): $pkg", "am force-stop — 지금 도는 프로세스만 끝냅니다. 되돌릴 것 없음", Tier.CAUTION, listOf(pkg),
        readState = { null }, apply = listOf(listOf("shell", "am", "force-stop", pkg)), undo = { null },
    )

    private fun clear(pkg: String) = Lever(
        "clear:$pkg", "앱 데이터 삭제: $pkg", "pm clear — 로그인·설정이 지워지고 되돌릴 수 없습니다. 캐시 문제가 의심될 때만", Tier.STRONG, listOf(pkg),
        readState = { null }, apply = listOf(listOf("shell", "pm", "clear", pkg)), undo = { null },
    )

    private fun rollback(pkg: String, name: String) = Lever(
        "rollback:$pkg", "시스템 앱 업데이트 제거: $name", "$pkg — pm uninstall-system-updates (공장 버전으로). 되돌리기 = 스토어에서 업데이트", Tier.STRONG, listOf(pkg),
        readState = { adb -> GmsPackage.versionName(adb.run(listOf("shell", "dumpsys", "package", pkg), 60).out) },
        apply = listOf(listOf("shell", "pm", "uninstall-system-updates", pkg)), undo = { null },
    )

    private fun AdbRunner.settingsGet(ns: String, key: String): String? =
        run(listOf("shell", "settings", "get", ns, key), 60).takeIf { it.ok }?.out?.trim()
}
