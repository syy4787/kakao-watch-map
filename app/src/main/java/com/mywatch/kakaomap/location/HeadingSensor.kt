package com.mywatch.kakaomap.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import com.mywatch.kakaomap.model.Coord
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 화면을 보는 내가 바라보는 방위(0° = 정북, 시계 방향) — 손목을 들어 화면이 서도, 오른손목에 화면을 180° 돌려 차도
 * ([HeadingMath]). 시스템이 화면 회전을 알려 주지 않아도 찬 방향을 센서로 알아내 저장해 둔다([WearSide]).
 * 회전 벡터 센서를 쓰고, 위치를 알면 자북 → 진북 보정을 한다.
 */
class HeadingSensor(private val context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    /** 화면이 돌아간 정도(시스템이 알려 준 것) — 갤럭시워치는 버튼 위치를 바꿔도 0 으로 알려 준다 */
    private var displayRotation = Surface.ROTATION_0
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** 화면의 위쪽이 실제로는 몸 쪽인지 — 지난번에 알아낸 것부터 */
    private val wearSide = WearSide(flipped = prefs.getBoolean(KEY_FLIPPED, false))
    private var sx = 0.0
    private var sy = 0.0
    private var initialized = false
    private var lastEmit = 0L
    @Volatile var declination = 0f
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        displayRotation = runCatching { context.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
        initialized = false
    }

    fun updateLocation(c: Coord) {
        declination = GeomagneticField(c.lat.toFloat(), c.lng.toFloat(), 0f, System.currentTimeMillis()).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        if (wearSide.observe(matrix, displayRotation)) {
            prefs.edit().putBoolean(KEY_FLIPPED, wearSide.flipped).apply()
            initialized = false     // 반대였던 방위를 천천히 돌리지 않고 바로
        }
        // 화면을 비스듬히 땅으로 향하는 등 바라보는 방향을 알 수 없는 자세면 이전 방위를 그대로 둔다
        val raw = HeadingMath.screenHeadingDeg(matrix, displayRotation, wearSide.flipped) ?: return
        val azimuth = Math.toRadians(raw)   // 라디안, 북=0, 동=+π/2
        // 각도 평활화: sin/cos 성분을 따로 저역 통과
        if (!initialized) {
            sx = cos(azimuth); sy = sin(azimuth); initialized = true
        } else {
            sx += 0.2 * (cos(azimuth) - sx)
            sy += 0.2 * (sin(azimuth) - sy)
        }
        val now = SystemClock.elapsedRealtime()   // 시계를 바꿔도 흔들리지 않는 시간
        if (now - lastEmit < 100) return
        lastEmit = now
        val deg = ((Math.toDegrees(atan2(sy, sx)) + declination + 360) % 360).toFloat()
        onHeading(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val PREFS = "heading"
        const val KEY_FLIPPED = "screen_up_is_back"
    }
}
