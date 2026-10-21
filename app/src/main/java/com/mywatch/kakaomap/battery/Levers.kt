package com.mywatch.kakaomap.battery

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.mywatch.battery.core.GuardianConfig
import com.mywatch.kakaomap.data.ErrorLog

/**
 * 지킴이가 당기는 레버들 — 절전 모드·AOD·손목 올림/터치로 켜기·'Hey Google'·위치·계정 동기화. 루트 없이도 PC 에서
 * `pm grant … WRITE_SECURE_SETTINGS` 를 한 번 해 주면 앱이 직접 바꿀 수 있다(동기화는 일반 권한). 바꾸기 전 값을 기억해 두었다가
 * [restoreNight]/[restoreAbnormal]/[restoreAll] 로 되돌린다. 어떤 설정 키가 이 워치에 있는지는 실행할 때 확인하고(없으면 그 레버는 건너뜀),
 * [check] 가 레버마다 이 워치에서 듣는지 보여 준다. 키 이름은 AOSP Wear OS 의 Settings.Global.Wearable 에 정의된 것.
 */
class Levers(context: Context) {

    private val app = context.applicationContext
    private val cr: ContentResolver = app.contentResolver
    private val prefs = app.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)

    enum class Table { GLOBAL, SECURE, SYSTEM }
    data class Key(val table: Table, val name: String)

    val canWriteSecure: Boolean
        get() = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 지금 절전 모드인가 — 시스템이 말하는 값, 못 읽으면 설정값 */
    fun saverOn(): Boolean =
        runCatching { app.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull()
            ?: (read(SAVER) == "1")

    fun read(key: Key): String? = runCatching {
        when (key.table) {
            Table.GLOBAL -> Settings.Global.getString(cr, key.name)
            Table.SECURE -> Settings.Secure.getString(cr, key.name)
            Table.SYSTEM -> Settings.System.getString(cr, key.name)
        }
    }.getOrNull()

    /** 쓰기 성공 여부 (권한이 없으면 false — 예외는 삼킨다) */
    fun write(key: Key, value: String): Boolean = writeHook?.invoke(key, value) ?: runCatching {
        when (key.table) {
            Table.GLOBAL -> Settings.Global.putString(cr, key.name, value)
            Table.SECURE -> Settings.Secure.putString(cr, key.name, value)
            Table.SYSTEM -> Settings.System.putString(cr, key.name, value)
        }
    }.onFailure { ErrorLog.record("배터리·지킴이", "설정 ${key.name} 쓰기 실패: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** 이 워치에 있는 AOD 설정 키 (없으면 null) */
    val aodKey: Key? get() = AOD_KEYS.firstOrNull { read(it) != null }
    val locationKey: Key get() = LOCATION
    val hasLocationKey: Boolean get() = read(LOCATION) != null
    /** 손목 올려 켜기 · 화면 터치로 켜기 · 'Hey Google' 음성 호출 — 이 워치에 있으면 그 키 */
    val tiltKey: Key? get() = TILT.takeIf { read(it) != null }
    val touchKey: Key? get() = TOUCH.takeIf { read(it) != null }
    val hotwordKey: Key? get() = HOTWORD.takeIf { read(it) != null }

    fun syncOn(): Boolean = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true)

    // ───── 밤 모드 ─────

    /** 설정대로 당기고, 실제로 바꾼 것의 이름을 돌려준다 (이미 꺼져 있던 것은 이름에 넣지 않는다) */
    fun applyNight(cfg: GuardianConfig): List<String> {
        val done = ArrayList<String>()
        if (cfg.nightSaver) { remember(B_SAVER) { if (saverOn()) "1" else "0" }; if (setSaver(true)) done += "절전 모드" }
        if (cfg.nightAodOff) aodKey?.let { k -> if (pull(B_AOD, k, "0")) done += "AOD 끔" }
        if (cfg.nightWakeOff) {
            val tilt = tiltKey?.let { pull(B_TILT, it, "0") } == true
            val touch = touchKey?.let { pull(B_TOUCH, it, "0") } == true
            wakeLabel(tilt, touch)?.let { done += "$it 켜기 끔" }
        }
        if (cfg.nightHotwordOff) hotwordKey?.let { k -> if (pull(B_HOTWORD, k, "0")) done += "'Hey Google' 끔" }
        if (cfg.nightLocationOff && hasLocationKey) { if (pull(B_LOCATION, LOCATION, "0")) done += "위치 끔" }
        if (cfg.nightSyncOff) { remember(B_SYNC) { if (syncOn()) "1" else "0" }; if (setSync(false)) done += "동기화 끔" }
        return done
    }

    fun restoreNight(): List<String> {
        val done = ArrayList<String>()
        if (putBack(B_AOD, aodKey)) done += "AOD 복구"
        val tilt = putBack(B_TILT, TILT)
        val touch = putBack(B_TOUCH, TOUCH)
        wakeLabel(tilt, touch)?.let { done += "$it 켜기 복구" }
        if (putBack(B_HOTWORD, HOTWORD)) done += "'Hey Google' 복구"
        if (putBack(B_LOCATION, LOCATION)) done += "위치 복구"
        restoreSlot(B_SYNC) { v -> setSync(v == "1") }?.let { if (it) done += "동기화 복구" }
        restoreSlot(B_SAVER) { v -> restoreSaver(v) }?.let { if (it) done += "절전 복구" }
        return done
    }

    /** "손목 올림·터치로" / "손목 올림으로" / "터치로" / null */
    private fun wakeLabel(tilt: Boolean, touch: Boolean): String? = when {
        tilt && touch -> "손목 올림·터치로"
        tilt -> "손목 올림으로"
        touch -> "터치로"
        else -> null
    }

    // ───── 비정상 소모 대응 ─────

    fun applyAbnormal(): Boolean {
        remember(B_SAVER) { if (saverOn()) "1" else "0" }
        return setSaver(true)
    }

    fun restoreAbnormal(): Boolean = restoreSlot(B_SAVER) { v -> restoreSaver(v) } ?: false

    /** 전부 원래대로. [keepSaver] 면 절전은 사용자가 손수 바꾼 것이라 건드리지 않고 기억만 지운다 */
    fun restoreAll(keepSaver: Boolean = false) {
        putBack(B_AOD, aodKey)
        putBack(B_TILT, TILT)
        putBack(B_TOUCH, TOUCH)
        putBack(B_HOTWORD, HOTWORD)
        putBack(B_LOCATION, LOCATION)
        restoreSlot(B_SYNC) { v -> setSync(v == "1") }
        if (keepSaver) prefs.edit().remove(B_SAVER).apply() else restoreSlot(B_SAVER) { v -> restoreSaver(v) }
    }

    /**
     * 권한(WRITE_SECURE_SETTINGS)이 없어져 되돌릴 수 없을 때: 지금도 우리가 둔 값인 설정을 사용자가 직접 되돌릴 일로 돌려주고
     * (동기화는 권한 없이 되돌린다) 기억을 모두 지운다 — 손을 떼고 사용자에게 맡긴다. 이미 원래대로면 넣지 않는다
     */
    fun releaseWithoutPermission(): List<String> {
        val todo = ArrayList<String>()
        fun held(slot: String, key: Key?, label: String) {
            val v = prefs.getString(slot, null) ?: return
            val now = key?.let { read(it) } ?: return
            if (now != v && now == "0") todo += label
        }
        held(B_AOD, aodKey, "AOD 켜기")
        held(B_TILT, TILT, "손목 올려 켜기")
        held(B_TOUCH, TOUCH, "화면 터치로 켜기")
        held(B_HOTWORD, HOTWORD, "'Hey Google' 켜기")
        held(B_LOCATION, LOCATION, "위치 켜기")
        prefs.getString(B_SYNC, null)?.let { v -> if (v == "1" && !syncOn() && !setSync(true)) todo += "계정 동기화 켜기" }
        prefs.getString(B_SAVER, null)?.let { v -> if (v == "0" && saverOn()) todo += "절전 모드 끄기" }
        prefs.edit().apply { SLOTS.forEach { remove(it) } }.apply()
        return todo
    }

    /** 우리가 무언가를 바꿔 둔 상태인가 */
    val holdsAnything: Boolean get() = SLOTS.any { prefs.contains(it) }

    // ───── 레버 점검 ─────

    /** 레버 하나가 이 워치에서 듣는지: [key] 는 "global/ambient_enabled" 처럼, 없으면 null */
    data class LeverStatus(val id: String, val label: String, val key: String?, val usable: Boolean, val state: String)

    /**
     * 레버마다 이 워치에 설정 키가 있는지·권한이 있는지·지금 값. [saverBroken] 은 지킴이가 '설정을 써도 절전이 안 켜지는 워치'로 본 경우
     * (실제 워치에서 어느 레버가 듣는지 사용자·PC 툴킷이 보게)
     */
    fun check(saverBroken: Boolean = false): List<LeverStatus> {
        val perm = canWriteSecure
        fun of(id: String, label: String, k: Key?): LeverStatus {
            if (k == null) return LeverStatus(id, label, null, false, "이 워치엔 없음")
            return LeverStatus(id, label, keyName(k), perm, if (perm) "지금 ${read(k) ?: "?"}" else "권한 필요")
        }
        val saver = when {
            !perm -> LeverStatus("saver", "절전 모드", keyName(SAVER), false, "권한 필요")
            saverBroken -> LeverStatus("saver", "절전 모드", keyName(SAVER), false, "설정이 안 들음")
            else -> LeverStatus("saver", "절전 모드", keyName(SAVER), true, if (saverOn()) "지금 켜짐" else "지금 꺼짐")
        }
        return listOf(
            saver,
            of("aod", "AOD", aodKey),
            of("tilt", "손목 올림 켜기", tiltKey),
            of("touch", "터치 켜기", touchKey),
            of("hotword", "Hey Google", hotwordKey),
            of("location", "위치", if (hasLocationKey) LOCATION else null),
            LeverStatus("sync", "계정 동기화", null, perm, if (perm) if (syncOn()) "지금 켜짐" else "지금 꺼짐" else "권한 필요"),
        )
    }

    /** 요약 파일·PC 툴킷용 한 줄: "perm=yes saver=global/low_power aod=global/ambient_enabled tilt=… touch=- …" */
    fun checkLine(saverBroken: Boolean = false): String =
        "perm=${if (canWriteSecure) "yes" else "no"} " + check(saverBroken).joinToString(" ") { s ->
            val k = s.key ?: if (s.id == "sync") "api" else "-"
            "${s.id}=$k" + (if (s.id == "saver" && saverBroken) "(broken)" else "")
        }

    private fun keyName(k: Key) = "${k.table.name.lowercase()}/${k.name}"

    private fun setSaver(on: Boolean): Boolean = write(SAVER, if (on) "1" else "0")

    /**
     * 절전을 원래 값으로. 원래 꺼져 있었으면 '끈 채 유지(sticky)' 표시도 지운다 — 설정으로 켠 절전은 시스템이 손수 켠 것으로 보고
     * sticky 를 남겨, 충전기에서 90% 아래로 뺄 때(배터리 보호 90%면 늘) 다시 켜 버린다(AOSP 'Sticky restore')
     */
    private fun restoreSaver(v: String): Boolean {
        val ok = setSaver(v == "1")
        if (ok && v == "0" && read(SAVER_STICKY) == "1") write(SAVER_STICKY, "0")
        return ok
    }

    private fun setSync(on: Boolean): Boolean = runCatching { ContentResolver.setMasterSyncAutomatically(on); true }
        .onFailure { ErrorLog.record("배터리·지킴이", "동기화 설정 실패: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** [key] 를 [value] 로 바꾼다 — 처음 값은 [slot] 에 기억. 실제로 바뀌었으면 true (이미 그 값이면 쓰지 않는다) */
    private fun pull(slot: String, key: Key, value: String): Boolean {
        val cur = read(key) ?: return false
        // 이미 그 값이면 바꾸지도 기억하지도 않는다 — 아침에 되돌릴 것이 없다
        if (cur == value) return false
        remember(slot) { cur }
        return write(key, value)
    }

    /**
     * 기억해 둔 값으로 되돌린다 (기억을 지운다). 실제로 바뀌었으면 true.
     * 지금 값이 우리가 둔 값([ours])일 때만 — 그 사이 사용자나 삼성 취침 모드가 바꿨으면 그쪽이 새것이라 건드리지 않는다
     */
    private fun putBack(slot: String, key: Key?, ours: String = "0"): Boolean {
        val v = prefs.getString(slot, null) ?: return false
        val k = key ?: run { prefs.edit().remove(slot).apply(); return false }
        val now = read(k)
        // 이미 원래 값이거나 그 사이 다른 쪽이 바꿨으면 기억만 지운다
        if (now == v || now != ours) { prefs.edit().remove(slot).apply(); return false }
        // 기억은 실제로 되돌렸을 때만 지운다 — 권한이 없어 못 쓰면 남겨 두어 나중에(또는 사용자에게 알려) 되돌린다
        val ok = write(k, v)
        if (ok) prefs.edit().remove(slot).apply()
        return ok
    }

    /** 기억한 값으로 [restore] 하고, 성공했을 때만 기억을 지운다 (기억이 없으면 null) */
    private fun restoreSlot(slot: String, restore: (String) -> Boolean): Boolean? {
        val v = prefs.getString(slot, null) ?: return null
        val ok = restore(v)
        if (ok) prefs.edit().remove(slot).apply()
        return ok
    }

    /** 처음 바꿀 때의 값만 기억한다 (두 번 당겨도 원래 값이 남게) */
    private fun remember(slot: String, current: () -> String?) {
        if (prefs.contains(slot)) return
        val v = current() ?: return
        prefs.edit().putString(slot, v).apply()
    }

    companion object {
        /** 안드로이드 절전 모드 (Settings.Global.LOW_POWER_MODE) — 삼성 절전 모드도 이 위에서 돈다 */
        val SAVER = Key(Table.GLOBAL, "low_power")
        /** Settings.Global.LOW_POWER_MODE_STICKY — 설정으로 켠 절전을 충전 뒤 다시 켜는 표시 */
        val SAVER_STICKY = Key(Table.GLOBAL, "low_power_sticky")

        /** 시험에서 '쓰기 실패'(권한 없음)를 흉내 낸다 */
        @VisibleForTesting @Volatile var writeHook: ((Key, String) -> Boolean)? = null
        /** Wear OS 의 Always On Display 키 후보 — 있는 것을 쓴다 */
        val AOD_KEYS = listOf(Key(Table.GLOBAL, "ambient_enabled"), Key(Table.SECURE, "ambient_enabled"), Key(Table.SYSTEM, "ambient_enabled"))
        val LOCATION = Key(Table.SECURE, "location_mode")
        /** Settings.Global.Wearable.AMBIENT_TILT_TO_WAKE — 손목 올려 켜기 */
        val TILT = Key(Table.GLOBAL, "ambient_tilt_to_wake")
        /** Settings.Global.Wearable.AMBIENT_TOUCH_TO_WAKE — 화면을 눌러 켜기 */
        val TOUCH = Key(Table.GLOBAL, "ambient_touch_to_wake")
        /** Settings.Global.Wearable.HOTWORD_DETECTION_ENABLED — 'Hey Google' 음성 호출 */
        val HOTWORD = Key(Table.GLOBAL, "hotword_detection_enabled")

        const val B_SAVER = "before_saver"
        const val B_AOD = "before_aod"
        const val B_LOCATION = "before_location"
        const val B_SYNC = "before_sync"
        const val B_TILT = "before_tilt"
        const val B_TOUCH = "before_touch"
        const val B_HOTWORD = "before_hotword"
        private val SLOTS = listOf(B_SAVER, B_AOD, B_LOCATION, B_SYNC, B_TILT, B_TOUCH, B_HOTWORD)
    }
}
