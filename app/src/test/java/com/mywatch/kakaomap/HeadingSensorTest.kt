package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.mywatch.kakaomap.location.HeadingSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 실제 센서 흐름으로: 사용자처럼 오른손목에 기본 방향(보려고 들면 버튼이 오른쪽)으로 차고 북쪽을 보며 화면을 볼 때
 * 내 위치 방향이 북쪽(앞)을 가리키는지. 예전 버전이 '뒤집어 찼다'고 잘못 배워 저장해 둔 값이 있어도 무시하고 지운다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-340dpi", application = Application::class)
class HeadingSensorTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR).also { shadowOf(sm).addSensor(it) }

    /** 북쪽을 보며 화면을 [tiltDeg] 만큼 얼굴 쪽으로 세운 워치(오른손목, 버튼은 오른쪽 = 동쪽) — 기기 12시(Y)는 앞(북) */
    private fun rightWristLookingNorth(tiltDeg: Double): FloatArray {
        val a = Math.toRadians(tiltDeg)
        val y = doubleArrayOf(0.0, cos(a), sin(a))             // 동·북·위
        val z = doubleArrayOf(0.0, -sin(a), cos(a))
        val x = doubleArrayOf(y[1] * z[2] - y[2] * z[1], y[2] * z[0] - y[0] * z[2], y[0] * z[1] - y[1] * z[0])
        return rotationVector(x, y, z)
    }

    /** 회전 행렬(열 = 기기 X·Y·Z 의 세계 좌표) → 회전 벡터 센서 값 (단위 사원수 x, y, z, w) */
    private fun rotationVector(x: DoubleArray, y: DoubleArray, z: DoubleArray): FloatArray {
        val m00 = x[0]; val m01 = y[0]; val m02 = z[0]
        val m10 = x[1]; val m11 = y[1]; val m12 = z[1]
        val m20 = x[2]; val m21 = y[2]; val m22 = z[2]
        val t = m00 + m11 + m22
        val (qx, qy, qz, qw) = if (t > 0) {
            val s = sqrt(t + 1.0) * 2
            listOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            listOf(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            listOf((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            listOf((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
        }
        val sign = if (qw < 0) -1.0 else 1.0
        return floatArrayOf((sign * qx).toFloat(), (sign * qy).toFloat(), (sign * qz).toFloat(), (sign * qw).toFloat())
    }

    private fun send(values: FloatArray) {
        shadowOf(sm).sendSensorEventToListeners(SensorEventBuilder.newBuilder(sensor, values).build())
        ShadowSystemClock.advanceBy(Duration.ofMillis(65))   // 센서는 초당 16번쯤
    }

    private fun offNorth(deg: Float): Float {
        val d = ((deg - 0f) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    @Test
    fun rightWristLookingNorthPointsNorthEvenWithAnOldFlippedGuessSaved() {
        // 예전 버전이 '뒤집어 찼다'고 저장해 뒀던 값 — 이제는 쓰지 않고 지운다
        val prefs = context.getSharedPreferences("heading", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("screen_up_is_back", true).commit()
        val got = mutableListOf<Float>()
        val sensor = HeadingSensor(context) { got += it }
        sensor.start()
        for (tilt in listOf(5.0, 20.0, 35.0, 50.0, 65.0)) repeat(4) { send(rightWristLookingNorth(tilt)) }
        sensor.stop()
        assertTrue("방위를 하나도 못 받음", got.isNotEmpty())
        got.forEach { assertTrue("방위 $it° — 북(0°)이어야", offNorth(it) < 10f) }
        assertEquals(false, prefs.contains("screen_up_is_back"))
    }
}
