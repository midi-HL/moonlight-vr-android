package com.limelight.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Observes initial sensor delivery without replacing Cardboard's sensor fusion or tracking. */
internal class VrTrackerReadiness(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var registered = false
    private data class Sample(val acceleration: FloatArray, val timestamp: Long)
    @Volatile private var sample: Sample? = null

    fun resume() {
        if (registered) return
        val manager = sensors ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        registered = manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME,
            Handler(Looper.getMainLooper()))
    }

    fun pause() {
        if (registered) sensors?.unregisterListener(this)
        registered = false
        sample = null
    }

    fun matches(headView: FloatArray, displayRotation: Int): Boolean {
        val current = sample ?: return false
        val age = SystemClock.elapsedRealtimeNanos() - current.timestamp
        return age in 0L..250_000_000L &&
            VrGravityAlignment.matches(headView, current.acceleration, displayRotation)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val norm = x * x + y * y + z * z
        if (!norm.isFinite() || norm < 1f) return
        sample = Sample(floatArrayOf(x, y, z), event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
