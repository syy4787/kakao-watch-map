package com.mywatch.kakaomap.battery

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import com.mywatch.battery.core.GuardianConfig
import com.mywatch.kakaomap.data.ErrorLog

/**
 * 지킴이가 당기는 레버들 — 절전 모드·AOD·위치·계정 동기화. 루트 없이도 PC 에서 `pm grant … WRITE_SECURE_SETTINGS` 를 한 번 해 주면
 * 앱이 직접 바꿀 수 있다(동기화는 일반 권한). 바꾸기 전 값을 기억해 두었다가 [restoreNight]/[restoreAbnormal]/[restoreAll] 로 되돌린다.
 * 어떤 설정 키가 이 워치에 있는지는 실행할 때 확인한다(없으면 그 레버는 건너뜀).
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
    fun write(key: Key, value: String): Boolean = runCatching {
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

    fun syncOn(): Boolean = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true)

    // ───── 밤 모드 ─────

    /** 설정대로 당기고, 바꾼 것의 이름을 돌려준다 */
    fun applyNight(cfg: GuardianConfig): List<String> {
        val done = ArrayList<String>()
        if (cfg.nightSaver) { remember(B_SAVER) { if (saverOn()) "1" else "0" }; if (setSaver(true)) done += "절전 모드" }
        if (cfg.nightAodOff) aodKey?.let { k -> remember(B_AOD) { read(k) }; if (write(k, "0")) done += "AOD 끔" }
        if (cfg.nightLocationOff && hasLocationKey) { remember(B_LOCATION) { read(LOCATION) }; if (write(LOCATION, "0")) done += "위치 끔" }
        if (cfg.nightSyncOff) { remember(B_SYNC) { if (syncOn()) "1" else "0" }; if (setSync(false)) done += "동기화 끔" }
        return done
    }

    fun restoreNight(): List<String> {
        val done = ArrayList<String>()
        recall(B_AOD)?.let { v -> aodKey?.let { k -> if (write(k, v)) done += "AOD 복구" } }
        recall(B_LOCATION)?.let { v -> if (write(LOCATION, v)) done += "위치 복구" }
        recall(B_SYNC)?.let { v -> if (setSync(v == "1")) done += "동기화 복구" }
        recall(B_SAVER)?.let { v -> if (setSaver(v == "1")) done += "절전 복구" }
        return done
    }

    // ───── 비정상 소모 대응 ─────

    fun applyAbnormal(): Boolean {
        remember(B_SAVER) { if (saverOn()) "1" else "0" }
        return setSaver(true)
    }

    fun restoreAbnormal(): Boolean = recall(B_SAVER)?.let { setSaver(it == "1") } ?: false

    /** 전부 원래대로. [keepSaver] 면 절전은 사용자가 손수 바꾼 것이라 건드리지 않고 기억만 지운다 */
    fun restoreAll(keepSaver: Boolean = false) {
        recall(B_AOD)?.let { v -> aodKey?.let { k -> write(k, v) } }
        recall(B_LOCATION)?.let { v -> write(LOCATION, v) }
        recall(B_SYNC)?.let { v -> setSync(v == "1") }
        recall(B_SAVER)?.let { v -> if (!keepSaver) setSaver(v == "1") }
    }

    /** 우리가 무언가를 바꿔 둔 상태인가 */
    val holdsAnything: Boolean get() = listOf(B_SAVER, B_AOD, B_LOCATION, B_SYNC).any { prefs.contains(it) }

    private fun setSaver(on: Boolean): Boolean = write(SAVER, if (on) "1" else "0")

    private fun setSync(on: Boolean): Boolean = runCatching { ContentResolver.setMasterSyncAutomatically(on); true }
        .onFailure { ErrorLog.record("배터리·지킴이", "동기화 설정 실패: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** 처음 바꿀 때의 값만 기억한다 (두 번 당겨도 원래 값이 남게) */
    private fun remember(slot: String, current: () -> String?) {
        if (prefs.contains(slot)) return
        val v = current() ?: return
        prefs.edit().putString(slot, v).apply()
    }

    /** 기억한 값을 꺼내고 지운다 */
    private fun recall(slot: String): String? {
        val v = prefs.getString(slot, null) ?: return null
        prefs.edit().remove(slot).apply()
        return v
    }

    companion object {
        /** 안드로이드 절전 모드 (Settings.Global.LOW_POWER_MODE) — 삼성 절전 모드도 이 위에서 돈다 */
        val SAVER = Key(Table.GLOBAL, "low_power")
        /** Wear OS 의 Always On Display 키 후보 — 있는 것을 쓴다 */
        val AOD_KEYS = listOf(Key(Table.GLOBAL, "ambient_enabled"), Key(Table.SECURE, "ambient_enabled"), Key(Table.SYSTEM, "ambient_enabled"))
        val LOCATION = Key(Table.SECURE, "location_mode")

        const val B_SAVER = "before_saver"
        const val B_AOD = "before_aod"
        const val B_LOCATION = "before_location"
        const val B_SYNC = "before_sync"
    }
}
