package com.mywatch.kakaomap.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.mywatch.kakaomap.model.Coord
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 워치 12시 방향이 가리키는 방위(0° = 정북, 시계 방향).
 * 회전 벡터 센서를 쓰고, 위치를 알면 자북 → 진북 보정을 한다.
 */
class HeadingSensor(context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var sx = 0.0
    private var sy = 0.0
    private var initialized = false
    private var lastEmit = 0L
    @Volatile var declination = 0f
        private set

    val available: Boolean get() = sensor != null

    fun start() {
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
        SensorManager.getOrientation(matrix, orientation)
        val azimuth = orientation[0].toDouble()   // 라디안, 북=0, 동=+π/2
        // 각도 평활화: sin/cos 성분을 따로 저역 통과
        if (!initialized) {
            sx = cos(azimuth); sy = sin(azimuth); initialized = true
        } else {
            sx += 0.2 * (cos(azimuth) - sx)
            sy += 0.2 * (sin(azimuth) - sy)
        }
        val now = System.currentTimeMillis()
        if (now - lastEmit < 100) return
        lastEmit = now
        val deg = ((Math.toDegrees(atan2(sy, sx)) + declination + 360) % 360).toFloat()
        onHeading(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
